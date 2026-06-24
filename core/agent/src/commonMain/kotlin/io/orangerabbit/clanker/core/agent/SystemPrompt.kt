package io.orangerabbit.clanker.core.agent

/**
 * The fixed, app-versioned agent definition (Layer 0). Not user-editable; it ships with the binary
 * and changes only with releases. It is capability-honest: it must not claim tools that do not yet
 * exist. Tool-use framing grows here as the tool registry lands. User preferences live in
 * `AGENTS.md` (Layer 1) and are appended by [composeSystemPrompt].
 */
object SystemPrompt {
    val TEXT: String = """
        You are clanker, an AI agent running on the user's Android device.
        Respond directly and concisely. Do not roleplay or invent a persona.

        You currently have no tools and cannot take actions on the user's systems or access
        external services. Do not claim or imply otherwise. When such capabilities are added,
        this definition will describe them explicitly.

        The user may supply additional instructions and preferences below. Follow them unless they
        conflict with safety or with this definition.
    """.trimIndent()
}

/**
 * Composes the System message sent on each request: the fixed [SystemPrompt.TEXT], plus the user's
 * `AGENTS.md` appended after a blank line when non-blank. Blank/whitespace-only instructions yield
 * the fixed prompt unchanged. Called at request time and never stored in history, so edits apply
 * retroactively.
 */
fun composeSystemPrompt(agentsMd: String): String =
    if (agentsMd.isBlank()) SystemPrompt.TEXT
    else SystemPrompt.TEXT + "\n\n" + agentsMd.trim()
