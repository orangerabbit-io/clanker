package io.orangerabbit.clanker.core.model

/**
 * A chat thread. The agent definition (fixed system prompt + AGENTS.md) is composed into the
 * System message at request time rather than baked into stored history, so edits apply
 * retroactively. An agent-profile reference will return here when profiles land.
 */
data class Conversation(
    val id: ConversationId,
    val title: String,
    val providerId: ProviderId,
    val modelId: String,
    val messages: List<ChatMessage>,
    val costAccumUsd: Double = 0.0,
)

/**
 * A configured LLM endpoint. [authRef] points into the encrypted secret store — the key itself
 * never lives in this object. [isCustomEndpoint] gates the confirmation flow before any key is
 * sent to a non-built-in host.
 */
data class ProviderConfig(
    val id: ProviderId,
    val displayName: String,
    val baseUrl: String,
    val authRef: SecretRef,
    val defaultHeaders: Map<String, String> = emptyMap(),
    val isCustomEndpoint: Boolean = false,
)
