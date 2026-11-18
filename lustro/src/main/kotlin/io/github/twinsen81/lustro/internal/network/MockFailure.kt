package io.github.twinsen81.lustro.internal.network

import io.github.twinsen81.lustro.network.MockRule
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import okhttp3.HttpUrl

/**
 * The connection errors a mock rule can fail a request with, by the names that
 * [MockRule.failure] and the wire protocol give them. Each throws the exception
 * OkHttp throws for that error on a real network, so the app takes the same
 * path as when the network fails. The message says which rule threw it, so a
 * rule left enabled is not taken for a real outage in the app's logs.
 */
internal enum class MockFailure(val wireName: String) {
    TIMEOUT("timeout"),
    UNKNOWN_HOST("unknownHost"),
    CONNECTION_REFUSED("connectionRefused"),
    CONNECTION_RESET("connectionReset"),
    TLS("tls"),
    ;

    fun exception(url: HttpUrl, rule: MockRule): IOException {
        val suffix = " (mocked by Lustro rule '${rule.id}')"
        return when (this) {
            TIMEOUT -> SocketTimeoutException("timeout$suffix")
            UNKNOWN_HOST -> UnknownHostException("Unable to resolve host \"${url.host}\": No address associated with hostname$suffix")
            CONNECTION_REFUSED -> ConnectException("Failed to connect to ${url.host}:${url.port}: Connection refused$suffix")
            CONNECTION_RESET -> SocketException("Connection reset$suffix")
            TLS -> SSLHandshakeException("SSL handshake aborted$suffix")
        }
    }

    companion object {
        /** The names, in the order the wire protocol lists them, for an error message. */
        val WIRE_NAMES: String = entries.joinToString(", ") { it.wireName }

        fun fromWireName(name: String): MockFailure? = entries.firstOrNull { it.wireName == name }
    }
}
