package io.github.twinsen81.lustro.network

import io.github.twinsen81.lustro.DebugRequest
import io.github.twinsen81.lustro.DebugResponse
import io.github.twinsen81.lustro.DebugTab
import io.github.twinsen81.lustro.ExperimentalPlatformCapture
import okhttp3.OkHttpClient

/**
 * Debug console tab for inspecting captured HTTP traffic and managing mock rules
 * (no-op build).
 *
 * Mirrors the `:lustro` [NetworkDebugTab] public surface so consumer code
 * compiles unchanged. The tab's identity ([id], [title], [icon], [order]) is
 * identical to the runtime so registration behaves the same, but every routing
 * and rendering body is a no-op: [handle] returns `null`, [renderContent] is
 * empty, and [schema] is `null`. No traffic is ever captured.
 */
public class NetworkDebugTab private constructor() : DebugTab() {
    override val id: String = "network"
    override val title: String = "Network"
    override val icon: String = "🌐"
    override val order: Int = 10

    /** Returns an empty body; the no-op build renders no UI. */
    override fun renderContent(): String = ""

    /** Returns `null`; the no-op build exposes no schema. */
    override fun schema(): String? = null

    /** Returns `null`; the no-op build routes nothing. */
    override fun handle(request: DebugRequest): DebugResponse? = null

    /** Factory for [NetworkDebugTab]. */
    public companion object {
        /**
         * Creates a [NetworkDebugTab] that captures only OkHttp traffic (via the
         * interceptor from [Lustro.networkInterceptor]).
         *
         * This is the safe, default factory: it does not touch the platform
         * `HttpURLConnection` machinery, so it needs no opt-in. To additionally
         * capture `HttpURLConnection` traffic, use the
         * [overload][create] that takes `capturePlatformHttp`, which is gated by
         * [ExperimentalPlatformCapture].
         *
         * @param senderClient when non-null, wrapped in an [OkHttpSender] to power
         *   the "Send Request" panel; when `null`, the Send route is hidden.
         * @param classifier tags captured URLs with category labels (default: none).
         * @param redactor removes sensitive data at capture time (default:
         *   [DefaultRedactor]).
         * @param mockRuleStorage persists mock rules; `null` keeps them in memory.
         *
         * No-op build: all parameters are accepted for API parity but never used.
         */
        @JvmStatic
        @JvmOverloads
        public fun create(
            senderClient: OkHttpClient? = null,
            classifier: NetworkClassifier = NoOpNetworkClassifier,
            redactor: Redactor = DefaultRedactor,
            mockRuleStorage: MockRuleStorage? = null,
        ): NetworkDebugTab = NetworkDebugTab()

        /**
         * Creates a [NetworkDebugTab], optionally installing the process-global
         * `HttpURLConnection` capture.
         *
         * [capturePlatformHttp] has no default so this overload never collides
         * with the safe [create] above. When `true`, the runtime would install
         * the platform `HttpURLConnection` capture (best-effort, fail-open). This
         * rests on a non-public platform detail, hence the
         * [ExperimentalPlatformCapture] opt-in.
         *
         * @param senderClient when non-null, wrapped in an [OkHttpSender] to power
         *   the "Send Request" panel; when `null`, the Send route is hidden.
         * @param capturePlatformHttp when `true`, installs the process-global
         *   `HttpURLConnection` capture (best-effort, fail-open).
         * @param classifier tags captured URLs with category labels (default: none).
         * @param redactor removes sensitive data at capture time (default:
         *   [DefaultRedactor]).
         * @param mockRuleStorage persists mock rules; `null` keeps them in memory.
         *
         * No-op build: all parameters are accepted for API parity but never used.
         */
        @ExperimentalPlatformCapture
        @JvmStatic
        @JvmOverloads
        public fun create(
            senderClient: OkHttpClient?,
            capturePlatformHttp: Boolean,
            classifier: NetworkClassifier = NoOpNetworkClassifier,
            redactor: Redactor = DefaultRedactor,
            mockRuleStorage: MockRuleStorage? = null,
        ): NetworkDebugTab = NetworkDebugTab()
    }
}
