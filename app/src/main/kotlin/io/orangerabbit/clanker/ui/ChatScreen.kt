package io.orangerabbit.clanker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import io.orangerabbit.clanker.core.model.ChatMessage
import io.orangerabbit.clanker.core.model.MsgLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(viewModel: ChatViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var modelMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .imePadding(),
        ) {
            // Session config (in-memory only for MVP)
            OutlinedTextField(
                value = state.apiKey,
                onValueChange = viewModel::setApiKey,
                label = { Text("OpenRouter API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = {
                    modelMenuExpanded = it
                    if (it && state.availableModels.isEmpty()) viewModel.loadModels()
                },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                OutlinedTextField(
                    value = state.model,
                    onValueChange = viewModel::setModel,
                    label = { Text("Model") },
                    singleLine = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false },
                ) {
                    if (state.modelsLoading) {
                        DropdownMenuItem(text = { Text("Loading models…") }, onClick = {}, enabled = false)
                    }
                    state.availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.id) },
                            onClick = {
                                viewModel.setModel(model.id)
                                modelMenuExpanded = false
                            },
                        )
                    }
                }
            }

            state.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(items = state.messages, key = { it.id.value }) { msg ->
                    MessageBubble(msg)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Message") },
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions.Default,
                )
                if (state.streaming) {
                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                } else {
                    Button(
                        onClick = {
                            viewModel.send(draft)
                            draft = ""
                        },
                        enabled = draft.isNotBlank() && state.apiKey.isNotBlank(),
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val (label, body) = when (message) {
        is ChatMessage.System -> "system" to message.content
        is ChatMessage.User -> "you" to message.content
        is ChatMessage.Assistant -> "assistant" to (message.content ?: "")
        is ChatMessage.Tool -> "tool" to message.content
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (message is ChatMessage.User) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall)
            when {
                body.isEmpty() && message.lifecycle == MsgLifecycle.Streaming ->
                    Text(text = "…", style = MaterialTheme.typography.bodyMedium)
                // Assistant replies are markdown; user/system/tool stay plain text.
                message is ChatMessage.Assistant && body.isNotEmpty() ->
                    Markdown(content = body)
                else ->
                    Text(text = body, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
