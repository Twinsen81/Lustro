package io.github.twinsen81.lustro.sample

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.Executors
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Demo activity for the Lustro sample.
 *
 * It is a plain launcher activity (no resource files) with one button per HTTP
 * shape. Every button fires a request through [SampleApplication.httpClient] —
 * the OkHttp client that has the Lustro interceptor installed — so the traffic
 * shows up in the Network tab of the debug console (debug variant). The buttons
 * cover GET, POST, PUT, PATCH, DELETE, a mocked 500, and a slow/throttled call.
 *
 * Demo traffic hits the public httpbingo.org fixture host. It is auth-free and
 * httpbin-compatible; Lustro's own debug API stays token-authenticated and is
 * unrelated to this traffic.
 */
public class MainActivity : Activity() {
    private val ioExecutor = Executors.newFixedThreadPool(4)
    private lateinit var statusView: TextView

    private val client: OkHttpClient
        get() = (application as SampleApplication).httpClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onDestroy() {
        super.onDestroy()
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
                        "OkHttpClient. Open the debug console (debug build) to watch " +
                        "the traffic in the Network tab."
                setPadding(0, PADDING / 2, 0, PADDING)
            },
        )

        addButton(root, "GET /get") { getRequest() }
        addButton(root, "POST /post") { postRequest() }
        addButton(root, "PUT /put") { putRequest() }
        addButton(root, "PATCH /patch") { patchRequest() }
        addButton(root, "DELETE /delete") { deleteRequest() }
        addButton(root, "Mocked 500 (/status/500)") { errorRequest() }
        addButton(root, "Slow call (/delay/3)") { slowRequest() }

        statusView =
            TextView(this).apply {
                setPadding(0, PADDING, 0, 0)
                text = "Ready."
            }
        root.addView(statusView)

        return ScrollView(this).apply { addView(root) }
    }

    private fun addButton(parent: LinearLayout, label: String, onClick: () -> Unit) {
        parent.addView(
            Button(this).apply {
                text = label
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener { onClick() }
            },
        )
    }

    // region Requests

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

    /**
     * A request that returns a 500. The fixture host returns it directly; in the
     * debug console you can also add a mock rule to short-circuit any request to
     * a synthetic 500, which is the "mocked 500" the Network tab demonstrates.
     */
    private fun errorRequest() {
        dispatch(Request.Builder().url("$BASE/status/500").get().build())
    }

    /** A slow response (server delays 3s). Combine with the Network tab's throttle to test spinners. */
    private fun slowRequest() {
        dispatch(Request.Builder().url("$BASE/delay/3").get().build())
    }

    // endregion

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
    }
}
