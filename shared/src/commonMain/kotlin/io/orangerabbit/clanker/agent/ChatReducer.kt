package io.orangerabbit.clanker.agent

import io.orangerabbit.clanker.model.MsgLifecycle
import io.orangerabbit.clanker.model.ReasoningBlock
import io.orangerabbit.clanker.model.ToolCall
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
)

/** Full UI state of one chat screen. */
data class ChatUiState(
    val messages: List<UiMessage> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
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
    data class Done(val usage: Usage?) : ChatUiEvent
    data class Interrupted(val usage: Usage?) : ChatUiEvent
    data class Failed(val message: String) : ChatUiEvent
}

private const val REASONING_TYPE = "reasoning"

/**
 * Pure chat reducer: `reduce(state, event) -> state`. Deltas append into the
 * in-progress (STREAMING) assistant bubble; the terminal event finalizes its
 * lifecycle and stops the running indicator. Tool-call rows (Task 9) will
 * render collapsed under the assistant bubble via [UiMessage.toolCalls].
 */
fun reduce(state: ChatUiState, event: ChatUiEvent): ChatUiState = when (event) {
    is ChatUiEvent.Content -> state.streamAssistant { it.copy(content = it.content + event.text) }
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
    is ChatUiEvent.Done -> state.finalizeAssistant(MsgLifecycle.COMPLETE, cost = event.usage?.totalCost, error = null)
    is ChatUiEvent.Interrupted ->
        state.finalizeAssistant(MsgLifecycle.INTERRUPTED, cost = event.usage?.totalCost, error = "Stream interrupted.")
    is ChatUiEvent.Failed -> state.finalizeAssistant(MsgLifecycle.INTERRUPTED, cost = null, error = event.message)
}

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
    error: String?,
): ChatUiState {
    val messages = messages.toMutableList()
    val last = messages.lastOrNull()
    if (last != null && last.role == "assistant" && last.lifecycle == MsgLifecycle.STREAMING) {
        messages[messages.lastIndex] = last.copy(lifecycle = lifecycle, cost = cost)
    }
    return copy(messages = messages, running = false, error = error ?: stateErrorOrNull(this, error))
}

/** Keep an existing error when a terminal event carries none (only Failed/Interrupted set one). */
private fun stateErrorOrNull(state: ChatUiState, error: String?): String? = state.error.takeIf { error == null } ?: error