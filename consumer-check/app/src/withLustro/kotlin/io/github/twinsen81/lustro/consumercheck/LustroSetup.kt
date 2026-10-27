package io.github.twinsen81.lustro.consumercheck

import android.app.Application
import android.util.Log
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.LustroStatus
import io.github.twinsen81.lustro.logs.LogsDebugTab
import io.github.twinsen81.lustro.network.NetworkDebugTab
import io.github.twinsen81.lustro.prefs.PrefsDebugTab
import io.github.twinsen81.lustro.sqlite.SqliteDb
import io.github.twinsen81.lustro.sqlite.SqliteDebugTab
import okhttp3.OkHttpClient

/**
 * The app with Lustro, wired as the quick start in README.md does it. It compiles
 * against `lustro`, `lustro-logs`, `lustro-prefs`, and `lustro-sqlite` in debug
 * and against their no-op halves in release, so every facade is checked from the
 * floor's Kotlin. The app has no androidx.sqlite: it registers a database file.
 */
object LustroSetup {
    fun start(app: Application, client: OkHttpClient): Clients {
        val lustro =
            Lustro.builder(app)
                .addTab(NetworkDebugTab.create(senderClient = client))
                .addTab(LogsDebugTab.create())
                .addTab(PrefsDebugTab.create(app))
                .addTab(SqliteDebugTab.create(listOf(SqliteDb.file("app", app.getDatabasePath("app.db"))), discoverFrom = app))
                .build()
        val httpClient = client.newBuilder().addInterceptor(lustro.networkInterceptor()).build()
        val status: LustroStatus = lustro.start()
        Log.i("ConsumerCheck", "Lustro start() returned $status")
        lustro.networkCaptureSink()
        return Clients(httpClient, lustro.webSocketFactory(httpClient))
    }
}
