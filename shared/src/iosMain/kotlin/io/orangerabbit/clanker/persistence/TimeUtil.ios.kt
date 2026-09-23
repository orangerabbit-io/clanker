package io.orangerabbit.clanker.persistence

import platform.Foundation.NSDate

internal actual fun currentTimeMs(): Long =
    (NSDate.date().timeIntervalSince1970 * 1000).toLong()
