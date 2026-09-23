package io.orangerabbit.clanker.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.orangerabbit.clanker.network.authUrl
import io.orangerabbit.clanker.network.exchangeKey
import io.orangerabbit.clanker.network.pkcePair
import io.orangerabbit.clanker.security.SecretStore
import kotlinx.coroutines.launch

/**
 * Settings screen for connecting an OpenRouter API key via PKCE OAuth.
 *
 * Flow:
 *  1. "Authorize in Browser" opens the OpenRouter auth page with a `clanker://oauth` callback
 *     URL and the generated PKCE challenge.
 *  2. OpenRouter redirects to `clanker://oauth?code=<code>` — the OS routes the app to
 *     the foreground via the registered scheme (see AndroidManifest intent-filter / iOS URL
 *     scheme).  The user then pastes the code into the text field.
 *  3. The paste path **always works** as a fallback: open the auth URL without a scheme
 *     handler or just copy-paste the code shown on-screen by OpenRouter.
 *  4. Tapping "Connect" exchanges the code for an API key and stores it under
 *     `"openrouter_key"` in [secretStore].
 *
 * Fail-closed: if [secretStore] throws at any point the error is displayed and the screen
 * stays in the disconnected state.
 */
@Composable
fun ConnectFlow(
    secretStore: SecretStore,
    httpClient: HttpClient,
    /** Optional code pre-filled from the `clanker://oauth?code=` deep-link callback. */
    initialCode: String = "",
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    // PKCE pair generated once per composition.  The pair stays stable across recompositions so
    // the verifier and the challenge used in the authorization URL remain consistent.
    val pkce = remember { pkcePair() }

    var codeInput by remember(initialCode) { mutableStateOf(initialCode) }
    var connected by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Connect OpenRouter",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (connected) {
            Text("✓ Connected to OpenRouter")
        } else {
            // ── primary path: open browser with scheme callback ──────────────
            Button(onClick = {
                val url = authUrl(callback = "clanker://oauth", challenge = pkce.challenge)
                try {
                    uriHandler.openUri(url)
                } catch (_: Exception) {
                    // openUri is best-effort; if it fails the paste path still works
                }
            }) {
                Text("Authorize in Browser")
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── paste fallback (always available) ────────────────────────────
            Text(
                text = "Paste the authorization code shown by OpenRouter:",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = codeInput,
                onValueChange = { codeInput = it },
                label = { Text("Authorization Code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            errorMessage?.let {
                Text(
                    text = it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            Button(
                onClick = {
                    scope.launch {
                        try {
                            val key = exchangeKey(
                                code = codeInput.trim(),
                                verifier = pkce.verifier,
                                httpClient = httpClient,
                            )
                            // Fail-closed: if getOrPut throws (e.g. Keystore unavailable)
                            // the exception propagates to the catch below and the screen
                            // stays disconnected.
                            secretStore.getOrPut("openrouter_key") { key }
                            connected = true
                            errorMessage = null
                        } catch (e: Exception) {
                            errorMessage = "Connection failed: ${e.message ?: "unknown error"}"
                        }
                    }
                },
                enabled = codeInput.isNotBlank(),
            ) {
                Text("Connect")
            }
        }
    }
}
