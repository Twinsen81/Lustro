package io.github.twinsen81.lustro

import android.app.Application
import okhttp3.Interceptor

/**
 * The Lustro debug runtime facade (no-op build).
 *
 * This is the release-safe `:lustro-noop` mirror of the `:lustro` runtime
 * facade. It exposes byte-identical public signatures so consumer code compiles
 * unchanged, but every runtime body is a no-op: no socket is bound, no traffic
 * is captured, and [networkInterceptor] returns a pass-through interceptor.
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
