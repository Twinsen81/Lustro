package io.github.twinsen81.lustro.sample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoRequestHooksTest {
    @Test
    fun `debug demo requests cover every additional network bucket`() {
        val base = "https://httpbingo.org"
        val requests = LustroBootstrap.extraDemoRequests(base)

        assertEquals(
            listOf(
                "GET /status/304",
                "GET /status/400",
                "GET /status/401",
                "GET /status/404",
                "GET /status/429",
                "GET /status/503",
                "GET /bearer",
                "GET /image/png",
                "GET /anything/other",
                "HEAD /status/204",
                "GET https://127.0.0.1:1/lustro-error",
            ),
            requests.map { it.label },
        )
        assertEquals(
            listOf("GET", "GET", "GET", "GET", "GET", "GET", "GET", "GET", "GET", "HEAD", "GET"),
            requests.map { it.method },
        )
        assertEquals("$base/status/304", requests[0].buildRequest().url.toString())
        assertEquals("HEAD", requests[9].buildRequest().method)
        assertEquals("https://127.0.0.1:1/lustro-error", requests[10].buildRequest().url.toString())
    }

    @Test
    fun `sample classifier labels the debug fixture categories`() {
        val classifier = SampleNetworkClassifier()

        assertEquals(listOf("Read"), classifier.classify("https://httpbingo.org/get"))
        assertEquals(listOf("Write"), classifier.classify("https://httpbingo.org/post"))
        assertEquals(listOf("Auth"), classifier.classify("https://httpbingo.org/bearer"))
        assertEquals(listOf("Media"), classifier.classify("https://httpbingo.org/image/png"))
        assertEquals(listOf("Streaming"), classifier.classify("https://httpbingo.org/sse"))
        assertEquals(listOf("Sync"), classifier.classify("https://httpbingo.org/anything/sync"))
        assertEquals(listOf("Platform"), classifier.classify("https://httpbingo.org/anything/platform/raw"))
        assertEquals(listOf("Errors"), classifier.classify("https://httpbingo.org/status/500"))
        assertEquals(listOf("Slow"), classifier.classify("https://httpbingo.org/delay/3"))
        assertEquals(listOf("Other"), classifier.classify("https://httpbingo.org/anything/other"))
        assertTrue(classifier.classify("https://127.0.0.1:1/lustro-error").isEmpty())
    }
}

