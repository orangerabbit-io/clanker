package io.orangerabbit.clanker

import androidx.compose.ui.window.ComposeUIViewController
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.orangerabbit.clanker.db.createDatabase
import io.orangerabbit.clanker.persistence.ConversationRepository
import io.orangerabbit.clanker.security.PlatformSecretStore
import io.orangerabbit.clanker.ui.App
import io.orangerabbit.clanker.util.createKeepAwake

fun MainViewController() = ComposeUIViewController {
    App(
        secretStore = PlatformSecretStore(Unit), // context is unused on iOS
        httpClient = HttpClient(Darwin),
        repository = ConversationRepository(createDatabase()),
        keepAwake = createKeepAwake(Unit), // context unused on iOS
    )
}