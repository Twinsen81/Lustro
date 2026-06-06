package io.github.twinsen81.lustro

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the [DebugResponse] companion factories. */
class DebugResponseTest {
    private fun DebugResponse.bodyString(): String = body.toString(Charsets.UTF_8)

    @Test
    fun `ok defaults to 200 json`() {
        val res = DebugResponse.ok("""{"a":1}""")
        assertEquals(200, res.status)
        assertEquals(MediaType.JSON, res.contentType)
        assertEquals("""{"a":1}""", res.bodyString())
        assertTrue(res.headers.isEmpty())
    }

    @Test
    fun `ok honors custom status`() {
        val res = DebugResponse.ok("""{}""", status = 201)
        assertEquals(201, res.status)
    }

    @Test
    fun `json builds body via StringBuilder lambda`() {
        val res = DebugResponse.json { append("{\"status\":\"ok\"}") }
        assertEquals(200, res.status)
        assertEquals(MediaType.JSON, res.contentType)
        assertEquals("""{"status":"ok"}""", res.bodyString())
    }

    @Test
    fun `text defaults to text plain utf-8`() {
        val res = DebugResponse.text("hello")
        assertEquals(200, res.status)
        assertEquals(MediaType.TEXT, res.contentType)
        assertEquals("hello", res.bodyString())
    }

    @Test
    fun `text honors custom content type and status`() {
        val html = MediaType.parse("text/html; charset=utf-8")!!
        val res = DebugResponse.text("<p>hi</p>", status = 202, contentType = html)
        assertEquals(202, res.status)
        assertEquals(html, res.contentType)
    }

    @Test
    fun `bytes defaults to octet-stream and preserves raw bytes`() {
        val payload = byteArrayOf(0, 1, 2, 3, -1)
        val res = DebugResponse.bytes(payload)
        assertEquals(200, res.status)
        assertEquals(MediaType.OCTET_STREAM, res.contentType)
        assertArrayEquals(payload, res.body)
    }

    @Test
    fun `bytes honors custom content type, status, and headers`() {
        val headers = Headers.of("X-Custom" to "v")
        val res =
            DebugResponse.bytes(
                body = byteArrayOf(9),
                contentType = MediaType.JSON,
                status = 206,
                headers = headers,
            )
        assertEquals(206, res.status)
        assertEquals(MediaType.JSON, res.contentType)
        assertEquals("v", res.headers.get("X-Custom"))
    }

    @Test
    fun `notFound is a 404 enveloped error with default message`() {
        val res = DebugResponse.notFound()
        assertEquals(404, res.status)
        assertEquals(MediaType.JSON, res.contentType)
        val json = JSONObject(res.bodyString())
        assertEquals("not_found", json.getString("error"))
        assertEquals("Not found", json.getString("message"))
        assertFalse(json.has("code"))
        assertFalse(json.has("field"))
        assertFalse(json.has("hint"))
    }

    @Test
    fun `notFound honors custom message`() {
        val json = JSONObject(DebugResponse.notFound("Transaction not found").bodyString())
        assertEquals("Transaction not found", json.getString("message"))
    }

    @Test
    fun `error defaults to 400 bad_request`() {
        val res = DebugResponse.error("bad input")
        assertEquals(400, res.status)
        val json = JSONObject(res.bodyString())
        assertEquals("bad_request", json.getString("error"))
        assertEquals("bad input", json.getString("message"))
    }

    @Test
    fun `error omits null optional keys but includes provided ones`() {
        val withAll = JSONObject(
            DebugResponse.error(
                message = "boom",
                status = 422,
                code = "C1",
                field = "name",
                hint = "try again",
            ).bodyString(),
        )
        assertEquals("error", withAll.getString("error")) // 422 -> generic "error"
        assertEquals("boom", withAll.getString("message"))
        assertEquals("C1", withAll.getString("code"))
        assertEquals("name", withAll.getString("field"))
        assertEquals("try again", withAll.getString("hint"))

        val onlyCode = JSONObject(DebugResponse.error("m", code = "X").bodyString())
        assertTrue(onlyCode.has("code"))
        assertFalse(onlyCode.has("field"))
        assertFalse(onlyCode.has("hint"))
    }

    @Test
    fun `error escapes string values for json`() {
        val res = DebugResponse.error(message = "he said \"hi\"\nbye")
        val json = JSONObject(res.bodyString())
        // org.json parses the escapes back into the original chars.
        assertEquals("he said \"hi\"\nbye", json.getString("message"))
    }

    @Test
    fun `status to error type mapping`() {
        val cases =
            mapOf(
                400 to "bad_request",
                401 to "unauthorized",
                403 to "forbidden",
                404 to "not_found",
                405 to "method_not_allowed",
                413 to "payload_too_large",
                500 to "internal_error",
                503 to "unavailable",
                504 to "timeout",
                418 to "error",
            )
        for ((status, type) in cases) {
            val json = JSONObject(DebugResponse.error("m", status = status).bodyString())
            assertEquals("status $status", type, json.getString("error"))
            assertEquals(status, DebugResponse.error("m", status = status).status)
        }
    }
}
