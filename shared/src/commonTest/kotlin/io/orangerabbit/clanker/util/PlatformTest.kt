package io.orangerabbit.clanker.util

import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformTest {
    @Test
    fun platformNameIsNonEmpty() {
        assertTrue(platformName().isNotEmpty(), "platformName() must return a non-empty string")
    }
}
