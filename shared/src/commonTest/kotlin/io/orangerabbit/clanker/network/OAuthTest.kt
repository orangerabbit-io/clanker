package io.orangerabbit.clanker.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class OAuthTest {

    // ── pkcePair() ─────────────────────────────────────────────────────────────

    @Test
    fun pkcePairVerifierMinLength() {
        val pair = pkcePair()
        assertTrue(
            pair.verifier.length >= 43,
            "verifier must be >= 43 chars per RFC 7636; got ${pair.verifier.length}",
        )
    }

    @Test
    fun pkcePairVerifierAllowedChars() {
        val pair = pkcePair()
        val allowed = Regex("^[A-Za-z0-9\\-._~]+$")
        assertTrue(
            allowed.matches(pair.verifier),
            "verifier must match [A-Za-z0-9-._~]+; got '${pair.verifier}'",
        )
    }

    @Test
    fun pkcePairChallengeEqualsSha256OfVerifier() {
        val pair = pkcePair()
        val expected = sha256B64Url(pair.verifier)
        assertEquals(
            expected,
            pair.challenge,
            "challenge must equal base64url(SHA-256(verifier))",
        )
    }

    @Test
    fun pkcePairTwoCallsProduceDifferentPairs() {
        val p1 = pkcePair()
        val p2 = pkcePair()
        assertNotEquals(p1.verifier, p2.verifier, "consecutive pkcePair() calls must differ")
        assertNotEquals(p1.challenge, p2.challenge, "consecutive challenges must differ")
    }

    // ── exchangeKey() ──────────────────────────────────────────────────────────

    /**
     * Verifies exchangeKey():
     *  - POSTs to exactly `https://openrouter.ai/api/v1/auth/keys`
     *  - sends the verifier and code_challenge_method=S256 in the body
     *  - returns the `key` field from the JSON response
     */
    @Test
    fun exchangeKeyPostsCorrectUrlAndReturnsKey() = runTest {
        var capturedUrl: String? = null
        var capturedMethod: HttpMethod? = null
        var capturedBody: String? = null

        val mockEngine = MockEngine { request ->
            capturedUrl = request.url.toString()
            capturedMethod = request.method
            // TextContent/ByteArrayContent — read bytes from OutgoingContent.ByteArrayContent
            capturedBody = (request.body as OutgoingContent.ByteArrayContent)
                .bytes()
                .decodeToString()
            respond(
                content = """{"key":"sk-or-v1-test-abc123"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val client = HttpClient(mockEngine)
        val key = exchangeKey(code = "auth-code-xyz", verifier = "test-verifier-abc", httpClient = client)

        assertEquals("https://openrouter.ai/api/v1/auth/keys", capturedUrl, "POST URL must be exact")
        assertEquals(HttpMethod.Post, capturedMethod, "method must be POST")
        assertNotNull(capturedBody, "body must be captured")
        val body = capturedBody ?: error("unreachable")
        assertTrue(body.contains("test-verifier-abc"), "body must contain the verifier")
        assertTrue(body.contains("S256"), "body must contain code_challenge_method=S256")
        assertEquals("sk-or-v1-test-abc123", key, "must return the key field from JSON response")
    }
}

private fun assertNotNull(value: Any?, message: String) {
    assertTrue(value != null, message)
}
