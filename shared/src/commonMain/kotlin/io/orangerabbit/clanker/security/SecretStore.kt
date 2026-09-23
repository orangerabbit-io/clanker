package io.orangerabbit.clanker.security

/**
 * Platform-independent contract for encrypted secret storage.
 *
 * [getOrPut] is the primary entry point for initial writes; [put] is the overwrite path used
 * when a value already exists and must be replaced (e.g. OAuth re-connect).
 *
 * Fail-closed contract: if the platform storage layer throws at any point every method
 * **rethrows** the exception. No method catches a storage failure and falls back to
 * returning or storing a plaintext value.
 */
interface SecretStore {
    /** Returns the stored value for [id], or null if absent. */
    suspend fun get(id: String): String?

    /**
     * Returns the stored value for [id]. If absent, invokes [generator] exactly once, stores
     * the result, and returns it. Any exception from the storage layer is rethrown (fail-closed).
     */
    suspend fun getOrPut(id: String, generator: suspend () -> String): String

    /**
     * Stores [value] under [id], overwriting any existing value.  Suitable for re-connect flows
     * where the caller has already obtained a fresh secret and must replace the stale one.
     *
     * Fail-closed: any exception from the storage layer is rethrown.
     */
    suspend fun put(id: String, value: String)
}

/** Platform-specific encrypted-secret storage. Android uses Tink+Keystore; iOS uses Keychain. */
expect class PlatformSecretStore(context: Any) : SecretStore
