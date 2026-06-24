package io.orangerabbit.clanker.core.character

/**
 * Canonical, provider-neutral persona model. Character Card V2 is the floor; V1 flat JSON is
 * backfilled and V3 extras are preserved where present. Lorebook/character_book support is
 * deferred (the parser tolerates and ignores it for now).
 */
data class CharacterCard(
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val characterVersion: String = "",
)
