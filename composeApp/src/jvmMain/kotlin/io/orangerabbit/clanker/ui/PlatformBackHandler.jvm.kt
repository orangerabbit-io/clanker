package io.orangerabbit.clanker.ui

import androidx.compose.runtime.Composable

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    // No system back on desktop; nothing to intercept.
}
