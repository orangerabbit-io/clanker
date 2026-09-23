package io.orangerabbit.clanker.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import io.orangerabbit.clanker.network.Source

/**
 * Source chips from server-tool url_citation annotations (Task 9): one row per
 * source showing its title (or URL when untitled); tapping opens the platform
 * browser via [LocalUriHandler].
 */
@Composable
fun SourcesRow(sources: List<Source>, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier = modifier) {
        Text(
            text = "Sources",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        sources.forEach { source ->
            TextButton(
                onClick = { runCatching { uriHandler.openUri(source.url) } },
            ) {
                Text(
                    text = source.title ?: source.url,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
