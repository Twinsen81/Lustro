package io.github.twinsen81.lustro

/**
 * Library-owned MIME media type, e.g. `application/json; charset=utf-8`.
 *
 * Standalone and free of any HTTP-client types (OkHttp adapters live in
 * `:lustro`). Parse strings with [parse]; the [type] and [subtype] and any
 * `charset` parameter are lower-cased, while [toString] preserves the original
 * full string verbatim.
 */
public class MediaType private constructor(
    /** The primary type, lower-cased, e.g. `application` in `application/json`. */
    public val type: String,
    /** The subtype, lower-cased, e.g. `json` in `application/json`. */
    public val subtype: String,
    /** The lower-cased value of the `charset` parameter, or `null` if absent. */
    public val charsetName: String?,
    private val full: String,
    private val parameters: Map<String, String>,
) {
    /**
     * Returns the value of the `; <name>=<value>` parameter (looked up
     * case-insensitively by [name]), or `null` if the parameter is absent.
     */
    public fun parameter(name: String): String? = parameters[name.lowercase()]

    /** Returns the original full media-type string this was parsed from. */
    override fun toString(): String = full

    /** Equality by the canonical form (lower-cased type/subtype with parameters). */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MediaType) return false
        return type == other.type &&
            subtype == other.subtype &&
            parameters == other.parameters
    }

    /** Hash code consistent with [equals]. */
    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + subtype.hashCode()
        result = 31 * result + parameters.hashCode()
        return result
    }

    /** Parser and well-known media-type constants. */
    public companion object {
        /**
         * Parses a media-type string of the form `type/subtype` optionally
         * followed by `; key=value` parameters. The [type], [subtype], and any
         * `charset` parameter are lower-cased; the original string is preserved
         * for [toString]. Returns `null` when [value] is not parseable.
         */
        @JvmStatic
        public fun parse(value: String): MediaType? {
            val parts = value.split(';')
            val typeSubtype = parts[0].trim()
            val slash = typeSubtype.indexOf('/')
            if (slash <= 0 || slash == typeSubtype.length - 1) {
                return null
            }
            val type = typeSubtype.substring(0, slash).trim().lowercase()
            val subtype = typeSubtype.substring(slash + 1).trim().lowercase()
            if (type.isEmpty() || subtype.isEmpty()) {
                return null
            }

            val parameters = LinkedHashMap<String, String>()
            for (i in 1 until parts.size) {
                val param = parts[i].trim()
                if (param.isEmpty()) {
                    continue
                }
                val eq = param.indexOf('=')
                if (eq <= 0) {
                    continue
                }
                val key = param.substring(0, eq).trim().lowercase()
                var paramValue = param.substring(eq + 1).trim()
                // Strip surrounding quotes from quoted parameter values.
                if (paramValue.length >= 2 && paramValue.first() == '"' && paramValue.last() == '"') {
                    paramValue = paramValue.substring(1, paramValue.length - 1)
                }
                // The charset is canonicalised to lower-case; other params are
                // preserved as written.
                parameters[key] = if (key == "charset") paramValue.lowercase() else paramValue
            }

            return MediaType(
                type = type,
                subtype = subtype,
                charsetName = parameters["charset"],
                full = value,
                parameters = parameters,
            )
        }

        /** `application/json; charset=utf-8`. */
        @JvmField
        public val JSON: MediaType = parse("application/json; charset=utf-8")!!

        /** `text/plain; charset=utf-8`. */
        @JvmField
        public val TEXT: MediaType = parse("text/plain; charset=utf-8")!!

        /** `application/octet-stream`. */
        @JvmField
        public val OCTET_STREAM: MediaType = parse("application/octet-stream")!!
    }
}
