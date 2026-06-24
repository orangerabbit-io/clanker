package io.orangerabbit.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf

/**
 * Aesthetic intensity controls for the cyberpunk effect layer.
 *
 * These are deliberately separate from [androidx.compose.material3.MaterialTheme]
 * tokens: colour, shape and type describe the *design system*, while [CyberpunkFx]
 * describes how much glow/glitch/CRT atmosphere is layered on top. Components read
 * [LocalCyberpunkFx] so a single provider can dial the whole UI from clinical to
 * deranged without touching individual call sites.
 *
 * @param glow      neon bloom on borders, icons and text
 * @param glitch    chromatic-aberration / decrypt animations on [io.orangerabbit.ui.effects.GlitchText]
 * @param scanlines animated CRT scanline overlay ([io.orangerabbit.ui.components.ScanlineOverlay])
 * @param crt       full-screen AGSL CRT post-process ([io.orangerabbit.ui.effects.CrtScreen])
 * @param intensity 0f..1f master scalar applied to every effect's strength
 */
@Immutable
data class CyberpunkFx(
    val glow: Boolean = true,
    val glitch: Boolean = true,
    val scanlines: Boolean = true,
    val crt: Boolean = true,
    val intensity: Float = 1f,
) {
    /** Effective glow strength: zero when glow is off, otherwise the master scalar. */
    val glowLevel: Float get() = if (glow) intensity else 0f

    /** Effective scanline strength. */
    val scanlineLevel: Float get() = if (scanlines) intensity else 0f

    companion object {
        /** Flat, no-effects baseline — equivalent to the original conservative theme. */
        val Off = CyberpunkFx(glow = false, glitch = false, scanlines = false, crt = false, intensity = 0f)

        /** Everything on, full strength. */
        val Max = CyberpunkFx(intensity = 1f)
    }
}

/**
 * Ambient effect configuration. Defaults to [CyberpunkFx.Max] so the design system
 * looks like a cyberpunk system out of the box; wrap a subtree in
 * `CompositionLocalProvider(LocalCyberpunkFx provides ...)` to override.
 */
val LocalCyberpunkFx = compositionLocalOf { CyberpunkFx() }
