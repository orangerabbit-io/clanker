package io.orangerabbit.clanker.network

/**
 * Returns [n] cryptographically secure random bytes.
 *
 * Platform actuals:
 *   JVM / Android — java.security.SecureRandom
 *   iOS           — platform.Security.SecRandomCopyBytes (macOS toolchain required)
 */
internal expect fun secureRandomBytes(n: Int): ByteArray
