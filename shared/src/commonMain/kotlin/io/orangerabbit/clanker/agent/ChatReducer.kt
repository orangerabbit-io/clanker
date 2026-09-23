package io.orangerabbit.clanker.agent

import io.orangerabbit.clanker.model.MsgLifecycle
import io.orangerabbit.clanker.model.ReasoningBlock
import io.orangerabbit.clanker.model.ToolCall
import io.orangerabbit.clanker.network.Source
import io.orangerabbit.clanker.network.Usage

/**
 * Immutable UI view of one chat message.
 *
 * [cost] is the assistant-message total cost from terminal usage (null when
 * the stream carried no usage — UI renders "cost unavailable").
 * [lifecycle] drives the streaming bubble: STREAMING while deltas accumulate,
 * then COMPLETE or INTERRUPTED on the terminal event.
 */
data class UiMessage(
    val role: String,
    val content: String,
    val lifecycle: MsgLifecycle = MsgLifecycle.COMPLETE,
    val reasoning: List<ReasoningBlock> = emptyList(),
    val cost: Double? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val sources: List<Source> = emptyList(),
    val serverToolUse: Map<String, Int>? = null,
)

/** Full UI state of one chat screen. */
data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,

    /**
     * Budget-exhausted terminal state (Task 10): accumulated cost exceeded
     * the per-request cap. Terminal for the session — the ViewModel's send()
     * refuses further sends; no automatic retry.
     */
    val budgetExhausted: Boolean = false,
)

/**
 * Reducer input events. Content/Reasoning arrive per streamed delta; terminal
 * events come from the [io.orangerabbit.clanker.network.ChatResult]:
 *  - [Done] from `ChatResult.Completed` — cost displayed from usage.
 *  - [Interrupted] from `ChatResult.Interrupted` — partial content preserved,
 *    partial usage cost still displayed (Task 6: Interrupted carries usage).
 *  - [Failed] from `ChatResult.Failed` — per the Task 8 brief this also yields
 *    lifecycle INTERRUPTED with content preserved plus an error banner.
 * (`StreamEvent.Failed` is never emitted by the client, so it has no mapping.)
 */
sealed interface ChatUiEvent {
    data class Content(val text: String) : ChatUiEvent
    data class Reasoning(val text: String) : ChatUiEvent
    data class Sources(val sources: List<Source>) : ChatUiEvent
    data class Done(val usage: Usage?) : ChatUiEvent
    data class Interrupted(val usage: Usage?) : ChatUiEvent
    data class Failed(val message: String) : ChatUiEvent

    /**
     * Terminal budget-exhausted marker (Task 10): emitted by the ViewModel
     * after a terminal event whose usage cost exceeded the per-request cap.
     * Sets the "spend cap reached" banner and [ChatUiState.budgetExhausted].
     */
    data object SpendCapReached : ChatUiEvent
}

private const val REASONING_TYPE = "reasoning"

/**
 * Pure chat reducer: `reduce(state, event) -> state`. Deltas append into the
 * in-progress (STREAMING) assistant bubble; the terminal event finalizes its
 * lifecycle and stops the running indicator. Tool-call rows (Task 9) will
 * render collapsed under the assistant bubble via [UiMessage.toolCalls].
 * [ChatUiEvent.SpendCapReached] (Task 10) lands the budget-exhausted terminal
 * state: banner "spend cap reached", sticky `budgetExhausted`, no retry.
 */
fun reduce(state: ChatUiState, event: ChatUiEvent): ChatUiState = when (event) {
    is ChatUiEvent.Content -> state.streamAssistant { it.copy(content = it.content + event.text) }
    is ChatUiEvent.Sources -> state.streamAssistant { it.copy(sources = it.sources + event.sources) }
    is ChatUiEvent.Reasoning -> state.streamAssistant { msg ->
        val blocks = msg.reasoning.toMutableList()
        if (blocks.isNotEmpty()) {
            val last = blocks.last()
            blocks[blocks.lastIndex] = last.copy(text = (last.text ?: "") + event.text)
        } else {
            blocks.add(ReasoningBlock(type = REASONING_TYPE, text = event.text, opaqueJson = null))
        }
        msg.copy(reasoning = blocks)
    }
    is ChatUiEvent.Done ->
        state.finalizeAssistant(MsgLifecycle.COMPLETE, cost = event.usage?.totalCost, serverToolUse = event.usage?.serverToolUse, error = null)
    is ChatUiEvent.Interrupted ->
        state.finalizeAssistant(
            MsgLifecycle.INTERRUPTED,
            cost = event.usage?.totalCost,
            serverToolUse = event.usage?.serverToolUse,
            error = "Stream interrupted.",
        )
    is ChatUiEvent.Failed -> state.finalizeAssistant(MsgLifecycle.INTERRUPTED, cost = null, serverToolUse = null, error = event.message)
    is ChatUiEvent.SpendCapReached -> state.finalizeAssistant(
        MsgLifecycle.INTERRUPTED,
        cost = null,
        serverToolUse = null,
        error = SPEND_CAP_BANNER,
    ).copy(budgetExhausted = true)
}

private const val SPEND_CAP_BANNER = "spend cap reached"

/** Appends to the trailing STREAMING assistant message, creating it on first delta. */
private fun ChatUiState.streamAssistant(transform: (UiMessage) -> UiMessage): ChatUiState {
    val messages = messages.toMutableList()
    val last = messages.lastOrNull()
    if (last != null && last.role == "assistant" && last.lifecycle == MsgLifecycle.STREAMING) {
        messages[messages.lastIndex] = transform(last)
    } else {
        messages.add(transform(UiMessage(role = "assistant", content = "", lifecycle = MsgLifecycle.STREAMING)))
    }
    return copy(messages = messages)
}

/** Marks the trailing STREAMING assistant message terminal (no-op if none is streaming). */
private fun ChatUiState.finalizeAssistant(
    lifecycle: MsgLifecycle,
    cost: Double?,
    serverToolUse: Map<String, Int>?,
    error: String?,
): ChatUiState {
    val messages = messages.toMutableList()
    val last = messages.lastOrNull()
    if (last != null && last.role == "assistant" && last.lifecycle == MsgLifecycle.STREAMING) {
        messages[messages.lastIndex] = last.copy(lifecycle = lifecycle, cost = cost, serverToolUse = serverToolUse)
    }
    // Budget-exhausted terminal state is sticky (Task 10): once set, later
    // terminal events never overwrite the "spend cap reached" banner.
    val nextError = if (budgetExhausted) this.error ?: error else error ?: stateErrorOrNull(this, error)
    return copy(messages = messages, running = false, error = nextError)
}

/** Keep an existing error when a terminal event carries none (only Failed/Interrupted set one). */
private fun stateErrorOrNull(state: ChatUiState, error: String?): String? = state.error.takeIf { error == null } ?: error