package io.orangerabbit.clanker.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task 9 Step 1: server tool policy.
 *
 * The exact wire shapes below are the Task 9 brief contract:
 *  - WEB_SEARCH → `{"type":"openrouter:web_search","parameters":{"max_results":<n>}}`
 *  - WEB_FETCH → `{"type":"openrouter:web_fetch"}`
 *  - DATETIME → `{"type":"openrouter:datetime"}`
 */
class ToolPolicyTest {

    @Test
    fun allThreeToolsProduceThreeSpecsInBriefOrder() {
        val specs = ToolPolicy.toToolSpecs(
            ChatSettings(tools = setOf(ServerTool.WEB_SEARCH, ServerTool.WEB_FETCH, ServerTool.DATETIME)),
        )

        assertEquals(3, specs.size)
        assertEquals("openrouter:web_search", specs[0].type)
        assertEquals("""{"max_results":5}""", specs[0].parametersJson)
        assertEquals("openrouter:web_fetch", specs[1].type)
        assertNull(specs[1].parametersJson, "web_fetch takes no parameters")
        assertEquals("openrouter:datetime", specs[2].type)
        assertNull(specs[2].parametersJson, "datetime takes no parameters")
    }

    @Test
    fun searchMaxResultsFlowsIntoWebSearchParameters() {
        val specs = ToolPolicy.toToolSpecs(ChatSettings(tools = setOf(ServerTool.WEB_SEARCH), searchMaxResults = 9))
        assertEquals("""{"max_results":9}""", specs.single().parametersJson)
    }

    @Test
    fun emptySettingsProduceEmptySpecList() {
        assertTrue(ToolPolicy.toToolSpecs(ChatSettings()).isEmpty())
    }

    @Test
    fun defaultMaxToolCallsIsTen() {
        assertEquals(10, ChatSettings().maxToolCalls)
    }
}
