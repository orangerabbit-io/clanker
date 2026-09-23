package io.orangerabbit.clanker.security

/**
 * JVM actual: secure storage is not available on the JVM target. Any caller on JVM will receive
 * [IllegalStateException]. Tests use [InMemorySecretStore] injected directly — they never reach
 * this class. Platform smoke tests run on Android/iOS in Task 11.
 */
actual class PlatformSecretStore actual constructor(@Suppress("UNUSED_PARAMETER") context: Any) :
    SecretStore {

    override suspend fun get(id: String): String? =
        throw IllegalStateException("no secure storage on jvm")

    override suspend fun getOrPut(id: String, generator: suspend () -> String): String =
        throw IllegalStateException("no secure storage on jvm")

    override suspend fun put(id: String, value: String): Unit =
        throw IllegalStateException("no secure storage on jvm")
}
