package io.github.twinsen81.lustro.consumercheck

import android.app.Application
import android.util.Log
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.LustroStatus
import io.github.twinsen81.lustro.network.NetworkDebugTab
import okhttp3.OkHttpClient

/**
 * The app with Lustro, wired as the quick start in README.md does it. It compiles
 * against `lustro` in debug and against `lustro-noop` in release, so both facades
 * are checked from the floor's Kotlin.
 */
object LustroSetup {
    fun start(app: Application, client: OkHttpClient): Clients {
        val lustro =
            Lustro.builder(app)
                .addTab(NetworkDebugTab.create(senderClient = client))
                .build()
        val httpClient = client.newBuilder().addInterceptor(lustro.networkInterceptor()).build()
        val status: LustroStatus = lustro.start()
        Log.i("ConsumerCheck", "Lustro start() returned $status")
        lustro.networkCaptureSink()
        return Clients(httpClient, lustro.webSocketFactory(httpClient))
    }
}
