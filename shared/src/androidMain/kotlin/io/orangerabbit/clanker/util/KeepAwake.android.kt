package io.orangerabbit.clanker.util

import android.app.Activity
import android.view.WindowManager

/**
 * Android keep-awake: sets FLAG_KEEP_SCREEN_ON on the host Activity's window,
 * which is the battery-safe alternative to a wakelock. The activity is supplied
 * by MainActivity; a non-Activity context degrades to a no-op.
 */
actual fun createKeepAwake(context: Any?): KeepAwake {
    val window = (context as? Activity)?.window ?: return NoOpKeepAwake
    return object : KeepAwake {
        override fun acquire() {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun release() {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

private object NoOpKeepAwake : KeepAwake {
    override fun acquire() {}
    override fun release() {}
}