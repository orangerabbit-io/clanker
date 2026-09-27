package io.orangerabbit.clanker.ui.settings

import io.orangerabbit.clanker.network.PkcePair

/**
 * Platform seam for the "Authorize in Browser" OAuth path (hand-threaded DI,
 * same pattern as SecretStore/KeepAwake).
 *
 * Implementations open the OpenRouter authorization page for [pkce] through
 * [openUri] and suspend until the authorization code is captured, then return
 * it.  Return null when the platform cannot capture the code automatically
 * (iOS headless paste mode: OpenRouter displays the code on screen and the
 * user pastes it).
 *
 * Cancellation must release every resource held while waiting — on Android the
 * loopback redirect server's listen socket.  Timeouts surface as
 * [kotlinx.coroutines.TimeoutCancellationException].
 */
expect class OAuthCallbackLauncher() {
    suspend fun awaitAuthCode(pkce: PkcePair, openUri: (String) -> Unit): String?
}
