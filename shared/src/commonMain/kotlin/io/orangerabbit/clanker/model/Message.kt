package io.orangerabbit.clanker.model

import kotlinx.serialization.Serializable

/** Lifecycle state of an assistant message. */
enum class MsgLifecycle {
    STREAMING,
    COMPLETE,
    INTERRUPTED,
    FAILED,
}

/**
 * Sealed hierarchy of chat messages.
 * Wire-faithful: AssistantMessage carries [rawJson] for verbatim storage of
 * the original OpenRouter response, ensuring opaque fields (e.g. Anthropic
 * `signature`) round-trip byte-identical.
 */
@Serializable
sealed interface ChatMessage

@Serializable
data class SystemMessage(val content: String) : ChatMessage

@Serializable
data class UserMessage(val content: String) : ChatMessage

@Serializable
data class AssistantMessage(
    val content: String?,
    val toolCalls: List<ToolCall> = emptyList(),
    val reasoning: List<ReasoningBlock> = emptyList(),
    val rawJson: String? = null,
    val lifecycle: MsgLifecycle = MsgLifecycle.STREAMING,
) : ChatMessage

@Serializable
data class ToolResultMessage(
    val toolCallId: String,
    val content: String,
    val isError: Boolean = false,
) : ChatMessage

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * Carries the typed interpretation of a reasoning block alongside any opaque
 * JSON fields (e.g. Anthropic `signature`) that must survive verbatim.
 */
@Serializable
data class ReasoningBlock(
    val type: String,
    val text: String?,
    val opaqueJson: String?,
)

/**
 * Persistence helper: stores the wire-verbatim [rawJson] alongside the typed
 * [typed] view, so opaque fields in the response are never lost.
 */
@Serializable
data class WireEnvelope(
    val rawJson: String,
    val typed: AssistantMessage,
)
