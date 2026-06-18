package io.github.twinsen81.lustro.sample

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Launcher activity with one button per sample HTTP request shape. */
public class MainActivity : Activity() {
    private val ioExecutor = Executors.newFixedThreadPool(IO_THREADS)
    private val syncHandler = Handler(Looper.getMainLooper())
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
                text = "Lustro sample"
                textSize = TITLE_TEXT_SIZE
            },
        )
        root.addView(
            TextView(this).apply {
                text =
                    "Each button fires a request through the Lustro-instrumented " +
                        "OkHttpClient — or, under Platform HTTP, the java.net stack. " +
                        "Open the debug console (debug build) and watch the Network " +
                        "tab. Flip the Overwrite toggle while a periodic sync runs to " +
                        "collapse repeats into one row, or use the Mock Rules panel to " +
                        "inject error responses."
                setPadding(0, PADDING / 2, 0, PADDING)
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

        addSection(root, "Periodic sync")
        val syncButton = addButton(root, START_SYNC_LABEL)
        syncButton.setOnClickListener { toggleSync(syncButton) }

        addSection(root, "Platform HTTP (non-OkHttp)")
        addButton(root, "Raw HttpURLConnection GET") { rawPlatformRequest() }
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

        return ScrollView(this).apply { addView(root) }
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
        return button
    }

    private fun getRequest() {
        dispatch(Request.Builder().url("$BASE/get").get().build())
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

    private fun rawPlatformRequest() {
        val url = "$BASE/anything/platform/raw"
        setStatus("→ GET $url (HttpURLConnection)")
        ioExecutor.execute {
            PlatformHttpDemo.rawGet(
                url = url,
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
        private const val BASE = "https://httpbingo.org"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val PADDING = 48
        private const val TITLE_TEXT_SIZE = 22f
        private const val SECTION_TEXT_SIZE = 16f
        private const val IO_THREADS = 4
        private const val SYNC_INTERVAL_MS = 2_000L
        private const val TIMEOUT_SECONDS = 2L
        private const val SSE_EVENT_COUNT = 5
        private const val SSE_DURATION_SECONDS = 5
        private const val START_SYNC_LABEL = "Start periodic sync"
        private const val STOP_SYNC_LABEL = "Stop periodic sync"
    }
}
