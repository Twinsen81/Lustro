package io.github.twinsen81.lustro.sample

import java.io.IOException
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reference implementation of a Server-Sent Events (SSE) client.
 *
 * Shows how to consume a `text/event-stream` response incrementally: the body
 * source is read line by line as bytes arrive, instead of being buffered whole.
 * That incremental read is also what lets the Network tab show the captured row
 * grow chunk by chunk and then complete — the library recognises the
 * `text/event-stream` content type and records progressive updates as the source
 * is drained.
 *
 * [stream] blocks while the stream is open, so callers must invoke it off the main
 * thread; callbacks are delivered on that same background thread.
 */
public object SseStreamingDemo {
    /**
     * Opens [url] on [client] and reads the event stream until it ends.
     *
     * @param onEvent invoked with the payload of each `data:` line as it arrives.
     * @param onComplete invoked once at end of stream with the number of events seen.
     * @param onError invoked if the request fails or returns a non-2xx status.
     */
    public fun stream(
        client: OkHttpClient,
        url: String,
        onEvent: (data: String) -> Unit,
        onComplete: (eventCount: Int) -> Unit,
        onError: (message: String) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        try {
            client.newCall(request).execute().use { response ->
                val source = response.body?.source()
                if (!response.isSuccessful || source == null) {
                    onError("HTTP ${response.code}")
                    return
                }
                var eventCount = 0
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith(DATA_PREFIX)) {
                        onEvent(line.removePrefix(DATA_PREFIX).trim())
                        eventCount++
                    }
                }
                onComplete(eventCount)
            }
        } catch (e: IOException) {
            onError(e.message ?: "stream error")
        }
    }

    private const val DATA_PREFIX = "data:"
}
