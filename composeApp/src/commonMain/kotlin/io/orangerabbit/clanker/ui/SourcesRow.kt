package io.orangerabbit.clanker.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.orangerabbit.clanker.model.ToolCall

/**
 * Collapsed row for tool-call results (Task 9's server tools populate these;
 * the reducer already carries [UiMessage.toolCalls] through).
 */
@Composable
fun SourcesRow(toolCalls: List<ToolCall>, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = modifier) {
        Text(text = "Sources (${toolCalls.size})")
    }
    if (expanded) {
        toolCalls.forEach { call ->
            Text(
                text = "${call.name}(${call.argumentsJson})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}