package io.orangerabbit.clanker.persistence

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.orangerabbit.clanker.db.ClankerDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class Conversation(
    val id: String,
    val title: String,
    val settingsJson: String,
    val createdAt: Long,
)

data class StoredMessage(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String?,
    val toolCallsJson: String?,
    val reasoningJson: String?,
    val rawJson: String?,
    val lifecycle: String,
    val createdAt: Long,
)

class ConversationRepository(private val db: ClankerDb) {
    private val queries get() = db.clankerQueries

    suspend fun createConversation(id: String, title: String, settingsJson: String): Unit =
        withContext(Dispatchers.IO) {
            queries.insertConversation(
                id = id,
                title = title,
                settingsJson = settingsJson,
                createdAt = currentTimeMs(),
            )
        }

    suspend fun appendMessage(
        conversationId: String,
        role: String,
        content: String?,
        toolCallsJson: String?,
        reasoningJson: String?,
        rawJson: String?,
        lifecycle: String,
    ): Unit = withContext(Dispatchers.IO) {
        queries.insertMessage(
            id = randomId(),
            conversationId = conversationId,
            role = role,
            content = content,
            toolCallsJson = toolCallsJson,
            reasoningJson = reasoningJson,
            rawJson = rawJson,
            lifecycle = lifecycle,
            createdAt = currentTimeMs(),
        )
    }

    suspend fun messagesFor(conversationId: String): List<StoredMessage> =
        withContext(Dispatchers.IO) {
            queries.selectMessages(conversationId).executeAsList().map { it.toStoredMessage() }
        }

    fun conversations(): Flow<List<Conversation>> =
        queries.selectAll()
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.map { it.toConversation() } }

    suspend fun deleteConversation(id: String): Unit =
        withContext(Dispatchers.IO) {
            queries.deleteConversation(id)
        }

    private fun io.orangerabbit.clanker.db.Message.toStoredMessage() = StoredMessage(
        id = id,
        conversationId = conversationId,
        role = role,
        content = content,
        toolCallsJson = toolCallsJson,
        reasoningJson = reasoningJson,
        rawJson = rawJson,
        lifecycle = lifecycle,
        createdAt = createdAt,
    )

    private fun io.orangerabbit.clanker.db.Conversation.toConversation() = Conversation(
        id = id,
        title = title,
        settingsJson = settingsJson,
        createdAt = createdAt,
    )
}

private fun randomId(): String = buildString(32) {
    val chars = "0123456789abcdef"
    repeat(32) { append(chars[Random.nextInt(chars.length)]) }
}
