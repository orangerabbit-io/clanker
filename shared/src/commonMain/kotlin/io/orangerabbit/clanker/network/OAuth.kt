package io.orangerabbit.clanker.network

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** PKCE verifier/challenge pair used for OpenRouter OAuth S256 flow. */
data class PkcePair(val verifier: String, val challenge: String)

private const val VERIFIER_CHARS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
private const val VERIFIER_LENGTH = 64

// VERIFIER_CHARS has 66 entries.  Rejection-sampling bound: 66 * (256/66) = 198.
// Accept a byte only if it is < 198; then byte % 66 gives a perfectly uniform index.
// Accept rate ≈ 198/256 ≈ 77%; two buffers of VERIFIER_LENGTH*2 bytes are virtually
// guaranteed to contain ≥ VERIFIER_LENGTH accepted bytes on the first attempt.
private val VERIFIER_CHARS_LEN = VERIFIER_CHARS.length          // 66
private val VERIFIER_ACCEPT_BOUND = VERIFIER_CHARS_LEN * (256 / VERIFIER_CHARS_LEN) // 198

/**
 * Generates a fresh RFC 7636 PKCE pair using a CSPRNG.  Each call produces a distinct pair.
 * Verifier is 64 characters from the unreserved set `[A-Za-z0-9\-._~]`, drawn with
 * rejection sampling so that every character is chosen with equal probability.
 */
fun pkcePair(): PkcePair {
    val verifier = buildString(VERIFIER_LENGTH) {
        while (length < VERIFIER_LENGTH) {
            // Oversample to minimise system-call overhead while guaranteeing enough
            // accepted bytes in virtually every iteration.
            val raw = secureRandomBytes((VERIFIER_LENGTH - length + 1) * 2)
            for (b in raw) {
                if (length >= VERIFIER_LENGTH) break
                val v = b.toInt() and 0xFF
                if (v < VERIFIER_ACCEPT_BOUND) append(VERIFIER_CHARS[v % VERIFIER_CHARS_LEN])
            }
        }
    }
    return PkcePair(verifier = verifier, challenge = sha256B64Url(verifier))
}

/**
 * Builds the OpenRouter authorization URL.
 *
 * @param callback callback URL (e.g. `"clanker://oauth"`).  Pass an empty string for
 *   headless/paste mode — OpenRouter then displays the code on-screen.
 * @param challenge PKCE S256 code_challenge (base64url SHA-256 of the verifier)
 * @param label optional key label shown in the OpenRouter dashboard; defaults to `"clanker"`
 * @return full authorization URL ready to open in a browser
 */
fun authUrl(callback: String, challenge: String, label: String = "clanker"): String = buildString {
    append("https://openrouter.ai/auth")
    if (callback.isNotBlank()) {
        append("?callback_url=")
        append(callback.encodeURLParameter())
        append("&code_challenge=")
    } else {
        // Headless / paste mode: omit callback_url entirely so OpenRouter displays the
        // code on-screen rather than attempting a redirect.
        append("?code_challenge=")
    }
    append(challenge)
    append("&code_challenge_method=S256")
    append("&key_label=")
    append(label.encodeURLParameter())
}

@Serializable
private data class KeyRequest(
    val code: String,
    val code_verifier: String,
    val code_challenge_method: String,
)

@Serializable
private data class KeyResponse(val key: String)

private val oauthJson = Json { ignoreUnknownKeys = true }

/**
 * Exchanges an OAuth authorization code for an OpenRouter API key.
 *
 * POSTs `{"code","code_verifier","code_challenge_method":"S256"}` to
 * `https://openrouter.ai/api/v1/auth/keys` and returns the `key` field.
 *
 * @param code the authorization code from the OAuth callback or manual paste
 * @param verifier the PKCE code verifier that produced the challenge in [authUrl]
 * @param httpClient Ktor [HttpClient] to use; caller provides the platform engine
 * @throws IllegalStateException on non-2xx HTTP response
 * @throws kotlinx.serialization.SerializationException on unexpected response shape
 */
suspend fun exchangeKey(code: String, verifier: String, httpClient: HttpClient): String {
    val body = oauthJson.encodeToString(KeyRequest(code = code, code_verifier = verifier, code_challenge_method = "S256"))
    val response = httpClient.post("https://openrouter.ai/api/v1/auth/keys") {
        setBody(TextContent(body, ContentType.Application.Json))
    }
    check(response.status.value in 200..299) {
        "OpenRouter key exchange failed: HTTP ${response.status}"
    }
    return oauthJson.decodeFromString<KeyResponse>(response.bodyAsText()).key
}
