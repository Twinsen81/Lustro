package io.github.twinsen81.lustro.sample

import android.app.Application
import okhttp3.OkHttpClient

/**
 * Sample app entry point.
 *
 * This class lives in `src/main` and is compiled by BOTH variants. It owns the
 * app's demo [OkHttpClient] and delegates ALL Lustro setup to [LustroBootstrap],
 * which is variant-split:
 *
 * - `src/debug/.../LustroBootstrap.kt` builds the real `:lustro` runtime,
 *   registers the Network tab + the custom `SampleFlagsTab`, starts the server,
 *   and returns the capturing interceptor.
 * - `src/release/.../LustroBootstrap.kt` has the SAME signatures but builds the
 *   `:lustro-noop` facade and returns a pass-through interceptor.
 *
 * Crucially, this file mentions NO Lustro type — every Lustro/`DebugTab`
 * reference is confined to the variant-split `src/debug` bootstrap. That is why
 * the published `LustroDebugUsageInRelease` lint check is clean for the sample
 * WITHOUT being disabled: there is nothing to flag in `src/main`.
 */
public class SampleApplication : Application() {
    /**
     * The app's OkHttp client, with the Lustro interceptor installed. In the
     * debug variant its traffic is captured and shown in the Network tab; in the
     * release variant the interceptor is a pass-through and the client behaves
     * like any other OkHttpClient. [MainActivity] uses this client for the demo
     * requests.
     */
    public lateinit var httpClient: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()

        // Build the client first so it can be handed to the bootstrap as the
        // "Send Request" sender, then add the bootstrap's interceptor. We use a
        // builder so the bootstrap-provided interceptor is added LAST (after any
        // app interceptors), per the documented ordering: the capture sees the
        // final application request.
        val builder = OkHttpClient.Builder()
        val client = builder.build()
        val interceptor = LustroBootstrap.start(this, client)

        httpClient = builder.addInterceptor(interceptor).build()
    }
}
