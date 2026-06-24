package io.orangerabbit.clanker.core.model

/**
 * Lifecycle of a single message. Persisted so a process death or interrupt mid-turn is
 * recoverable: on relaunch a message that was [Streaming] is reconciled rather than left dangling.
 */
enum class MsgLifecycle { Streaming, Complete, Failed, Interrupted, Aborted }

/**
 * Wire-faithful, provider-neutral chat message. Stored messages mirror the API wire shape
 * (including opaque structured reasoning) so they can be resent verbatim; UI state is a
 * separate projection derived from these.
 */
sealed interface ChatMessage {
    val id: MessageId
    val lifecycle: MsgLifecycle

    data class System(
        override val id: MessageId,
        val content: String,
        override val lifecycle: MsgLifecycle = MsgLifecycle.Complete,
    ) : ChatMessage

    data class User(
        override val id: MessageId,
        val content: String,
        val attachments: List<Attachment> = emptyList(),
        /**
         * Inline image inputs as `data:` URLs (base64). The disk-backed [attachments] path is the
         * eventual home (see [Attachment.Image]); for the in-memory MVP, multimodal vision input
         * rides here and is encoded to the OpenAI `image_url` content-array form at request time.
         */
        val imageUrls: List<String> = emptyList(),
        override val lifecycle: MsgLifecycle = MsgLifecycle.Complete,
    ) : ChatMessage

    data class Assistant(
        override val id: MessageId,
        /** Null when the turn carries only [toolCalls]. */
        val content: String?,
        val toolCalls: List<ToolCall> = emptyList(),
        /** Opaque structured reasoning — must round-trip verbatim (see [ReasoningBlock]). */
        val reasoning: List<ReasoningBlock> = emptyList(),
        /**
         * Images emitted by an image-generation model, as `data:` URLs (base64). Display-only on
         * resend for now — the wire encoder does not echo these back as assistant content, since
         * the Chat Completions input schema has no assistant-image slot.
         */
        val imageUrls: List<String> = emptyList(),
        override val lifecycle: MsgLifecycle = MsgLifecycle.Complete,
    ) : ChatMessage

    data class Tool(
        override val id: MessageId,
        /** Must match the [ToolCall.id] this result answers. */
        val toolCallId: String,
        val content: String,
        val isError: Boolean = false,
        override val lifecycle: MsgLifecycle = MsgLifecycle.Complete,
    ) : ChatMessage
}

/**
 * A tool invocation requested by the model. [argumentsJson] is the raw JSON *string* the model
 * emitted — it is validated and clamped against the tool's schema before execution, and is
 * always treated as hostile input.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * Provider reasoning/thinking. Anthropic-style signed or encrypted thinking cannot survive
 * flattening to a plain string, so the opaque parts ([signature], [opaqueData]) are preserved
 * exactly and resent unchanged.
 */
data class ReasoningBlock(
    val type: String,
    val text: String? = null,
    val signature: String? = null,
    val opaqueData: String? = null,
)

/** First-class multimodal input. Bytes live on disk behind a [BlobRef], never inlined. */
sealed interface Attachment {
    data class Image(
        val mime: String,
        val bytesRef: BlobRef,
        val width: Int,
        val height: Int,
    ) : Attachment

    data class File(
        val mime: String,
        val name: String,
        val bytesRef: BlobRef,
    ) : Attachment
}
