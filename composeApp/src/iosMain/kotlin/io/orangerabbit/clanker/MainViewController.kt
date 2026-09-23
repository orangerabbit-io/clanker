package io.orangerabbit.clanker

import androidx.compose.ui.window.ComposeUIViewController
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.orangerabbit.clanker.security.PlatformSecretStore
import io.orangerabbit.clanker.ui.App

fun MainViewController() = ComposeUIViewController {
    App(
        secretStore = PlatformSecretStore(Unit), // context is unused on iOS
        httpClient = HttpClient(Darwin),
    )
}
