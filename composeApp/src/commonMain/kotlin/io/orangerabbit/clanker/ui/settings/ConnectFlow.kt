package io.orangerabbit.clanker.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.orangerabbit.clanker.network.exchangeKey
import io.orangerabbit.clanker.network.pkcePair
import io.orangerabbit.clanker.security.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

/**
 * Settings screen for connecting an OpenRouter API key via PKCE OAuth.
 *
 * Flow (primary "Authorize in Browser" path):
 *  1. Tapping the button opens the OpenRouter auth page.  On Android/JVM the
 *     callback is `http://127.0.0.1:<port>/` served by a one-shot loopback
 *     redirect server (OpenRouter only accepts https/localhost callbacks —
 *     custom schemes are silently rejected); the code is captured automatically
 *     when OpenRouter redirects back.  On iOS the headless URL is opened
 *     (no `callback_url` — OpenRouter displays the code on screen) and the
 *     user pastes it.
 *  2. While waiting, a "Waiting for authorization…" state with a Cancel button
 *     is shown; cancel shuts the loopback server down.
 *  3. The paste path **always works** as a fallback: the code shown by
 *     OpenRouter can be typed/pasted into the text field on any platform.
 *  4. Tapping "Connect" (or an automatically captured code) exchanges the code
 *     for an API key and stores it under `"openrouter_key"` in [secretStore].
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
    val launcher = remember { OAuthCallbackLauncher() }

    // PKCE pair generated once per composition.  The pair stays stable across recompositions so
    // the verifier and the challenge used in the authorization URL remain consistent.
    val pkce = remember { pkcePair() }

    var codeInput by remember(initialCode) { mutableStateOf(initialCode) }
    var connected by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var waitingForAuth by remember { mutableStateOf(false) }
    var authJob by remember { mutableStateOf<Job?>(null) }

    // Restore connected state from persisted key on first composition.
    // Fail-closed: if the storage layer throws, remain disconnected.
    LaunchedEffect(Unit) {
        try {
            connected = secretStore.get("openrouter_key") != null
        } catch (_: Exception) {
            // storage unavailable — stay disconnected
        }
    }

    val openUriSafely: (String) -> Unit = { url ->
        try {
            uriHandler.openUri(url)
        } catch (_: Exception) {
            // openUri is best-effort; if it fails the paste path still works
        }
    }

    val connect: suspend (String) -> Unit = { code ->
        try {
            val key = exchangeKey(
                code = code.trim(),
                verifier = pkce.verifier,
                httpClient = httpClient,
            )
            // put() overwrites any stale key so re-connect always stores
            // the fresh key.  Fail-closed: if put throws (e.g. Keystore
            // unavailable) the exception propagates and the screen stays
            // disconnected.
            secretStore.put("openrouter_key", key)
            connected = true
            errorMessage = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorMessage = "Connection failed: ${e.message ?: "unknown error"}"
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "Connect OpenRouter",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (connected) {
            Text("✓ Connected to OpenRouter")
        } else {
            // ── primary path: open browser, capture the code automatically ──
            Button(
                onClick = {
                    authJob = scope.launch {
                        waitingForAuth = true
                        try {
                            val code = launcher.awaitAuthCode(pkce, openUriSafely)
                            if (code != null) {
                                connect(code)
                            }
                        } catch (e: TimeoutCancellationException) {
                            errorMessage =
                                "Authorization timed out — paste the code shown by OpenRouter below."
                        } catch (e: CancellationException) {
                            // user cancelled — fall back to the paste path
                        } catch (e: Exception) {
                            errorMessage =
                                "Authorization failed: ${e.message ?: "unknown error"}"
                        } finally {
                            waitingForAuth = false
                        }
                    }
                },
                enabled = !waitingForAuth,
            ) {
                Text("Authorize in Browser")
            }

            if (waitingForAuth) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Waiting for authorization…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = { authJob?.cancel() }) {
                        Text("Cancel")
                    }
                }
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
                    scope.launch { connect(codeInput) }
                },
                enabled = codeInput.isNotBlank(),
            ) {
                Text("Connect")
            }
        }
    }
}
