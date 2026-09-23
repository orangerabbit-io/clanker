package io.orangerabbit.clanker.network

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Returns the SHA-256 hash of [input] (UTF-8 encoded) as a base64url string without padding,
 * per RFC 4648 §5.  Suitable as a PKCE S256 code_challenge.
 *
 * Platform actuals:
 *   JVM / Android — java.security.MessageDigest("SHA-256")
 *   iOS           — CommonCrypto CC_SHA256 (macOS toolchain required; not locally compilable)
 */
expect fun sha256B64Url(input: String): String

/**
 * Encodes this byte array as base64url (RFC 4648 §5) without padding characters.
 * Uses [kotlin.io.encoding.Base64.UrlSafe] (stable in Kotlin 2.0+).
 */
@OptIn(ExperimentalEncodingApi::class)
internal fun ByteArray.toBase64UrlNoPadding(): String =
    Base64.UrlSafe.encode(this).trimEnd('=')
