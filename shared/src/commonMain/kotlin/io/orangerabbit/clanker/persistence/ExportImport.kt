package io.orangerabbit.clanker.persistence

import io.orangerabbit.clanker.db.ClankerDb
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExportMessage(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String? = null,
    val toolCallsJson: String? = null,
    val reasoningJson: String? = null,
    val rawJson: String? = null,
    val lifecycle: String,
    val createdAt: Long,
)

@Serializable
data class ExportConversation(
    val id: String,
    val title: String,
    val settingsJson: String,
    val createdAt: Long,
    val messages: List<ExportMessage>,
)

@Serializable
data class ExportPayload(
    val conversations: List<ExportConversation>,
)

class ExportImport(private val db: ClankerDb) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Serializes all conversations and their messages to a JSON string. */
    suspend fun exportAll(): String = withContext(ioDispatcher) {
        val conversations = db.clankerQueries.selectAll().executeAsList()
        val exportData = conversations.map { conv ->
            val messages = db.clankerQueries.selectMessages(conv.id).executeAsList()
            ExportConversation(
                id = conv.id,
                title = conv.title,
                settingsJson = conv.settingsJson,
                createdAt = conv.createdAt,
                messages = messages.map { msg ->
                    ExportMessage(
                        id = msg.id,
                        conversationId = msg.conversationId,
                        role = msg.role,
                        content = msg.content,
                        toolCallsJson = msg.toolCallsJson,
                        reasoningJson = msg.reasoningJson,
                        rawJson = msg.rawJson,
                        lifecycle = msg.lifecycle,
                        createdAt = msg.createdAt,
                    )
                },
            )
        }
        json.encodeToString(ExportPayload(exportData))
    }

    /**
     * Inserts all conversations and messages from [jsonStr], preserving ids and rawJson.
     * Returns the number of conversations imported.
     */
    suspend fun importAll(jsonStr: String): Int = withContext(ioDispatcher) {
        val payload = json.decodeFromString<ExportPayload>(jsonStr)
        for (conv in payload.conversations) {
            db.clankerQueries.insertConversation(
                id = conv.id,
                title = conv.title,
                settingsJson = conv.settingsJson,
                createdAt = conv.createdAt,
            )
            for (msg in conv.messages) {
                db.clankerQueries.insertMessage(
                    id = msg.id,
                    conversationId = msg.conversationId,
                    role = msg.role,
                    content = msg.content,
                    toolCallsJson = msg.toolCallsJson,
                    reasoningJson = msg.reasoningJson,
                    rawJson = msg.rawJson,
                    lifecycle = msg.lifecycle,
                    createdAt = msg.createdAt,
                )
            }
        }
        payload.conversations.size
    }
}
