package io.orangerabbit.clanker.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
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
        // Image generation can stall for a long time before any bytes arrive (the model renders
        // before emitting), so the default ~10s socket read timeout trips mid-generation. Allow a
        // long inter-byte gap and an unbounded total request (the SSE stream length is open-ended).
        // requestTimeoutMillis is left unset (no cap on total request duration — the SSE stream is
        // open-ended); only the connect and inter-byte read timeouts are bounded.
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECT_TIMEOUT_MS
            socketTimeoutMillis = SOCKET_TIMEOUT_MS
        }
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

    /**
     * Validates the API key against an AUTHENTICATED endpoint (OpenRouter `GET /key`). Note that
     * `GET /models` is PUBLIC and ignores the key, so it must NOT be used as an auth check — a bad
     * key still returns the full catalogue there. Throws [LlmApiException] when the key is rejected.
     */
    suspend fun validateKey() {
        val client = newClient()
        try {
            val resp = client.get("$baseUrl/key") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                defaultHeaders.forEach { (k, v) -> header(k, v) }
            }
            if (!resp.status.isSuccess()) {
                val body = runCatching { resp.bodyAsText() }.getOrNull()
                throw LlmApiException(errorFor(resp.status.value, body))
            }
        } finally {
            client.close()
        }
    }

    override suspend fun listModels(): List<ModelInfo> {
        val client = newClient()
        try {
            val httpResponse = client.get("$baseUrl/models") {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                defaultHeaders.forEach { (k, v) -> header(k, v) }
            }
            // Surface auth/quota failures as a meaningful message (e.g. "User not found", "Invalid
            // API key") instead of an opaque deserialization error from parsing the error body.
            if (!httpResponse.status.isSuccess()) {
                val body = runCatching { httpResponse.bodyAsText() }.getOrNull()
                throw LlmApiException(errorFor(httpResponse.status.value, body))
            }
            return httpResponse.body<ModelsResponse>().data.map { it.toModelInfo() }
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
        modalities = modalities.takeIf { it.isNotEmpty() },
        usage = UsageInclude(include = true), // guarantee usage+cost in the trailing chunk
    )

    private fun ChatMessage.toWire(): WireMessage = when (this) {
        is ChatMessage.System -> WireMessage(role = "system", content = textContent(content))
        is ChatMessage.User -> WireMessage(role = "user", content = userContent(content, imageUrls))
        is ChatMessage.Assistant -> WireMessage(
            role = "assistant",
            content = content?.let { textContent(it) },
            toolCalls = toolCalls.takeIf { it.isNotEmpty() }?.map {
                WireToolCall(id = it.id, function = WireFunctionCall(it.name, it.argumentsJson))
            },
        )
        is ChatMessage.Tool -> WireMessage(role = "tool", content = textContent(content), toolCallId = toolCallId)
    }

    /** Plain-string message content (the common case). */
    private fun textContent(text: String): JsonElement = JsonPrimitive(text)

    /**
     * User content. With no images this is the plain string form; with images it becomes the
     * OpenAI multimodal content array (`[{type:text},{type:image_url,image_url:{url:…}}]`), which
     * is the wire shape vision models require for inline base64 `data:` URLs.
     */
    private fun userContent(text: String, imageUrls: List<String>): JsonElement {
        if (imageUrls.isEmpty()) return JsonPrimitive(text)
        return buildJsonArray {
            if (text.isNotEmpty()) {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
            imageUrls.forEach { url ->
                addJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", url) }
                }
            }
        }
    }

    private fun ToolSpec.toWire() = WireTool(
        function = WireFunction(
            name = name,
            description = description,
            parameters = json.decodeFromString(JsonObject.serializer(), parametersJsonSchema),
        ),
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000L
        // Inter-byte read timeout. Image models can think for ~minute(s) before emitting bytes.
        const val SOCKET_TIMEOUT_MS = 180_000L
    }
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
        if (architecture?.outputModalities?.any { it == "image" } == true) add(Capability.ImageOutput)
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
    @SerialName("output_modalities") val outputModalities: List<String> = emptyList(),
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
    val modalities: List<String>? = null,
    val usage: UsageInclude? = null,
)

/** Opt into usage accounting so OpenRouter returns the `cost` field in the trailing usage chunk. */
@Serializable
private data class UsageInclude(val include: Boolean)

@Serializable
private data class WireMessage(
    val role: String,
    // String for plain text, or a content-part array for multimodal user messages.
    val content: JsonElement? = null,
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
