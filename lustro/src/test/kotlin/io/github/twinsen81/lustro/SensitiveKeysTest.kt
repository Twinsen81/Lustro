package io.github.twinsen81.lustro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public name check that the Network redactor and the plugin tabs share.
 * DefaultRedactorTest covers it through the redactor, body by body.
 */
class SensitiveKeysTest {
    @Test
    fun `names of secrets are sensitive, in any spelling`() {
        listOf(
            "token", "access_token", "accessToken", "X-Auth-Token", "api_key", "APIKey", "apikey",
            "client_secret", "password", "passwd", "user_pwd", "Authorization", "session_id",
            "credentials", "bearer", "signature", "presigned_url", "refresh_tokens", "PIN_SECRET",
        ).forEach { assertTrue(it, SensitiveKeys.isSensitive(it)) }
    }

    @Test
    fun `ordinary words, public names, counts, and columns are not sensitive`() {
        listOf(
            "username", "theme", "author_name", "authors", "design", "assignments", "keywords",
            "Idempotency-Key", "public_key", "vapidKey", "prompt_tokens", "max_tokens", "totalTokenCount",
            "primary_key", "partitionKey", "session_count", "",
        ).forEach { assertFalse(it, SensitiveKeys.isSensitive(it)) }
    }

    @Test
    fun `a name outside ASCII is matched in its lowercase form`() {
        assertTrue(SensitiveKeys.isSensitive("ÜSER_TOKEN"))
        assertFalse(SensitiveKeys.isSensitive("ÜSER_NAME"))
    }
}
