package io.github.twinsen81.lustro.internal.network

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the golden HAR fixture to what [HarExport] writes, so the fixture that
 * clients test against can't drift from the server.
 */
class HarExportGoldenTest {
    private val gif =
        byteArrayOf(
            71, 73, 70, 56, 57, 97, 1, 0, 1, 0, -128, 0, 0, -1, -1, -1, 0, 0, 0, 33, -7, 4, 1, 0, 0, 0, 0,
            44, 0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 68, 1, 0, 59,
        )

    private val transactions =
        listOf(
            NetworkTransaction(
                id = "tx_4b1d77a0",
                startedAt = 1790605329003,
                completedAt = 1790605329325,
                durationMs = 322,
                categories = listOf("api"),
                method = "POST",
                url = "https://api.example.com/v1/orders",
                requestHeaders =
                    mapOf("Content-Type" to "application/json; charset=utf-8", "Authorization" to "[REDACTED]"),
                requestBody = """{"sku":"A-1","quantity":2}""",
                requestContentType = "application/json; charset=utf-8",
                requestBodyBytes = 26,
                protocol = "h2",
                statusCode = 201,
                responseHeaders = mapOf("Content-Type" to "application/json; charset=utf-8", "Location" to "/v1/orders/9c2e"),
                responseBody = """{"id":"9c2e","sku":"A-1","quantity":2,"status":"open"}""",
                responseContentType = "application/json; charset=utf-8",
                responseBodyBytes = 54,
                responseComplete = true,
            ),
            NetworkTransaction(
                id = "tx_77e2c014",
                startedAt = 1790605331781,
                completedAt = 1790605331822,
                durationMs = 41,
                categories = listOf("api"),
                method = "GET",
                url = "https://api.example.com/v1/orders/77e2",
                requestHeaders = mapOf("Accept" to "application/json", "Authorization" to "[REDACTED]"),
                requestBodyBytes = 0,
                statusCode = 500,
                responseHeaders = mapOf("Content-Type" to "application/json"),
                responseBody = """{"error":"boom"}""",
                responseContentType = "application/json",
                responseBodyBytes = 16,
                responseComplete = true,
                isMocked = true,
            ),
            NetworkTransaction(
                id = "tx_e6b0d413",
                startedAt = 1790605333140,
                completedAt = 1790605333175,
                durationMs = 35,
                categories = listOf("media"),
                method = "GET",
                url = "https://cdn.example.com/pixel.gif",
                requestHeaders = mapOf("Accept" to "image/*"),
                protocol = "h2",
                statusCode = 200,
                responseHeaders = mapOf("Content-Type" to "image/gif", "Content-Length" to "43"),
                responseBinaryBody = gif,
                responseContentType = "image/gif",
                responseBodyBytes = 43,
                responseComplete = true,
            ),
            NetworkTransaction(
                id = "tx_5e8a2f61",
                startedAt = 1790605334022,
                completedAt = 1790605335232,
                durationMs = 1210,
                categories = listOf("api"),
                method = "GET",
                url = "https://api.example.com/v1/catalog?page=2&tag=new%20in",
                requestHeaders = mapOf("Accept" to "application/json"),
                protocol = "h2",
                statusCode = 200,
                responseHeaders = mapOf("Content-Type" to "application/json; charset=utf-8"),
                responseBody = """[{"sku":"A-1","name":"Desk lamp"},{"sku":"A-2","na""",
                responseBodyTruncated = true,
                responseContentType = "application/json; charset=utf-8",
                responseBodyBytes = 524288,
                responseComplete = true,
            ),
            NetworkTransaction(
                id = "tx_d03a9b44",
                startedAt = 1790605335917,
                completedAt = 1790605335925,
                durationMs = 8,
                method = "GET",
                url = "https://telemetry.example.com/v1/events",
                requestHeaders = mapOf("Accept" to "application/json"),
                responseComplete = true,
                error = "java.net.UnknownHostException: Unable to resolve host \"telemetry.example.com\"",
            ),
        )

    @Test
    fun `the golden HAR fixture is what the export writes`() {
        val written = HarExport.write(transactions, creatorVersion = "0.1.0")
        assertEquals(
            "$GOLDEN differs from:\n$written\n",
            canonical(JSONObject(File(GOLDEN).readText())),
            canonical(JSONObject(written)),
        )
    }

    // Key order is not part of JSON, so objects compare as sorted maps.
    private fun canonical(value: Any?): Any? =
        when (value) {
            is JSONObject -> value.keys().asSequence().associateWith { canonical(value.get(it)) }.toSortedMap()
            is JSONArray -> (0 until value.length()).map { canonical(value.get(it)) }
            else -> value
        }

    private companion object {
        // Unit tests run in the module directory.
        const val GOLDEN = "../wire-protocol/v1/golden/export-har.json"
    }
}
