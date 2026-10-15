package io.github.twinsen81.lustro.consumercheck

import android.app.Application
import okhttp3.OkHttpClient
import okhttp3.WebSocket

/** The app under check. [LustroSetup], which each flavor defines, prepares its HTTP client. */
class ConsumerApplication : Application() {
    lateinit var httpClient: OkHttpClient
        private set

    lateinit var webSockets: WebSocket.Factory
        private set

    override fun onCreate() {
        super.onCreate()
        val clients = LustroSetup.start(this, OkHttpClient())
        httpClient = clients.http
        webSockets = clients.webSockets
    }
}
