package io.github.twinsen81.lustro.sample

import android.content.Context
import com.android.volley.Request
import com.android.volley.RequestQueue
import com.android.volley.toolbox.StringRequest
import com.android.volley.toolbox.Volley
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reference for generating traffic that bypasses the app's OkHttpClient and uses
 * the platform `java.net.HttpURLConnection` stack instead.
 *
 * Neither path goes through the Lustro OkHttp interceptor. They are captured only
 * because the debug build opts into platform capture ([LustroBootstrap] passes
 * `capturePlatformHttp = true`), which installs a process-wide hook over
 * `HttpURLConnection`. That hook is best-effort: on Android releases whose
 * hidden-API policy blocks it, these requests still succeed but do not appear in
 * the Network tab.
 */
public object PlatformHttpDemo {
    /**
     * Fires a GET with a raw [HttpURLConnection]. Blocks, so call off the main
     * thread. Drains the response body so it is captured before disconnecting.
     */
    public fun rawGet(
        url: String,
        onResult: (status: String) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            val code = connection.responseCode
            val stream =
                if (code < HttpURLConnection.HTTP_BAD_REQUEST) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
            stream?.use { it.readBytes() }
            onResult("HTTP $code")
        } catch (e: IOException) {
            onError(e.message ?: "connection error")
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Fires a GET through Volley, whose default transport is `HttpURLConnection`.
     * Volley manages its own worker threads and delivers callbacks on the main
     * thread, so [onResult]/[onError] need no extra marshalling.
     */
    public fun volleyGet(
        context: Context,
        url: String,
        onResult: (status: String) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        val request =
            StringRequest(
                Request.Method.GET,
                url,
                { response -> onResult("Volley OK (${response.length} chars)") },
                { error -> onError(error.message ?: "volley error") },
            )
        obtainQueue(context).add(request)
    }

    @Volatile
    private var requestQueue: RequestQueue? = null

    private fun obtainQueue(context: Context): RequestQueue =
        requestQueue ?: synchronized(this) {
            requestQueue ?: Volley.newRequestQueue(context.applicationContext).also { requestQueue = it }
        }

    private const val TIMEOUT_MS = 10_000
}
