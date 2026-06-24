package io.orangerabbit.clanker.core.network

import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.ReasoningBlock
import kotlinx.coroutines.flow.Flow

/**
 * The single seam every LLM backend implements. No OpenAI-shaped DTO escapes this boundary;
 * callers (the agent loop, the UI) only ever see [ChatRequest]/[ChatEvent]/[ChatResponse] and
 * the provider-neutral domain types from `:core:model`.
 */
interface LlmProvider {
    val id: io.orangerabbit.clanker.core.model.ProviderId
    val displayName: String

    /** Discover models and their capabilities; populates the capability set used by the UI/loop. */
    suspend fun listModels(): List<ModelInfo>

    /** Streaming chat completion. The returned flow is cold; collecting it starts the request. */
    fun streamChat(request: ChatRequest): Flow<ChatEvent>

    /** Non-streaming chat completion. */
    suspend fun chat(request: ChatRequest): ChatResponse
}

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val parallelToolCalls: Boolean? = null,
)

/** A tool advertised to the model: name + JSON-Schema parameters. */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

data class ChatResponse(
    val message: ChatMessage.Assistant,
    val usage: Usage?,
    val finishReason: FinishReason,
)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val contextLength: Int?,
    val capabilities: Set<Capability>,
)

enum class Capability { Streaming, ToolCalling, Vision, Reasoning }

enum class FinishReason { Stop, ToolCalls, Length, ContentFilter, Error }

data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val costUsd: Double? = null,
)

data class ApiError(
    val httpStatus: Int?,
    val code: String?,
    val message: String,
    /** True for 429/5xx/network — the retry layer may retry these; never retry 4xx auth/validation. */
    val retryable: Boolean,
)

/**
 * Streaming events emitted by [LlmProvider.streamChat]. Tool-call *fragments* are surfaced as
 * [ToolCallDelta] keyed by [ToolCallDelta.index]; accumulation into a final tool call is the
 * agent loop's job (id/name may be null on later fragments; args arrive in pieces).
 */
sealed interface ChatEvent {
    data class TextDelta(val text: String) : ChatEvent
    data class ReasoningDelta(val block: ReasoningBlock) : ChatEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argsFragment: String?,
    ) : ChatEvent

    data class UsageReport(val usage: Usage) : ChatEvent
    data class Finished(val reason: FinishReason) : ChatEvent
    data class Failed(val error: ApiError) : ChatEvent
}
