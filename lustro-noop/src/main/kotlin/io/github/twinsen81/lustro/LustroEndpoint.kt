package io.github.twinsen81.lustro

/**
 * Where the debug server listens, and the token that a client sends to it
 * (no-op build).
 *
 * Mirrors the `:lustro` type so consumer code compiles unchanged. The no-op
 * build has no server, so [Lustro.endpoint] always returns `null` and no
 * instance exists.
 */
public class LustroEndpoint internal constructor(
    /** The host that a client connects to. */
    public val host: String,
    /** The port the server listens on. */
    public val port: Int,
    /** The token that a client sends as `Authorization: Bearer <token>`. */
    public val token: String,
    /** The device's network addresses that a client on the same network connects to. */
    public val lanHosts: List<String>,
) {
    /** `http://<host>:<port>`. */
    public val url: String
        get() = urlOf(host)

    /** `http://<address>:<port>` for each of [lanHosts]. */
    public val lanUrls: List<String>
        get() = lanHosts.map(::urlOf)

    private fun urlOf(address: String): String = if (':' in address) "http://[$address]:$port" else "http://$address:$port"

    /** Leaves the [token] out. */
    override fun toString(): String = "LustroEndpoint(url=$url, lanUrls=$lanUrls)"
}
