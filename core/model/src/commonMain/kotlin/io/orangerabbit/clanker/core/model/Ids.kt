package io.orangerabbit.clanker.core.model

/**
 * Identifier value classes. Wrapping [String] keeps `:core:model` free of the experimental
 * `kotlin.uuid` opt-in while still giving us type-safe ids across the domain. Generation of
 * fresh ids lives in platform/data code, not here.
 */

@JvmInline
value class ConversationId(val value: String)

@JvmInline
value class MessageId(val value: String)

@JvmInline
value class CharacterId(val value: String)

@JvmInline
value class ProviderId(val value: String)

@JvmInline
value class SshTargetId(val value: String)

/** Opaque reference to bytes stored on disk (attachment payloads), never inlined into the DB. */
@JvmInline
value class BlobRef(val value: String)

/** Opaque reference to a secret held in the encrypted secret store; never the secret itself. */
@JvmInline
value class SecretRef(val value: String)
