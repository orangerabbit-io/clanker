package io.orangerabbit.clanker.agent

import io.orangerabbit.clanker.model.MsgLifecycle
import io.orangerabbit.clanker.network.Source
import io.orangerabbit.clanker.network.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the pure chat reducer (Task 8 Step 1).
 *
 * Review Focus #1 (interruption): partial content must survive an interrupted
 * stream with lifecycle INTERRUPTED and an error banner at the state level.
 * Review Focus #2 (usage): cost must be displayed from terminal usage, and
 * remain "cost unavailable" (null) when the stream carried no usage.
 */
class ChatReducerTest {

    private fun initialState(): ChatUiState = ChatUiState(
        messages = listOf(UiMessage(role = "user", content = "Hi")),
        running = true,
        error = null,
    )

    private val usage = Usage(
        totalCost = 0.0012,
        promptTokens = 10L,
        completionTokens = 5L,
        serverToolUse = null,
    )

    @Test
    fun contentDeltasAccumulateIntoStreamingAssistantBubble() {
        var state = initialState()

        state = reduce(state, ChatUiEvent.Content("Hel"))
        assertEquals(listOf("user", "assistant"), state.messages.map { it.role })
        assertEquals(MsgLifecycle.STREAMING, state.messages.last().lifecycle)
        assertEquals("Hel", state.messages.last().content)
        assertTrue(state.running)

        state = reduce(state, ChatUiEvent.Content("lo"))
        assertEquals("Hello", state.messages.last().content)
        // deltas must not duplicate: exactly one assistant bubble
        assertEquals(2, state.messages.size)
    }

    @Test
    fun doneFinalizesAssistantWithContentAndCostFromUsage() {
        var state = initialState()
        state = reduce(state, ChatUiEvent.Content("Hel"))
        state = reduce(state, ChatUiEvent.Content("lo"))
        state = reduce(state, ChatUiEvent.Done(usage))

        val assistant = state.messages.last()
        assertEquals("assistant", assistant.role)
        assertEquals("Hello", assistant.content)
        assertEquals(MsgLifecycle.COMPLETE, assistant.lifecycle)
        assertEquals(0.0012, assistant.cost)
        assertTrue(!state.running)
        assertNull(state.error)
    }

    @Test
    fun doneWithoutUsageLeavesCostUnavailable() {
        var state = initialState()
        state = reduce(state, ChatUiEvent.Content("Hello"))
        state = reduce(state, ChatUiEvent.Done(usage = null))

        val assistant = state.messages.last()
        assertEquals(MsgLifecycle.COMPLETE, assistant.lifecycle)
        assertNull(assistant.cost)
        assertTrue(!state.running)
    }

    @Test
    fun failedPreservesPartialContentAndShowsErrorBanner() {
        var state = initialState()
        state = reduce(state, ChatUiEvent.Content("He"))
        state = reduce(state, ChatUiEvent.Failed("HTTP 500: Internal Server Error"))

        val assistant = state.messages.last()
        assertEquals(MsgLifecycle.INTERRUPTED, assistant.lifecycle)
        assertEquals("He", assistant.content)
        assertEquals("HTTP 500: Internal Server Error", state.error)
        assertTrue(!state.running)
    }

    @Test
    fun interruptedPreservesContentAndCarriesPartialUsage() {
        var state = initialState()
        state = reduce(state, ChatUiEvent.Content("He"))
        val partialUsage = Usage(0.0003, 10L, 5L, null)
        state = reduce(state, ChatUiEvent.Interrupted(partialUsage))

        val assistant = state.messages.last()
        assertEquals(MsgLifecycle.INTERRUPTED, assistant.lifecycle)
        assertEquals("He", assistant.content)
        assertEquals(0.0003, assistant.cost)
        assertTrue(!state.running)
        assertEquals("Stream interrupted.", state.error)
    }

    @Test
    fun failedWithNoAssistantBubbleStillClearsRunningAndShowsError() {
        val state = reduce(initialState(), ChatUiEvent.Failed("stream setup failed: no key"))

        assertTrue(state.messages.all { it.role == "user" })
        assertTrue(!state.running)
        assertEquals("stream setup failed: no key", state.error)
    }

    @Test
    fun reasoningDeltasAccumulateIntoStreamingBubble() {
        var state = initialState()
        state = reduce(state, ChatUiEvent.Reasoning("thin"))
        state = reduce(state, ChatUiEvent.Reasoning("king"))
        state = reduce(state, ChatUiEvent.Content("Hi"))
        state = reduce(state, ChatUiEvent.Done(usage))

        val assistant = state.messages.last()
        assertEquals(1, assistant.reasoning.size)
        assertEquals("thinking", assistant.reasoning.single().text)
        assertEquals("Hi", assistant.content)
        assertEquals(MsgLifecycle.COMPLETE, assistant.lifecycle)
    }

    @Test
    fun userMessageIsUntouchedByTerminalEvents() {
        var state = initialState()
        state = reduce(state, ChatUiEvent.Done(usage))

        assertEquals(listOf("user"), state.messages.map { it.role })
        assertEquals("Hi", state.messages.single().content)
        assertTrue(!state.running)
    }

    // ── Task 9: server tools ─────────────────────────────────────────────

    @Test
    fun sourcesEventsAccumulateIntoStreamingAssistantBubble() {
        var state = initialState()
        state = reduce(state, ChatUiEvent.Content("answer"))
        state = reduce(
            state,
            ChatUiEvent.Sources(listOf(Source("https://a.io", "A"), Source("https://b.io", null))),
        )
        state = reduce(state, ChatUiEvent.Sources(listOf(Source("https://c.io", "C"))))

        val assistant = state.messages.last()
        assertEquals(
            listOf(
                Source("https://a.io", "A"),
                Source("https://b.io", null),
                Source("https://c.io", "C"),
            ),
            assistant.sources,
        )
        assertEquals(MsgLifecycle.STREAMING, assistant.lifecycle)
    }

    @Test
    fun doneCarriesServerToolUseCountsIntoUiMessage() {
        val usageWithTools = Usage(
            totalCost = 0.01,
            promptTokens = 10L,
            completionTokens = 5L,
            serverToolUse = mapOf("web_search_requests" to 2, "web_fetch_requests" to 1),
        )
        var state = initialState()
        state = reduce(state, ChatUiEvent.Content("hi"))
        state = reduce(state, ChatUiEvent.Done(usageWithTools))

        assertEquals(mapOf("web_search_requests" to 2, "web_fetch_requests" to 1), state.messages.last().serverToolUse)
    }

    @Test
    fun interruptedCarriesServerToolUseCountsToo() {
        var state = initialState()
        state = reduce(state, ChatUiEvent.Content("hi"))
        state = reduce(
            state,
            ChatUiEvent.Interrupted(Usage(0.01, 10L, 5L, mapOf("web_search_requests" to 1))),
        )

        assertEquals(mapOf("web_search_requests" to 1), state.messages.last().serverToolUse)
    }
}