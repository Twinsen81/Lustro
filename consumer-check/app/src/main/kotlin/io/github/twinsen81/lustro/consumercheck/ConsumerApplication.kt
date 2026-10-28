package io.github.twinsen81.lustro.consumercheck

import android.app.Application
import androidx.work.Configuration
import okhttp3.OkHttpClient
import okhttp3.WebSocket

/**
 * The app under check. [LustroSetup], which each flavor defines, prepares its HTTP
 * client and its WorkManager configuration: the app initializes WorkManager on demand.
 */
class ConsumerApplication : Application(), Configuration.Provider {
    lateinit var httpClient: OkHttpClient
        private set

    lateinit var webSockets: WebSocket.Factory
        private set

    override val workManagerConfiguration: Configuration
        get() = LustroSetup.workManagerConfiguration(this, Configuration.Builder()).build()

    override fun onCreate() {
        super.onCreate()
        val clients = LustroSetup.start(this, OkHttpClient())
        httpClient = clients.http
        webSockets = clients.webSockets
    }
}
