package io.orangerabbit.clanker.security

/**
 * Platform-independent contract for encrypted secret storage.
 *
 * [getOrPut] is the primary entry point: it returns the stored value for [id] if one exists,
 * otherwise invokes [generator] **exactly once**, persists the result, and returns it.
 *
 * Fail-closed contract: if the platform storage layer throws at any point, [getOrPut] **rethrows**
 * the exception. It never catches a storage failure and falls back to returning a plaintext value.
 */
interface SecretStore {
    /** Returns the stored value for [id], or null if absent. */
    suspend fun get(id: String): String?

    /**
     * Returns the stored value for [id]. If absent, invokes [generator] exactly once, stores
     * the result, and returns it. Any exception from the storage layer is rethrown (fail-closed).
     */
    suspend fun getOrPut(id: String, generator: suspend () -> String): String
}

/** Platform-specific encrypted-secret storage. Android uses Tink+Keystore; iOS uses Keychain. */
expect class PlatformSecretStore(context: Any) : SecretStore
