package io.orangerabbit.clanker.core.character

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonaTest {

    @Test
    fun substitutesCharAndUserCaseInsensitively() {
        assertEquals(
            "Hi Bob, I am Aria",
            Persona.substituteMacros("Hi {{user}}, I am {{Char}}", charName = "Aria", userName = "Bob"),
        )
    }

    @Test
    fun systemPromptComposesDefinitionWithSubstitution() {
        val card = CharacterCard(
            name = "Aria",
            description = "A wizard named {{char}}",
            personality = "wise",
            scenario = "in a tower",
        )
        val prompt = Persona.systemPrompt(card, userName = "Bob")
        assertTrue("A wizard named Aria" in prompt)
        assertTrue("Personality: wise" in prompt)
        assertTrue("Scenario: in a tower" in prompt)
    }

    @Test
    fun systemPromptUsesSystemPromptField() {
        val card = CharacterCard(name = "Aria", systemPrompt = "You are {{char}}.")
        assertTrue("You are Aria." in Persona.systemPrompt(card))
    }

    @Test
    fun greetingUsesFirstMesAndSubstitutes() {
        val card = CharacterCard(name = "Aria", firstMes = "Hello {{user}}!")
        assertEquals("Hello Bob!", Persona.greeting(card, userName = "Bob"))
    }

    @Test
    fun greetingPicksAlternateByIndex() {
        val card = CharacterCard(name = "Aria", firstMes = "A", alternateGreetings = listOf("B", "C"))
        assertEquals("C", Persona.greeting(card, index = 2)) // 0=firstMes, 1=B, 2=C
    }
}
