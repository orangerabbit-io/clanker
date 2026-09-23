package io.orangerabbit.clanker.security

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecretStoreBehaviorTest {

    /**
     * Simulates a platform storage layer that always fails (e.g., Keystore inaccessible).
     * [get] throws to let the exception propagate through [getOrPut] — proving fail-closed.
     */
    private class ThrowingStore : SecretStore {
        override suspend fun get(id: String): String? =
            throw IllegalStateException("platform storage failure")

        override suspend fun getOrPut(id: String, generator: suspend () -> String): String {
            get(id) // throws; must not be caught and must not fall back to plaintext
            @Suppress("UNREACHABLE_CODE")
            return generator()
        }

        override suspend fun put(id: String, value: String): Unit =
            throw IllegalStateException("platform storage failure")
    }

    @Test
    fun throwingBackendRethrows(): Unit = runBlocking {
        assertFailsWith<IllegalStateException>("storage failure must propagate, never fall back") {
            ThrowingStore().getOrPut("key") { "plaintext-fallback-must-not-appear" }
        }
    }

    @Test
    fun normalPathStoresAndReturns() = runBlocking {
        val store = InMemorySecretStore()
        val result = store.getOrPut("api-key") { "sk-test-1234" }
        assertEquals("sk-test-1234", result)
        assertEquals("sk-test-1234", store.get("api-key"))
    }

    @Test
    fun secondCallDoesNotInvokeGenerator() = runBlocking {
        val store = InMemorySecretStore()
        var callCount = 0
        val first = store.getOrPut("api-key") { callCount++; "sk-test-1234" }
        val second = store.getOrPut("api-key") { callCount++; "sk-test-xxxx" }
        assertEquals("sk-test-1234", first)
        assertEquals("sk-test-1234", second)
        assertEquals(1, callCount, "generator must be invoked only once, not on cache-hit")
    }

    @Test
    fun putOnAbsentIdStores() = runBlocking {
        val store = InMemorySecretStore()
        store.put("openrouter_key", "sk-or-v1-fresh")
        assertEquals("sk-or-v1-fresh", store.get("openrouter_key"),
            "put on absent id must store the value")
    }

    @Test
    fun putAfterGetOrPutReplacesValue() = runBlocking {
        val store = InMemorySecretStore()
        store.getOrPut("openrouter_key") { "sk-or-v1-old" }
        store.put("openrouter_key", "sk-or-v1-new")
        assertEquals("sk-or-v1-new", store.get("openrouter_key"),
            "put after getOrPut must overwrite the stale value")
    }

    @Test
    fun throwingStorePutRethrows(): Unit = runBlocking {
        assertFailsWith<IllegalStateException>("put storage failure must propagate") {
            ThrowingStore().put("key", "value")
        }
    }
}
