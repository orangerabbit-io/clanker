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
import io.orangerabbit.clanker.core.character.CharacterCard
import io.orangerabbit.clanker.core.character.CharacterCardParser
import io.orangerabbit.clanker.core.character.Persona
import io.orangerabbit.clanker.core.network.ModelInfo
import io.orangerabbit.clanker.core.network.openRouterProvider
import io.orangerabbit.clanker.data.SecretStore
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
    private val secrets: SecretStore,
) : ViewModel() {

    data class UiState(
        val apiKey: String = "",
        val model: String = "openai/gpt-4o-mini",
        val availableModels: List<ModelInfo> = emptyList(),
        val modelsLoading: Boolean = false,
        val character: CharacterCard? = null,
        val messages: List<ChatMessage> = emptyList(),
        val streaming: Boolean = false,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            secrets.loadApiKey()?.let { stored -> _state.update { it.copy(apiKey = stored) } }
        }
    }

    fun setApiKey(value: String) {
        _state.update { it.copy(apiKey = value) }
        viewModelScope.launch { secrets.saveApiKey(value) }
    }
    fun setModel(value: String) = _state.update { it.copy(model = value) }

    /** Imports a Character Card from raw bytes (PNG with embedded card, or JSON) and applies it. */
    fun applyCardBytes(bytes: ByteArray) {
        viewModelScope.launch {
            try {
                val card = if (isPng(bytes)) {
                    CharacterCardParser.fromPng(bytes)
                } else {
                    CharacterCardParser.fromJson(bytes.decodeToString())
                }
                val greeting = Persona.greeting(card)
                val seeded = if (greeting.isNotBlank()) {
                    listOf(ChatMessage.Assistant(MessageId(newId()), content = greeting))
                } else {
                    emptyList()
                }
                _state.update { it.copy(character = card, messages = seeded, error = null) }
            } catch (e: Throwable) {
                _state.update { it.copy(error = "Card import failed: ${e.message}") }
            }
        }
    }

    private fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()

    /** Fetches the provider's model catalogue for the dropdown. No-op without an API key. */
    fun loadModels() {
        val current = _state.value
        if (current.apiKey.isBlank() || current.modelsLoading) return
        _state.update { it.copy(modelsLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val models = openRouterProvider(apiKey = current.apiKey, engine = engine)
                    .listModels()
                    .sortedBy { it.id }
                _state.update { it.copy(availableModels = models, modelsLoading = false) }
            } catch (e: Throwable) {
                _state.update { it.copy(modelsLoading = false, error = "Model load failed: ${e.message}") }
            }
        }
    }

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
            // Persona is composed into a System message at request time, never stored in history.
            val systemMessages = current.character?.let {
                listOf(ChatMessage.System(MessageId(newId()), Persona.systemPrompt(it)))
            } ?: emptyList()
            val request = ChatRequest(model = current.model, messages = systemMessages + history)
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
