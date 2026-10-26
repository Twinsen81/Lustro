package io.github.twinsen81.lustro

/**
 * Tells whether a name, such as a JSON key, a query parameter, a header, or a
 * SharedPreferences key, looks like it holds a secret.
 *
 * The Network tab's default redactor masks the values of these names, and the
 * Preferences tab masks them by default. A tab of the app's own can use it the
 * same way, so that all tabs agree on what is a secret.
 *
 * A name is sensitive when it contains one of `token`, `key`, `secret`,
 * `password`, `passwd`, `pwd`, `auth`, `session`, `credential`, `bearer`,
 * `signature`, or `sig`, in any case. The name is split into words, at
 * separators and camelCase humps, and the fragment must be in a word that isn't
 * an ordinary one that happens to contain it, in any of its forms: `author_name`,
 * `authors`, `design`, `assignments`, and `keywords` are not sensitive, while
 * `authorization`, `accessToken`, and `presigned_url` are. Names that hold no
 * secret although they have a sensitive word are not sensitive either, in any
 * spelling: `Idempotency-Key`, `public_key`, and `vapid_key`; a token count or
 * limit, where `token` or `tokens` sits next to a word such as `max`, `total`,
 * `count`, `remaining`, `prompt`, or `input`, as in `prompt_tokens`,
 * `max_tokens`, and `totalTokenCount`; and a key that names a column, such as
 * `primary_key` and `partitionKey`.
 *
 * It goes by the name only, so a secret under a name that none of these words
 * are in is not found.
 */
public object SensitiveKeys {
    // Names that have a sensitive word but hold no secret: an idempotency key
    // names a request, a VAPID or other public key is published, a primary or
    // partition key names a column, and a session count is a number. Compared
    // with the name's words joined, so `Idempotency-Key`, `idempotency_key`, and
    // `idempotencyKey` all match, and so does the plural `public_keys`.
    private val PUBLIC_NAMES =
        setOf(
            "idempotencykey", "publickey", "vapidkey",
            "primarykey", "foreignkey", "partitionkey", "sortkey", "rowkey",
            "sessioncount",
        )

    // The word `token` or `tokens` next to one of these is a count or a limit,
    // as LLM APIs report usage and take a cap, and as rate-limit headers say
    // what is left: `prompt_tokens`, `max_tokens`, `totalTokenCount`,
    // `x-ratelimit-remaining-tokens`. A credential's name has none of them:
    // `access_token`, `refresh_tokens`, `device_tokens`. Matched as listed, not
    // through INFLECTIONS: a count word exempts `token`, a sensitive word, and
    // `limited` is no count although `limit` is, as a limited-use token is a
    // credential.
    private val COUNT_WORDS =
        setOf(
            "count", "counts", "total", "totals", "max", "maximum", "min", "minimum",
            "limit", "limits", "remaining", "used", "usage", "num", "number", "budget",
            "quota", "ratelimit", "per", "prompt", "completion", "input", "output",
            "reasoning", "cached", "candidates", "thoughts", "prediction", "estimated",
            "billed", "cost",
        )

    private val TOKEN_WORDS = setOf("token", "tokens")

    // Ordinary words that contain a sensitive fragment. A word that only has a
    // fragment as part of one of these doesn't make its name sensitive.
    private val NON_SENSITIVE_WORDS =
        setOf(
            "author", "authorship", "authority",
            "design", "designer",
            "signup", "signal",
            "assign", "assignee", "assignment",
            "keyboard", "keyword",
            "insight",
        )

    // The regular English inflections, each with what replaces it. PUBLIC_NAMES
    // and NON_SENSITIVE_WORDS list a word once, and a lookup accepts its plural,
    // past tense, and -ing form as well, so `assignment` covers `assignments`,
    // `author` covers `authors` and `authored`, and `authority` covers
    // `authorities`.
    private val INFLECTIONS = listOf("s" to "", "es" to "", "ies" to "y", "ed" to "", "ing" to "")

    private val SENSITIVE_KEY_FRAGMENTS =
        listOf(
            "token",
            "key",
            "secret",
            "password",
            "passwd",
            "pwd",
            "auth",
            "session",
            "credential",
            "bearer",
            "signature",
            "sig",
        )

    /** Whether [name] looks like it holds a secret: see [SensitiveKeys]. */
    @JvmStatic
    public fun isSensitive(name: String): Boolean {
        // Runs for every key and element name in a captured body. Most names have no
        // sensitive fragment at all, so that is checked first, without allocating.
        // Only a name that has one is looked at word by word, and a part of it stays
        // sensitive unless the ordinary words in it, or a counted `token`, were what
        // held the fragment.
        if (!hasSensitiveFragment(name)) return false
        val parts = nameParts(name)
        if (PUBLIC_NAMES.containsIgnoringInflection(parts.joinToString("").lowercase())) return false
        val words = parts.map { camelCaseWords(it) }
        val counted = words.any { part -> part.any { it in COUNT_WORDS } }
        return words.any { part ->
            val rest =
                part.filterNot { NON_SENSITIVE_WORDS.containsIgnoringInflection(it) || (counted && it in TOKEN_WORDS) }
                    .joinToString("")
            SENSITIVE_KEY_FRAGMENTS.any { rest.contains(it) }
        }
    }

    /**
     * Whether [word], or the base form it is an inflection of, is in this set.
     * The base form is a guess from the word's ending alone (`authored` tries
     * `author`, `authorities` tries `authority`), and a wrong guess is safe: the
     * sets hold the words that mark a name as not secret, so a stem that is in
     * none of them changes nothing, and no word in them has an inflection that
     * names a credential. `authorization` has no ending to strip and stays.
     */
    private fun Set<String>.containsIgnoringInflection(word: String): Boolean =
        word in this ||
            INFLECTIONS.any { (suffix, base) -> word.endsWith(suffix) && word.dropLast(suffix.length) + base in this }

    // An ASCII name is matched without allocating a lowercase copy. Other names
    // still go through lowercase(), which can turn one char into several.
    private fun hasSensitiveFragment(name: String): Boolean {
        if (name.any { it >= '\u0080' }) {
            val lower = name.lowercase()
            return SENSITIVE_KEY_FRAGMENTS.any { lower.contains(it) }
        }
        return SENSITIVE_KEY_FRAGMENTS.any { name.containsIgnoringAsciiCase(it) }
    }

    /** The runs of letters and digits in [name], split at every other char. */
    private fun nameParts(name: String): List<String> {
        val parts = ArrayList<String>()
        var start = -1
        for (i in name.indices) {
            if (name[i].isLetterOrDigit()) {
                if (start < 0) start = i
            } else if (start >= 0) {
                parts += name.substring(start, i)
                start = -1
            }
        }
        if (start >= 0) parts += name.substring(start)
        return parts
    }

    /** The lowercase camelCase words of [part]: `authorName` and `APIKey` have two each. */
    private fun camelCaseWords(part: String): List<String> {
        val words = ArrayList<String>()
        var start = 0
        for (i in 1 until part.length) {
            val c = part[i]
            val prev = part[i - 1]
            val hump = c.isUpperCase() && (!prev.isUpperCase() || part.getOrNull(i + 1)?.isLowerCase() == true)
            if (hump) {
                words += part.substring(start, i).lowercase()
                start = i
            }
        }
        words += part.substring(start).lowercase()
        return words
    }

    /** Whether this ASCII text contains the lowercase [fragment], in any case. */
    private fun String.containsIgnoringAsciiCase(fragment: String): Boolean {
        for (start in 0..length - fragment.length) {
            var matched = 0
            while (matched < fragment.length && this[start + matched].lowercaseAscii() == fragment[matched]) matched++
            if (matched == fragment.length) return true
        }
        return false
    }

    private fun Char.lowercaseAscii(): Char = if (this in 'A'..'Z') this + ('a' - 'A') else this
}
