package io.orangerabbit.clanker.agent

import io.orangerabbit.clanker.network.ToolSpec

/** Server-side tools executed by OpenRouter mid-request (Task 9). */
enum class ServerTool {
    WEB_SEARCH,
    WEB_FETCH,
    DATETIME,
}

/**
 * Spend caps for server-tool requests. [perRequestUsd] is sent to OpenRouter
 * as a docs-verified `stop_server_tools_when` max_cost condition and enforced
 * client-side as the budget-exhausted terminal state when terminal usage cost
 * exceeds it. [perDayUsd] is persisted for later enforcement (Phase 1:
 * per-request cap only; per-day needs local day tracking).
 */
data class SpendLimits(
    val perRequestUsd: Double = 0.25,
    val perDayUsd: Double = 2.0,
)

/**
 * Per-chat tool preferences. [maxToolCalls] caps how many server tool calls
 * OpenRouter may make per request; when a spend cap is set it is combined
 * into `stop_server_tools_when` (which overrides `max_tool_calls`).
 * [guardrailsEnabled] is the client-side guardrail posture: OpenRouter
 * defines guardrails (prompt-injection detection, PII/secret redaction) only
 * as account/workspace defaults via its management API — there is no
 * chat-completions request-level field (docs-verified), so nothing is sent on
 * the wire; enforcement happens account-level and the flag is persisted so a
 * future request-level flag can adopt it.
 */
data class ChatSettings(
    val tools: Set<ServerTool> = emptySet(),
    val maxToolCalls: Int = 10,
    val searchMaxResults: Int = 5,
    val spendLimits: SpendLimits = SpendLimits(),
    val guardrailsEnabled: Boolean = true,
)

/**
 * Maps enabled server tools to OpenRouter wire tool specs, in brief order.
 * web_search carries `{"max_results":<n>}`; web_fetch and datetime take none
 * (their [ToolSpec.parametersJson] stays null and the client omits the key).
 */
object ToolPolicy {
    fun toToolSpecs(settings: ChatSettings): List<ToolSpec> = buildList {
        if (ServerTool.WEB_SEARCH in settings.tools) {
            add(ToolSpec(type = "openrouter:web_search", parametersJson = """{"max_results":${settings.searchMaxResults}}"""))
        }
        if (ServerTool.WEB_FETCH in settings.tools) {
            add(ToolSpec(type = "openrouter:web_fetch"))
        }
        if (ServerTool.DATETIME in settings.tools) {
            add(ToolSpec(type = "openrouter:datetime"))
        }
    }
}
