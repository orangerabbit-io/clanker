package io.orangerabbit.clanker.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.orangerabbit.clanker.agent.ChatSettings
import io.orangerabbit.clanker.agent.ServerTool
import io.orangerabbit.clanker.network.OpenRouterClient
import io.orangerabbit.clanker.persistence.ChatSettingsCodec
import io.orangerabbit.clanker.persistence.ConversationRepository
import io.orangerabbit.clanker.security.SecretStore
import io.orangerabbit.clanker.ui.MessageBubble
import io.orangerabbit.clanker.util.KeepAwake

/**
 * Chat screen: LazyColumn of [MessageBubble]s, bottom input, running indicator,
 * and error banner. Keep-awake is acquired while `running == true` (owned by
 * [ChatViewModel] so it is always released on terminal paths).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    repository: ConversationRepository,
    secretStore: SecretStore,
    client: OpenRouterClient,
    keepAwake: KeepAwake,
    conversationId: String,
    storedSettingsJson: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val decodedSettings = remember(conversationId, storedSettingsJson) {
        ChatSettingsCodec.decode(storedSettingsJson)
    }
    val viewModel = remember(conversationId, decodedSettings) {
        ChatViewModel(
            client = client,
            repository = repository,
            secretStore = secretStore,
            keepAwake = keepAwake,
            conversationId = conversationId,
            model = decodedSettings.model ?: "",
            chatSettings = decodedSettings.settings,
        )
    }
    val toolSettings by viewModel.toolSettings.collectAsState()
    var toolsExpanded by rememberSaveable { mutableStateOf(false) }
    val state by viewModel.state.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll as bubbles appear and deltas accumulate.
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Chat") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { toolsExpanded = !toolsExpanded }) {
                            Icon(Icons.Filled.Build, contentDescription = "Tools")
                        }
                    },
                )
                if (toolsExpanded) {
                    ToolToggles(
                        settings = toolSettings,
                        onToggle = { viewModel.toggleTool(it) },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding(),
        ) {
            state.error?.let { error ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.messages) { message ->
                    MessageBubble(message = message)
                }
            }

            if (state.running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                )
                if (state.running) {
                    IconButton(onClick = { viewModel.cancel() }) {
                        Icon(Icons.Filled.Close, contentDescription = "Stop")
                    }
                } else {
                    IconButton(
                        onClick = {
                            viewModel.send(input)
                            input = ""
                        },
                        enabled = input.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

/**
 * Expandable toolbar row: one switch per server tool (Task 9). Toggles persist
 * into the conversation's settingsJson via [ChatViewModel.toggleTool].
 */
@Composable
private fun ToolToggles(
    settings: ChatSettings,
    onToggle: (ServerTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServerTool.entries.forEach { tool ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = TOOL_LABELS.getValue(tool),
                    style = MaterialTheme.typography.labelSmall,
                )
                Switch(checked = tool in settings.tools, onCheckedChange = { onToggle(tool) })
            }
        }
    }
}

private val TOOL_LABELS = mapOf(
    ServerTool.WEB_SEARCH to "WEB_SEARCH",
    ServerTool.WEB_FETCH to "WEB_FETCH",
    ServerTool.DATETIME to "DATETIME",
)