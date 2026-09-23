package io.orangerabbit.clanker.persistence

import io.orangerabbit.clanker.agent.ChatSettings
import io.orangerabbit.clanker.agent.ServerTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Decoded per-chat settings: the chat model plus tool preferences. */
data class DecodedChatSettings(
    val model: String?,
    val settings: ChatSettings,
)

/**
 * settingsJson codec for conversations. Two shapes exist:
 *  - legacy (Task 8): the raw string is the bare model id;
 *  - Task 9+: JSON `{"model":..., "tools":[...], "maxToolCalls":..., "searchMaxResults":...}`.
 * Decoding never throws: malformed JSON falls back to defaults.
 */
object ChatSettingsCodec {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(raw: String): DecodedChatSettings {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{")) {
            return DecodedChatSettings(model = trimmed.ifEmpty { null }, settings = ChatSettings())
        }
        val stored = try {
            json.decodeFromString<StoredChatSettings>(trimmed)
        } catch (_: Exception) {
            return DecodedChatSettings(model = null, settings = ChatSettings())
        }
        return DecodedChatSettings(
            model = stored.model,
            settings = ChatSettings(
                tools = stored.tools,
                maxToolCalls = stored.maxToolCalls ?: ChatSettings().maxToolCalls,
                searchMaxResults = stored.searchMaxResults ?: ChatSettings().searchMaxResults,
            ),
        )
    }

    fun encode(model: String?, settings: ChatSettings): String =
        json.encodeToString(
            StoredChatSettings(
                model = model,
                tools = settings.tools,
                maxToolCalls = settings.maxToolCalls,
                searchMaxResults = settings.searchMaxResults,
            ),
        )
}

/** JSON shape persisted in `conversation.settings_json`. */
@Serializable
private data class StoredChatSettings(
    val model: String? = null,
    val tools: Set<ServerTool> = emptySet(),
    val maxToolCalls: Int? = null,
    val searchMaxResults: Int? = null,
)
