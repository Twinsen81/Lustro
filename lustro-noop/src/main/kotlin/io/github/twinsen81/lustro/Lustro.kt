package io.github.twinsen81.lustro

import android.app.Application
import io.github.twinsen81.lustro.internal.NoOpCaptureSink
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import okhttp3.Interceptor
import okhttp3.WebSocket

/**
 * The Lustro debug runtime facade (no-op build).
 *
 * This is the release-safe `:lustro-noop` mirror of the `:lustro` runtime
 * facade. It exposes byte-identical public signatures so consumer code compiles
 * unchanged, but every runtime body is a no-op: no socket is bound, no traffic
 * is captured, [networkInterceptor] returns a pass-through interceptor,
 * [webSocketFactory] returns the factory it is given, and [networkCaptureSink]
 * returns a sink that records nothing.
 *
 * Build one with [builder], register [DebugTab]s, and [start] it. In this build
 * [start] returns [LustroStatus.DISABLED] and [stop] does nothing; both are
 * idempotent.
 */
public class Lustro internal constructor() {
    /**
     * Returns an OkHttp application interceptor. In the no-op build this is always
     * a pass-through interceptor that forwards the request unchanged.
     */
    public fun networkInterceptor(): Interceptor = Interceptor { it.proceed(it.request()) }

    /**
     * Returns a [WebSocket.Factory] for the app's sockets. In the no-op build
     * this is [delegate] itself: nothing is wrapped and nothing is recorded.
     */
    public fun webSocketFactory(delegate: WebSocket.Factory): WebSocket.Factory = delegate

    /**
     * Returns a [NetworkCaptureSink] for the requests of another HTTP client. In
     * the no-op build it records nothing, finds no mock rule, and every
     * [NetworkCaptureSink.beginRequest] returns the same id.
     */
    public fun networkCaptureSink(): NetworkCaptureSink = NoOpCaptureSink

    /**
     * Starts the debug server. In the no-op build nothing binds and this always
     * returns [LustroStatus.DISABLED]. Idempotent.
     */
    public fun start(): LustroStatus = LustroStatus.DISABLED

    /** Stops the debug server. No-op in this build. Idempotent. */
    public fun stop() {
        // No-op: the no-op build never binds a socket.
    }

    /** Factory for [Lustro]. */
    public companion object {
        /** Returns a new [Builder] bound to [application]. */
        @JvmStatic
        public fun builder(application: Application): Builder = Builder(application)
    }

    /** Builder for [Lustro]; register tabs and apply a [DebugConfig], then [build]. */
    public class Builder internal constructor(
        @Suppress("UNUSED_PARAMETER") application: Application,
    ) {
        /** Applies the given [config]. No-op: the config is never used. */
        public fun config(config: DebugConfig): Builder = apply {
            // No-op: configuration is accepted for API parity but never used.
        }

        /**
         * Registers a [tab]. In the no-op build the tab is accepted for API parity
         * but never invoked.
         */
        public fun addTab(tab: DebugTab): Builder = apply {
            // No-op: tabs are accepted for API parity but never served.
        }

        /** Builds the [Lustro] runtime. */
        public fun build(): Lustro = Lustro()
    }
}
