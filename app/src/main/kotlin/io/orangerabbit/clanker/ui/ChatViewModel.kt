package io.orangerabbit.clanker.ui

import android.util.Base64
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.Inject
import io.ktor.client.engine.HttpClientEngine
import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.Citation
import io.orangerabbit.clanker.core.model.MessageId
import io.orangerabbit.clanker.core.model.MsgLifecycle
import io.orangerabbit.clanker.core.network.ChatEvent
import io.orangerabbit.clanker.core.network.ChatRequest
import io.orangerabbit.clanker.core.agent.composeSystemPrompt
import io.orangerabbit.clanker.core.network.ModelInfo
import io.orangerabbit.clanker.core.network.defaultServerTools
import io.orangerabbit.clanker.core.network.openRouterProvider
import io.orangerabbit.clanker.data.SecretStore
import io.orangerabbit.clanker.data.Settings
import io.orangerabbit.clanker.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Holds chat state and drives the streaming loop. The API key is encrypted-at-rest ([SecretStore]);
 * model defaults + agent instructions + FX are non-secret ([SettingsStore]). Each request is
 * prefixed with a System message composed at request time from the fixed system prompt plus the
 * user-editable `AGENTS.md` (`composeSystemPrompt`), and image mode swaps the chat model for the
 * image model.
 */
@Inject
class ChatViewModel(
    private val engine: HttpClientEngine,
    private val secrets: SecretStore,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    data class UiState(
        val apiKey: String = "",
        val defaultChatModel: String = Settings.DEFAULT_CHAT_MODEL,
        val defaultImageModel: String = Settings.DEFAULT_IMAGE_MODEL,
        /** User-editable agent instructions (Layer 1, `AGENTS.md`). */
        val agentsMd: String = "",
        val fxIntensity: Float = 1f,
        val availableModels: List<ModelInfo> = emptyList(),
        val modelsLoading: Boolean = false,
        val messages: List<ChatMessage> = emptyList(),
        val streaming: Boolean = false,
        val error: String? = null,
        /** When on, requests ask the model for image output (`modalities: ["image","text"]`). */
        val imageMode: Boolean = false,
        /** Vision inputs staged for the next user message, as `data:` URLs. */
        val pendingImages: List<String> = emptyList(),
        /** Running conversation cost in USD, accumulated from the trailing usage chunk. */
        val costUsd: Double = 0.0,
        /** Persisted cumulative spend across all sessions (USD). */
        val lifetimeCostUsd: Double = 0.0,
        /** Result of the last "test connection" probe; null = not run. */
        val keyTest: String? = null,
        val testingKey: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** Handle to the active streaming coroutine, so Stop can cancel it. */
    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            secrets.loadApiKey()?.let { stored -> _state.update { it.copy(apiKey = stored) } }
        }
        viewModelScope.launch {
            settingsStore.settings.collect { s ->
                _state.update {
                    it.copy(
                        defaultChatModel = s.defaultChatModel,
                        defaultImageModel = s.defaultImageModel,
                        agentsMd = s.agentsMd,
                        fxIntensity = s.fxIntensity,
                        lifetimeCostUsd = s.lifetimeCostUsd,
                    )
                }
            }
        }
    }

    fun setApiKey(value: String) {
        // Trim: a stray space/newline (common on paste) makes "Bearer <key>" a malformed
        // Authorization header, which OpenRouter rejects as "missing authentication header".
        val key = value.trim()
        _state.update { it.copy(apiKey = key, keyTest = null) }
        viewModelScope.launch { secrets.saveApiKey(key) }
    }

    /**
     * One-tap key check: hits GET /models and reports the outcome, surfacing the provider's own
     * error message (e.g. "User not found", "Invalid API key") so a bad key is unambiguous. On
     * success the catalogue is cached for the model dropdowns too.
     */
    fun testConnection() {
        val current = _state.value
        if (current.apiKey.isBlank()) {
            _state.update { it.copy(keyTest = "✗ enter a key first") }
            return
        }
        if (current.testingKey) return
        _state.update { it.copy(testingKey = true, keyTest = null) }
        viewModelScope.launch {
            try {
                val provider = openRouterProvider(apiKey = current.apiKey, engine = engine)
                // Authenticated probe FIRST — /models is public and would pass even with a bad key.
                provider.validateKey()
                val models = provider.listModels().sortedBy { it.id }
                _state.update {
                    it.copy(
                        testingKey = false,
                        availableModels = models,
                        keyTest = "✓ key valid — ${models.size} models",
                    )
                }
            } catch (e: Throwable) {
                _state.update { it.copy(testingKey = false, keyTest = "✗ ${e.message ?: "connection failed"}") }
            }
        }
    }

    fun setDefaultChatModel(value: String) {
        _state.update { it.copy(defaultChatModel = value) }
        viewModelScope.launch { settingsStore.setDefaultChatModel(value) }
    }

    fun setDefaultImageModel(value: String) {
        _state.update { it.copy(defaultImageModel = value) }
        viewModelScope.launch { settingsStore.setDefaultImageModel(value) }
    }

    fun setFxIntensity(value: Float) {
        _state.update { it.copy(fxIntensity = value) }
        viewModelScope.launch { settingsStore.setFxIntensity(value) }
    }

    fun resetLifetimeCost() {
        viewModelScope.launch { settingsStore.resetLifetimeCost() }
    }

    fun setAgentsMd(value: String) {
        _state.update { it.copy(agentsMd = value) }
        viewModelScope.launch { settingsStore.setAgentsMd(value) }
    }

    fun setImageMode(value: Boolean) = _state.update { it.copy(imageMode = value) }

    /** Stages an image (raw bytes from the picker) as a `data:` URL for the next user turn. */
    fun attachImage(bytes: ByteArray, mime: String) {
        val dataUrl = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        _state.update { it.copy(pendingImages = it.pendingImages + dataUrl) }
    }

    fun clearPendingImages() = _state.update { it.copy(pendingImages = emptyList()) }

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
        // Allow an empty prompt when images are attached (a bare "describe these") or in image mode.
        val hasInput = text.isNotBlank() || current.pendingImages.isNotEmpty()
        if (!hasInput || current.apiKey.isBlank() || current.streaming) return

        val userMsg = ChatMessage.User(
            id = MessageId(newId()),
            content = text.trim(),
            imageUrls = current.pendingImages,
        )
        val history = current.messages + userMsg
        val assistantId = MessageId(newId())
        _state.update {
            it.copy(
                messages = history + ChatMessage.Assistant(assistantId, content = "", lifecycle = MsgLifecycle.Streaming),
                streaming = true,
                error = null,
                pendingImages = emptyList(),
            )
        }

        streamJob = viewModelScope.launch {
            val provider = openRouterProvider(apiKey = current.apiKey, engine = engine)
            // The agent definition (fixed) + AGENTS.md (user) is composed at request time,
            // never stored in history, so edits apply retroactively.
            val systemMessages = listOf(
                ChatMessage.System(MessageId(newId()), composeSystemPrompt(current.agentsMd)),
            )
            val modelId = if (current.imageMode) current.defaultImageModel else current.defaultChatModel
            // Image mode talks to an image-output model that rejects a `tools` array, so NEVER attach
            // server tools there — it owns image output via `modalities`. In chat mode, modelCaps is
            // null when the catalogue hasn't been fetched (fresh send before TEST CONNECTION / model
            // dropdown); defaultServerTools then enables optimistically so web search isn't silently
            // dropped.
            val modelCaps = current.availableModels.find { it.id == modelId }?.capabilities
            val serverTools = if (current.imageMode) emptyList() else defaultServerTools(modelCaps)
            val request = ChatRequest(
                model = modelId,
                messages = systemMessages + history,
                serverTools = serverTools,
                modalities = if (current.imageMode) listOf("image", "text") else emptyList(),
            )
            val buffer = StringBuilder()
            val images = mutableListOf<String>()
            val citations = mutableListOf<Citation>()
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
                                updateAssistant(assistantId, buffer.toString(), images, citations, MsgLifecycle.Streaming)
                            }
                        }
                        is ChatEvent.ImageDelta -> {
                            images += event.dataUrl
                            updateAssistant(assistantId, buffer.toString(), images, citations, MsgLifecycle.Streaming)
                        }
                        is ChatEvent.CitationDelta -> {
                            val c = event.citation
                            if (citations.none { it.url == c.url && it.startIndex == c.startIndex }) {
                                citations += c
                                updateAssistant(assistantId, buffer.toString(), images, citations, MsgLifecycle.Streaming)
                            }
                        }
                        is ChatEvent.UsageReport ->
                            event.usage.costUsd?.let { c ->
                                _state.update { it.copy(costUsd = it.costUsd + c) }
                                settingsStore.addLifetimeCost(c) // persisted lifetime total
                            }
                        is ChatEvent.Finished ->
                            updateAssistant(assistantId, buffer.toString(), images, citations, MsgLifecycle.Complete)
                        is ChatEvent.Failed -> {
                            _state.update { it.copy(error = event.error.message) }
                            updateAssistant(assistantId, buffer.toString(), images, citations, MsgLifecycle.Failed)
                        }
                        else -> Unit // reasoning / tool-call deltas: not surfaced in MVP UI yet
                    }
                }
            } catch (e: CancellationException) {
                // User pressed Stop: keep whatever streamed, mark it aborted, and respect cancellation.
                updateAssistant(assistantId, buffer.toString(), images, citations, MsgLifecycle.Aborted)
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(error = e.message ?: "Unknown error") }
                updateAssistant(assistantId, buffer.toString(), images, citations, MsgLifecycle.Failed)
            } finally {
                _state.update { it.copy(streaming = false) }
            }
        }
    }

    /** Cancels an in-flight generation (DESIGN §11 stop-generation path). No-op when idle. */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
    }

    private fun updateAssistant(
        id: MessageId,
        content: String,
        images: List<String>,
        citations: List<Citation>,
        lifecycle: MsgLifecycle,
    ) {
        _state.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg is ChatMessage.Assistant && msg.id == id) {
                        msg.copy(
                            content = content,
                            imageUrls = images.toList(),
                            citations = citations.toList(),
                            lifecycle = lifecycle,
                        )
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
