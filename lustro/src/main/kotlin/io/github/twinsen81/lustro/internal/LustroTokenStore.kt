package io.github.twinsen81.lustro.internal

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import java.util.Base64

/**
 * Persists the always-on access token used to authenticate debug requests.
 *
 * The token is a 256-bit [SecureRandom] value, Base64-URL encoded without
 * padding (via [java.util.Base64], NOT `android.util.Base64`, so it works under
 * plain JVM unit tests). It is stored in a private `lustro_debug`
 * [SharedPreferences] file (`Context.MODE_PRIVATE`).
 *
 * Because the prefs file lives in app-private storage, clearing the app's data
 * or a fresh install naturally yields a new (empty) prefs file and therefore a
 * new token — no special handling is required.
 */
internal class LustroTokenStore(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns the persisted token, generating and persisting one on first
     * access. Subsequent calls return the same value until [rotate]/[reset].
     */
    @Synchronized
    fun token(): String {
        prefs.getString(KEY_TOKEN, null)?.let { if (it.isNotEmpty()) return it }
        return generateAndStore()
    }

    /** Generates, persists, and returns a fresh token, invalidating the old one. */
    @Synchronized
    fun rotate(): String = generateAndStore()

    /** Clears the persisted token. The next [token] call generates a new one. */
    @Synchronized
    fun reset() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private fun generateAndStore(): String {
        val raw = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(raw)
        val token = ENCODER.encodeToString(raw)
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    private companion object {
        private const val PREFS_NAME = "lustro_debug"
        private const val KEY_TOKEN = "lustro_token"

        // 256 bits of entropy.
        private const val TOKEN_BYTES = 32

        private val ENCODER: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    }
}
