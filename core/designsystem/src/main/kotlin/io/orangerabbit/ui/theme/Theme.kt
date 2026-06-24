package io.orangerabbit.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The neutral design-system entrypoint. This is the swap contract: a replacement
 * design-system library must expose `io.orangerabbit.ui.theme.AppTheme`.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val view = LocalView.current
    val activity = view.context as? Activity
    if (!view.isInEditMode && activity != null) {
        SideEffect {
            WindowCompat.getInsetsController(activity.window, view).apply {
                // Dark theme -> light (white) system bar icons on dark surfaces.
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = CyberpunkColorScheme,
        shapes = CyberpunkShapes,
        typography = CyberpunkTypography,
        content = content,
    )
}

/** Aesthetic-named alias for discoverability. Identical to [AppTheme]. */
@Composable
fun CyberpunkTheme(content: @Composable () -> Unit) = AppTheme(content)
