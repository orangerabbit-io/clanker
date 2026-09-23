package io.orangerabbit.clanker.agent

import io.orangerabbit.clanker.network.ToolSpec

/** Server-side tools executed by OpenRouter mid-request (Task 9). */
enum class ServerTool {
    WEB_SEARCH,
    WEB_FETCH,
    DATETIME,
}

/**
 * Per-chat tool preferences. [maxToolCalls] caps how many server tool calls
 * OpenRouter may make per request (top-level `max_tool_calls` wire field);
 * [searchMaxResults] feeds the web_search `max_results` parameter.
 */
data class ChatSettings(
    val tools: Set<ServerTool> = emptySet(),
    val maxToolCalls: Int = 10,
    val searchMaxResults: Int = 5,
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
