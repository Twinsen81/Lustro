package io.github.twinsen81.lustro.sample

import android.app.Application
import android.content.Context
import io.github.twinsen81.lustro.DebugConfig
import io.github.twinsen81.lustro.ExperimentalPlatformCapture
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.LustroStatus
import io.github.twinsen81.lustro.logs.LogsDebugTab
import io.github.twinsen81.lustro.network.NetworkDebugTab
import io.github.twinsen81.lustro.network.OkHttpSender
import io.github.twinsen81.lustro.network.SharedPreferencesMockRuleStorage
import io.github.twinsen81.lustro.prefs.PrefsDataStore
import io.github.twinsen81.lustro.prefs.PrefsDebugTab
import io.github.twinsen81.lustro.prefs.PrefsFile
import io.github.twinsen81.lustro.prefs.ValuePolicy
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import kotlinx.coroutines.flow.StateFlow
import okhttp3.WebSocket

/**
 * Debug-variant Lustro bootstrap.
 *
 * This file lives in `src/debug` and compiles against the REAL `:lustro`
 * runtime. It owns ALL Lustro registration for the sample: building [Lustro],
 * registering the built-in [NetworkDebugTab], the [LogsDebugTab] and
 * [PrefsDebugTab] plugins, and the app's custom [SampleFlagsTab], starting the
 * server, and exposing the [networkInterceptor] for the app's OkHttp client.
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

    @Volatile
    private var flagsTab: SampleFlagsTab? = null

    @Volatile
    private var running = false

    /**
     * Builds and starts Lustro for the debug variant, then returns the OkHttp
     * application interceptor the app must install on the client whose traffic
     * should appear in the Network tab.
     *
     * @param app the application, used for [Lustro.builder] and for the
     *   [SharedPreferencesMockRuleStorage] that persists mock rules.
     * @param appOkHttpClient returns the app's OkHttp client, which the Network
     *   tab's Send form sends through. The client is built after this call,
     *   because it carries the interceptor this call returns (see
     *   [SampleApplication]); Lustro asks for it only when a request is sent, so
     *   the requests sent from the console are captured like the app's own.
     */
    @OptIn(ExperimentalPlatformCapture::class)
    public fun start(app: Application, appOkHttpClient: () -> OkHttpClient): Interceptor {
        interceptor?.let { return it }

        val prefs = app.getSharedPreferences("lustro_sample_mocks", Context.MODE_PRIVATE)
        val flags = SampleFlagsTab(app)

        val instance =
            Lustro.builder(app)
                .config(
                    DebugConfig.builder()
                        .serverPort(CONSOLE_PORT)
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
                    // substring; mock rules persist across restarts via prefs. The
                    // capture filter leaves analytics calls and marked requests out
                    // of the list, on both paths.
                    // Two senders over the same client: one resolves a relative
                    // URL such as `/get` against the fixture host, the other takes
                    // absolute URLs only.
                    NetworkDebugTab.create(
                        senders =
                            listOf(
                                OkHttpSender.create("httpbingo.org", baseUrl = "https://httpbingo.org", client = appOkHttpClient),
                                OkHttpSender.create("Any host", client = appOkHttpClient),
                            ),
                        capturePlatformHttp = true,
                        classifier = SampleNetworkClassifier(),
                        mockRuleStorage = SharedPreferencesMockRuleStorage(prefs),
                        captureFilter = SampleCaptureFilter(),
                    ),
                )
                // The app's own logcat, from :lustro-logs; the release bootstrap
                // registers the no-op half from :lustro-logs-noop.
                .addTab(LogsDebugTab.create())
                // The app's SharedPreferences and DataStores, from :lustro-prefs: one file
                // with every value visible, one with keys only and read-only, one
                // DataStore with its sensitive values masked, and the other files and
                // stores on disk, which the tab finds and keeps read-only. The release
                // bootstrap registers the no-op half from :lustro-prefs-noop.
                .addTab(
                    PrefsDebugTab.create(
                        app,
                        files =
                            listOf(
                                PrefsFile(DemoPrefs.SETTINGS, app.getSharedPreferences(DemoPrefs.SETTINGS, Context.MODE_PRIVATE), ValuePolicy.VISIBLE),
                                PrefsFile(
                                    DemoPrefs.SESSION,
                                    app.getSharedPreferences(DemoPrefs.SESSION, Context.MODE_PRIVATE),
                                    ValuePolicy.KEYS_ONLY,
                                    readOnly = true,
                                ),
                            ),
                        dataStores = listOf(PrefsDataStore(DemoDataStore.APP_STATE, DemoDataStore.appState(app))),
                    ),
                )
                // Custom, schema-backed tab. It is debug-only: it never appears in
                // src/release, so the no-op variant has no Feature Flags tab.
                .addTab(flags)
                .build()

        val captured = instance.networkInterceptor()
        lustro = instance
        interceptor = captured
        flagsTab = flags

        val status: LustroStatus = instance.start()
        running = status == LustroStatus.ENABLED
        android.util.Log.i("LustroSample", "Lustro start() returned $status")
        return captured
    }

    /**
     * Returns the factory the app creates its WebSockets with, so their messages
     * appear in the Network tab. The interceptor alone shows only a socket's
     * handshake. Call it after [start], with the client that carries the
     * interceptor: the console then links each socket to its handshake request.
     */
    public fun webSocketFactory(client: OkHttpClient): WebSocket.Factory = lustro?.webSocketFactory(client) ?: client

    /** Stops the debug runtime. Idempotent; safe to call from any variant. */
    public fun stop() {
        lustro?.stop()
        running = false
    }

    /** Starts the debug runtime again after [stop]. Idempotent. */
    public fun restart() {
        running = lustro?.start() == LustroStatus.ENABLED
    }

    /** True in this variant: the app has a debug server to show and control. */
    internal const val CONSOLE_AVAILABLE: Boolean = true

    /** Whether the server is armed: the last [start] or [restart] armed it, and [stop] has not run since. */
    internal fun isRunning(): Boolean = running

    /** The flags of the Feature Flags tab, which the app reads. */
    internal fun flags(): StateFlow<List<SampleFlag>>? = flagsTab?.flags

    /** Sets a flag from the app. The console shows the change on its next poll. */
    internal fun setFlag(id: String, enabled: Boolean) {
        flagsTab?.setEnabled(id, enabled)
    }

    internal fun extraDemoRequests(base: String): List<DemoRequestSpec> =
        listOf(
            status(base, 304, "Not Modified, without a body."),
            status(base, 400, "Bad Request. The Errors category of the sample's classifier tags it."),
            status(base, 401, "Unauthorized. The Errors category tags it."),
            status(base, 404, "Not Found. The Errors category tags it."),
            status(base, 429, "Too Many Requests. The Errors category tags it."),
            status(base, 503, "Service Unavailable. The Errors category tags it."),
            DemoRequestSpec(
                "GET /bearer",
                "GET",
                "$base/bearer",
                detail = "Calls an endpoint that needs a bearer token, without one, so the server answers 401. The Auth category tags it.",
            ),
            DemoRequestSpec("GET /image/png", "GET", "$base/image/png", detail = "A PNG response. The Preview view shows the picture."),
            DemoRequestSpec("GET /anything/other", "GET", "$base/anything/other", detail = "A request that the sample's classifier tags as Other."),
            DemoRequestSpec("HEAD /status/204", "HEAD", "$base/status/204", detail = "A HEAD request. The server answers 204, with no body."),
            DemoRequestSpec(
                "GET https://127.0.0.1:1/lustro-error",
                "GET",
                "https://127.0.0.1:1/lustro-error",
                chip = "Refused connection",
                detail = "Connects to port 1 on the phone itself, where nothing listens. The row shows the connection error.",
            ),
            DemoRequestSpec(
                "GET /get with $NO_CAPTURE_HEADER (not captured)",
                "GET",
                "$base/get",
                headers = mapOf(NO_CAPTURE_HEADER to "1"),
                chip = "Not captured ($NO_CAPTURE_HEADER)",
                detail = "GET /get with the header $NO_CAPTURE_HEADER: 1. The capture filter skips it, so no row appears, and the ⚠ count goes up.",
            ),
            DemoRequestSpec(
                "GET ${SampleCaptureFilter.ANALYTICS_HOST} (not captured)",
                "GET",
                "https://${SampleCaptureFilter.ANALYTICS_HOST}/collect",
                chip = "Not captured (analytics host)",
                detail =
                    "A call to ${SampleCaptureFilter.ANALYTICS_HOST}. The capture filter skips this host. " +
                        "The host does not exist, so the call also fails.",
            ),
        )

    private fun status(base: String, code: Int, meaning: String): DemoRequestSpec =
        DemoRequestSpec("GET /status/$code", "GET", "$base/status/$code", detail = "The server answers $code $meaning")
}
