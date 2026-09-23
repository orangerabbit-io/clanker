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
 * CC_SHA256 operates on `unsigned char *` (Kotlin `UByteVar`), so the pinned
 * buffers must be UByteArrays — a pinned ByteArray yields CPointer<ByteVar>,
 * which does not match the interop signature.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun sha256B64Url(input: String): String {
    val data = input.encodeToByteArray().asUByteArray()
    val digest = UByteArray(CC_SHA256_DIGEST_LENGTH)
    data.usePinned { pinnedInput ->
        digest.usePinned { pinnedDigest ->
            CC_SHA256(
                pinnedInput.addressOf(0),
                data.size.convert(),
                pinnedDigest.addressOf(0),
            )
        }
    }
    return digest.toByteArray().toBase64UrlNoPadding()
}
