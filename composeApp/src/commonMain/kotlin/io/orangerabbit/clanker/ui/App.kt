package io.orangerabbit.clanker.ui

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient
import io.orangerabbit.clanker.security.SecretStore
import io.orangerabbit.clanker.ui.settings.ConnectFlow
import io.orangerabbit.clanker.ui.theme.ClankerTheme

@Composable
fun App(
    secretStore: SecretStore,
    httpClient: HttpClient,
    /** Code pre-filled from a `clanker://oauth?code=` deep-link callback; null if none. */
    pendingOAuthCode: String? = null,
) {
    ClankerTheme {
        ConnectFlow(
            secretStore = secretStore,
            httpClient = httpClient,
            initialCode = pendingOAuthCode ?: "",
        )
    }
}
