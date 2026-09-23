package io.orangerabbit.clanker.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.orangerabbit.clanker.network.ModelSummary
import io.orangerabbit.clanker.network.OpenRouterClient
import io.orangerabbit.clanker.security.SecretStore

/**
 * Settings screen: Task 5 PKCE connect flow embedded, plus the default model
 * picker fed by the client's `models()` catalog.
 *
 * Task 7 contract: `models()` throws IllegalStateException on non-2xx — the
 * picker degrades gracefully (error text, empty picker) rather than crashing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    secretStore: SecretStore,
    httpClient: HttpClient,
    client: OpenRouterClient,
    defaultModel: String?,
    onModelSelected: (String) -> Unit,
    /** Code pre-filled from the `clanker://oauth?code=` deep-link; null if none. */
    pendingOAuthCode: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            ConnectFlow(
                secretStore = secretStore,
                httpClient = httpClient,
                initialCode = pendingOAuthCode.orEmpty(),
            )

            Spacer(modifier = Modifier.height(24.dp))
            Text("Default model", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            ModelPicker(
                selected = defaultModel,
                onSelected = onModelSelected,
                client = client,
            )
        }
    }
}

@Composable
private fun ModelPicker(
    selected: String?,
    onSelected: (String) -> Unit,
    client: OpenRouterClient,
    modifier: Modifier = Modifier,
) {
    var models by remember { mutableStateOf<List<ModelSummary>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            models = client.models()
        } catch (e: Exception) {
            // graceful degradation: keep picker empty, show error text (Task 7 contract)
            error = "Model catalog unavailable: ${e.message ?: "unknown error"}"
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = selected ?: "None selected",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedButton(
            onClick = { expanded = !expanded },
            enabled = models.isNotEmpty(),
        ) {
            Text(if (expanded) "Hide models" else "Choose model")
        }
        error?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (expanded) {
            Spacer(modifier = Modifier.height(4.dp))
            LazyColumn(modifier = Modifier.height(300.dp)) {
                items(models) { model ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelected(model.id)
                                expanded = false
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                    ) {
                        Text(
                            text = "${model.name} — ${model.id}",
                            style = if (model.id == selected) {
                                MaterialTheme.typography.bodyMedium
                            } else {
                                MaterialTheme.typography.bodySmall
                            },
                            color = if (model.id == selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
            }
        }
    }
}