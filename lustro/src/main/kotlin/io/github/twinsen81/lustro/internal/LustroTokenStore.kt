package io.github.twinsen81.lustro.internal

import android.content.Context
import android.content.SharedPreferences
import java.security.SecureRandom
import kotlin.io.encoding.Base64

/**
 * Persists the always-on access token used to authenticate debug requests.
 *
 * The token is a 256-bit [SecureRandom] value, Base64-URL encoded without
 * padding. The encoder is the Kotlin standard library's: `android.util.Base64`
 * is absent under plain JVM unit tests, and `java.util.Base64` needs API 26.
 * The token is stored in a private `lustro_debug` [SharedPreferences] file
 * (`Context.MODE_PRIVATE`).
 *
 * Because the prefs file lives in app-private storage, clearing the app's data
 * or a fresh install naturally yields a new (empty) prefs file and therefore a
 * new token — no special handling is required.
 *
 * With [newTokenOnFirstUse], the first [token] call of this instance replaces
 * the persisted token. A `Lustro` whose server listens beyond loopback sets it,
 * so each process starts with a new token: on a network the token travels as
 * plain HTTP and the app must show it to a client that has no adb, so a token
 * that leaked must not stay valid for the life of the install. The first read
 * does it, not the constructor, so no caller of this instance ever gets the
 * token of the process before.
 *
 * Construction does no disk I/O: the prefs file is opened on first use, so
 * call [token], [rotate], and [reset] off the main thread.
 */
internal class LustroTokenStore(
    context: Context,
    private val newTokenOnFirstUse: Boolean = false,
) {
    private val appContext: Context = context.applicationContext

    // Guarded by LOCK.
    private var replacedOnFirstUse = false

    private val prefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Returns the persisted token, generating and persisting one on first
     * access, or a new one in place of it with [newTokenOnFirstUse].
     * Subsequent calls return the same value until [rotate]/[reset].
     */
    fun token(): String =
        synchronized(LOCK) {
            if (newTokenOnFirstUse && !replacedOnFirstUse) {
                // Set after the write, so that a failed one is tried again.
                generateAndStore().also { replacedOnFirstUse = true }
            } else {
                prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotEmpty() } ?: generateAndStore()
            }
        }

    /** Generates, persists, and returns a fresh token, invalidating the old one. */
    fun rotate(): String = synchronized(LOCK) { generateAndStore() }

    /**
     * Stores where the server is listening, next to the token, so a tool that
     * can't read the `LustroToken` log line finds both in this prefs file
     * (`adb shell run-as <package> cat shared_prefs/lustro_debug.xml`). A
     * device that drops Info logs drops that line.
     */
    fun recordEndpoint(host: String, port: Int) {
        synchronized(LOCK) { prefs.edit().putString(KEY_HOST, host).putInt(KEY_PORT, port).apply() }
    }

    /** Clears the persisted token. The next [token] call generates a new one. */
    fun reset() {
        synchronized(LOCK) { prefs.edit().remove(KEY_TOKEN).apply() }
    }

    private fun generateAndStore(): String {
        val raw = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(raw)
        val token = ENCODER.encode(raw)
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    private companion object {
        private const val PREFS_NAME = "lustro_debug"
        private const val KEY_TOKEN = "lustro_token"
        private const val KEY_HOST = "lustro_host"
        private const val KEY_PORT = "lustro_port"

        // 256 bits of entropy.
        private const val TOKEN_BYTES = 32

        private val ENCODER: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

        // Process-wide rather than per instance: every store shares the one prefs
        // file, so two stores reading it concurrently for the first time must not
        // both generate a token and persist different values.
        private val LOCK = Any()
    }
}
