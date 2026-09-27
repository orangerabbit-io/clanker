package io.orangerabbit.clanker.network

import io.ktor.http.ContentType
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Loopback HTTP server that captures the OpenRouter OAuth authorization code
 * via the docs-sanctioned localhost callback (`http://127.0.0.1:<port>/`).
 *
 * OpenRouter's `callback_url` only supports https and localhost/127.0.0.1 URLs
 * on any port — custom schemes like `clanker://oauth` are silently rejected
 * (the /auth request bounces to the marketing homepage).  This server binds a
 * free random port on the loopback interface, answers the browser's
 * `GET /?code=...&state=...` redirect with a small confirmation page, and hands
 * the `code` to [awaitCode].
 *
 * Lifecycle:
 *  1. [start] binds the socket and returns the actual port.
 *  2. The caller opens `authUrl(callback = "http://127.0.0.1:<port>/", ...)`.
 *  3. [awaitCode] suspends until the redirect arrives (or timeout/cancellation)
 *     and shuts the server down in every outcome — cancellation always releases
 *     the listen socket.
 *
 * Lives in the `jvmAndroidMain` source set: the Ktor server CIO engine is
 * JVM-only, and iOS uses the headless/paste flow instead (its compilation must
 * never see these JVM types).
 */
class LoopbackRedirectServer {

    private var server: EmbeddedServer<*, *>? = null
    private val codeDeferred = CompletableDeferred<String>()

    /**
     * Binds `127.0.0.1` on a free random OS port (port 0) and starts accepting
     * requests.  @return the actual bound port, for building the callback URL.
     */
    suspend fun start(): Int {
        val s = embeddedServer(CIO, host = "127.0.0.1", port = 0) {
            routing {
                get("/") {
                    val code = call.request.queryParameters["code"]
                    if (code != null) {
                        codeDeferred.complete(code)
                        call.respondText(AUTHORIZED_HTML, ContentType.Text.Html)
                    } else {
                        // Redirect without a code yet — keep the browser informed.
                        call.respondText(WAITING_HTML, ContentType.Text.Html)
                    }
                }
            }
        }
        server = s
        s.start(wait = false)
        val port = s.engine.resolvedConnectors().first().port
        check(port != 0) { "loopback server failed to resolve an actual port" }
        return port
    }

    /**
     * Suspends until the first `GET /?code=...` is captured, then shuts the
     * server down.  Cancellable: cancelling this coroutine (or the timeout
     * elapsing) also shuts the server down.
     *
     * @throws CancellationException on cancellation (server still shut down)
     * @throws kotlinx.coroutines.TimeoutCancellationException after [timeout]
     * @throws IllegalStateException if [start] was never called
     */
    suspend fun awaitCode(timeout: Duration = 10.minutes): String {
        try {
            return withTimeout(timeout) { codeDeferred.await() }
        } finally {
            stop()
        }
    }

    /** Idempotent shutdown of the listen socket and request handling. */
    fun stop() {
        server?.stop(gracePeriodMillis = 500, timeoutMillis = 1_000)
        server = null
    }

    private companion object {
        val AUTHORIZED_HTML = """
            <html><body style="font-family: sans-serif; text-align: center; padding-top: 3em;">
            <h2>Authorization received</h2>
            <p>Return to clanker.</p>
            </body></html>
        """.trimIndent()
        val WAITING_HTML = """
            <html><body style="font-family: sans-serif; text-align: center; padding-top: 3em;">
            <p>Waiting for authorization…</p>
            </body></html>
        """.trimIndent()
    }
}
