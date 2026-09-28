package io.orangerabbit.clanker.network

import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Live-socket tests for [LoopbackRedirectServer] (the OpenRouter OAuth loopback
 * callback capture).  These use a real HTTP client against 127.0.0.1 — no mocks —
 * because the entire point of the server is OS-level socket behaviour.
 */
class LoopbackRedirectServerTest {

    /** Blocking GET returning (status, body); used off the test coroutine. */
    private fun get(url: String): Pair<Int, String> {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 2_000
        conn.readTimeout = 2_000
        try {
            val status = conn.responseCode
            val body = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            return status to body
        } finally {
            conn.disconnect()
        }
    }

    /** Polls until a connection to 127.0.0.1:[port] is refused (server shut down). */
    private fun assertConnectionRefused(port: Int, label: String) {
        val deadline = System.currentTimeMillis() + 5_000
        var refused = false
        while (System.currentTimeMillis() < deadline) {
            try {
                get("http://127.0.0.1:$port/?code=late&state=x")
            } catch (_: Exception) {
                refused = true
                break
            }
            Thread.sleep(100)
        }
        assertTrue(refused, "$label: server must stop accepting connections after shutdown")
    }

    @Test
    fun awaitCodeReturnsCodeFromFirstRequestAndShutsDown() = runBlocking {
        val server = LoopbackRedirectServer()
        val port = server.start()
        try {
            // Fire the loopback redirect from another coroutine, like the browser would.
            val http = async {
                get("http://127.0.0.1:$port/?code=testcode123&state=x")
            }
            val code = withTimeout(10_000) { server.awaitCode() }
            assertEquals("testcode123", code, "awaitCode() must return the code query param")

            val (status, body) = http.await()
            assertEquals(200, status, "GET /?code=... must be answered with HTTP 200")
            assertTrue(
                body.contains("Authorization received"),
                "response page must tell the user authorization was received; got: $body",
            )

            assertConnectionRefused(port, "after awaitCode() returned")
        } finally {
            server.stop()
        }
    }

    @Test
    fun cancelledAwaitCodeShutsDownServer() {
        val server = LoopbackRedirectServer()
        val port = kotlinx.coroutines.runBlocking {
            val started = kotlinx.coroutines.withTimeout(10_000) { server.start() }
            val job = launch { server.awaitCode() }
            kotlinx.coroutines.yield() // let the coroutine reach awaitCode's suspension
            job.cancel()
            job.join()
            started
        }

        // Cancellation must release the listen socket: rebinding the same port
        // must succeed (poll — ktor shutdown is asynchronous). 
        val rebound = ServerSocket()
        rebound.reuseAddress = true
        val deadline = System.currentTimeMillis() + 5_000
        var bound = false
        while (System.currentTimeMillis() < deadline) {
            try {
                rebound.bind(InetSocketAddress("127.0.0.1", port))
                bound = true
                break
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        assertTrue(bound, "cancelled awaitCode() must release the listen port $port")
        rebound.close()

        assertConnectionRefused(port, "after cancelled awaitCode()")
    }
}
