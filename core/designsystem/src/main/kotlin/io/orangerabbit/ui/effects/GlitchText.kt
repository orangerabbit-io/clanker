package io.orangerabbit.ui.effects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import io.orangerabbit.ui.theme.LocalCyberpunkFx
import io.orangerabbit.ui.theme.glowShadow
import kotlinx.coroutines.delay

private const val SCRAMBLE_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#%&@/\\<>*+-="

/**
 * A [Text] with cyberpunk affectations driven by [LocalCyberpunkFx]:
 *  - **decrypt-on-appear** — when [decryptOnAppear] is set, the value resolves from
 *    random glyphs to the real text, like a terminal decrypting a field.
 *  - **chromatic split** — periodic RGB-offset ghost copies (the "glitch" jitter).
 *  - **neon glow** — a centred bloom [glowShadow] in [color].
 *
 * Each affectation is gated by its FX flag, so the same call site renders flat when
 * effects are dialled down and unhinged when they're maxed.
 *
 * @param decryptOnAppear scramble→resolve animation keyed on [text]
 * @param glitchColors    the (red, blue) ghost channels for the split; defaults to
 *                        secondary/tertiary theme accents
 */
@Composable
fun GlitchText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    decryptOnAppear: Boolean = true,
    /** When false, only the one-shot decrypt plays — no recurring chromatic-aberration burst. */
    periodicGlitch: Boolean = true,
    glitchColors: Pair<Color, Color> = MaterialTheme.colorScheme.secondary to MaterialTheme.colorScheme.tertiary,
) {
    val fx = LocalCyberpunkFx.current
    val resolvedColor = color.takeIf { it != Color.Unspecified } ?: style.color

    // Decrypt animation.
    var display by remember(text) { mutableStateOf(if (decryptOnAppear && fx.glitch) "" else text) }
    LaunchedEffect(text, fx.glitch) {
        if (!decryptOnAppear || !fx.glitch) {
            display = text
            return@LaunchedEffect
        }
        val steps = 16
        for (i in 0..steps) {
            val revealed = text.length * i / steps
            display = buildString {
                append(text.take(revealed))
                repeat(text.length - revealed) { append(SCRAMBLE_CHARSET.random()) }
            }
            delay(38)
        }
        display = text
    }

    // Periodic chromatic-aberration burst.
    var glitching by remember { mutableStateOf(false) }
    LaunchedEffect(fx.glitch, fx.intensity, periodicGlitch) {
        if (!fx.glitch || !periodicGlitch) {
            glitching = false
            return@LaunchedEffect
        }
        while (true) {
            delay((2200 + SCRAMBLE_CHARSET.indexOf(text.firstOrNull() ?: 'A') * 40).toLong())
            glitching = true
            delay(110)
            glitching = false
        }
    }

    val glowStyle = if (fx.glowLevel > 0f) {
        style.copy(shadow = glowShadow(resolvedColor, blurRadius = 26f * fx.glowLevel))
    } else {
        style
    }

    Box(modifier) {
        if (fx.glitch && glitching) {
            val (red, blue) = glitchColors
            val shift = (1.5f * fx.intensity).dp
            Text(text = display, color = red, style = style, modifier = Modifier.offset(x = -shift).alpha(0.8f))
            Text(text = display, color = blue, style = style, modifier = Modifier.offset(x = shift).alpha(0.8f))
        }
        Text(text = display, color = resolvedColor, style = glowStyle)
    }
}
