package io.github.twinsen81.lustro

/**
 * Library-owned, immutable collection of HTTP header name/value pairs.
 *
 * Unlike a plain [Map], [Headers] preserves both duplicate names and the
 * original insertion order, while still supporting case-insensitive lookup
 * (HTTP header names are case-insensitive). Build instances via the [companion
 * object][Companion] factories or the nested [Builder].
 */
public class Headers private constructor(
    private val entries: List<Pair<String, String>>,
) {
    /** The total number of name/value pairs, counting duplicate names. */
    public val size: Int
        get() = entries.size

    /**
     * Returns the value of the first header whose name matches [name]
     * case-insensitively, or `null` if there is no such header.
     */
    public fun get(name: String): String? {
        for ((n, v) in entries) {
            if (n.equals(name, ignoreCase = true)) {
                return v
            }
        }
        return null
    }

    /**
     * Returns every value whose header name matches [name] case-insensitively,
     * in insertion order. Returns an empty list when there is no match.
     */
    public fun getAll(name: String): List<String> {
        val result = ArrayList<String>()
        for ((n, v) in entries) {
            if (n.equals(name, ignoreCase = true)) {
                result.add(v)
            }
        }
        return result
    }

    /** Returns all header names in insertion order; names may repeat. */
    public fun names(): List<String> = entries.map { it.first }

    /** Returns `true` when there are no headers. */
    public fun isEmpty(): Boolean = entries.isEmpty()

    /** Invokes [action] for each header name/value pair, in insertion order. */
    public fun forEach(action: (name: String, value: String) -> Unit) {
        for ((n, v) in entries) {
            action(n, v)
        }
    }

    /** Returns an ordered copy of the header pairs. */
    public fun toList(): List<Pair<String, String>> = ArrayList(entries)

    /** Value equality over the ordered list of name/value pairs. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Headers) return false
        return entries == other.entries
    }

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int = entries.hashCode()

    /** Human-readable rendering of the header pairs. */
    override fun toString(): String = "Headers$entries"

    /** Factories for creating [Headers]. */
    public companion object {
        /** A shared, empty [Headers] instance. */
        @JvmField
        public val EMPTY: Headers = Headers(emptyList())

        /** Creates [Headers] from the given name/value [pairs], preserving order. */
        @JvmStatic
        public fun of(vararg pairs: Pair<String, String>): Headers =
            if (pairs.isEmpty()) EMPTY else Headers(pairs.toList())

        /**
         * Creates [Headers] from a [map]; entry order follows the map's
         * iteration order. Because a map cannot hold duplicate keys, the result
         * has at most one value per name.
         */
        @JvmStatic
        public fun from(map: Map<String, String>): Headers =
            if (map.isEmpty()) EMPTY else Headers(map.entries.map { it.key to it.value })
    }

    /** Mutable builder that accumulates header pairs and produces a [Headers]. */
    public class Builder {
        private val entries = ArrayList<Pair<String, String>>()

        /** Appends a header [name]/[value] pair, keeping any existing entries. */
        public fun add(name: String, value: String): Builder {
            entries.add(name to value)
            return this
        }

        /**
         * Removes every existing header whose name matches [name]
         * case-insensitively, then appends the [name]/[value] pair.
         */
        public fun set(name: String, value: String): Builder {
            entries.removeAll { it.first.equals(name, ignoreCase = true) }
            entries.add(name to value)
            return this
        }

        /** Builds an immutable [Headers] from the accumulated pairs. */
        public fun build(): Headers =
            if (entries.isEmpty()) EMPTY else Headers(ArrayList(entries))
    }
}
