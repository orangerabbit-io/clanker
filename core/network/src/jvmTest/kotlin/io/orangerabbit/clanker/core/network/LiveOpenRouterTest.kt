package io.orangerabbit.clanker.core.network

import io.ktor.client.engine.cio.CIO
import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.MessageId
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live end-to-end test against the real OpenRouter API. Self-skips unless OPENROUTER_API_KEY is
 * set, so it is safe to keep in the suite and in CI. Run with:
 *   OPENROUTER_API_KEY=... ./gradlew :core:network:jvmTest --tests '*LiveOpenRouterTest' --rerun-tasks
 */
class LiveOpenRouterTest {

    @Test
    fun liveStreamingCompletion() {
        val key = System.getenv("OPENROUTER_API_KEY")
        if (key.isNullOrBlank()) {
            println("OPENROUTER_API_KEY not set — skipping live test")
            return
        }

        val provider = openRouterProvider(apiKey = key, engine = CIO.create())
        val request = ChatRequest(
            model = "openai/gpt-4o-mini",
            messages = listOf(ChatMessage.User(MessageId("1"), "Reply with the single word: pong")),
        )

        val events = runBlocking { provider.streamChat(request).toList() }

        val text = events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        val failures = events.filterIsInstance<ChatEvent.Failed>()
        val usage = events.filterIsInstance<ChatEvent.UsageReport>().lastOrNull()
        println("LIVE: events=${events.size} text='$text' usage=${usage?.usage} failures=${failures.map { it.error }}")

        assertTrue(failures.isEmpty(), "provider returned errors: ${failures.map { it.error }}")
        assertTrue(events.any { it is ChatEvent.Finished }, "stream never finished")
        assertTrue(text.isNotBlank(), "no text was streamed")
    }

    @Test
    fun liveWebSearchReturnsCitations() {
        val key = System.getenv("OPENROUTER_API_KEY")
        if (key.isNullOrBlank()) {
            println("OPENROUTER_API_KEY not set — skipping live web search test")
            return
        }

        val provider = openRouterProvider(apiKey = key, engine = CIO.create())
        val request = ChatRequest(
            model = "openai/gpt-4o-mini",
            messages = listOf(
                ChatMessage.User(MessageId("1"), "Search the web: what is the latest stable Kotlin version? Cite a source."),
            ),
            serverTools = listOf(ServerTool.WebSearch()),
        )

        val events = runBlocking { provider.streamChat(request).toList() }

        val text = events.filterIsInstance<ChatEvent.TextDelta>().joinToString("") { it.text }
        val citations = events.filterIsInstance<ChatEvent.CitationDelta>().map { it.citation }
        val usage = events.filterIsInstance<ChatEvent.UsageReport>().lastOrNull()?.usage
        val failures = events.filterIsInstance<ChatEvent.Failed>()
        println("LIVE web_search: text='${text.take(120)}' citations=${citations.size} usage=$usage failures=${failures.map { it.error }}")

        assertTrue(failures.isEmpty(), "provider returned errors: ${failures.map { it.error }}")
        assertTrue(citations.isNotEmpty(), "expected at least one url_citation; got none")
    }
}
