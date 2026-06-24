package io.orangerabbit.clanker.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemPromptTest {

    @Test
    fun systemPromptTextIsNonBlankAndNamesClanker() {
        assertTrue(SystemPrompt.TEXT.isNotBlank())
        assertTrue(SystemPrompt.TEXT.contains("clanker", ignoreCase = true))
    }

    @Test
    fun blankAgentsMdYieldsSystemPromptOnly() {
        assertEquals(SystemPrompt.TEXT, composeSystemPrompt(""))
    }

    @Test
    fun whitespaceOnlyAgentsMdIsTreatedAsBlank() {
        assertEquals(SystemPrompt.TEXT, composeSystemPrompt("   \n\t "))
    }

    @Test
    fun nonBlankAgentsMdIsAppendedAfterDoubleNewline() {
        val instructions = "Use conventional commits."
        assertEquals(
            SystemPrompt.TEXT + "\n\n" + instructions,
            composeSystemPrompt(instructions),
        )
    }

    @Test
    fun agentsMdOuterWhitespaceIsTrimmedBeforeAppending() {
        assertEquals(
            SystemPrompt.TEXT + "\n\n" + "Be terse.",
            composeSystemPrompt("  Be terse.  "),
        )
    }
}
