package io.orangerabbit.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

// Cyberpunk palette — transcribed from cyberpunk.json
internal val CyberAmber = Color(0xFFFFB300)
internal val CyberOnPrimary = Color(0xFF000000)
internal val CyberPrimaryContainer = Color(0xFF805A00)
internal val CyberOnPrimaryContainer = Color(0xFFFFE082)
internal val CyberHalRed = Color(0xFFFF1744)
internal val CyberOnSecondary = Color(0xFF000000)
internal val CyberSecondaryContainer = Color(0xFF7F0B22)
internal val CyberOnSecondaryContainer = Color(0xFFFF8A80)
internal val CyberCyan = Color(0xFF00E5FF)
internal val CyberOnTertiary = Color(0xFF000000)
internal val CyberTertiaryContainer = Color(0xFF007380)
internal val CyberOnTertiaryContainer = Color(0xFF84FFFF)
internal val CyberBackground = Color(0xFF08080E)
internal val CyberOnBackground = Color(0xFFE8E8EC)
internal val CyberSurface = Color(0xFF0F0F17)
internal val CyberOnSurface = Color(0xFFE8E8EC)
internal val CyberSurfaceVariant = Color(0xFF181822)
internal val CyberOnSurfaceVariant = Color(0xFF8A8A96)
internal val CyberOutline = Color(0xFF3A3A48)
internal val CyberOutlineVariant = Color(0xFF262630)
internal val CyberError = Color(0xFFFF1744)
internal val CyberOnError = Color(0xFF000000)
internal val CyberErrorContainer = Color(0xFF7F0B22)
internal val CyberOnErrorContainer = Color(0xFFFF8A80)
internal val CyberInverseSurface = Color(0xFFE8E8EC)
internal val CyberInverseOnSurface = Color(0xFF08080E)
internal val CyberInversePrimary = Color(0xFF805A00)

// Custom (non-MD3) aesthetic constants used by components.
internal val CyberStatusOnline = Color(0xFF00E676)

internal val CyberpunkColorScheme: ColorScheme = darkColorScheme(
    primary = CyberAmber,
    onPrimary = CyberOnPrimary,
    primaryContainer = CyberPrimaryContainer,
    onPrimaryContainer = CyberOnPrimaryContainer,
    secondary = CyberHalRed,
    onSecondary = CyberOnSecondary,
    secondaryContainer = CyberSecondaryContainer,
    onSecondaryContainer = CyberOnSecondaryContainer,
    tertiary = CyberCyan,
    onTertiary = CyberOnTertiary,
    tertiaryContainer = CyberTertiaryContainer,
    onTertiaryContainer = CyberOnTertiaryContainer,
    background = CyberBackground,
    onBackground = CyberOnBackground,
    surface = CyberSurface,
    onSurface = CyberOnSurface,
    surfaceVariant = CyberSurfaceVariant,
    onSurfaceVariant = CyberOnSurfaceVariant,
    outline = CyberOutline,
    outlineVariant = CyberOutlineVariant,
    error = CyberError,
    onError = CyberOnError,
    errorContainer = CyberErrorContainer,
    onErrorContainer = CyberOnErrorContainer,
    inverseSurface = CyberInverseSurface,
    inverseOnSurface = CyberInverseOnSurface,
    inversePrimary = CyberInversePrimary,
)
