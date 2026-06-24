package io.orangerabbit.clanker.core.network

import io.orangerabbit.clanker.core.model.ReasoningBlock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive

/**
 * Maps a single OpenAI-style SSE `data:` payload to zero or more [ChatEvent]s. Stream framing
 * (splitting on blank lines, stripping the `data: ` prefix, keepalive `:` comments) is handled
 * upstream by the Ktor SSE client; this unit owns only the per-payload JSON → event mapping.
 *
 * Stateless and pure: tool-call *fragments* are surfaced as [ChatEvent.ToolCallDelta] keyed by
 * index, and the agent loop is responsible for accumulating them into a final tool call.
 */
class OpenAiSseDecoder(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun decode(dataPayload: String): List<ChatEvent> {
        val payload = dataPayload.trim()
        if (payload.isEmpty() || payload == DONE) return emptyList()

        val chunk = try {
            json.decodeFromString<StreamChunk>(payload)
        } catch (e: Exception) {
            return listOf(
                ChatEvent.Failed(
                    ApiError(
                        httpStatus = null,
                        code = null,
                        message = "Malformed SSE payload: ${e.message}",
                        retryable = false,
                    ),
                ),
            )
        }

        chunk.error?.let { return listOf(ChatEvent.Failed(it.toApiError())) }

        val events = mutableListOf<ChatEvent>()
        // Single-choice streaming (n=1) is all clanker requests; index 0 is the active choice.
        val choice = chunk.choices.firstOrNull()
        if (choice != null) {
            choice.delta.content?.takeIf { it.isNotEmpty() }
                ?.let { events += ChatEvent.TextDelta(it) }
            choice.delta.reasoning?.let {
                events += ChatEvent.ReasoningDelta(ReasoningBlock(type = "reasoning", text = it))
            }
            choice.delta.images.forEach { img ->
                img.imageUrl?.url?.takeIf { it.isNotEmpty() }
                    ?.let { events += ChatEvent.ImageDelta(it) }
            }
            choice.delta.toolCalls.forEach { tc ->
                events += ChatEvent.ToolCallDelta(
                    index = tc.index,
                    id = tc.id,
                    name = tc.function.name,
                    argsFragment = tc.function.arguments,
                )
            }
        }
        chunk.usage?.let { events += ChatEvent.UsageReport(it.toUsage()) }
        choice?.finishReason?.let { events += ChatEvent.Finished(it.toFinishReason()) }
        return events
    }

    private companion object {
        const val DONE = "[DONE]"
    }
}

private fun String.toFinishReason(): FinishReason = when (this) {
    "stop" -> FinishReason.Stop
    "tool_calls" -> FinishReason.ToolCalls
    "length" -> FinishReason.Length
    "content_filter" -> FinishReason.ContentFilter
    else -> FinishReason.Error
}

private fun UsageDto.toUsage() = Usage(
    promptTokens = promptTokens,
    completionTokens = completionTokens,
    totalTokens = totalTokens,
    costUsd = cost,
)

private fun ErrorDto.toApiError(): ApiError {
    val codeStr = code?.jsonPrimitive?.content
    val retryable = when {
        type?.contains("rate", ignoreCase = true) == true -> true
        type?.contains("overload", ignoreCase = true) == true -> true
        codeStr in setOf("429", "500", "502", "503", "504") -> true
        else -> false
    }
    return ApiError(httpStatus = null, code = codeStr, message = message, retryable = retryable)
}

// --- Wire DTOs (isolated to :core:network; never escape the provider boundary) ---

@Serializable
private data class StreamChunk(
    val choices: List<Choice> = emptyList(),
    val usage: UsageDto? = null,
    val error: ErrorDto? = null,
)

@Serializable
private data class Choice(
    val index: Int = 0,
    val delta: Delta = Delta(),
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class Delta(
    val content: String? = null,
    val reasoning: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto> = emptyList(),
    /** Generated images (image-output models). Each carries a `data:` URL in [ImageDto.imageUrl]. */
    val images: List<ImageDto> = emptyList(),
)

@Serializable
private data class ImageDto(
    val type: String? = null,
    @SerialName("image_url") val imageUrl: ImageUrlDto? = null,
)

@Serializable
private data class ImageUrlDto(val url: String? = null)

@Serializable
private data class ToolCallDto(
    val index: Int = 0,
    val id: String? = null,
    val type: String? = null,
    val function: FunctionDto = FunctionDto(),
)

@Serializable
private data class FunctionDto(
    val name: String? = null,
    val arguments: String? = null,
)

@Serializable
private data class UsageDto(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    val cost: Double? = null,
)

@Serializable
private data class ErrorDto(
    val message: String = "",
    // code may be an int (OpenRouter HTTP status) or a string (OpenAI) — accept either.
    val code: JsonElement? = null,
    val type: String? = null,
)
