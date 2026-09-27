package io.orangerabbit.clanker.ui.settings

import io.orangerabbit.clanker.network.LoopbackRedirectServer
import io.orangerabbit.clanker.network.PkcePair
import io.orangerabbit.clanker.network.authUrl

/**
 * Android/JVM: OpenRouter only accepts https and localhost callbacks, so the
 * authorization page gets `http://127.0.0.1:<port>/` backed by a one-shot
 * loopback HTTP server (custom schemes like `clanker://oauth` are silently
 * rejected — the /auth request bounces to the marketing homepage).
 */
actual class OAuthCallbackLauncher {
    actual suspend fun awaitAuthCode(pkce: PkcePair, openUri: (String) -> Unit): String? {
        val server = LoopbackRedirectServer()
        try {
            val port = server.start()
            openUri(authUrl(callback = "http://127.0.0.1:$port/", challenge = pkce.challenge))
            return server.awaitCode()
        } finally {
            // Belt and braces: awaitCode() already stops the server on code,
            // timeout and cancellation; this also covers early exits.
            server.stop()
        }
    }
}
