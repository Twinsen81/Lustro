package io.github.twinsen81.lustro.sample

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.GzipSink
import okio.buffer

/**
 * Launcher activity with one button per sample HTTP request shape.
 *
 * A launch with a `request` extra that names a button clicks that button, so a
 * script can fire a request without the UI. `--activity-single-top` delivers
 * the extra when the sample is already open:
 * `adb shell am start --activity-single-top -n io.github.twinsen81.lustro.sample/.MainActivity --es request "'GET /get'"`.
 */
public class MainActivity : Activity() {
    private val ioExecutor = Executors.newFixedThreadPool(IO_THREADS)
    private val syncHandler = Handler(Looper.getMainLooper())
    private val buttonsByLabel = HashMap<String, Button>()
    private lateinit var statusView: TextView

    private var syncing = false
    private var syncCount = 0
    private val syncRunnable =
        object : Runnable {
            override fun run() {
                if (!syncing) return
                syncCount++
                // Same method+path every tick, so with the Network tab's "Overwrite"
                // toggle on the repeats collapse into a single row (request
                // compaction). A production app would schedule periodic sync with
                // WorkManager; a foreground ticker just keeps the demo observable
                // within seconds.
                dispatch(Request.Builder().url("$BASE/anything/sync").get().build())
                syncHandler.postDelayed(this, SYNC_INTERVAL_MS)
            }
        }

    private val client: OkHttpClient
        get() = (application as SampleApplication).httpClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        // A recreated activity, after a rotation for example, must not click it again.
        if (savedInstanceState == null) clickButtonNamedIn(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        clickButtonNamedIn(intent)
    }

    private fun clickButtonNamedIn(intent: Intent) {
        val label = intent.getStringExtra(EXTRA_REQUEST) ?: return
        val button = buttonsByLabel[label]
        if (button == null) setStatus("No button is labelled \"$label\"") else button.performClick()
    }

    override fun onDestroy() {
        super.onDestroy()
        syncing = false
        syncHandler.removeCallbacks(syncRunnable)
        ioExecutor.shutdownNow()
    }

    private fun buildUi(): View {
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(PADDING, PADDING, PADDING, PADDING)
            }

        root.addView(
            TextView(this).apply {
                text =
                    "Each button fires a request through the Lustro-instrumented " +
                        "OkHttpClient — or, under Platform HTTP, the java.net stack. " +
                        "Open the debug console (debug build) and watch the Network " +
                        "tab. Flip the Overwrite toggle while a periodic sync runs to " +
                        "collapse repeats into one row, or use the Mock Rules panel to " +
                        "inject error responses."
                setPadding(0, 0, 0, PADDING)
            },
        )

        addSection(root, "Basic")
        addButton(root, "GET /get") { getRequest() }
        addButton(root, "POST /post") { postRequest() }
        addButton(root, "PUT /put") { putRequest() }
        addButton(root, "PATCH /patch") { patchRequest() }
        addButton(root, "DELETE /delete") { deleteRequest() }

        addSection(root, "Errors & timeouts")
        addButton(root, "Mocked 500 (/status/500)") { errorRequest() }
        addButton(root, "Slow call (/delay/3)") { slowRequest() }
        addButton(root, "Timeout (/delay/10, 2s budget)") { timeoutRequest() }

        addSection(root, "Streaming")
        addButton(root, "Stream SSE (/sse)") { streamSse() }

        // OkHttp decompresses a response on its own only when it asked for gzip
        // itself. An app that sets Accept-Encoding gets the compressed bytes, and
        // so does every interceptor on its way back.
        addSection(root, "Compressed bodies")
        addButton(root, "GET /get, app asks for gzip") { compressedGet("gzip") }
        addButton(root, "GET /get, app asks for deflate") { compressedGet("deflate") }
        addButton(root, "GET /get, app asks for br (not decoded)") { compressedGet("br") }
        addButton(root, "POST a gzip JSON body (/status/200)") { gzipPostRequest() }

        // The Network tab picks a viewer for a body by its content type.
        addSection(root, "Body viewers")
        addButton(root, "GET /json") { getRequest("json") }
        addButton(root, "GET /xml") { getRequest("xml") }
        addButton(root, "GET /html") { getRequest("html") }
        addButton(root, "GET /image/jpeg") { getRequest("image/jpeg") }
        addButton(root, "GET /image/svg") { getRequest("image/svg") }
        addButton(root, "GET /robots.txt") { getRequest("robots.txt") }
        addButton(root, "POST a form (/post)") { formPostRequest() }
        addButton(root, "POST a PNG (/anything)") { imagePostRequest(pngBody(), "image/png") }
        addButton(root, "POST a BMP (/anything)") { imagePostRequest(bmpBody(), "image/bmp") }
        addButton(root, "POST a 250 KB JSON body (/anything)") { largeJsonPostRequest() }

        addSection(root, "Periodic sync")
        val syncButton = addButton(root, START_SYNC_LABEL)
        syncButton.setOnClickListener { toggleSync(syncButton) }

        addSection(root, "Platform HTTP (non-OkHttp)")
        addButton(root, "Raw HttpURLConnection GET") { rawPlatformRequest() }
        addButton(root, "Raw HttpURLConnection GET with $NO_CAPTURE_HEADER") {
            rawPlatformRequest(headers = mapOf(NO_CAPTURE_HEADER to "1"))
        }
        addButton(root, "Raw HttpURLConnection GET, app asks for gzip") {
            rawPlatformRequest(path = "gzip", headers = mapOf("Accept-Encoding" to "gzip"))
        }
        addButton(root, "Volley GET") { volleyPlatformRequest() }

        val extras = LustroBootstrap.extraDemoRequests(BASE)
        if (extras.isNotEmpty()) {
            addSection(root, "More requests (debug)")
            extras.forEach { spec ->
                addButton(root, spec.label) { dispatch(spec.buildRequest()) }
            }
        }

        statusView =
            TextView(this).apply {
                setPadding(0, PADDING, 0, 0)
                text = "Ready."
            }
        root.addView(statusView)

        return ScrollView(this).apply {
            // Targeting API 35 draws the window edge-to-edge, so the action bar and
            // the system bars overlap the content unless it pads itself by the insets.
            fitsSystemWindows = true
            addView(root)
        }
    }

    private fun addSection(parent: LinearLayout, title: String) {
        parent.addView(
            TextView(this).apply {
                text = title
                textSize = SECTION_TEXT_SIZE
                setPadding(0, PADDING, 0, PADDING / 4)
            },
        )
    }

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit = {}): Button {
        val button =
            Button(this).apply {
                text = label
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { onClick() }
            }
        parent.addView(button)
        buttonsByLabel[label] = button
        return button
    }

    private fun getRequest(path: String = "get") {
        dispatch(Request.Builder().url("$BASE/$path").get().build())
    }

    private fun formPostRequest() {
        val form =
            FormBody.Builder()
                .add("name", "Ada Lovelace")
                .add("email", "ada@example.com")
                .add("password", "not-a-real-one")
                .add("topic", "engines")
                .add("topic", "poetry")
                .add("note", "café & crème, 100% <real>")
                .build()
        dispatch(Request.Builder().url("$BASE/post").post(form).build())
    }

    private fun imagePostRequest(body: ByteArray, contentType: String) {
        dispatch(Request.Builder().url("$BASE/anything").post(body.toRequestBody(contentType.toMediaType())).build())
    }

    private fun pngBody(): ByteArray {
        val bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888)
        val dot = Paint().apply { color = Color.rgb(0xFB, 0xBF, 0x24) }
        Canvas(bitmap).apply {
            drawColor(Color.rgb(0x1E, 0x3A, 0x8A))
            drawCircle(IMAGE_WIDTH / 2f, IMAGE_HEIGHT / 2f, IMAGE_HEIGHT / 3f, dot)
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    // Android has no BMP encoder, so this writes a 24-bit one: a file header, a
    // BITMAPINFOHEADER, and rows of blue, green, and red bytes from the bottom up,
    // each padded to 4 bytes. The console shows no preview for this type.
    private fun bmpBody(): ByteArray {
        val rowBytes = (IMAGE_WIDTH * 3 + 3) / 4 * 4
        val pixelBytes = rowBytes * IMAGE_HEIGHT
        val bmp = ByteBuffer.allocate(BMP_HEADER_BYTES + pixelBytes).order(ByteOrder.LITTLE_ENDIAN)
        bmp.put('B'.code.toByte()).put('M'.code.toByte())
        bmp.putInt(BMP_HEADER_BYTES + pixelBytes).putInt(0).putInt(BMP_HEADER_BYTES)
        bmp.putInt(BMP_INFO_HEADER_BYTES).putInt(IMAGE_WIDTH).putInt(IMAGE_HEIGHT)
        bmp.putShort(1).putShort(BMP_BITS_PER_PIXEL)
        bmp.putInt(0).putInt(pixelBytes).putInt(0).putInt(0).putInt(0).putInt(0)
        for (y in 0 until IMAGE_HEIGHT) {
            for (x in 0 until IMAGE_WIDTH) {
                val light = (x / BMP_SQUARE + y / BMP_SQUARE) % 2 == 0
                val shade = (if (light) 0xF4 else 0x2A).toByte()
                bmp.put(shade).put(shade).put(if (light) shade else 0xC0.toByte())
            }
            repeat(rowBytes - IMAGE_WIDTH * 3) { bmp.put(0) }
        }
        return bmp.array()
    }

    // Just under the default capture cap of 256 KiB, so it is kept whole.
    private fun largeJsonPostRequest() {
        val body =
            buildString {
                append("{\"items\":[")
                var id = 0
                while (length < LARGE_JSON_BYTES) {
                    if (id > 0) append(',')
                    append("{\"id\":").append(id)
                    append(",\"name\":\"Item ").append(id).append('"')
                    append(",\"price\":").append(id % 100).append(".10")
                    append(",\"tags\":[\"red\",\"blue\"],\"inStock\":").append(id % 3 != 0)
                    append(",\"size\":{\"w\":").append(id % 40).append(",\"h\":").append(id % 25).append("},\"note\":null}")
                    id++
                }
                append("]}")
            }
        dispatch(Request.Builder().url("$BASE/anything").post(body.toRequestBody(JSON)).build())
    }

    private fun postRequest() {
        dispatch(
            Request.Builder()
                .url("$BASE/post")
                .post("""{"hello":"world"}""".toRequestBody(JSON))
                .build(),
        )
    }

    private fun putRequest() {
        dispatch(
            Request.Builder()
                .url("$BASE/put")
                .put("""{"updated":true}""".toRequestBody(JSON))
                .build(),
        )
    }

    private fun patchRequest() {
        dispatch(
            Request.Builder()
                .url("$BASE/patch")
                .patch("""{"patched":true}""".toRequestBody(JSON))
                .build(),
        )
    }

    private fun deleteRequest() {
        dispatch(Request.Builder().url("$BASE/delete").delete().build())
    }

    private fun errorRequest() {
        dispatch(Request.Builder().url("$BASE/status/500").get().build())
    }

    private fun slowRequest() {
        dispatch(Request.Builder().url("$BASE/delay/3").get().build())
    }

    private fun timeoutRequest() {
        val request = Request.Builder().url("$BASE/delay/10").get().build()
        setStatus("→ GET ${request.url} (2s budget)")
        val call = client.newCall(request)
        // Per-call timeout: bounds the whole call without a separate client.
        call.timeout().timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    setStatus("✗ timeout ${request.url}\n${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { setStatus("✓ ${response.code} ${request.url} (no timeout?)") }
                }
            },
        )
    }

    private fun streamSse() {
        val url = "$BASE/sse?count=$SSE_EVENT_COUNT&duration=${SSE_DURATION_SECONDS}s"
        setStatus("→ GET $url (streaming…)")
        ioExecutor.execute {
            SseStreamingDemo.stream(
                client = client,
                url = url,
                onEvent = { data -> setStatus("SSE ⇢ $data") },
                onComplete = { count -> setStatus("✓ SSE complete ($count events)") },
                onError = { message -> setStatus("✗ SSE\n$message") },
            )
        }
    }

    private fun toggleSync(button: Button) {
        syncing = !syncing
        if (syncing) {
            syncCount = 0
            button.text = STOP_SYNC_LABEL
            syncHandler.post(syncRunnable)
        } else {
            button.text = START_SYNC_LABEL
            syncHandler.removeCallbacks(syncRunnable)
            setStatus("Periodic sync stopped after $syncCount requests")
        }
    }

    private fun compressedGet(encoding: String) {
        dispatch(Request.Builder().url("$BASE/get").header("Accept-Encoding", encoding).build())
    }

    private fun gzipPostRequest() {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8("""{"hello":"gzip"}""") }
        dispatch(
            Request.Builder()
                .url("$BASE/status/200")
                .header("Content-Encoding", "gzip")
                .post(compressed.readByteString().toRequestBody(JSON))
                .build(),
        )
    }

    private fun rawPlatformRequest(path: String = "raw", headers: Map<String, String> = emptyMap()) {
        val url = "$BASE/anything/platform/$path"
        setStatus("→ GET $url (HttpURLConnection)")
        ioExecutor.execute {
            PlatformHttpDemo.rawGet(
                url = url,
                headers = headers,
                onResult = { status -> setStatus("✓ $status $url (HttpURLConnection)") },
                onError = { message -> setStatus("✗ $url\n$message") },
            )
        }
    }

    private fun volleyPlatformRequest() {
        val url = "$BASE/anything/platform/volley"
        setStatus("→ GET $url (Volley)")
        PlatformHttpDemo.volleyGet(
            context = this,
            url = url,
            onResult = { status -> setStatus("✓ $status $url") },
            onError = { message -> setStatus("✗ $url\n$message") },
        )
    }

    private fun dispatch(request: Request) {
        setStatus("→ ${request.method} ${request.url}")
        ioExecutor.execute {
            client.newCall(request).enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        setStatus("✗ ${request.method} ${request.url}\n${e.message}")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            setStatus("✓ ${response.code} ${request.method} ${request.url}")
                        }
                    }
                },
            )
        }
    }

    private fun setStatus(text: String) {
        runOnUiThread { statusView.text = text }
    }

    private companion object {
        private const val EXTRA_REQUEST = "request"
        private const val BASE = "https://httpbingo.org"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val PADDING = 48
        private const val SECTION_TEXT_SIZE = 16f
        private const val IO_THREADS = 4
        private const val SYNC_INTERVAL_MS = 2_000L
        private const val TIMEOUT_SECONDS = 2L
        private const val SSE_EVENT_COUNT = 5
        private const val SSE_DURATION_SECONDS = 5
        private const val LARGE_JSON_BYTES = 255_000
        private const val IMAGE_WIDTH = 96
        private const val IMAGE_HEIGHT = 64
        private const val PNG_QUALITY = 100
        private const val BMP_HEADER_BYTES = 54
        private const val BMP_INFO_HEADER_BYTES = 40
        private const val BMP_BITS_PER_PIXEL: Short = 24
        private const val BMP_SQUARE = 8
        private const val START_SYNC_LABEL = "Start periodic sync"
        private const val STOP_SYNC_LABEL = "Stop periodic sync"
    }
}
