package io.github.twinsen81.lustro

/**
 * Immutable configuration for the Lustro debug runtime.
 *
 * A value object (deliberately not a `data class`, to keep the public surface
 * stable and mirrorable by `:lustro-noop`). Build instances with [builder] or
 * use [DEFAULT]. The no-op module accepts and stores every field for API parity
 * with the runtime, but enforces none of them (nothing binds, captures, or serves).
 *
 * No-op build: the values are accepted and held identically to the runtime, but
 * never used (nothing binds, captures, or serves).
 */
public class DebugConfig private constructor(
    /** The TCP port the debug server binds to. Default `8080`. */
    public val serverPort: Int,
    /** The loopback address to bind. Default `"127.0.0.1"`. */
    public val bindAddress: String,
    /** Whether to fall back to an ephemeral port when [serverPort] is taken. Default `false`. */
    public val bindFallback: Boolean,
    /** Base URL for resolving relative "Send Request" URLs, or `null`. Default `null`. */
    public val appServerBaseUrl: String?,
    /** Total in-memory capture budget in bytes (enforced by the :lustro runtime). Default 50 MB. */
    public val captureBudgetBytes: Long,
    /** Maximum number of retained transactions (ring cap). Default `1000`. */
    public val maxCaptureTransactions: Int,
    /** Maximum bytes captured per request/response body. Default 256 KB. */
    public val maxBodyCaptureBytes: Long,
    /** Maximum accepted request body size (enforced by the :lustro runtime). Default 1 MB. */
    public val maxRequestBodyBytes: Long,
    /** Maximum concurrent in-flight requests (enforced by the :lustro runtime). Default `16`. */
    public val maxConcurrentRequests: Int,
    /** Bounded request queue capacity (enforced by the :lustro runtime). Default `64`. */
    public val requestQueueCapacity: Int,
    /** Per-request timeout in milliseconds (enforced by the :lustro runtime). Default `30000`. */
    public val requestTimeoutMs: Long,
    /** Extra allowed CORS origins; loopback is auto-allowed (enforced by the :lustro runtime). Default empty. */
    public val allowedOrigins: List<String>,
) {
    /** Factories and the [DEFAULT] configuration. */
    public companion object {
        /** Returns a new [Builder] seeded with the defaults. */
        @JvmStatic
        public fun builder(): Builder = Builder()

        /** The default configuration. */
        @JvmField
        public val DEFAULT: DebugConfig = Builder().build()
    }

    /** Mutable builder for [DebugConfig]. Each setter returns `this` for chaining. */
    public class Builder {
        private var serverPort: Int = 8080
        private var bindAddress: String = "127.0.0.1"
        private var bindFallback: Boolean = false
        private var appServerBaseUrl: String? = null
        private var captureBudgetBytes: Long = 50L * 1024 * 1024
        private var maxCaptureTransactions: Int = 1000
        private var maxBodyCaptureBytes: Long = 256L * 1024
        private var maxRequestBodyBytes: Long = 1L * 1024 * 1024
        private var maxConcurrentRequests: Int = 16
        private var requestQueueCapacity: Int = 64
        private var requestTimeoutMs: Long = 30_000
        private var allowedOrigins: List<String> = emptyList()

        /** Sets [DebugConfig.serverPort]. */
        public fun serverPort(value: Int): Builder = apply { serverPort = value }

        /** Sets [DebugConfig.bindAddress]. */
        public fun bindAddress(value: String): Builder = apply { bindAddress = value }

        /** Sets [DebugConfig.bindFallback]. */
        public fun bindFallback(value: Boolean): Builder = apply { bindFallback = value }

        /** Sets [DebugConfig.appServerBaseUrl]. */
        public fun appServerBaseUrl(value: String?): Builder = apply { appServerBaseUrl = value }

        /** Sets [DebugConfig.captureBudgetBytes]. */
        public fun captureBudgetBytes(value: Long): Builder = apply { captureBudgetBytes = value }

        /** Sets [DebugConfig.maxCaptureTransactions]. */
        public fun maxCaptureTransactions(value: Int): Builder = apply { maxCaptureTransactions = value }

        /** Sets [DebugConfig.maxBodyCaptureBytes]. */
        public fun maxBodyCaptureBytes(value: Long): Builder = apply { maxBodyCaptureBytes = value }

        /** Sets [DebugConfig.maxRequestBodyBytes]. */
        public fun maxRequestBodyBytes(value: Long): Builder = apply { maxRequestBodyBytes = value }

        /** Sets [DebugConfig.maxConcurrentRequests]. */
        public fun maxConcurrentRequests(value: Int): Builder = apply { maxConcurrentRequests = value }

        /** Sets [DebugConfig.requestQueueCapacity]. */
        public fun requestQueueCapacity(value: Int): Builder = apply { requestQueueCapacity = value }

        /** Sets [DebugConfig.requestTimeoutMs]. */
        public fun requestTimeoutMs(value: Long): Builder = apply { requestTimeoutMs = value }

        /** Sets [DebugConfig.allowedOrigins]. */
        public fun allowedOrigins(value: List<String>): Builder = apply { allowedOrigins = value.toList() }

        /** Builds the immutable [DebugConfig]. */
        public fun build(): DebugConfig =
            DebugConfig(
                serverPort = serverPort,
                bindAddress = bindAddress,
                bindFallback = bindFallback,
                appServerBaseUrl = appServerBaseUrl,
                captureBudgetBytes = captureBudgetBytes,
                maxCaptureTransactions = maxCaptureTransactions,
                maxBodyCaptureBytes = maxBodyCaptureBytes,
                maxRequestBodyBytes = maxRequestBodyBytes,
                maxConcurrentRequests = maxConcurrentRequests,
                requestQueueCapacity = requestQueueCapacity,
                requestTimeoutMs = requestTimeoutMs,
                allowedOrigins = allowedOrigins,
            )
    }
}
