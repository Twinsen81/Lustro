package io.github.twinsen81.lustro.sample

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import okhttp3.OkHttpClient
import okhttp3.WebSocket

/** The port the debug server binds; both [LustroBootstrap] variants configure it. */
internal const val CONSOLE_PORT: Int = 8080

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
 * The app initializes WorkManager on demand, as a `Configuration.Provider`, and
 * its manifest removes WorkManager's default initializer, so that its
 * configuration goes through the bootstrap: the debug one adds the WorkManager
 * tab's listeners, and the release one returns it as it is.
 *
 * Crucially, this file mentions NO Lustro type — every Lustro/`DebugTab`
 * reference is confined to the variant-split `src/debug` bootstrap. That is why
 * the published `LustroDebugUsageInRelease` lint check is clean for the sample
 * WITHOUT being disabled: there is nothing to flag in `src/main`.
 */
public class SampleApplication : Application(), Configuration.Provider {
    override val workManagerConfiguration: Configuration
        get() = LustroBootstrap.workManagerConfiguration(this, DemoWork.configuration(this)).build()

    /**
     * The app's OkHttp client, with the Lustro interceptor installed. In the
     * debug variant its traffic is captured and shown in the Network tab; in the
     * release variant the interceptor is a pass-through and the client behaves
     * like any other OkHttpClient. [MainActivity] uses this client for the demo
     * requests.
     */
    public lateinit var httpClient: OkHttpClient
        private set

    /**
     * Where the app's WebSockets come from. In the debug variant it wraps
     * [httpClient] so the Network tab shows each socket's messages; in the
     * release variant it is [httpClient] itself.
     */
    public lateinit var webSockets: WebSocket.Factory
        private set

    override fun onCreate() {
        super.onCreate()

        // The client carries the bootstrap's interceptor, so it is built after
        // the bootstrap; the console's Send form gets it through the lambda,
        // which runs only when a request is sent. The interceptor is added LAST
        // (after any app interceptors), per the documented ordering: the capture
        // sees the final application request.
        // Written before Lustro starts: the Logs tab still shows it, because it
        // reads logcat from the start of the process.
        Log.i("LustroSample", "Application.onCreate, before Lustro starts")
        // The demo files of the Preferences tab, on disk before the console lists them.
        DemoPrefs.seed(this)
        DemoDataStore.seed(this)
        // The two database files of the SQLite tab; the notes database writes its demo
        // rows when it first opens.
        DemoSqlite.seedFiles(this)
        val interceptor = LustroBootstrap.start(this) { httpClient }

        httpClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        webSockets = LustroBootstrap.webSocketFactory(httpClient)
    }
}
