package io.orangerabbit.clanker.ui.chat

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.orangerabbit.clanker.db.ClankerDb
import io.orangerabbit.clanker.network.ChatResult
import io.orangerabbit.clanker.persistence.ConversationRepository
import io.orangerabbit.clanker.network.ChatRequest
import io.orangerabbit.clanker.network.OpenRouterClient
import io.orangerabbit.clanker.network.StreamEvent
import io.orangerabbit.clanker.security.SecretStore
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Plumbing test for wire-faithful persistence (final review fix 3): the
 * verbatim raw payload carried on [ChatResult.Completed] must reach the
 * repository's rawJson column via ChatViewModel. The verbatim-storage property
 * itself is covered by RepositoryTest.rawJsonIsStoredVerbatim.
 */
class ChatViewModelRawJsonTest {

    private class RecordingSecretStore : SecretStore {
        val values = mutableMapOf<String, String>()
        override suspend fun get(id: String): String? = values[id]
        override suspend fun getOrPut(id: String, generator: suspend () -> String): String =
            values.getOrPut(id) { generator() }
        override suspend fun put(id: String, value: String) { values[id] = value }
    }

    private fun secretStoreFor(store: SecretStore): suspend () -> String = { store.getOrPut("openrouter_key") { "sk-test" } }

    @Test
    fun persistAssistantMessageStoresRawJsonFromChatResult() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ClankerDb.Schema.create(driver)
        val repository = ConversationRepository(ClankerDb(driver))
        val secretStore = RecordingSecretStore()
        secretStore.put("openrouter_key", "sk-test")

        val chunk1 = """{"id":"chatcmpl-1","model":"openai/gpt-4o-mini","choices":[{"index":0,"delta":{"content":"Hel"}}]}"""
        val chunk2 = """{"choices":[{"delta":{"content":"lo"}}]}"""
        val fixture = listOf(
            "data: $chunk1",
            "",
            "data: $chunk2",
            "",
            "data: [DONE]",
            "",
        ).joinToString("\n")
        val engine = MockEngine { _ ->
            respond(fixture, HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val client = OpenRouterClient(
            apiKeyProvider = secretStoreFor(secretStore),
            engine = engine,
        )

        repository.createConversation("c1", "Test", "{}")

        val vm = ChatViewModel(
            client = client,
            repository = repository,
            secretStore = secretStore,
            keepAwake = object : io.orangerabbit.clanker.util.KeepAwake {
                override fun acquire() {}
                override fun release() {}
            },
            conversationId = "c1",
            model = "openai/gpt-4o-mini",
            scope = this.backgroundScope,
        )

        vm.send("Hi")
        // Drain the scope so the in-flight send() reaches its terminal write.
        this.testScheduler.advanceUntilIdle()

        // Persistence hops to Dispatchers.IO (real threads, outside the test
        // scheduler), so poll briefly for the row to land before asserting.
        val deadline = System.currentTimeMillis() + 5_000
        var messages = repository.messagesFor("c1")
        while (messages.none { it.role == "assistant" } && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
            messages = repository.messagesFor("c1")
        }
        val assistant = messages.last { it.role == "assistant" }
        assertEquals("Hello", assistant.content, "assistant content must be persisted")
        assertEquals(
            listOf(chunk1, chunk2).joinToString("\n"),
            assistant.rawJson,
            "persisted rawJson must be the verbatim chunk payloads from ChatResult",
        )
    }
}
