package io.orangerabbit.clanker.util

/**
 * Keeps the device screen awake while a chat stream is running.
 *
 * Design (Task 8): the Android actual needs an Activity window reference, the
 * iOS actual is global, and JVM is a no-op — so the platform seam is a factory
 * taking an opaque `context: Any?` (the smallest contract that compiles
 * multiplatform). Android passes the Activity; iOS/JVM pass anything (ignored).
 */
interface KeepAwake {
    fun acquire()
    fun release()
}

/** Android: pass the Activity (its Window gets FLAG_KEEP_SCREEN_ON); elsewhere: ignored. */
expect fun createKeepAwake(context: Any?): KeepAwake