package io.orangerabbit.clanker.network

import io.orangerabbit.clanker.model.ChatMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Minimal tool placeholder for the request payload; Task 9 replaces this with
 * the real ToolSpec definition.
 */
@Serializable
data class ToolSpec(
    val type: String,
    val parametersJson: String,
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
    data class Done(val usage: Usage) : StreamEvent
    data class Failed(val message: String) : StreamEvent
}

/** Terminal outcome of a [OpenRouterClient.streamChat] call. */
sealed interface ChatResult {
    data class Completed(val usage: Usage?) : ChatResult
    data class Interrupted(val usage: Usage?) : ChatResult
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
)

@Serializable
internal data class StreamUsage(
    @SerialName("total_tokens") val totalTokens: Long? = null,
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
    val cost: Double? = null,
) {
    fun toUsage(): Usage = Usage(
        totalCost = cost,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        serverToolUse = null,
    )
}
