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
    fun `a yes-or-no flag about a secret is not sensitive`() {
        listOf(
            "is_password_protected", "isPasswordProtected", "has_password", "hasApiKey", "can_authenticate",
            "should_refresh_token", "sharing_public_password_enforced", "end_to_end_encryption_keys_exist",
            "passwordEnabled", "auth_required", "sessionDisabled", "sharing_public_ask_for_optional_password",
            "askForPassword",
        ).forEach { assertFalse(it, SensitiveKeys.isSensitive(it)) }
    }

    @Test
    fun `a flag word elsewhere in the name keeps it sensitive`() {
        listOf(
            "existing_session_token", "required_token", "enabled_api_key", "password_is", "this_password",
            "task_token", "hash_key", "protected_key", "token_exists_value",
        ).forEach { assertTrue(it, SensitiveKeys.isSensitive(it)) }
    }

    @Test
    fun `a name outside ASCII is matched in its lowercase form`() {
        assertTrue(SensitiveKeys.isSensitive("ÜSER_TOKEN"))
        assertFalse(SensitiveKeys.isSensitive("ÜSER_NAME"))
    }
}
