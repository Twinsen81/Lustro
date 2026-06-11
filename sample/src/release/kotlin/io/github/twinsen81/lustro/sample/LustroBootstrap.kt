package io.github.twinsen81.lustro.sample

import android.app.Application
import io.github.twinsen81.lustro.DebugConfig
import io.github.twinsen81.lustro.ExperimentalPlatformCapture
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.LustroStatus
import io.github.twinsen81.lustro.network.NetworkDebugTab
import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * Release-variant Lustro bootstrap.
 *
 * This file lives in `src/release` and compiles against `:lustro-noop`, the
 * release-safe no-op facade. It exposes the SAME object/function signatures as
 * the `src/debug` [LustroBootstrap], so [SampleApplication] (in `src/main`)
 * compiles unchanged under both variants.
 *
 * Building against the no-op `Lustro.builder` / `NetworkDebugTab.create` here is
 * what preserves the cross-variant parity gate: if the no-op facade signatures
 * ever drift from the real `:lustro` runtime, ONE of the two variants fails to
 * compile. The release variant registers NO custom tab — [SampleFlagsTab] is a
 * debug-only `DebugTab` and does not exist in this source set. Everything here
 * is a no-op at runtime: nothing binds, nothing is captured, and
 * [Lustro.networkInterceptor] returns a pass-through interceptor.
 */
// This src/release stub deliberately calls the Lustro API against :lustro-noop to
// drive the cross-variant facade-parity gate; the no-op makes it inert in release,
// so the debug-only lint check (LustroDebugUsageInRelease) does not apply here.
@Suppress("LustroDebugUsageInRelease")
public object LustroBootstrap {
    /**
     * No-op equivalent of the debug bootstrap. Builds Lustro against the no-op
     * facade and returns its pass-through interceptor. Releasing apps add this
     * interceptor unconditionally; in the no-op build it forwards every request
     * unchanged, so there is no release overhead and no debug server.
     */
    public fun start(app: Application, appOkHttpClient: OkHttpClient): Interceptor {
        val instance =
            Lustro.builder(app)
                .config(
                    DebugConfig.builder()
                        .serverPort(8080)
                        .appServerBaseUrl("https://httpbingo.org")
                        .build(),
                )
                .addTab(NetworkDebugTab.create(senderClient = appOkHttpClient))
                .build()

        val interceptor = instance.networkInterceptor()
        val status: LustroStatus = instance.start()
        android.util.Log.i("LustroSample", "Lustro start() returned $status")
        return interceptor
    }

    /** No-op stop; the no-op build never binds a socket. Idempotent. */
    public fun stop() {
        // No-op: the release facade never started a server.
    }

    /**
     * Mirrors the debug bootstrap's platform-capture demo so the opt-in call
     * site also compiles against the no-op facade. Never invoked at runtime.
     */
    @OptIn(ExperimentalPlatformCapture::class)
    public fun platformCaptureTab(appOkHttpClient: OkHttpClient): NetworkDebugTab =
        NetworkDebugTab.create(senderClient = appOkHttpClient, capturePlatformHttp = true)
}
