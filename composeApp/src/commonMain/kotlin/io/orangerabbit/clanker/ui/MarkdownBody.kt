package io.orangerabbit.clanker.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Markdown rendering seam for assistant bubbles (Task 8 Step 4):
 * multiplatform-markdown-renderer-m3 with the code-highlighting extension.
 *
 * If the renderer ever fails to resolve or misbehaves on a target, this is the
 * single seam to swap for a plain-text fallback without touching call sites.
 */
@Composable
internal fun MarkdownBody(content: String, modifier: Modifier = Modifier) {
    Markdown(
        content = content,
        colors = markdownColor(),
        typography = markdownTypography(),
        components = markdownComponents(
            codeFence = highlightedCodeFence,
            codeBlock = highlightedCodeBlock,
        ),
        modifier = modifier,
    )
}