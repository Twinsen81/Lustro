package io.github.twinsen81.lustro.consumercheck

import okhttp3.OkHttpClient
import okhttp3.WebSocket

/** The app's HTTP client and the factory its WebSockets come from. */
class Clients(val http: OkHttpClient, val webSockets: WebSocket.Factory)
