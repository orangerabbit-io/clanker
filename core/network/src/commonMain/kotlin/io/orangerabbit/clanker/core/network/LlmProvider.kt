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
    /**
     * OpenRouter server tools (e.g. web search) advertised in the request. OpenRouter executes
     * these server-side within the same completion; the client never runs them and never returns a
     * `role:tool` reply. Serialized into the same wire `tools` array as [tools].
     */
    val serverTools: List<ServerTool> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val parallelToolCalls: Boolean? = null,
    /**
     * Output modalities requested from the model. Empty = the provider default (text). Pass
     * `["image", "text"]` against an image-output model (e.g. `google/gemini-2.5-flash-image`)
     * to ask for generated images, surfaced as [ChatEvent.ImageDelta].
     */
    val modalities: List<String> = emptyList(),
)

/** A tool advertised to the model: name + JSON-Schema parameters. */
data class ToolSpec(
    val name: String,
    val description: String,
    val parametersJsonSchema: String,
)

/**
 * An OpenRouter server tool: a model-callable tool OpenRouter operates server-side. Declared in the
 * request `tools` array as `{"type":"openrouter:…","parameters":{…}}`. Results stream back inline
 * (web tools surface as `url_citation` annotations); the client executes nothing.
 */
sealed interface ServerTool {
    /** Web search. [engine] defaults to "auto" (falls back to Exa); never send "native" by default. */
    data class WebSearch(val maxResults: Int? = null, val engine: String = "auto") : ServerTool
    data object WebFetch : ServerTool
    data object Datetime : ServerTool
    data class ImageGeneration(
        val model: String? = null,
        val size: String? = null,
        val quality: String? = null,
    ) : ServerTool
}

/**
 * The server tools clanker enables "always-on for capable models": web search, fetch, and datetime.
 * Returned unless the model is KNOWN to lack tool calling. [capabilities] is null when the model
 * catalogue hasn't been fetched yet (the common case on a fresh send): we default to enabling the
 * tools, since OpenRouter runs web tools broadly with `engine=auto` and degrades gracefully —
 * suppressing them on unknown capability would silently break web search whenever the catalogue
 * isn't loaded.
 *
 * [ServerTool.ImageGeneration] is deliberately NOT in the always-on set: invoked inline against an
 * arbitrary chat model it fails ("Server tool request failed", verified on deepseek-v4-flash).
 * Image generation runs through the dedicated image mode instead (modalities + the settings image
 * model). The type remains available for explicit use. Pure so it is unit-testable without the UI.
 */
fun defaultServerTools(capabilities: Set<Capability>?): List<ServerTool> =
    if (capabilities == null || Capability.ToolCalling in capabilities) {
        listOf(
            ServerTool.WebSearch(),
            ServerTool.WebFetch,
            ServerTool.Datetime,
        )
    } else {
        emptyList()
    }

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

enum class Capability { Streaming, ToolCalling, Vision, Reasoning, ImageOutput }

enum class FinishReason { Stop, ToolCalls, Length, ContentFilter, Error }

data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val costUsd: Double? = null,
    /** Server-tool web search calls OpenRouter executed this turn (from `server_tool_use`). */
    val webSearchRequests: Int? = null,
)

/** Thrown by non-streaming calls (e.g. [LlmProvider.listModels]) when the API returns an error. */
class LlmApiException(val apiError: ApiError) : Exception(apiError.message)

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

    /** A fully-formed generated image as a `data:` URL (base64). Image deltas are not chunked. */
    data class ImageDelta(val dataUrl: String) : ChatEvent
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argsFragment: String?,
    ) : ChatEvent

    /** A web source cited by a server-executed web tool. May arrive multiple times per turn. */
    data class CitationDelta(val citation: io.orangerabbit.clanker.core.model.Citation) : ChatEvent

    data class UsageReport(val usage: Usage) : ChatEvent
    data class Finished(val reason: FinishReason) : ChatEvent
    data class Failed(val error: ApiError) : ChatEvent
}
