package io.github.twinsen81.lustro.consumercheck

import android.app.Application
import okhttp3.OkHttpClient

/** The app without Lustro: the control that checkLustroKeepsAppVersions compares against. */
object LustroSetup {
    fun start(app: Application, client: OkHttpClient): Clients = Clients(client, client)
}
