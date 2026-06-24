package io.orangerabbit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.orangerabbit.ui.theme.AppTheme
import io.orangerabbit.ui.theme.LocalCyberpunkFx
import io.orangerabbit.ui.theme.neonGlow

/** Accent role for [ThemedButton]; maps to a (container, content) token pair. */
enum class ThemedButtonAccent { Primary, Secondary, Tertiary, Neutral }

@Composable
private fun ThemedButtonAccent.container(): Color = when (this) {
    ThemedButtonAccent.Primary -> MaterialTheme.colorScheme.primary
    ThemedButtonAccent.Secondary -> MaterialTheme.colorScheme.secondary
    ThemedButtonAccent.Tertiary -> MaterialTheme.colorScheme.tertiary
    ThemedButtonAccent.Neutral -> MaterialTheme.colorScheme.surfaceVariant
}

@Composable
private fun ThemedButtonAccent.onContainer(): Color = when (this) {
    ThemedButtonAccent.Primary -> MaterialTheme.colorScheme.onPrimary
    ThemedButtonAccent.Secondary -> MaterialTheme.colorScheme.onSecondary
    ThemedButtonAccent.Tertiary -> MaterialTheme.colorScheme.onTertiary
    ThemedButtonAccent.Neutral -> MaterialTheme.colorScheme.onSurface
}

/**
 * Color for the outlined style's border and label. For the chromatic accents
 * this is the accent itself; for [ThemedButtonAccent.Neutral] the container
 * token ([androidx.compose.material3.ColorScheme.surfaceVariant]) is a dark fill
 * and would be invisible as a border on a dark surface, so the readable
 * `onSurfaceVariant` is used instead.
 */
@Composable
private fun ThemedButtonAccent.outline(): Color = when (this) {
    ThemedButtonAccent.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> container()
}

/**
 * Generic cut-corner button. [filled] = solid accent container; otherwise an
 * outlined style with an accent border and accent label. Typography (monospace)
 * is inherited from the theme, so callers pass a styled [Text] in [content].
 *
 * All appearance derives from [MaterialTheme] tokens — no hardcoded colors — so
 * a theme swap restyles every button.
 */
@Composable
fun ThemedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: ThemedButtonAccent = ThemedButtonAccent.Primary,
    filled: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit,
) {
    val container = accent.container()
    val onContainer = accent.onContainer()
    val outline = accent.outline()
    val shape = MaterialTheme.shapes.small
    val glowLevel = if (enabled) LocalCyberpunkFx.current.glowLevel else 0f
    // Filled buttons bloom in their container colour, outlined ones in their accent.
    val glowModifier = if (glowLevel > 0f) {
        modifier.neonGlow(
            color = if (filled) container else outline,
            shape = shape,
            glowRadius = (16 * glowLevel).dp,
            alpha = (if (filled) 0.6f else 0.45f) * glowLevel,
        )
    } else {
        modifier
    }
    if (filled) {
        Button(
            onClick = onClick,
            modifier = glowModifier,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = onContainer,
            ),
            contentPadding = contentPadding,
            content = content,
        )
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = glowModifier,
            enabled = enabled,
            shape = shape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = outline),
            border = BorderStroke(1.dp, outline),
            contentPadding = contentPadding,
            content = content,
        )
    }
}

@Preview
@Composable
private fun ThemedButtonFilledPreview() {
    AppTheme {
        ThemedButton(
            onClick = {},
            accent = ThemedButtonAccent.Primary,
            filled = true,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(text = "EXECUTE", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Preview
@Composable
private fun ThemedButtonOutlinedPreview() {
    AppTheme {
        ThemedButton(
            onClick = {},
            accent = ThemedButtonAccent.Tertiary,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(text = "ABORT", style = MaterialTheme.typography.labelLarge)
        }
    }
}
