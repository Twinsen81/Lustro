package io.github.twinsen81.lustro.sample

import android.app.Application
import android.content.Context
import io.github.twinsen81.lustro.DebugConfig
import io.github.twinsen81.lustro.ExperimentalPlatformCapture
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.LustroStatus
import io.github.twinsen81.lustro.network.NetworkDebugTab
import io.github.twinsen81.lustro.network.SharedPreferencesMockRuleStorage
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Debug-variant Lustro bootstrap.
 *
 * This file lives in `src/debug` and compiles against the REAL `:lustro`
 * runtime. It owns ALL Lustro registration for the sample: building [Lustro],
 * registering the built-in [NetworkDebugTab] plus the app's custom
 * [SampleFlagsTab], starting the server, and exposing the
 * [networkInterceptor] for the app's OkHttp client.
 *
 * Because every Lustro/[io.github.twinsen81.lustro.DebugTab] reference is
 * confined to `src/debug`, the published `LustroDebugUsageInRelease` lint check
 * finds nothing to flag — this is the idiomatic pattern that check enforces.
 * `src/main` ([SampleApplication]) only calls [start] and reads
 * [networkInterceptor], neither of which mentions a Lustro type.
 *
 * A matching `src/release` [LustroBootstrap] with the SAME signatures compiles
 * against `:lustro-noop`, so the no-op facade is still exercised at compile time
 * (the cross-variant parity gate) even though release ships no custom tab.
 */
public object LustroBootstrap {
    @Volatile
    private var lustro: Lustro? = null

    @Volatile
    private var interceptor: Interceptor? = null

    /**
     * Builds and starts Lustro for the debug variant, then returns the OkHttp
     * application interceptor the app must install on the client whose traffic
     * should appear in the Network tab.
     *
     * @param app the application, used for [Lustro.builder] and for the
     *   [SharedPreferencesMockRuleStorage] that persists mock rules.
     * @param appOkHttpClient the app's OkHttp client, wired into the Network tab
     *   as the "Send Request" sender. The capturing interceptor is installed on a
     *   separate client built afterwards (see [SampleApplication]), so Send-Request
     *   replays report their inline outcome but do not appear as new rows in the
     *   traffic list.
     */
    @OptIn(ExperimentalPlatformCapture::class)
    public fun start(app: Application, appOkHttpClient: OkHttpClient): Interceptor {
        interceptor?.let { return it }

        val prefs = app.getSharedPreferences("lustro_sample_mocks", Context.MODE_PRIVATE)

        val instance =
            Lustro.builder(app)
                .config(
                    DebugConfig.builder()
                        .serverPort(8080)
                        // Relative "Send Request" URLs resolve against this base,
                        // so the demo can replay e.g. `/get` against the fixture host.
                        .appServerBaseUrl("https://httpbingo.org")
                        .build(),
                )
                .addTab(
                    // Experimental overload: alongside OkHttp capture (via the
                    // returned interceptor), install the process-global platform
                    // capture so traffic from libraries that bypass OkHttp and use
                    // java.net.HttpURLConnection (here: Volley and a raw
                    // HttpURLConnection demo) also surfaces in the Network tab.
                    // Best-effort and fail-open — it rests on a non-public platform
                    // detail (hence the opt-in) and silently disables itself if the
                    // platform blocks the hook. The classifier tags traffic by URL
                    // substring; mock rules persist across restarts via prefs.
                    NetworkDebugTab.create(
                        senderClient = appOkHttpClient,
                        capturePlatformHttp = true,
                        classifier = SampleNetworkClassifier(),
                        mockRuleStorage = SharedPreferencesMockRuleStorage(prefs),
                    ),
                )
                // Custom, schema-backed tab. It is debug-only: it never appears in
                // src/release, so the no-op variant has no Feature Flags tab.
                .addTab(SampleFlagsTab(app))
                .build()

        val captured = instance.networkInterceptor()
        lustro = instance
        interceptor = captured

        val status: LustroStatus = instance.start()
        android.util.Log.i("LustroSample", "Lustro start() returned $status")
        return captured
    }

    /** Stops the debug runtime. Idempotent; safe to call from any variant. */
    public fun stop() {
        lustro?.stop()
    }

    internal fun extraDemoRequests(base: String): List<DemoRequestSpec> =
        listOf(
            DemoRequestSpec("GET /status/304", "GET", "$base/status/304"),
            DemoRequestSpec("GET /status/400", "GET", "$base/status/400"),
            DemoRequestSpec("GET /status/401", "GET", "$base/status/401"),
            DemoRequestSpec("GET /status/404", "GET", "$base/status/404"),
            DemoRequestSpec("GET /status/429", "GET", "$base/status/429"),
            DemoRequestSpec("GET /status/503", "GET", "$base/status/503"),
            DemoRequestSpec("GET /bearer", "GET", "$base/bearer"),
            DemoRequestSpec("GET /image/png", "GET", "$base/image/png"),
            DemoRequestSpec("GET /anything/other", "GET", "$base/anything/other"),
            DemoRequestSpec("HEAD /status/204", "HEAD", "$base/status/204"),
            DemoRequestSpec("GET https://127.0.0.1:1/lustro-error", "GET", "https://127.0.0.1:1/lustro-error"),
        )
}
