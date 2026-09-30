@file:Suppress("TooGenericExceptionCaught", "SwallowedException")

package io.github.twinsen81.lustro.internal.network

import android.util.Log
import io.github.twinsen81.lustro.Headers
import io.github.twinsen81.lustro.MediaType
import io.github.twinsen81.lustro.network.CapturedBody
import io.github.twinsen81.lustro.network.CapturedResponse
import io.github.twinsen81.lustro.network.NetworkCaptureSink
import io.github.twinsen81.lustro.network.TransactionId
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.URLStreamHandlerFactory
import java.security.Permission
import java.security.cert.Certificate
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/**
 * Process-wide capture of [java.net.HttpURLConnection] traffic into a
 * [NetworkCaptureSink].
 *
 * The OkHttp [LustroNetworkInterceptor] only sees calls made through the app's
 * own OkHttpClient instances. Libraries that talk to the network via the
 * platform `HttpURLConnection` stack are invisible to it. This installs a single
 * global [URLStreamHandlerFactory] that delegates to the platform's real
 * http/https handlers but wraps each connection so the request/response is
 * recorded into the same sink the OkHttp interceptor feeds. We sit *above* TLS
 * (we read the already-decrypted streams), so no certificate, proxy, or MITM is
 * involved.
 *
 * Caveats:
 * - The factory can only be set once per process; install early and exactly once.
 * - Obtaining the real delegate handler requires reflecting the platform-internal
 *   `com.android.okhttp.HttpHandler`, which Android's hidden-API policy may block
 *   on newer releases. If that fails we degrade gracefully (no capture).
 * - Capture is strictly best-effort: any failure in the capture path falls back
 *   to the real connection's behavior so app traffic is never affected.
 */
internal class HttpUrlConnectionCapture(
    private val sink: NetworkCaptureSink,
    private val isPaused: () -> Boolean,
    private val captureFilter: SafeCaptureFilter,
    private val maxBodySize: Int,
) {
    private val installed = AtomicBoolean(false)

    /**
     * How [install] got the platform's http handler, or null when capture is not
     * installed for http. The instrumented test reads it, so a release that
     * blocks a path fails that test instead of turning capture off without notice.
     */
    @Volatile
    var httpSource: HandlerSource? = null
        private set

    /** How [install] got the platform's https handler, as [httpSource] does for http. */
    @Volatile
    var httpsSource: HandlerSource? = null
        private set

    fun install() {
        if (!installed.compareAndSet(false, true)) return

        // Capture the platform's real handlers BEFORE installing our factory, otherwise
        // later lookups would resolve back into us.
        val realHttps = platformHandler("HttpsHandler", "https://localhost")
        val realHttp = platformHandler("HttpHandler", "http://localhost")

        if (realHttps == null && realHttp == null) {
            Log.w(TAG, "Could not obtain platform URL handlers (hidden-API blocked?); capture disabled")
            installed.set(false)
            return
        }

        val factory =
            URLStreamHandlerFactory { protocol ->
                when (protocol) {
                    "https" -> realHttps?.let { CapturingStreamHandler(it.handler, sink, isPaused, captureFilter, maxBodySize, secure = true) }
                    "http" -> realHttp?.let { CapturingStreamHandler(it.handler, sink, isPaused, captureFilter, maxBodySize, secure = false) }
                    else -> null
                }
            }

        try {
            URL.setURLStreamHandlerFactory(factory)
            httpsSource = realHttps?.source
            httpSource = realHttp?.source
            Log.d(TAG, "HttpURLConnection capture installed (https=${realHttps?.source}, http=${realHttp?.source})")
        } catch (t: Throwable) {
            // Already set by something else, or denied — leave the platform stack untouched.
            Log.w(TAG, "Failed to install URLStreamHandlerFactory; capture disabled", t)
            installed.set(false)
        }
    }

    private fun platformHandler(className: String, spec: String): PlatformHandler? =
        realHandler(className)?.let { PlatformHandler(it, HandlerSource.HANDLER_CLASS) }
            ?: handlerOf(spec)?.let { PlatformHandler(it, HandlerSource.URL_FIELD) }

    /** Instantiate a platform-internal handler (e.g. com.android.okhttp.HttpHandler) reflectively. */
    private fun realHandler(simpleName: String): URLStreamHandler? =
        try {
            val clazz = Class.forName("com.android.okhttp.$simpleName")
            clazz.getDeclaredConstructor().newInstance() as URLStreamHandler
        } catch (t: Throwable) {
            Log.w(TAG, "Reflective handler $simpleName unavailable: ${t.javaClass.simpleName}")
            null
        }

    /** Fallback: read the resolved handler off a freshly-built URL (before our factory is set). */
    private fun handlerOf(spec: String): URLStreamHandler? =
        try {
            val field = URL::class.java.getDeclaredField("handler")
            field.isAccessible = true
            field.get(URL(spec)) as? URLStreamHandler
        } catch (t: Throwable) {
            null
        }

    /** The two ways [install] can get a platform handler. The hidden-API policy decides which works. */
    enum class HandlerSource {
        /** A new instance of the platform's `com.android.okhttp` handler class. */
        HANDLER_CLASS,

        /** The handler of a new [URL], read from its private `handler` field. */
        URL_FIELD,
    }

    private class PlatformHandler(val handler: URLStreamHandler, val source: HandlerSource)

    // Internal for tests, which hand it a stand-in for the platform handler.
    internal class CapturingStreamHandler(
        private val real: URLStreamHandler,
        private val sink: NetworkCaptureSink,
        private val isPaused: () -> Boolean,
        private val captureFilter: SafeCaptureFilter,
        private val maxBodySize: Int,
        private val secure: Boolean,
    ) : URLStreamHandler() {
        // Build the real connection by handing the URL the real handler explicitly, which
        // bypasses our just-installed factory (no recursion).
        override fun openConnection(u: URL): URLConnection =
            wrap(u, URL(null, u.toExternalForm(), real).openConnection())

        override fun openConnection(u: URL, p: Proxy): URLConnection =
            wrap(u, URL(null, u.toExternalForm(), real).openConnection(p))

        // Without this the base handler returns -1, breaking URL.getPort()/equals for
        // http/https URLs that omit an explicit port.
        override fun getDefaultPort(): Int = if (secure) HTTPS_PORT else HTTP_PORT

        private fun wrap(u: URL, conn: URLConnection): URLConnection =
            try {
                if (isPaused()) {
                    conn
                } else if (secure && conn is HttpsURLConnection) {
                    CapturingHttpsURLConnection(u, conn, sink, captureFilter, maxBodySize)
                } else if (conn is HttpURLConnection) {
                    CapturingHttpURLConnection(u, conn, sink, captureFilter, maxBodySize)
                } else {
                    conn
                }
            } catch (t: Throwable) {
                conn
            }
    }

    private class CaptureState(
        private val url: URL,
        private val connection: HttpURLConnection,
        private val sink: NetworkCaptureSink,
        private val captureFilter: SafeCaptureFilter,
        private val maxBodySize: Int,
    ) {
        private val startTime = System.currentTimeMillis()
        private val requestHeaders = LinkedHashMap<String, String>()
        private var requestBodyBuffer: ByteArrayOutputStream? = null
        private var wrappedOutput: OutputStream? = null
        private val requestRecorded = AtomicBoolean(false)
        private val responseRecorded = AtomicBoolean(false)
        private val bodyFinalized = AtomicBoolean(false)
        private val responseBodyBuffer = ByteArrayOutputStream()
        private var transactionId: TransactionId? = null

        // The capture filter's answer, asked once the app can no longer change the
        // request: when it opens the body stream or connects. Null until then.
        private var filterAnswer: Boolean? = null

        fun onSetHeader(key: String, value: String?) {
            if (value == null) requestHeaders.remove(key) else requestHeaders[key] = value
        }

        fun onAddHeader(key: String, value: String) {
            requestHeaders.merge(key, value) { old, new -> "$old, $new" }
        }

        fun wrapOutput(real: OutputStream): OutputStream {
            // The platform returns the same OutputStream on repeated getOutputStream() calls, so we
            // must too — a fresh buffer per call would drop earlier writes from the captured body.
            wrappedOutput?.let { return it }
            // Not copied when the filter skips the request. The platform's own stream is
            // the same on every call already.
            if (!passesFilter()) return real
            val buffer = ByteArrayOutputStream()
            requestBodyBuffer = buffer
            return BoundedTeeOutputStream(real, buffer, maxBodySize).also { wrappedOutput = it }
        }

        fun recordRequestOnce() {
            if (!requestRecorded.compareAndSet(false, true)) return
            try {
                if (!passesFilter()) return
                val bodyBytes = requestBodyBuffer?.toByteArray()
                val headers = headersOf(requestHeaders)
                val contentType = contentTypeOf(requestHeaders)
                transactionId =
                    sink.beginRequest(
                        url = url.toString(),
                        method = requestMethod(),
                        headers = headers,
                        requestBody =
                            bodyBytes?.let {
                                platformCapturedBody(it, maxBodySize, contentType, headers.getAll(CONTENT_ENCODING), headers.declaredLength())
                            },
                        contentType = contentType,
                    )
            } catch (t: Throwable) {
                Log.w(TAG, "recordRequest failed: ${t.javaClass.simpleName}")
            }
        }

        fun recordResponseHeaders(statusCode: Int, headers: Map<String, List<String>>) {
            if (!responseRecorded.compareAndSet(false, true)) return
            val id = transactionId ?: return
            try {
                // Complete as soon as we have status + headers: some callers (e.g. on a 202)
                // check the response code but never read the body stream, so finalizeBody
                // would never fire and the transaction would be stuck "in flight". If a body
                // is read, finalizeBody enriches this (still complete).
                sink.completeRequest(id, capturedResponse(statusCode, flatten(headers), body = null))
            } catch (t: Throwable) {
                Log.w(TAG, "recordResponseHeaders failed: ${t.javaClass.simpleName}")
            }
        }

        fun wrapInput(real: InputStream, statusCode: Int, headers: Map<String, List<String>>): InputStream {
            if (filterAnswer == false) return real
            recordResponseHeaders(statusCode, headers)
            return BoundedTeeInputStream(real, responseBodyBuffer, maxBodySize) {
                finalizeBody(statusCode, headers)
            }
        }

        private fun finalizeBody(statusCode: Int, headers: Map<String, List<String>>) {
            if (!bodyFinalized.compareAndSet(false, true)) return
            val id = transactionId ?: return
            try {
                val flat = flatten(headers)
                val contentType = flat.get("Content-Type")?.let { MediaType.parse(it) }
                val body =
                    platformCapturedBody(
                        responseBodyBuffer.toByteArray(),
                        maxBodySize,
                        contentType,
                        flat.getAll(CONTENT_ENCODING),
                        flat.declaredLength(),
                    )
                sink.completeRequest(id, capturedResponse(statusCode, flat, body))
            } catch (t: Throwable) {
                Log.w(TAG, "finalizeBody failed: ${t.javaClass.simpleName}")
            }
        }

        // No protocol: HttpURLConnection has no public API that reports it.
        private fun capturedResponse(
            statusCode: Int,
            headers: Headers,
            body: CapturedBody?,
        ): CapturedResponse =
            CapturedResponse.Builder(statusCode, System.currentTimeMillis() - startTime)
                .headers(headers)
                .body(body)
                .build()

        fun recordError(message: String?) {
            // A response (incl. 4xx/5xx) was already captured; don't clobber it with an "error"
            // when the caller's getInputStream() subsequently throws reading the error body.
            if (responseRecorded.get()) return
            val id = transactionId ?: return
            try {
                sink.failRequest(id, System.currentTimeMillis() - startTime, message ?: "error")
            } catch (t: Throwable) {
                Log.w(TAG, "recordError failed: ${t.javaClass.simpleName}")
            }
        }

        private fun passesFilter(): Boolean =
            filterAnswer
                ?: captureFilter.shouldCapture(url.toString(), requestMethod(), headersOf(requestHeaders))
                    .also { filterAnswer = it }

        // The method that goes on the wire. The platform sends a GET with doOutput set
        // as a POST, but switches the method only when it connects, and an explicit
        // connect() records the request before that.
        private fun requestMethod(): String =
            connection.requestMethod.let { if (it == "GET" && connection.doOutput) "POST" else it }

        private fun headersOf(headers: Map<String, String>): Headers {
            val builder = Headers.Builder()
            headers.forEach { (k, v) -> builder.add(k, v) }
            return builder.build()
        }

        private fun contentTypeOf(headers: Map<String, String>): MediaType? =
            headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value
                ?.let { MediaType.parse(it) }

        private fun Headers.declaredLength(): Long? = get("Content-Length")?.toLongOrNull()?.takeIf { it >= 0 }

        private fun flatten(headers: Map<String, List<String>>): Headers {
            val builder = Headers.Builder()
            // HttpURLConnection.getHeaderFields() uses a null key for the HTTP
            // status line, despite the declared non-null key type — guard against it.
            @Suppress("USELESS_ELVIS")
            for ((key, values) in headers) {
                val name = key ?: continue
                builder.add(name, values.joinToString(", "))
            }
            return builder.build()
        }
    }

    private class CapturingHttpURLConnection(
        url: URL,
        private val real: HttpURLConnection,
        sink: NetworkCaptureSink,
        captureFilter: SafeCaptureFilter,
        maxBodySize: Int,
    ) : HttpURLConnection(url) {
        private val capture = CaptureState(url, real, sink, captureFilter, maxBodySize)

        override fun connect() {
            capture.recordRequestOnce()
            real.connect()
        }

        override fun disconnect() = real.disconnect()

        override fun usingProxy(): Boolean = real.usingProxy()

        override fun getOutputStream(): OutputStream = capture.wrapOutput(real.outputStream)

        override fun getInputStream(): InputStream {
            capture.recordRequestOnce()
            return try {
                capture.wrapInput(real.inputStream, real.responseCode, real.headerFields)
            } catch (e: Exception) {
                capture.recordError(e.message)
                throw e
            }
        }

        override fun getErrorStream(): InputStream? {
            val stream = real.errorStream ?: return null
            return capture.wrapInput(stream, runCatching { real.responseCode }.getOrDefault(-1), real.headerFields)
        }

        override fun getResponseCode(): Int {
            capture.recordRequestOnce()
            return try {
                val code = real.responseCode
                capture.recordResponseHeaders(code, real.headerFields)
                code
            } catch (e: Exception) {
                capture.recordError(e.message)
                throw e
            }
        }

        override fun getResponseMessage(): String? = real.responseMessage

        override fun setRequestMethod(method: String) {
            real.requestMethod = method
        }

        override fun getRequestMethod(): String = real.requestMethod

        override fun setRequestProperty(key: String?, value: String?) {
            if (key != null) capture.onSetHeader(key, value)
            real.setRequestProperty(key, value)
        }

        override fun addRequestProperty(key: String?, value: String?) {
            if (key != null && value != null) capture.onAddHeader(key, value)
            real.addRequestProperty(key, value)
        }

        override fun getRequestProperty(key: String?): String? = real.getRequestProperty(key)

        override fun getRequestProperties(): MutableMap<String, MutableList<String>> = real.requestProperties

        override fun getHeaderField(n: Int): String? = real.getHeaderField(n)

        override fun getHeaderField(name: String?): String? = real.getHeaderField(name)

        override fun getHeaderFieldKey(n: Int): String? = real.getHeaderFieldKey(n)

        override fun getHeaderFields(): MutableMap<String, MutableList<String>> = real.headerFields

        override fun getHeaderFieldDate(name: String?, default: Long): Long = real.getHeaderFieldDate(name, default)

        override fun setDoInput(value: Boolean) {
            real.doInput = value
        }

        override fun getDoInput(): Boolean = real.doInput

        override fun setDoOutput(value: Boolean) {
            real.doOutput = value
        }

        override fun getDoOutput(): Boolean = real.doOutput

        override fun setUseCaches(value: Boolean) {
            real.useCaches = value
        }

        override fun getUseCaches(): Boolean = real.useCaches

        override fun setConnectTimeout(timeout: Int) {
            real.connectTimeout = timeout
        }

        override fun getConnectTimeout(): Int = real.connectTimeout

        override fun setReadTimeout(timeout: Int) {
            real.readTimeout = timeout
        }

        override fun getReadTimeout(): Int = real.readTimeout

        override fun setInstanceFollowRedirects(followRedirects: Boolean) {
            real.instanceFollowRedirects = followRedirects
        }

        override fun getInstanceFollowRedirects(): Boolean = real.instanceFollowRedirects

        override fun setIfModifiedSince(value: Long) {
            real.ifModifiedSince = value
        }

        override fun getIfModifiedSince(): Long = real.ifModifiedSince

        override fun setAllowUserInteraction(value: Boolean) {
            real.allowUserInteraction = value
        }

        override fun getAllowUserInteraction(): Boolean = real.allowUserInteraction

        override fun setChunkedStreamingMode(chunklen: Int) {
            real.setChunkedStreamingMode(chunklen)
        }

        override fun setFixedLengthStreamingMode(contentLength: Int) {
            real.setFixedLengthStreamingMode(contentLength)
        }

        override fun setFixedLengthStreamingMode(contentLength: Long) {
            real.setFixedLengthStreamingMode(contentLength)
        }

        override fun getPermission(): Permission = real.permission

        override fun getContentLength(): Int = real.contentLength

        override fun getContentLengthLong(): Long = real.contentLengthLong

        override fun getContentType(): String? = real.contentType

        override fun getContentEncoding(): String? = real.contentEncoding
    }

    private class CapturingHttpsURLConnection(
        url: URL,
        private val real: HttpsURLConnection,
        sink: NetworkCaptureSink,
        captureFilter: SafeCaptureFilter,
        maxBodySize: Int,
    ) : HttpsURLConnection(url) {
        private val capture = CaptureState(url, real, sink, captureFilter, maxBodySize)

        override fun connect() {
            capture.recordRequestOnce()
            real.connect()
        }

        override fun disconnect() = real.disconnect()

        override fun usingProxy(): Boolean = real.usingProxy()

        override fun getOutputStream(): OutputStream = capture.wrapOutput(real.outputStream)

        override fun getInputStream(): InputStream {
            capture.recordRequestOnce()
            return try {
                capture.wrapInput(real.inputStream, real.responseCode, real.headerFields)
            } catch (e: Exception) {
                capture.recordError(e.message)
                throw e
            }
        }

        override fun getErrorStream(): InputStream? {
            val stream = real.errorStream ?: return null
            return capture.wrapInput(stream, runCatching { real.responseCode }.getOrDefault(-1), real.headerFields)
        }

        override fun getResponseCode(): Int {
            capture.recordRequestOnce()
            return try {
                val code = real.responseCode
                capture.recordResponseHeaders(code, real.headerFields)
                code
            } catch (e: Exception) {
                capture.recordError(e.message)
                throw e
            }
        }

        override fun getResponseMessage(): String? = real.responseMessage

        override fun setRequestMethod(method: String) {
            real.requestMethod = method
        }

        override fun getRequestMethod(): String = real.requestMethod

        override fun setRequestProperty(key: String?, value: String?) {
            if (key != null) capture.onSetHeader(key, value)
            real.setRequestProperty(key, value)
        }

        override fun addRequestProperty(key: String?, value: String?) {
            if (key != null && value != null) capture.onAddHeader(key, value)
            real.addRequestProperty(key, value)
        }

        override fun getRequestProperty(key: String?): String? = real.getRequestProperty(key)

        override fun getRequestProperties(): MutableMap<String, MutableList<String>> = real.requestProperties

        override fun getHeaderField(n: Int): String? = real.getHeaderField(n)

        override fun getHeaderField(name: String?): String? = real.getHeaderField(name)

        override fun getHeaderFieldKey(n: Int): String? = real.getHeaderFieldKey(n)

        override fun getHeaderFields(): MutableMap<String, MutableList<String>> = real.headerFields

        override fun getHeaderFieldDate(name: String?, default: Long): Long = real.getHeaderFieldDate(name, default)

        override fun setDoInput(value: Boolean) {
            real.doInput = value
        }

        override fun getDoInput(): Boolean = real.doInput

        override fun setDoOutput(value: Boolean) {
            real.doOutput = value
        }

        override fun getDoOutput(): Boolean = real.doOutput

        override fun setUseCaches(value: Boolean) {
            real.useCaches = value
        }

        override fun getUseCaches(): Boolean = real.useCaches

        override fun setConnectTimeout(timeout: Int) {
            real.connectTimeout = timeout
        }

        override fun getConnectTimeout(): Int = real.connectTimeout

        override fun setReadTimeout(timeout: Int) {
            real.readTimeout = timeout
        }

        override fun getReadTimeout(): Int = real.readTimeout

        override fun setInstanceFollowRedirects(followRedirects: Boolean) {
            real.instanceFollowRedirects = followRedirects
        }

        override fun getInstanceFollowRedirects(): Boolean = real.instanceFollowRedirects

        override fun setIfModifiedSince(value: Long) {
            real.ifModifiedSince = value
        }

        override fun getIfModifiedSince(): Long = real.ifModifiedSince

        override fun setAllowUserInteraction(value: Boolean) {
            real.allowUserInteraction = value
        }

        override fun getAllowUserInteraction(): Boolean = real.allowUserInteraction

        override fun setChunkedStreamingMode(chunklen: Int) {
            real.setChunkedStreamingMode(chunklen)
        }

        override fun setFixedLengthStreamingMode(contentLength: Int) {
            real.setFixedLengthStreamingMode(contentLength)
        }

        override fun setFixedLengthStreamingMode(contentLength: Long) {
            real.setFixedLengthStreamingMode(contentLength)
        }

        override fun getPermission(): Permission = real.permission

        override fun getContentLength(): Int = real.contentLength

        override fun getContentLengthLong(): Long = real.contentLengthLong

        override fun getContentType(): String? = real.contentType

        override fun getContentEncoding(): String? = real.contentEncoding

        override fun getCipherSuite(): String = real.cipherSuite

        override fun getLocalCertificates(): Array<Certificate>? = real.localCertificates

        override fun getServerCertificates(): Array<Certificate> = real.serverCertificates

        override fun getPeerPrincipal() = real.peerPrincipal

        override fun getLocalPrincipal() = real.localPrincipal

        override fun setHostnameVerifier(v: HostnameVerifier?) {
            real.hostnameVerifier = v
        }

        override fun getHostnameVerifier(): HostnameVerifier = real.hostnameVerifier

        override fun setSSLSocketFactory(sf: SSLSocketFactory?) {
            real.sslSocketFactory = sf
        }

        override fun getSSLSocketFactory(): SSLSocketFactory = real.sslSocketFactory
    }

    private class BoundedTeeOutputStream(
        private val delegate: OutputStream,
        private val sink: ByteArrayOutputStream,
        private val max: Int,
    ) : FilterOutputStream(delegate) {
        override fun write(b: Int) {
            if (sink.size() < max) sink.write(b)
            delegate.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            val remaining = max - sink.size()
            if (remaining > 0) sink.write(b, off, minOf(len, remaining))
            delegate.write(b, off, len)
        }
    }

    private class BoundedTeeInputStream(
        delegate: InputStream,
        private val sink: ByteArrayOutputStream,
        private val max: Int,
        private val onComplete: () -> Unit,
    ) : FilterInputStream(delegate) {
        private val done = AtomicBoolean(false)

        override fun read(): Int {
            val b = super.read()
            if (b == -1) {
                complete()
            } else if (sink.size() < max) {
                sink.write(b)
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n == -1) {
                complete()
            } else {
                val remaining = max - sink.size()
                if (remaining > 0) sink.write(b, off, minOf(n, remaining))
            }
            return n
        }

        override fun close() {
            complete()
            super.close()
        }

        private fun complete() {
            if (done.compareAndSet(false, true)) onComplete()
        }
    }

    private companion object {
        private const val TAG = "LustroHttpUrlCapture"
        private const val HTTPS_PORT = 443
        private const val HTTP_PORT = 80
    }
}

/**
 * Builds a [CapturedBody] from tee-captured [bytes] of a body of [contentType]:
 * an image body as bytes, anything else decoded as UTF-8 text. The tee caps
 * writes at [maxBodySize], so a buffer at the cap signals truncation (a buffer
 * length equal to the cap indicates the full body was not captured). `byteSize`
 * is the [declaredSize] when the body has one, else the captured size, or null
 * when the tee filled up: the full size isn't tracked beyond the cap.
 *
 * The platform inflates a gzip response itself only when it asked for gzip. A
 * body the app compressed, or asked for in an encoding itself, arrives here
 * compressed and is inflated as in the OkHttp adapter (see [decodeBody]).
 */
internal fun platformCapturedBody(
    bytes: ByteArray,
    maxBodySize: Int,
    contentType: MediaType?,
    contentEncoding: List<String>,
    declaredSize: Long?,
): CapturedBody {
    val filled = bytes.size >= maxBodySize
    val byteSize = declaredSize ?: bytes.size.toLong().takeUnless { filled }
    val decoded =
        decodeBody(bytes, rawTruncated = filled, contentEncoding, maxBodySize.toLong())
            ?: return CapturedBody(text = null, truncated = false, byteSize = byteSize)
    return if (contentType.isRetainedBinary()) {
        CapturedBody(text = null, truncated = decoded.truncated, byteSize = byteSize, bytes = decoded.bytes())
    } else {
        CapturedBody(text = decoded.text(Charsets.UTF_8), truncated = decoded.truncated, byteSize = byteSize)
    }
}
