package io.orangerabbit.clanker.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.ProviderId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Provider for any OpenAI-compatible Chat Completions endpoint. OpenRouter and every other
 * OpenAI-shaped host are covered by this one class; see [openRouterProvider] for the OpenRouter
 * attribution-header variant. Genuinely non-compatible providers implement [LlmProvider] directly.
 *
 * The [engine] is injected so the provider is testable with Ktor's MockEngine; in production it is
 * an OkHttp engine (HTTP/2 streaming).
 */
class OpenAiCompatibleProvider(
    override val id: ProviderId,
    override val displayName: String,
    private val baseUrl: String,
    private val apiKey: String,
    private val engine: HttpClientEngine,
    private val defaultHeaders: Map<String, String> = emptyMap(),
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    },
) : LlmProvider {

    private val decoder = OpenAiSseDecoder(json)

    private fun newClient() = HttpClient(engine) {
        install(ContentNegotiation) { json(json) }
    }

    override fun streamChat(request: ChatRequest): Flow<ChatEvent> = channelFlow {
        val client = newClient()
        try {
            client.preparePost("$baseUrl/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                defaultHeaders.forEach { (k, v) -> header(k, v) }
                contentType(ContentType.Application.Json)
                setBody(request.toWire(stream = true))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    send(ChatEvent.Failed(errorFor(response.status.value, runCatching { response.bodyAsText() }.getOrNull())))
                    return@execute
                }
                val channel: ByteReadChannel = response.bodyAsChannel()
                while (!channel.isClosedForRead) {
                    val line = channel.readLine() ?: break
                    if (line.startsWith("data:")) {
                        val payload = line.substring("data:".length).trim()
                        for (event in decoder.decode(payload)) send(event)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            send(
                ChatEvent.Failed(
                    ApiError(
                        httpStatus = null,
                        code = null,
                        message = e.message ?: "Network error",
                        retryable = true,
                    ),
                ),
            )
        } finally {
            client.close()
        }
    }

    override suspend fun chat(request: ChatRequest): ChatResponse =
        throw UnsupportedOperationException("Non-streaming chat() not yet implemented; use streamChat().")

    override suspend fun listModels(): List<ModelInfo> {
        val client = newClient()
        try {
            val response: ModelsResponse = client.get("$baseUrl/models") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                defaultHeaders.forEach { (k, v) -> header(k, v) }
            }.body()
            return response.data.map { it.toModelInfo() }
        } finally {
            client.close()
        }
    }

    private fun errorFor(status: Int, body: String?): ApiError {
        val parsed = body?.let { runCatching { json.decodeFromString<HttpErrorEnvelope>(it).error }.getOrNull() }
        return ApiError(
            httpStatus = status,
            code = parsed?.code,
            message = parsed?.message ?: "HTTP $status",
            retryable = status == 429 || status >= 500,
        )
    }

    private fun ChatRequest.toWire(stream: Boolean) = ChatCompletionRequest(
        model = model,
        messages = messages.map { it.toWire() },
        stream = stream,
        temperature = temperature,
        maxTokens = maxTokens,
        tools = tools.takeIf { it.isNotEmpty() }?.map { it.toWire() },
        parallelToolCalls = parallelToolCalls,
    )

    private fun ChatMessage.toWire(): WireMessage = when (this) {
        is ChatMessage.System -> WireMessage(role = "system", content = content)
        is ChatMessage.User -> WireMessage(role = "user", content = content)
        is ChatMessage.Assistant -> WireMessage(
            role = "assistant",
            content = content,
            toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map {
                WireToolCall(id = it.id, function = WireFunctionCall(it.name, it.argumentsJson))
            },
        )
        is ChatMessage.Tool -> WireMessage(role = "tool", content = content, toolCallId = toolCallId)
    }

    private fun ToolSpec.toWire() = WireTool(
        function = WireFunction(
            name = name,
            description = description,
            parameters = json.decodeFromString(JsonObject.serializer(), parametersJsonSchema),
        ),
    )
}

/** OpenRouter = OpenAI-compatible + attribution headers (recommended for app identification). */
fun openRouterProvider(
    apiKey: String,
    engine: HttpClientEngine,
    appUrl: String = "https://orangerabbit.io/clanker",
    appTitle: String = "clanker",
): OpenAiCompatibleProvider = OpenAiCompatibleProvider(
    id = ProviderId("openrouter"),
    displayName = "OpenRouter",
    baseUrl = "https://openrouter.ai/api/v1",
    apiKey = apiKey,
    engine = engine,
    defaultHeaders = mapOf("HTTP-Referer" to appUrl, "X-Title" to appTitle),
)

private fun ModelDto.toModelInfo(): ModelInfo {
    val capabilities = buildSet {
        add(Capability.Streaming) // OpenAI-compatible endpoints stream by default
        if (supportedParameters.any { it == "tools" }) add(Capability.ToolCalling)
        if (supportedParameters.any { it.contains("reasoning") }) add(Capability.Reasoning)
        if (architecture?.inputModalities?.any { it == "image" } == true) add(Capability.Vision)
    }
    return ModelInfo(
        id = id,
        displayName = name ?: id,
        contextLength = contextLength,
        capabilities = capabilities,
    )
}

// --- Wire request DTOs (isolated to :core:network) ---

@Serializable
private data class ModelsResponse(val data: List<ModelDto> = emptyList())

@Serializable
private data class ModelDto(
    val id: String,
    val name: String? = null,
    @SerialName("context_length") val contextLength: Int? = null,
    val architecture: ArchitectureDto? = null,
    @SerialName("supported_parameters") val supportedParameters: List<String> = emptyList(),
)

@Serializable
private data class ArchitectureDto(
    @SerialName("input_modalities") val inputModalities: List<String> = emptyList(),
)

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<WireMessage>,
    val stream: Boolean,
    val temperature: Double? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val tools: List<WireTool>? = null,
    @SerialName("parallel_tool_calls") val parallelToolCalls: Boolean? = null,
)

@Serializable
private data class WireMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<WireToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
)

@Serializable
private data class WireToolCall(
    val id: String,
    val type: String = "function",
    val function: WireFunctionCall,
)

@Serializable
private data class WireFunctionCall(val name: String, val arguments: String)

@Serializable
private data class WireTool(val type: String = "function", val function: WireFunction)

@Serializable
private data class WireFunction(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
private data class HttpErrorEnvelope(val error: HttpError? = null)

@Serializable
private data class HttpError(
    val message: String = "",
    @SerialName("code") val codeRaw: JsonElement? = null,
    val type: String? = null,
) {
    val code: String? get() = codeRaw?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
}
