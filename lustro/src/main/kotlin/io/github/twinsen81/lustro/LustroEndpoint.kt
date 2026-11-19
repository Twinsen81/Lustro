package io.github.twinsen81.lustro

import io.github.twinsen81.lustro.internal.LanAddresses

/**
 * Where the debug server listens, and the token that a client sends to it, as
 * [Lustro.endpoint] returns them.
 *
 * A client sends the [token] as `Authorization: Bearer <token>`. The token
 * gives full access to the app's data through the server: the captured
 * traffic, and whatever the registered tabs show and change. Show it only in a
 * debug build, and do not send it off the device.
 */
public class LustroEndpoint internal constructor(
    /**
     * The host that a client on the device, or a computer through
     * `adb forward`, connects to: `127.0.0.1` when the server listens on all
     * interfaces, and otherwise the address that `DebugConfig.bindAddress`
     * gives.
     */
    public val host: String,
    /** The port the server listens on. It differs from `DebugConfig.serverPort` after a fallback bind. */
    public val port: Int,
    /** The token that a client sends as `Authorization: Bearer <token>`. */
    public val token: String,
    /**
     * The device's network addresses that a client on the same network, such
     * as a computer on the same Wi-Fi, connects to: the IPv4 addresses, Wi-Fi
     * first, or the IPv6 ones when the device has no IPv4 address.
     * Empty when the server listens on loopback only, the default. With
     * `DebugConfig.bindAddress("0.0.0.0")`, the addresses of the device's
     * interfaces that are up, read on each [Lustro.endpoint] call; with
     * another address, that address.
     */
    public val lanHosts: List<String>,
) {
    /** `http://<host>:<port>`, the base URL for a client on the device or through `adb forward`. */
    public val url: String
        get() = LanAddresses.url(host, port)

    /** `http://<address>:<port>` for each of [lanHosts]. */
    public val lanUrls: List<String>
        get() = lanHosts.map { LanAddresses.url(it, port) }

    /** Leaves the [token] out, so that a log of the endpoint does not leak it. */
    override fun toString(): String = "LustroEndpoint(url=$url, lanUrls=$lanUrls)"
}
