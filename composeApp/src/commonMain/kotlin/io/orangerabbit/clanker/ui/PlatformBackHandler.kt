package io.orangerabbit.clanker.ui

import androidx.compose.runtime.Composable

/**
 * Cross-platform system-back seam. On Android this maps to
 * `androidx.activity.compose.BackHandler`; on JVM/iOS there is no system back
 * gesture that would exit the app, so it is a no-op.
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit)
