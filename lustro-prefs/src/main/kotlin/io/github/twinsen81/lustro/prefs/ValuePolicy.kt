package io.github.twinsen81.lustro.prefs

/**
 * What the Preferences tab shows of the values in a file. The keys, types, and
 * sizes always show. The `masked` keys of [PrefsFile] and [PrefsDataStore] are
 * left out under every policy.
 */
public enum class ValuePolicy {
    /** Every value. */
    VISIBLE,

    /**
     * Every value, except the value of a key that looks like it holds a secret,
     * such as `auth_token`, `apiKey`, or `password`. The check is
     * [SensitiveKeys.isSensitive][io.github.twinsen81.lustro.SensitiveKeys.isSensitive],
     * the same as the Network tab's redaction. It goes by the key, and never
     * leaves out a boolean, a float, or a double, which an app does not use for
     * a secret, so a switch whose key is named `show_original_time_ago_key`
     * shows its value. The app can show the values of some of these keys with
     * the `unmasked` keys of [PrefsFile] and [PrefsDataStore]: a name there
     * matches a key with exactly that name, in any case.
     *
     * A string value that is a JSON object or array shows with the values of its
     * sensitive fields replaced by `[REDACTED]`, at any depth, as the Network tab
     * redacts a JSON body, except a boolean or a `null`. The console opens such a
     * value empty to edit it, so that the placeholder never goes back into the file.
     */
    MASK_SENSITIVE,

    /** No value: for a file that holds secrets under any key, such as a decrypting `EncryptedSharedPreferences`. */
    KEYS_ONLY,
}
