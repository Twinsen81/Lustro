package io.github.twinsen81.lustro.network

import okhttp3.OkHttpClient

/**
 * The default [NetworkSender], backed by an OkHttp [OkHttpClient] (no-op build).
 *
 * Mirrors the `:lustro` `OkHttpSender` signature so consumer code compiles
 * unchanged, but [send] never issues a network call: it always returns a
 * [failure][NetworkSendResult.failure] result. The client is accepted for API
 * parity but never used, and a client provider is never called.
 */
public class OkHttpSender private constructor(
    /** The name that the Send form lists this sender under. Unique within a tab. */
    public val name: String,
    private val baseUrlProvider: () -> String?,
) : NetworkSender {
    /** Creates a sender named "Default"; the no-op build never uses [client]. */
    @Suppress("UNUSED_PARAMETER")
    public constructor(client: OkHttpClient) : this(DEFAULT_NAME, { null })

    /**
     * The URL that a relative URL in the Send form resolves against, or `null`
     * when this sender takes absolute URLs only. For a sender created with a
     * `baseUrlProvider`, each read calls the provider.
     */
    public val baseUrl: String?
        get() = baseUrlProvider()

    /** Returns a failure result; the no-op build never sends anything. */
    override fun send(request: NetworkSendRequest): NetworkSendResult =
        NetworkSendResult.failure("Lustro no-op build")

    /** Factories for [OkHttpSender]. */
    public companion object {
        private const val DEFAULT_NAME = "Default"

        /**
         * Creates a sender that the Send form lists as [name].
         *
         * No-op build: the arguments are kept for API parity, they are not
         * checked, and [client] is never called.
         *
         * @param name the name the Send form lists the sender under.
         * @param baseUrl the URL that a relative URL resolves against, or `null`
         *   for absolute URLs only.
         * @param client returns the client to send through; never called here.
         */
        @JvmStatic
        @JvmOverloads
        @Suppress("UNUSED_PARAMETER")
        public fun create(
            name: String,
            baseUrl: String? = null,
            client: () -> OkHttpClient,
        ): OkHttpSender = OkHttpSender(name) { baseUrl }

        /**
         * Creates a sender that the Send form lists as [name], with a base URL
         * that can change while the app runs.
         *
         * No-op build: the arguments are kept for API parity, and [client] is
         * never called. Java callers find this factory as
         * `createWithBaseUrlProvider`.
         *
         * @param name the name the Send form lists the sender under.
         * @param baseUrlProvider returns the URL that a relative URL resolves
         *   against now, or `null`.
         * @param client returns the client to send through; never called here.
         */
        @JvmStatic
        @JvmName("createWithBaseUrlProvider")
        @Suppress("UNUSED_PARAMETER")
        public fun create(
            name: String,
            baseUrlProvider: () -> String?,
            client: () -> OkHttpClient,
        ): OkHttpSender = OkHttpSender(name, baseUrlProvider)
    }
}
