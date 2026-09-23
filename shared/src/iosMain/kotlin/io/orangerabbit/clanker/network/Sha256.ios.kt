package io.orangerabbit.clanker.network

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH

/**
 * iOS actual: SHA-256 via Apple CommonCrypto CC_SHA256.
 *
 * Requires a macOS toolchain to compile — this file is structurally correct per the
 * CommonCrypto C API and is CI / macOS-verified. It cannot be compiled locally on JVM/Linux.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun sha256B64Url(input: String): String {
    val data = input.encodeToByteArray()
    val digest = ByteArray(CC_SHA256_DIGEST_LENGTH.toInt())
    data.usePinned { pinnedInput ->
        digest.usePinned { pinnedDigest ->
            CC_SHA256(
                pinnedInput.addressOf(0),
                data.size.convert(),
                pinnedDigest.addressOf(0),
            )
        }
    }
    return digest.toBase64UrlNoPadding()
}
