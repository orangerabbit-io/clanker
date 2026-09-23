package io.orangerabbit.clanker.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.orangerabbit.clanker.model.AssistantMessage
import io.orangerabbit.clanker.model.ChatMessage
import io.orangerabbit.clanker.model.SystemMessage
import io.orangerabbit.clanker.model.ToolResultMessage
import io.orangerabbit.clanker.model.UserMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** OpenRouter app attribution headers (required by OpenRouter for app identification). */
const val APP_REFERER: String = "https://github.com/orangerabbit-io/clanker"
const val APP_TITLE: String = "clanker"

/**
 * Streaming OpenRouter chat-completions client.
 *
 * @param baseUrl override for tests/self-hosted gateways; MUST be HTTPS or the
 *   constructor throws, so API keys are never sent over plaintext.
 */
class OpenRouterClient(
    private val apiKeyProvider: suspend () -> String,
    engine: HttpClientEngine? = null,
    baseUrl: String = BASE_URL,
) {
    init {
        if (!baseUrl.startsWith("https://")) {
            throw IllegalArgumentException("baseUrl must be HTTPS-only; got: $baseUrl")
        }
    }

    private val http = engine?.let { HttpClient(it) } ?: HttpClient()
    private val endpoint = "$baseUrl/chat/completions"
    private val modelsEndpoint = "$baseUrl/models"

    /**
     * Streams [req] to OpenRouter, delivering [StreamEvent]s to [onEvent] as
     * they arrive. Returns the terminal [ChatResult]:
     *  - [ChatResult.Completed] after the `[DONE]` sentinel (usage may be null).
     *  - [ChatResult.Interrupted] when the transport fails mid-stream; content
     *    already emitted via [onEvent] is never lost or re-emitted.
     *  - [ChatResult.Failed] on non-2xx HTTP responses.
     *
     * Completed and Interrupted also carry [ChatResult.rawJson]: the verbatim
     * `data:` payload of every parsed chunk, joined with `\n` in arrival
     * order, so persisted messages stay wire-faithful across restarts.
     * The API key provider is invoked exactly once per call.
     */
    suspend fun streamChat(req: ChatRequest, onEvent: (StreamEvent) -> Unit): ChatResult {
        val apiKey = apiKeyProvider()
        var lastUsage: Usage? = null
        fun recording(onEvent: (StreamEvent) -> Unit): (StreamEvent) -> Unit = { event ->
            if (event is StreamEvent.Done) lastUsage = event.usage
            onEvent(event)
        }
        return try {
            http.preparePost(endpoint) {
                contentType(ContentType.Application.Json)
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                    append(REFERER_HEADER, APP_REFERER)
                    append(TITLE_HEADER, APP_TITLE)
                }
                setBody(requestBody(req).toString())
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    return@execute ChatResult.Failed(
                        "HTTP ${response.status.value}: ${response.status.description}",
                    )
                }
                val channel = response.bodyAsChannel()
                val reader = SseReader(channel)
                val rawChunks = mutableListOf<String>()
                return@execute try {
                    val done = reader.events(recording(onEvent)) { rawChunks += it }
                    val rawJson = rawChunks.joinToString("\n").takeIf { it.isNotEmpty() }
                    if (done) {
                        ChatResult.Completed(lastUsage, rawJson)
                    } else {
                        ChatResult.Interrupted(lastUsage, rawJson)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Chunks parsed before the failure still round-trip.
                    ChatResult.Interrupted(lastUsage, rawChunks.joinToString("\n").takeIf { it.isNotEmpty() })
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChatResult.Failed("stream setup failed: ${e.message}")
        }
    }

    /**
     * Fetches the public model catalog from `GET /api/v1/models`, parsed into
     * [ModelSummary]s sorted by name.
     *
     * No Authorization header: this endpoint is public and headers are
     * per-request in this client, so it is simply omitted.
     *
     * @throws IllegalStateException on non-2xx HTTP response
     */
    suspend fun models(): List<ModelSummary> {
        val response = http.get(modelsEndpoint)
        check(response.status.value in 200..299) { "Model catalog fetch failed: HTTP ${response.status}" }
        return parseCatalog(response.bodyAsText())
    }

    companion object {
        const val BASE_URL: String = "https://openrouter.ai/api/v1"
        private const val REFERER_HEADER = "HTTP-Referer"
        private const val TITLE_HEADER = "X-Title"
    }
}

/** OpenAI wire format: `{"role":..., "content":...}` (+ tool_call_id for tool results). */
private fun ChatMessage.wireJson(): JsonObject = buildJsonObject {
    when (this@wireJson) {
        is SystemMessage -> {
            put("role", "system")
            put("content", content)
        }
        is UserMessage -> {
            put("role", "user")
            put("content", content)
        }
        is AssistantMessage -> {
            put("role", "assistant")
            put("content", content ?: "")
            if (toolCalls.isNotEmpty()) {
                put(
                    "tool_calls",
                    JsonArray(
                        toolCalls.map { call ->
                            buildJsonObject {
                                put("id", call.id)
                                put("type", "function")
                                put(
                                    "function",
                                    buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.argumentsJson)
                                    },
                                )
                            }
                        },
                    ),
                )
            }
        }
        is ToolResultMessage -> {
            put("role", "tool")
            put("content", content)
            put("tool_call_id", toolCallId)
        }
    }
}

private fun requestBody(req: ChatRequest): JsonObject = buildJsonObject {
    put("model", req.model)
    put("stream", true)
    put("messages", JsonArray(req.messages.map { it.wireJson() }))
    // Docs-verified (OpenRouter API reference, stop_server_tools_when):
    // [{"step_count":5,"type":"step_count_is"},{"max_cost_in_dollars":0.5,"type":"max_cost"}]
    // OR-logic stop conditions; when set they override max_tool_calls entirely,
    // so max_tool_calls is omitted. Emitted only when a spend cap is present —
    // a step-count-only request keeps the plain max_tool_calls field.
    if (req.spendCapUsd != null) {
        val stopConditions = buildList {
            req.spendCapUsd?.let { cap ->
                add(buildJsonObject {
                    put("type", "max_cost")
                    put("max_cost_in_dollars", cap)
                })
            }
            req.maxToolCalls?.let { steps ->
                add(buildJsonObject {
                    put("type", "step_count_is")
                    put("step_count", steps)
                })
            }
        }
        put("stop_server_tools_when", JsonArray(stopConditions))
    } else {
        req.maxToolCalls?.let { put("max_tool_calls", it) }
    }
    if (req.tools.isNotEmpty()) {
        put(
            "tools",
            JsonArray(
                req.tools.map { tool ->
                    buildJsonObject {
                        put("type", tool.type)
                        tool.parametersJson?.let { put("parameters", chatJson.parseToJsonElement(it)) }
                    }
                },
            ),
        )
    }
}
