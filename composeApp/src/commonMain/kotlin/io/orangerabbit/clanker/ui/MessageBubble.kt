package io.orangerabbit.clanker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.orangerabbit.clanker.agent.UiMessage
import io.orangerabbit.clanker.model.MsgLifecycle
import io.orangerabbit.clanker.model.ReasoningBlock

/**
 * One chat bubble. Assistant content renders markdown (mikepenz renderer via
 * [MarkdownBody]); user content renders as plain text. Assistant bubbles carry
 * the collapsible "Thinking" section, the cost line, and tool-call rows.
 */
@Composable
fun MessageBubble(message: UiMessage, modifier: Modifier = Modifier) {
    val isUser = message.role == "user"
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ) {
            if (isUser) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                AssistantBubble(message)
            }
        }
    }
}

@Composable
private fun AssistantBubble(message: UiMessage, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(12.dp)) {
        if (message.reasoning.isNotEmpty()) {
            ReasoningSection(message.reasoning)
        }
        if (message.content.isNotEmpty()) {
            MarkdownBody(message.content)
        }
        Text(
            text = formatCost(message.cost),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message.serverToolUse?.let { counts ->
            Text(
                text = counts.entries.joinToString(
                    separator = ", ",
                ) { "${it.key.replace("_requests", "")}: ${it.value}" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (message.lifecycle == MsgLifecycle.INTERRUPTED) {
            Text(
                text = "interrupted",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (message.sources.isNotEmpty()) {
            SourcesRow(message.sources)
        }
    }
}

/** Collapsible "Thinking" section: renders reasoning `text` only. */
@Composable
private fun ReasoningSection(blocks: List<ReasoningBlock>, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { expanded = !expanded }, modifier = modifier) {
        Text(text = if (expanded) "Thinking" else "Thinking (collapsed)")
    }
    if (expanded) {
        blocks.forEach { block ->
            block.text?.takeIf { it.isNotEmpty() }?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Cost line: `$0.0012` or `cost unavailable` (brief Task 8 Step 4). */
internal fun formatCost(cost: Double?): String =
    if (cost == null) "cost unavailable" else "$%.4f".format(cost)