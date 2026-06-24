package io.orangerabbit.clanker.core.character

/**
 * Maps a [CharacterCard] into the runtime prompt. Macro substitution is case-insensitive; the
 * system message composes system_prompt (if any) with description/personality/scenario.
 */
object Persona {

    fun substituteMacros(text: String, charName: String, userName: String): String =
        text.replace("{{char}}", charName, ignoreCase = true)
            .replace("{{user}}", userName, ignoreCase = true)

    /** The composed system message for this persona. */
    fun systemPrompt(card: CharacterCard, userName: String = "User"): String {
        val name = card.name.ifBlank { "Assistant" }
        fun sub(s: String) = substituteMacros(s, name, userName)
        return buildList {
            if (card.systemPrompt.isNotBlank()) add(sub(card.systemPrompt))
            if (card.description.isNotBlank()) add(sub(card.description))
            if (card.personality.isNotBlank()) add("Personality: " + sub(card.personality))
            if (card.scenario.isNotBlank()) add("Scenario: " + sub(card.scenario))
        }.joinToString("\n\n")
    }

    /** The opening assistant message: first_mes, or the chosen alternate greeting. */
    fun greeting(card: CharacterCard, userName: String = "User", index: Int = 0): String {
        val name = card.name.ifBlank { "Assistant" }
        val greetings = listOf(card.firstMes) + card.alternateGreetings
        val chosen = greetings.getOrNull(index) ?: card.firstMes
        return substituteMacros(chosen, name, userName)
    }
}
