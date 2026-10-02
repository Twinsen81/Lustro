package io.github.twinsen81.lustro.network

/**
 * What a [Redactor] knows about a WebSocket message besides its payload.
 *
 * Library-constructed: the concrete implementation is `internal` in `:lustro`,
 * and a later release can add properties.
 */
public interface WebSocketMessageInfo {
    /**
     * The URL of the connection's request as OkHttp holds it, so `https://` for
     * a `wss://` connection. Not redacted.
     */
    public val url: String

    /** `true` for a message the app sent, `false` for one it received. */
    public val isOutgoing: Boolean
}
