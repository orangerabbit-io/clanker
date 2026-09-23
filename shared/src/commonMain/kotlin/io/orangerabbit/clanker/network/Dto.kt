package io.orangerabbit.clanker.network

import io.orangerabbit.clanker.model.ChatMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Server-tool spec for the request `tools` array. `parametersJson` is null
 * for parameterless tools (web_fetch, datetime) — the client omits the key.
 */
@Serializable
data class ToolSpec(
    val type: String,
    val parametersJson: String? = null,
)

/** Request for [OpenRouterClient.streamChat]. */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val maxToolCalls: Int? = null,
    val spendCapUsd: Double? = null,
)

/**
 * Token/cost accounting for a completed or interrupted stream, surfaced to
 * the UI for cost display (Task 8 shows `$0.0012` or `cost unavailable`).
 */
data class Usage(
    val totalCost: Double?,
    val promptTokens: Long?,
    val completionTokens: Long?,
    val serverToolUse: Map<String, Int>?,
)

/** Events streamed from OpenRouter during [OpenRouterClient.streamChat]. */
sealed interface StreamEvent {
    data class Content(val text: String) : StreamEvent
    data class Reasoning(val text: String) : StreamEvent

    /**
     * Server-tool source citations (url_citation annotations) carried on a
     * streamed assistant delta. Server tools execute at OpenRouter
     * mid-request, so sources arrive with already-final deltas.
     */
    data class Sources(val sources: List<Source>) : StreamEvent
    data class Done(val usage: Usage) : StreamEvent
    data class Failed(val message: String) : StreamEvent
}

/** One source citation (url_citation annotation): link + title for the UI. */
data class Source(
    val url: String,
    val title: String?,
)

/** Terminal outcome of a [OpenRouterClient.streamChat] call. */
sealed interface ChatResult {
    /** Verbatim `data:` payloads of every parsed chunk, joined with `\n` in arrival order (null when none were parsed). */
    data class Completed(val usage: Usage?, val rawJson: String? = null) : ChatResult
    data class Interrupted(val usage: Usage?, val rawJson: String? = null) : ChatResult
    data class Failed(val message: String) : ChatResult
}

/** Shared decoder: OpenRouter chunks carry fields we don't model (id, model, finish_reason, ...). */
internal val chatJson: Json = Json { ignoreUnknownKeys = true }

// ── Wire DTOs (OpenAI-compatible SSE chunks) ─────────────────────────────────

@Serializable
internal data class StreamChunk(
    val choices: List<StreamChunkChoice> = emptyList(),
    val usage: StreamUsage? = null,
)

@Serializable
internal data class StreamChunkChoice(
    val delta: StreamDelta? = null,
)

/**
 * OpenAI-compatible delta. `reasoning` is OpenRouter's reasoning token text;
 * unknown fields such as `reasoning_details` are tolerated via
 * [chatJson] (ignoreUnknownKeys).
 */
@Serializable
internal data class StreamDelta(
    val content: String? = null,
    val reasoning: String? = null,
    val annotations: List<StreamAnnotation> = emptyList(),
)

/** Delta annotation; only `url_citation` is modeled (others are ignored). */
@Serializable
internal data class StreamAnnotation(
    val type: String? = null,
    @SerialName("url_citation") val urlCitation: StreamUrlCitation? = null,
)

@Serializable
internal data class StreamUrlCitation(
    val url: String? = null,
    val title: String? = null,
    val content: String? = null,
)

@Serializable
internal data class StreamUsage(
    @SerialName("total_tokens") val totalTokens: Long? = null,
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
    val cost: Double? = null,
    @SerialName("server_tool_use") val serverToolUse: StreamServerToolUse? = null,
) {
    fun toUsage(): Usage = Usage(
        totalCost = cost,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        serverToolUse = serverToolUse?.toCountMap(),
    )
}

/** OpenRouter `usage.server_tool_use`: per-tool request counts. */
@Serializable
internal data class StreamServerToolUse(
    @SerialName("web_search_requests") val webSearchRequests: Int? = null,
    @SerialName("web_fetch_requests") val webFetchRequests: Int? = null,
) {
    fun toCountMap(): Map<String, Int> = buildMap {
        webSearchRequests?.let { put("web_search_requests", it) }
        webFetchRequests?.let { put("web_fetch_requests", it) }
    }
}
