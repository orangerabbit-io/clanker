package io.orangerabbit.clanker.network

import java.security.MessageDigest

actual fun sha256B64Url(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.encodeToByteArray())
    return digest.toBase64UrlNoPadding()
}
