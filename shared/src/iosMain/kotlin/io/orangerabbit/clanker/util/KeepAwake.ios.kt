package io.orangerabbit.clanker.util

import platform.UIKit.UIApplication

/**
 * iOS keep-awake: disables the idle timer for the whole app while acquired
 * (UIApplication.sharedApplication.isIdleTimerDisabled). `context` is unused.
 */
actual fun createKeepAwake(context: Any?): KeepAwake = object : KeepAwake {
    override fun acquire() {
        // K/N imports the ObjC `isIdleTimerDisabled` getter as a function; the
        // setter is setIdleTimerDisabled(Boolean).
        UIApplication.sharedApplication.setIdleTimerDisabled(true)
    }

    override fun release() {
        UIApplication.sharedApplication.setIdleTimerDisabled(false)
    }
}