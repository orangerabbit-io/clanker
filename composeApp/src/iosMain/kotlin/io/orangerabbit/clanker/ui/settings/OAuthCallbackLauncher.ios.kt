package io.orangerabbit.clanker.ui.settings

import io.orangerabbit.clanker.network.PkcePair
import io.orangerabbit.clanker.network.authUrl

/**
 * iOS: no loopback HTTP server and no custom-scheme callback — use OpenRouter's
 * documented headless flow: omit `callback_url` entirely (OpenRouter then
 * displays the authorization code on screen) and let the user paste it.
 * The paste field in [ConnectFlow] stays the primary iOS path.
 */
actual class OAuthCallbackLauncher {
    actual suspend fun awaitAuthCode(pkce: PkcePair, openUri: (String) -> Unit): String? {
        openUri(authUrl(callback = "", challenge = pkce.challenge))
        return null
    }
}
