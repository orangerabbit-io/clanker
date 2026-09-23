package io.orangerabbit.clanker.ui.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import io.orangerabbit.clanker.agent.ChatUiEvent
import io.orangerabbit.clanker.agent.ChatUiState
import io.orangerabbit.clanker.agent.UiMessage
import io.orangerabbit.clanker.agent.reduce
import io.orangerabbit.clanker.model.AssistantMessage
import io.orangerabbit.clanker.model.ChatMessage
import io.orangerabbit.clanker.model.ReasoningBlock
import io.orangerabbit.clanker.model.MsgLifecycle
import io.orangerabbit.clanker.model.UserMessage
import io.orangerabbit.clanker.network.ChatRequest
import io.orangerabbit.clanker.network.ChatResult
import io.orangerabbit.clanker.network.OpenRouterClient
import io.orangerabbit.clanker.network.StreamEvent
import io.orangerabbit.clanker.persistence.ConversationRepository
import io.orangerabbit.clanker.persistence.StoredMessage
import io.orangerabbit.clanker.security.SecretStore
import io.orangerabbit.clanker.util.KeepAwake

/**
 * Chat screen state holder: streams from [OpenRouterClient], reduces deltas via
 * the pure [reduce] (unit-tested in shared), and persists messages to
 * [ConversationRepository] as they complete (user message on send; assistant
 * message on terminal result).
 *
 * Raw streamed responses: the client does not expose verbatim response JSON, so
 * `rawJson` is persisted as null (Phase 1 best-effort; wire-faithful raw storage
 * of streamed responses lands with tool tasks that capture full responses).
 * Per-message cost is likewise only available in the live session — it is not a
 * persisted column, so reloaded messages render "cost unavailable".
 *
 * Keep-awake ownership: the ViewModel acquires on send and releases on every
 * terminal path (completed, interrupted, cancelled, stream error), so the flag
 * tracks the `running` lifecycle even if the composable leaves composition.
 */
class ChatViewModel(
    private val client: OpenRouterClient,
    private val repository: ConversationRepository,
    private val secretStore: SecretStore,
    private val keepAwake: KeepAwake,
    private val conversationId: String,
    private val model: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var streamJob: Job? = null

    init {
        scope.launch {
            try {
                val stored = repository.messagesFor(conversationId)
                _state.update { it.copy(messages = stored.map { m -> m.toUiMessage() }) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to load history: ${e.message ?: "unknown error"}") }
            }
        }
    }

    /** Sends [text] as a user message and starts the assistant stream. */
    fun send(text: String) {
        val trimmed = text.trim()
        val current = _state.value
        if (trimmed.isEmpty() || current.running) return
        if (model.isBlank()) {
            _state.update { it.copy(error = "Select a default model in Settings first.") }
            return
        }

        _state.update {
            it.copy(
                messages = it.messages + UiMessage(role = ROLE_USER, content = trimmed),
                running = true,
                error = null,
            )
        }
        streamJob = scope.launch {
            try {
                repository.appendMessage(
                    conversationId = conversationId,
                    role = ROLE_USER,
                    content = trimmed,
                    toolCallsJson = null,
                    reasoningJson = null,
                    rawJson = null,
                    lifecycle = MsgLifecycle.COMPLETE.name,
                )
            } catch (e: Exception) {
                _state.update { it.copy(error = "Failed to save message: ${e.message ?: "unknown error"}") }
            }

            keepAwake.acquire()
            try {
                val request = ChatRequest(
                    model = model,
                    messages = _state.value.messages.mapNotNull { it.toChatMessage() },
                )
                val result = client.streamChat(request) { event ->
                    when (event) {
                        is StreamEvent.Content -> _state.update { s -> reduce(s, ChatUiEvent.Content(event.text)) }
                        is StreamEvent.Reasoning -> _state.update { s -> reduce(s, ChatUiEvent.Reasoning(event.text)) }
                        // StreamEvent.Done is ignored: the terminal reduce is driven by
                        // ChatResult below (exactly one finalization per stream).
                        is StreamEvent.Done -> {}
                        // Never emitted by the client (Task 6 contract).
                        is StreamEvent.Failed -> {}
                    }
                }
                val uiEvent = when (result) {
                    is ChatResult.Completed -> ChatUiEvent.Done(result.usage)
                    is ChatResult.Interrupted -> ChatUiEvent.Interrupted(result.usage)
                    is ChatResult.Failed -> ChatUiEvent.Failed(result.message)
                }
                _state.update { s -> reduce(s, uiEvent) }
                persistAssistantMessage()
                keepAwake.release()
            } catch (e: CancellationException) {
                // cancel()/scope teardown mid-stream: finalize partial content without
                // suspension, then propagate so the coroutine ends cleanly.
                withContext(NonCancellable) {
                    _state.update { s -> reduce(s, ChatUiEvent.Interrupted(usage = null)) }
                    persistAssistantMessage()
                    keepAwake.release()
                }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = e.message ?: "Unknown error") }
                keepAwake.release()
            }
        }
    }

    /** Interrupts the in-flight stream; partial content is preserved via the reducer. */
    fun cancel() {
        streamJob?.cancel()
        streamJob = null
    }

    /** Persists the trailing assistant message; best-effort (failure surfaces as banner). */
    private suspend fun persistAssistantMessage() {
        val message = _state.value.messages.lastOrNull { it.role == ROLE_ASSISTANT } ?: return
        try {
            repository.appendMessage(
                conversationId = conversationId,
                role = ROLE_ASSISTANT,
                content = message.content.ifEmpty { null },
                toolCallsJson = null,
                reasoningJson = message.reasoning.takeIf { it.isNotEmpty() }
                    ?.let { json.encodeToString(ReasoningList, it) },
                rawJson = null,
                lifecycle = message.lifecycle.name,
            )
        } catch (e: Exception) {
            _state.update { it.copy(error = "Failed to save message: ${e.message ?: "unknown error"}") }
        }
    }

    private fun UiMessage.toChatMessage(): ChatMessage? = when (role) {
        ROLE_USER -> UserMessage(content)
        ROLE_ASSISTANT -> if (content.isEmpty()) null else AssistantMessage(content = content)
        else -> null
    }

    private companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        val ReasoningList = ListSerializer(ReasoningBlock.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** Maps a persisted row back to the UI view (cost is not persisted → null). */
internal fun StoredMessage.toUiMessage(): UiMessage = when (role) {
    "assistant" -> UiMessage(
        role = role,
        content = content.orEmpty(),
        lifecycle = MsgLifecycle.entries.firstOrNull { it.name == lifecycle } ?: MsgLifecycle.COMPLETE,
        reasoning = reasoningJson?.let { raw ->
            try {
                Json.decodeFromString(ReasoningBlockList, raw)
            } catch (_: Exception) {
                emptyList()
            }
        }.orEmpty(),
    )
    else -> UiMessage(role = role, content = content.orEmpty())
}

private val ReasoningBlockList = ListSerializer(ReasoningBlock.serializer())