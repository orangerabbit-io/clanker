package io.orangerabbit.clanker.network

import java.security.SecureRandom

private val csprng = SecureRandom()

internal actual fun secureRandomBytes(n: Int): ByteArray =
    ByteArray(n).also { csprng.nextBytes(it) }
