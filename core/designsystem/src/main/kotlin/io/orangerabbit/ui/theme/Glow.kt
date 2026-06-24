package io.orangerabbit.ui.theme

import android.graphics.BlurMaskFilter
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Draws a soft neon bloom *behind* the composable, in the silhouette of [shape].
 *
 * The glow is a blurred fill of the element's own outline, so it hugs cut corners
 * and rounded rects alike and leaks past the edges to read as emitted light rather
 * than a drop shadow. Backed by [BlurMaskFilter] (hardware-accelerated since API 28),
 * so it needs no `RenderEffect` and works wherever the design system is supported.
 *
 * Apply it *before* the element's background/border so the bloom sits underneath:
 * ```
 * Modifier
 *     .neonGlow(color = accent, shape = MaterialTheme.shapes.medium)
 *     .background(surface, shape)
 *     .border(1.dp, accent, shape)
 * ```
 *
 * @param glowRadius blur radius of the bloom
 * @param alpha      peak opacity of the bloom (0f disables the draw entirely)
 */
fun Modifier.neonGlow(
    color: Color,
    shape: androidx.compose.ui.graphics.Shape,
    glowRadius: Dp = 18.dp,
    alpha: Float = 0.55f,
): Modifier = this.drawBehind {
    if (alpha <= 0f || glowRadius <= 0.dp) return@drawBehind
    val outline = shape.createOutline(Size(size.width, size.height), layoutDirection, this)
    val path = Path().apply {
        when (outline) {
            is Outline.Rectangle -> addRect(outline.rect)
            is Outline.Rounded -> addRoundRect(outline.roundRect)
            is Outline.Generic -> addPath(outline.path)
        }
    }
    drawIntoCanvas { canvas ->
        val paint = Paint().also {
            it.color = color
            it.alpha = alpha
            it.asFrameworkPaint().maskFilter = BlurMaskFilter(glowRadius.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(path, paint)
    }
}

/**
 * A centred, blur-only [Shadow] for use in [androidx.compose.ui.text.TextStyle.shadow].
 * Gives text a neon emission halo without offsetting it.
 */
fun glowShadow(color: Color, blurRadius: Float = 24f): Shadow =
    Shadow(color = color, offset = Offset.Zero, blurRadius = blurRadius)
