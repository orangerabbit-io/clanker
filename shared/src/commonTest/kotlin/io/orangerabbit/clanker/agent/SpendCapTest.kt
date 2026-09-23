package io.orangerabbit.clanker.agent

import io.orangerabbit.clanker.model.MsgLifecycle
import io.orangerabbit.clanker.network.Usage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Task 10 Step 1: budget-exhausted terminal state. When accumulated cost
 * exceeds the per-request cap, the reducer must land in a terminal state:
 * banner "spend cap reached", `budgetExhausted` set (the ViewModel's send()
 * refuses to start another turn), the in-flight turn finalized INTERRUPTED
 * with content preserved, and no automatic retry (Review Focus #5).
 */
class SpendCapTest {

    private val usage = Usage(
        totalCost = 0.40,
        promptTokens = 10L,
        completionTokens = 5L,
        serverToolUse = null,
    )

    @Test
    fun spendCapReachedSetsTerminalBannerAndBudgetExhaustedState() {
        var state = ChatUiState(
            messages = listOf(UiMessage(role = "user", content = "Hi")),
            running = true,
        )
        state = reduce(state, ChatUiEvent.Content("finished answer"))
        state = reduce(state, ChatUiEvent.Done(usage))
        state = reduce(state, ChatUiEvent.SpendCapReached)

        assertTrue(state.budgetExhausted, "budget exhaustion must be terminal at the state level")
        assertFalse(state.running, "the in-flight turn must be finalized")
        assertEquals("spend cap reached", state.error, "terminal banner must say spend cap reached")
        // The server finished the turn naturally (docs: one final turn with tool
        // calls disabled), so the message stays COMPLETE — the cap flag is
        // state-level, not a lifecycle downgrade.
        assertEquals(MsgLifecycle.COMPLETE, state.messages.last().lifecycle)
        assertEquals("finished answer", state.messages.last().content, "content must be preserved")
        assertEquals(0.40, state.messages.last().cost, "cost must stay displayed")
    }

    @Test
    fun budgetExhaustedStateIsStickyAcrossLaterTerminalEvents() {
        var state = ChatUiState(running = true)
        state = reduce(state, ChatUiEvent.SpendCapReached)
        state = reduce(state, ChatUiEvent.Interrupted(usage))

        assertTrue(state.budgetExhausted, "later terminal events must not clear budget exhaustion")
        assertFalse(state.running, "no automatic retry: state stays terminal")
        assertEquals("spend cap reached", state.error)
    }
}