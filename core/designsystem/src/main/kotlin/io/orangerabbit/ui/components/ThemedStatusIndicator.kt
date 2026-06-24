package io.orangerabbit.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.orangerabbit.ui.theme.AppTheme
import io.orangerabbit.ui.theme.CyberStatusOnline
import io.orangerabbit.ui.theme.LocalCyberpunkFx

@Composable
fun ThemedStatusIndicator(
    label: String,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
) {
    val onlineColor = CyberStatusOnline
    val offlineColor = MaterialTheme.colorScheme.error
    val color = if (isOnline) onlineColor else offlineColor
    val statusText = if (isOnline) "ONLINE" else "OFFLINE"
    val pulseDurationMs = 1200
    val glowLevel = LocalCyberpunkFx.current.glowLevel

    val infiniteTransition = rememberInfiniteTransition(label = "statusPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                // Unclipped bloom halo — concentric falloff that breathes with the pulse.
                .drawBehind {
                    if (glowLevel > 0f) {
                        val base = this.size.minDimension / 2f
                        val layers = 5
                        for (i in layers downTo 1) {
                            val t = i / layers.toFloat()
                            drawCircle(
                                color = color.copy(alpha = pulseAlpha * 0.22f * glowLevel * (1f - t) ),
                                radius = base * (1f + t * 2.4f * glowLevel),
                            )
                        }
                    }
                }
                .clip(CircleShape)
                .drawBehind {
                    drawCircle(
                        color = color.copy(alpha = pulseAlpha * 0.3f),
                        radius = this.size.minDimension,
                    )
                    drawCircle(
                        color = color.copy(alpha = pulseAlpha),
                        radius = this.size.minDimension / 2f,
                    )
                }
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (label.isNotEmpty()) {
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Preview
@Composable
private fun ThemedStatusIndicatorPreview() {
    AppTheme {
        Surface {
            ThemedStatusIndicator(
                label = "relay",
                isOnline = true,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
