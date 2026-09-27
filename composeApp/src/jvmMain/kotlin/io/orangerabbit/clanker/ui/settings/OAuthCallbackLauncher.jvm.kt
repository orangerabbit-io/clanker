package io.orangerabbit.clanker.ui.settings

import io.orangerabbit.clanker.network.LoopbackRedirectServer
import io.orangerabbit.clanker.network.PkcePair
import io.orangerabbit.clanker.network.authUrl

/** JVM: identical localhost-callback flow to the Android actual. */
actual class OAuthCallbackLauncher {
    actual suspend fun awaitAuthCode(pkce: PkcePair, openUri: (String) -> Unit): String? {
        val server = LoopbackRedirectServer()
        try {
            val port = server.start()
            openUri(authUrl(callback = "http://127.0.0.1:$port/", challenge = pkce.challenge))
            return server.awaitCode()
        } finally {
            server.stop()
        }
    }
}
