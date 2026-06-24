package io.orangerabbit.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.orangerabbit.ui.theme.AppTheme
import io.orangerabbit.ui.theme.LocalCyberpunkFx
import io.orangerabbit.ui.theme.neonGlow

@Composable
fun ThemedCard(
    modifier: Modifier = Modifier,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    borderWidth: Dp = 1.dp,
    glow: Boolean = true,
    glowColor: Color = borderColor,
    content: @Composable () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val glowLevel = LocalCyberpunkFx.current.glowLevel
    // A neutral outline shouldn't bloom; only accent-bordered cards emit light.
    val emit = glow && glowLevel > 0f && glowColor != MaterialTheme.colorScheme.outline
    val cardModifier = if (emit) {
        modifier.neonGlow(
            color = glowColor,
            shape = shape,
            glowRadius = (22 * glowLevel).dp,
            alpha = 0.5f * glowLevel,
        )
    } else {
        modifier
    }
    Card(
        modifier = cardModifier.fillMaxWidth(),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Box(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Preview
@Composable
private fun ThemedCardPreview() {
    AppTheme {
        ThemedCard(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "CARD CONTENT",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
