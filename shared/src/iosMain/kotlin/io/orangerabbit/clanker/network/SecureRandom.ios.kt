package io.orangerabbit.clanker.network

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault

/**
 * iOS actual: CSPRNG via SecRandomCopyBytes (platform.Security).
 * Requires a macOS toolchain — structurally correct per the Security framework C API.
 * Cannot be compiled locally; CI/macOS-verified in Task 11.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun secureRandomBytes(n: Int): ByteArray {
    val bytes = ByteArray(n)
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(kSecRandomDefault, n.convert(), pinned.addressOf(0))
        check(status == 0) { "SecRandomCopyBytes failed: status=$status" }
    }
    return bytes
}
