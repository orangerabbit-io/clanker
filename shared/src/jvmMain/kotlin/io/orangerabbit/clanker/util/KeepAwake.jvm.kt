package io.orangerabbit.clanker.util

/** JVM has no screen to keep awake; no-op (used only by tests/dev consumers). */
actual fun createKeepAwake(context: Any?): KeepAwake = object : KeepAwake {
    override fun acquire() {}
    override fun release() {}
}