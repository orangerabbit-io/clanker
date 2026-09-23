package io.orangerabbit.clanker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.ktor.client.HttpClient
import io.orangerabbit.clanker.network.OpenRouterClient
import io.orangerabbit.clanker.persistence.ChatSettingsCodec
import io.orangerabbit.clanker.persistence.ConversationRepository
import io.orangerabbit.clanker.security.SecretStore
import io.orangerabbit.clanker.ui.chat.ChatScreen
import io.orangerabbit.clanker.ui.list.ConversationListScreen
import io.orangerabbit.clanker.ui.settings.SettingsScreen
import io.orangerabbit.clanker.ui.theme.ClankerTheme
import io.orangerabbit.clanker.util.KeepAwake
import io.orangerabbit.clanker.agent.ChatSettings
import kotlinx.coroutines.launch

/** Top-level navigation state: List → Chat(id) → Settings (Task 8 Step 4). */
sealed interface AppScreen {
    data object List : AppScreen
    data object Settings : AppScreen
    data class Chat(val conversationId: String, val settingsJson: String) : AppScreen
}

/**
 * App entry: hand-threaded DI (no Koin) — the platform entry points
 * (MainActivity/MainViewController) construct the SecretStore, HttpClient,
 * database/repository, and KeepAwake, then hand them here.
 *
 * `pendingOAuthCode` preserves the Task 5 deep-link handoff: it is forwarded to
 * the embedded ConnectFlow whenever the Settings screen opens.
 *
 * New chats are created through the repository before navigation so the chat
 * always has a persisted conversation id (title "New chat"; the model id and
 * per-chat tool preferences are stored in settingsJson via ChatSettingsCodec).
 */
@Composable
fun App(
    secretStore: SecretStore,
    httpClient: HttpClient,
    repository: ConversationRepository,
    keepAwake: KeepAwake,
    pendingOAuthCode: String? = null,
) {
    ClankerTheme {
        // One client for the whole app; key read lazily per request (fail-closed:
        // a missing/failed read surfaces as ChatResult.Failed in the chat).
        val client = remember {
            OpenRouterClient(
                apiKeyProvider = {
                    secretStore.get("openrouter_key")
                        ?: throw IllegalStateException("OpenRouter key not connected.")
                },
            )
        }
        // Default model picked in Settings; kept in memory for Phase 1.
        var defaultModel by remember { mutableStateOf<String?>(null) }
        var screen by remember { mutableStateOf<AppScreen>(AppScreen.List) }
        val scope = rememberCoroutineScope()

        val onBack: () -> Unit = { screen = AppScreen.List }

        when (val current = screen) {
            AppScreen.List -> {
                ConversationListScreen(
                    repository = repository,
                    onOpenConversation = { conversation ->
                        screen = AppScreen.Chat(conversation.id, conversation.settingsJson)
                    },
                    onNewChat = {
                        val model = defaultModel
                        if (model.isNullOrBlank()) {
                            screen = AppScreen.Settings
                        } else {
                            scope.launch {
                                val id = newConversationId()
                                val settingsJson = ChatSettingsCodec.encode(model = model, settings = ChatSettings())
                                repository.createConversation(id, "New chat", settingsJson)
                                screen = AppScreen.Chat(id, settingsJson)
                            }
                        }
                    },
                    onOpenSettings = { screen = AppScreen.Settings },
                )
            }
            AppScreen.Settings -> SettingsScreen(
                secretStore = secretStore,
                httpClient = httpClient,
                client = client,
                defaultModel = defaultModel,
                onModelSelected = { defaultModel = it },
                pendingOAuthCode = pendingOAuthCode,
                onBack = onBack,
            )
            is AppScreen.Chat -> {
                ChatScreen(
                    repository = repository,
                    secretStore = secretStore,
                    client = client,
                    keepAwake = keepAwake,
                    conversationId = current.conversationId,
                    storedSettingsJson = current.settingsJson,
                    onBack = onBack,
                )
            }
            else -> {}
        }
    }
}

/** 32-hex conversation id (mirrors the repository's message-id generator). */
private fun newConversationId(): String = buildString(32) {
    val chars = "0123456789abcdef"
    repeat(32) { append(chars[kotlin.random.Random.nextInt(chars.length)]) }
}