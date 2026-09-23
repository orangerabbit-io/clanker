package io.orangerabbit.clanker.persistence

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.orangerabbit.clanker.db.ClankerDb
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RepositoryTest {

    private fun freshDb(): Pair<JdbcSqliteDriver, ClankerDb> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClankerDb.Schema.create(driver)
        val db = ClankerDb(driver)
        return driver to db
    }

    @Test
    fun rawJsonIsStoredVerbatim() = runBlocking {
        val (_, db) = freshDb()
        val repo = ConversationRepository(db)

        repo.createConversation("c1", "Test", "{}")
        repo.appendMessage("c1", "assistant", null, null, null, """{"x":1}""", "COMPLETE")

        val messages = repo.messagesFor("c1")
        assertEquals(1, messages.size)
        assertEquals("""{"x":1}""", messages.first().rawJson)
    }

    @Test
    fun updateConversationSettingsPersistsChange() = runBlocking {
        val (_, db) = freshDb()
        val repo = ConversationRepository(db)

        repo.createConversation("c1", "Test", "{}")
        repo.updateConversationSettings("c1", """{"model":"m","tools":["WEB_SEARCH"]}""")

        val conversation = repo.conversations().first().single { it.id == "c1" }
        assertEquals("""{"model":"m","tools":["WEB_SEARCH"]}""", conversation.settingsJson)
    }

    @Test
    fun deleteConversationCascadesMessages() = runBlocking {
        val (driver, db) = freshDb()
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        val repo = ConversationRepository(db)

        repo.createConversation("c1", "Test", "{}")
        repo.appendMessage("c1", "assistant", null, null, null, """{"x":1}""", "COMPLETE")

        // Verify message exists before delete
        assertEquals(1, repo.messagesFor("c1").size)

        repo.deleteConversation("c1")

        val remaining = repo.messagesFor("c1")
        assertEquals(0, remaining.size, "FK cascade should have deleted messages with conversation")
    }

    @Test
    fun conversationsFlowEmitsInserted() = runBlocking {
        val (_, db) = freshDb()
        val repo = ConversationRepository(db)

        repo.createConversation("c1", "Alpha", "{}")
        repo.createConversation("c2", "Beta", "{}")

        val channel = Channel<List<Conversation>>(capacity = Channel.UNLIMITED)
        val job = launch {
            repo.conversations().collect { channel.send(it) }
        }

        // First emission: c1 and c2 already present
        val first = channel.receive()
        assertEquals(2, first.size)
        val firstIds = first.map { it.id }.toSet()
        assertTrue("c1" in firstIds)
        assertTrue("c2" in firstIds)

        // Insert c3 — flow must re-emit on query invalidation
        repo.createConversation("c3", "Gamma", "{}")

        // Second emission must include c3
        val second = channel.receive()
        assertEquals(3, second.size)
        assertTrue("c3" in second.map { it.id })

        job.cancel()
    }

    @Test
    fun exportImportRoundTrip() = runBlocking {
        val (_, db1) = freshDb()
        val repo1 = ConversationRepository(db1)
        val exportImport1 = ExportImport(db1)

        repo1.createConversation("c1", "Round-trip", """{"model":"gpt-4"}""")
        repo1.appendMessage("c1", "user", "hello", null, null, null, "COMPLETE")
        repo1.appendMessage("c1", "assistant", "world", null, null, """{"id":"msg_1","content":"world"}""", "COMPLETE")

        // Capture original IDs before export (generated internally by appendMessage)
        val orig = repo1.messagesFor("c1")
        val assistantId = orig.first { it.role == "assistant" }.id

        val exported = exportImport1.exportAll()

        // Fresh in-memory database — freshDb() creates the schema
        val (_, db2) = freshDb()
        val repo2 = ConversationRepository(db2)
        val exportImport2 = ExportImport(db2)

        val count = exportImport2.importAll(exported)
        assertEquals(1, count)

        val messages = repo2.messagesFor("c1")
        assertEquals(2, messages.size)

        val assistantMsg = messages.first { it.id == assistantId }
        assertEquals("""{"id":"msg_1","content":"world"}""", assistantMsg.rawJson)

        // Verify all IDs are preserved (not regenerated)
        assertEquals(orig.map { it.id }, messages.map { it.id })
    }
}
