package io.github.twinsen81.lustro.work.internal

import android.annotation.TargetApi
import android.net.NetworkRequest
import android.os.Build

/**
 * The transports and capabilities of the [NetworkRequest] that work needs, by the
 * names of their `NetworkCapabilities` constants. WorkManager keeps such a request
 * only from API 28, where `Constraints.Builder.setRequiredNetworkRequest` uses it in
 * place of a `NetworkType`, and `requiredNetworkType` is then `NOT_REQUIRED`.
 */
internal object WorkNetworkRequest {
    // The TRANSPORT_* constants of NetworkCapabilities, without the prefix.
    private val TRANSPORTS =
        listOf(
            "CELLULAR", "WIFI", "BLUETOOTH", "ETHERNET", "VPN", "WIFI_AWARE", "LOWPAN", "TEST", "USB", "THREAD",
            "SATELLITE",
        )

    // The NET_CAPABILITY_* constants of NetworkCapabilities, without the prefix.
    private val CAPABILITIES =
        listOf(
            "MMS", "SUPL", "DUN", "FOTA", "IMS", "CBS", "WIFI_P2P", "IA", "RCS", "XCAP", "EIMS", "NOT_METERED",
            "INTERNET", "NOT_RESTRICTED", "TRUSTED", "NOT_VPN", "VALIDATED", "CAPTIVE_PORTAL", "NOT_ROAMING",
            "FOREGROUND", "NOT_CONGESTED", "NOT_SUSPENDED", "OEM_PAID", "MCX", "PARTIAL_CONNECTIVITY",
            "TEMPORARILY_NOT_METERED", "OEM_PRIVATE", "VEHICLE_INTERNAL", "NOT_VCN_MANAGED", "ENTERPRISE", "VSIM",
            "BIP", "HEAD_UNIT", "MMTEL", "PRIORITIZE_LATENCY", "PRIORITIZE_BANDWIDTH", "LOCAL_NETWORK",
            "NOT_BANDWIDTH_CONSTRAINED",
        )

    // Before API 31, a request lists neither, so the tab asks for each value. The
    // constants of both are below 64.
    private const val MAX_CONSTANT = 63

    /** The names of the request's transports, in the order of their constants. */
    @TargetApi(Build.VERSION_CODES.P)
    fun transports(request: NetworkRequest): List<String> {
        val values =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) request.transportTypes.sorted() else (0..MAX_CONSTANT).filter { request.hasTransport(it) }
        return values.map { TRANSPORTS.getOrNull(it) ?: it.toString() }
    }

    /** The names of the request's capabilities, in the order of their constants. */
    @TargetApi(Build.VERSION_CODES.P)
    fun capabilities(request: NetworkRequest): List<String> {
        val values =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) request.capabilities.sorted() else (0..MAX_CONSTANT).filter { request.hasCapability(it) }
        return values.map { CAPABILITIES.getOrNull(it) ?: it.toString() }
    }
}
