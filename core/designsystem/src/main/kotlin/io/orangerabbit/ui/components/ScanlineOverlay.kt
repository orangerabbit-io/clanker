package io.orangerabbit.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.orangerabbit.ui.theme.AppTheme
import io.orangerabbit.ui.theme.LocalCyberpunkFx

/**
 * A CRT scanline overlay that slowly rolls downward, plus a faint travelling bright
 * band, for that "the picture isn't quite locked" tube feel.
 *
 * Strength follows [LocalCyberpunkFx] ([io.orangerabbit.ui.theme.CyberpunkFx.scanlineLevel]):
 * the lines fade out entirely when scanlines are disabled. [alpha] is the strength at
 * full FX intensity. When stacked under [io.orangerabbit.ui.effects.CrtScreen] the two
 * scanline sources compound — leave one off if that's too much.
 *
 * @param spacing distance between lines
 * @param alpha   per-line opacity at full intensity
 * @param animate roll the lines (set false for a static overlay)
 */
@Composable
fun ScanlineOverlay(
    modifier: Modifier = Modifier,
    spacing: Dp = 3.dp,
    alpha: Float = 0.12f,
    animate: Boolean = true,
) {
    val level = LocalCyberpunkFx.current.scanlineLevel
    val effectiveAlpha = alpha * level

    // Always created (no conditional composable calls); ignored when not animating.
    val roll by rememberInfiniteTransition(label = "scanroll").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanphase",
    )
    val phase = if (animate) roll else 0f

    Box(
        modifier = modifier.drawBehind {
            if (effectiveAlpha <= 0f) return@drawBehind
            val spacingPx = spacing.toPx()
            val lineColor = Color.Black.copy(alpha = effectiveAlpha)
            var y = -spacingPx + phase * spacingPx
            while (y < size.height) {
                drawLine(
                    color = lineColor,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1f,
                )
                y += spacingPx
            }
            // Travelling refresh band.
            val bandY = (phase * 4f % 1f) * size.height
            drawLine(
                color = Color.White.copy(alpha = effectiveAlpha * 0.4f),
                start = Offset(0f, bandY),
                end = Offset(size.width, bandY),
                strokeWidth = spacingPx * 2f,
            )
        },
    )
}

@Preview
@Composable
private fun ScanlineOverlayPreview() {
    AppTheme {
        Surface {
            ScanlineOverlay(modifier = Modifier.fillMaxSize())
        }
    }
}
