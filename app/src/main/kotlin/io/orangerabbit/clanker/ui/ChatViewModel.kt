package io.orangerabbit.clanker.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Inject
import io.ktor.client.engine.HttpClientEngine
import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.MessageId
import io.orangerabbit.clanker.core.model.MsgLifecycle
import io.orangerabbit.clanker.core.network.ChatEvent
import io.orangerabbit.clanker.core.network.ChatRequest
import io.orangerabbit.clanker.core.network.openRouterProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Holds chat state and drives the streaming loop. For this MVP slice the API key lives only in
 * memory (entered per session) — encrypted-at-rest secret storage (Tink/Keystore) is a later
 * phase, so no plaintext secret is ever persisted.
 */
@Inject
class ChatViewModel(
    private val engine: HttpClientEngine,
) : ViewModel() {

    data class UiState(
        val apiKey: String = "",
        val model: String = "openai/gpt-4o-mini",
        val messages: List<ChatMessage> = emptyList(),
        val streaming: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun setApiKey(value: String) = _state.update { it.copy(apiKey = value) }
    fun setModel(value: String) = _state.update { it.copy(model = value) }

    fun send(text: String) {
        val current = _state.value
        if (text.isBlank() || current.apiKey.isBlank() || current.streaming) return

        val userMsg = ChatMessage.User(MessageId(newId()), text.trim())
        val history = current.messages + userMsg
        val assistantId = MessageId(newId())
        _state.update {
            it.copy(
                messages = history + ChatMessage.Assistant(assistantId, content = "", lifecycle = MsgLifecycle.Streaming),
                streaming = true,
                error = null,
            )
        }

        viewModelScope.launch {
            val provider = openRouterProvider(apiKey = current.apiKey, engine = engine)
            val request = ChatRequest(model = current.model, messages = history)
            val buffer = StringBuilder()
            var lastUiUpdate = 0L
            try {
                provider.streamChat(request).collect { event ->
                    when (event) {
                        is ChatEvent.TextDelta -> {
                            buffer.append(event.text)
                            // Throttle recomposition to ~frame cadence; the final text is always
                            // flushed on Finished/Failed below.
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdate >= UI_THROTTLE_MS) {
                                lastUiUpdate = now
                                updateAssistant(assistantId, buffer.toString(), MsgLifecycle.Streaming)
                            }
                        }
                        is ChatEvent.Finished ->
                            updateAssistant(assistantId, buffer.toString(), MsgLifecycle.Complete)
                        is ChatEvent.Failed -> {
                            _state.update { it.copy(error = event.error.message) }
                            updateAssistant(assistantId, buffer.toString(), MsgLifecycle.Failed)
                        }
                        else -> Unit // reasoning / tool-call deltas / usage: not surfaced in MVP UI yet
                    }
                }
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "Unknown error") }
                updateAssistant(assistantId, buffer.toString(), MsgLifecycle.Failed)
            } finally {
                _state.update { it.copy(streaming = false) }
            }
        }
    }

    private fun updateAssistant(id: MessageId, content: String, lifecycle: MsgLifecycle) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg is ChatMessage.Assistant && msg.id == id) {
                        msg.copy(content = content, lifecycle = lifecycle)
                    } else {
                        msg
                    }
                },
            )
        }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private companion object {
        const val UI_THROTTLE_MS = 50L
    }
}
