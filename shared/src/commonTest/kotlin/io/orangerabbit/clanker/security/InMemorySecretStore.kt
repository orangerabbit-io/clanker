package io.orangerabbit.clanker.security

/**
 * In-memory [SecretStore] for use in unit tests. Not thread-safe; suitable for single-threaded
 * test coroutines only. Platform actuals are smoke-tested on devices in Task 11.
 */
class InMemorySecretStore : SecretStore {
    private val data = mutableMapOf<String, String>()

    override suspend fun get(id: String): String? = data[id]

    override suspend fun getOrPut(id: String, generator: suspend () -> String): String {
        data[id]?.let { return it }
        val value = generator()
        data[id] = value
        return value
    }

    override suspend fun put(id: String, value: String) {
        data[id] = value
    }
}
