package io.github.twinsen81.lustro.sample

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
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
 * The sample's HTTP requests. Each one goes through the app's OkHttpClient, which
 * carries the Lustro interceptor, or, for the platform ones, through the
 * java.net stack. Each reports its result to a [Feedback].
 */
internal class DemoRequests(
    private val context: Context,
    private val client: () -> OkHttpClient,
    private val io: Executor,
) {
    fun get(path: String, feedback: Feedback) = send(Request.Builder().url("$BASE/$path").get().build(), feedback)

    fun post(feedback: Feedback) = send(Request.Builder().url("$BASE/post").post("""{"hello":"world"}""".toRequestBody(JSON)).build(), feedback)

    fun put(feedback: Feedback) = send(Request.Builder().url("$BASE/put").put("""{"updated":true}""".toRequestBody(JSON)).build(), feedback)

    fun patch(feedback: Feedback) = send(Request.Builder().url("$BASE/patch").patch("""{"patched":true}""".toRequestBody(JSON)).build(), feedback)

    fun delete(feedback: Feedback) = send(Request.Builder().url("$BASE/delete").delete().build(), feedback)

    // Per-call timeout: it bounds the whole call without a separate client.
    fun timeout(feedback: Feedback) = send(Request.Builder().url("$BASE/delay/10").get().build(), feedback, budgetSeconds = TIMEOUT_SECONDS)

    fun formPost(feedback: Feedback) {
        val form =
            FormBody.Builder()
                .add("name", "Ada Lovelace")
                .add("email", "ada@example.com")
                .add("password", "not-a-real-one")
                .add("topic", "engines")
                .add("topic", "poetry")
                .add("note", "café & crème, 100% <real>")
                .build()
        send(Request.Builder().url("$BASE/post").post(form).build(), feedback)
    }

    fun pngPost(feedback: Feedback) = imagePost(pngBody(), "image/png", feedback)

    fun bmpPost(feedback: Feedback) = imagePost(bmpBody(), "image/bmp", feedback)

    // Just under the default capture cap of 256 KiB, so it is kept whole.
    fun largeJsonPost(feedback: Feedback) {
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
        send(Request.Builder().url("$BASE/anything").post(body.toRequestBody(JSON)).build(), feedback)
    }

    // OkHttp decompresses a response on its own only when it asked for gzip
    // itself. An app that sets Accept-Encoding gets the compressed bytes, and so
    // does every interceptor on its way back.
    fun compressedGet(encoding: String, feedback: Feedback) =
        send(Request.Builder().url("$BASE/get").header("Accept-Encoding", encoding).build(), feedback, suffix = "($encoding)")

    fun gzipPost(feedback: Feedback) {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { it.writeUtf8("""{"hello":"gzip"}""") }
        send(
            Request.Builder()
                .url("$BASE/status/200")
                .header("Content-Encoding", "gzip")
                .post(compressed.readByteString().toRequestBody(JSON))
                .build(),
            feedback,
            suffix = "(gzip body)",
        )
    }

    fun streamSse(feedback: Feedback) {
        val url = "$BASE/sse?count=$SSE_EVENT_COUNT&duration=${SSE_DURATION_SECONDS}s"
        feedback.running("GET /sse", "Streaming")
        io.execute {
            SseStreamingDemo.stream(
                client = client(),
                url = url,
                onEvent = { data -> feedback.running("SSE event: $data") },
                onComplete = { count -> feedback.ok("SSE complete: $count events") },
                onError = { message -> feedback.failed("SSE failed", message) },
            )
        }
    }

    fun rawPlatformGet(feedback: Feedback, path: String = "raw", headers: Map<String, String> = emptyMap()) {
        val url = "$BASE/anything/platform/$path"
        val what = "${describe("GET", url)} (HttpURLConnection)"
        feedback.running(what)
        io.execute {
            PlatformHttpDemo.rawGet(
                url = url,
                headers = headers,
                onResult = { code -> feedback.response(code, "$code $what") },
                onError = { message -> feedback.failed(what, message) },
            )
        }
    }

    fun volleyGet(feedback: Feedback) {
        val url = "$BASE/anything/platform/volley"
        val what = "${describe("GET", url)} (Volley)"
        feedback.running(what)
        PlatformHttpDemo.volleyGet(
            context = context,
            url = url,
            onResult = { chars -> feedback.ok(what, "$chars chars") },
            onError = { message -> feedback.failed(what, message) },
        )
    }

    fun send(request: Request, feedback: Feedback, budgetSeconds: Long? = null, suffix: String? = null) {
        val what = describe(request.method, request.url.toString()) + (suffix?.let { " $it" } ?: "")
        feedback.running(what, budgetSeconds?.let { "$it s budget" })
        io.execute {
            val call = client().newCall(request)
            budgetSeconds?.let { call.timeout().timeout(it, TimeUnit.SECONDS) }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        feedback.failed(what, e.message ?: e.javaClass.simpleName)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { feedback.response(it.code, "${it.code} $what") }
                    }
                },
            )
        }
    }

    private fun imagePost(body: ByteArray, contentType: String, feedback: Feedback) =
        send(Request.Builder().url("$BASE/anything").post(body.toRequestBody(contentType.toMediaType())).build(), feedback, suffix = "($contentType)")

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

    companion object {
        const val BASE: String = "https://httpbingo.org"
        private val JSON = "application/json; charset=utf-8".toMediaType()
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

        /** `GET /get` for the fixture host, the whole URL for any other host. */
        fun describe(method: String, url: String): String = "$method ${if (url.startsWith(BASE)) url.removePrefix(BASE) else url}"
    }
}
