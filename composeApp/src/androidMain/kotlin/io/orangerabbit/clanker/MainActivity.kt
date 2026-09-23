package io.orangerabbit.clanker

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.orangerabbit.clanker.db.createDatabase
import io.orangerabbit.clanker.persistence.ConversationRepository
import io.orangerabbit.clanker.security.PlatformSecretStore
import io.orangerabbit.clanker.ui.App
import io.orangerabbit.clanker.util.createKeepAwake

class MainActivity : ComponentActivity() {

    private val secretStore by lazy { PlatformSecretStore(this) }
    private val httpClient by lazy { HttpClient(OkHttp) }
    private val repository by lazy { ConversationRepository(createDatabase(this)) }
    private val keepAwake by lazy { createKeepAwake(this) }

    // Compose-observable state: updated in onNewIntent when the OS routes the
    // clanker://oauth?code=<code> deep-link back to this activity (singleTask).
    private var pendingOAuthCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        setContent {
            App(
                secretStore = secretStore,
                httpClient = httpClient,
                repository = repository,
                keepAwake = keepAwake,
                pendingOAuthCode = pendingOAuthCode,
            )
        }
    }

    /** Called when a new `clanker://oauth` intent arrives while the activity is already running. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "clanker" && uri.host == "oauth") {
            pendingOAuthCode = uri.getQueryParameter("code")
        }
    }
}