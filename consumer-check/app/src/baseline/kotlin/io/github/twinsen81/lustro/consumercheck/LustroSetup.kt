package io.github.twinsen81.lustro.consumercheck

import android.app.Application
import androidx.work.Configuration
import okhttp3.OkHttpClient

/** The app without Lustro: the control that checkLustroKeepsAppVersions compares against. */
object LustroSetup {
    fun start(app: Application, client: OkHttpClient): Clients = Clients(client, client)

    fun workManagerConfiguration(app: Application, builder: Configuration.Builder): Configuration.Builder = builder
}
