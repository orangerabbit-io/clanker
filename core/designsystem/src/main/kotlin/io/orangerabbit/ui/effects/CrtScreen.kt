package io.orangerabbit.ui.effects

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import io.orangerabbit.ui.theme.LocalCyberpunkFx

/**
 * AGSL fragment shader: a flat (non-curved) phosphor-display post-process applied to
 * whatever content is recorded into the layer below. Effects (all scaled by
 * `intensity`):
 *  - a fine RGB phosphor mask (fixed ~3px sub-pixel pitch) + faint pixel-row gaps,
 *    giving a CRT/LCD pixel texture **without downsampling** — the UI stays sharp
 *  - subtle per-channel chromatic aberration radiating from the screen centre
 *  - per-pixel grain and a faint mains-hum flicker
 *
 * No screen curvature, bezel, vignette, or block pixelation — the picture stays
 * rectangular, flat, and fully legible; `intensity` only controls how pronounced the
 * phosphor texture is.
 *
 * `content` is the child shader bound via [RenderEffect.createRuntimeShaderEffect];
 * `.eval()` samples the captured UI in pixel space.
 */
private const val CRT_AGSL = """
uniform shader content;
uniform float2 resolution;
uniform float time;
uniform float intensity;

float hash21(float2 p) {
    p = fract(p * float2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

half4 main(float2 fragCoord) {
    float2 px = fragCoord;
    float2 uv = px / resolution;

    // Subtle chromatic aberration — sampled at full resolution (no snapping).
    float2 dir = uv - 0.5;
    float ca = 1.5 * intensity;
    half4 cr = content.eval(px + dir * ca);
    half4 cg = content.eval(px);
    half4 cb = content.eval(px - dir * ca);
    float3 col = float3(cr.r, cg.g, cb.b);
    float a = cg.a;

    // RGB phosphor mask at a fine fixed pitch: tints sub-pixels R/G/B so the panel
    // reads as a pixel display, but the underlying glyphs are never resampled.
    float pitch = 3.0;
    float off = mix(1.0, 0.78, intensity);
    float sub = mod(floor(px.x / (pitch / 3.0)), 3.0);
    float3 phosphor = float3(off, off, off);
    if (sub < 0.5) { phosphor.r = 1.0; }
    else if (sub < 1.5) { phosphor.g = 1.0; }
    else { phosphor.b = 1.0; }
    col *= phosphor;

    // Faint pixel-row gaps at the same pitch (kept light so it doesn't read as scanlines).
    float TAU = 6.2831853;
    float row = 0.5 + 0.5 * cos((px.y / pitch) * TAU);
    col *= mix(1.0, 0.85 + 0.15 * row, intensity);

    // Restore the brightness the mask removes.
    col *= 1.0 + 0.22 * intensity;

    // Grain.
    float n = hash21(px + fract(time));
    col += (n - 0.5) * (0.035 * intensity);

    // Faint flicker.
    col *= 1.0 - (0.015 * intensity) * (0.5 + 0.5 * sin(time * 36.0));

    return half4(col, a);
}
"""

/**
 * Wraps [content] in a full-screen CRT post-process. The entire composed subtree is
 * captured into a [androidx.compose.ui.graphics.layer.GraphicsLayer] each frame and
 * re-rendered through [CRT_AGSL], so curvature, bloomy scanlines and aberration apply
 * to everything at once — the way a real tube would distort the whole picture.
 *
 * When [enabled] is false (or effective intensity is zero) the content is drawn
 * untouched and no shader work happens.
 *
 * The AGSL [RuntimeShader] post-process requires API 33; on older devices (clanker's floor is
 * minSdk 28) the content is drawn untouched — the rest of the cyberpunk FX layer (glow, glitch,
 * scanlines) is API-28-safe and still applies.
 *
 * @param enabled   master switch; defaults to [io.orangerabbit.ui.theme.CyberpunkFx.crt]
 * @param intensity 0f..1f strength; defaults to the ambient FX intensity
 */
@Composable
fun CrtScreen(
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalCyberpunkFx.current.crt,
    intensity: Float = LocalCyberpunkFx.current.intensity,
    content: @Composable () -> Unit,
) {
    val active = enabled && intensity > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    if (!active) {
        Box(modifier) { content() }
        return
    }
    CrtScreenShader(modifier, intensity, content)
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun CrtScreenShader(
    modifier: Modifier,
    intensity: Float,
    content: @Composable () -> Unit,
) {
    val shader = remember { RuntimeShader(CRT_AGSL) }
    val graphicsLayer = rememberGraphicsLayer()
    var time by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { now -> time = (now - start).toFloat() / 1_000_000_000f }
        }
    }

    Box(
        modifier = modifier.drawWithContent {
            graphicsLayer.record { this@drawWithContent.drawContent() }
            shader.setFloatUniform("resolution", size.width, size.height)
            shader.setFloatUniform("time", time)
            shader.setFloatUniform("intensity", intensity)
            graphicsLayer.renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
            drawLayer(graphicsLayer)
        },
    ) {
        content()
    }
}
