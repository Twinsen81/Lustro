# Other HTTP clients

Lustro has adapters for OkHttp and for platform `HttpURLConnection`. For another HTTP client,
such as Apache HttpClient or a client of your own, write a small adapter that reports each
request to `lustro.networkCaptureSink()`:

```kotlin
val sink = lustro.networkCaptureSink()

val started = SystemClock.elapsedRealtime()
val id = sink.beginRequest(url, "PROPFIND", requestHeaders, CapturedBody(requestXml), MediaType.parse("application/xml"))
try {
    val response = client.execute(request)
    sink.completeRequest(
        id,
        CapturedResponse.Builder(response.status, SystemClock.elapsedRealtime() - started)
            .headers(response.headers)
            .body(CapturedBody(response.text))
            .build(),
    )
} catch (e: IOException) {
    sink.failRequest(id, SystemClock.elapsedRealtime() - started, e.message ?: e.javaClass.simpleName)
    throw e
}
```

- **The same rules as OkHttp's capture.** The redactor, the classifier, and the
    [capture filter](okhttp.md) apply, and **Pause** stops the recording. A request that
    the filter leaves out, or that starts while capture is paused, is not recorded, and the calls
    for it do nothing.
- **The body is what you report.** Cut a large body before you report it, and set `truncated`
    and `byteSize` on the `CapturedBody`. For a body that your code reads later, such as a
    download, call `completeRequest` with a response built with `.complete(false)` when the
    headers arrive. When the body ends, call `completeRequest` again, with the body and
    `.complete(true)`.
- **Mock rules and the throttle are your adapter's.** `sink.findMockRule(url, method)` returns
    the rule that matches, and counts a hit on it. To answer the request from the rule, report the
    response with `.mockedBy(rule)`, so the Network tab can say which rule answered it. Nothing holds
    the request for the throttle.
- **A rule can fail the request.** When `rule.failure` is not `null`, don't answer from the rule:
    throw your client's exception for that [connection error](mocks.md), and ignore the rule's
    status, headers, and body. Report it with `failRequest(id, durationMs, error, rule)`, so the
    Network tab shows the request as failed and mocked by that rule. Throw an `IOException` for a
    name that your adapter does not know, as a later release can add names:

    ```kotlin
    val rule = sink.findMockRule(url, method)
    rule?.failure?.let { failure ->
        val error = when (failure) {
            "timeout" -> SocketTimeoutException("timeout")
            "unknownHost" -> UnknownHostException("Unable to resolve host")
            "connectionRefused" -> ConnectException("Connection refused")
            "connectionReset" -> SocketException("Connection reset")
            "tls" -> SSLHandshakeException("SSL handshake aborted")
            else -> IOException("Mock rule failure: $failure")
        }
        sink.failRequest(id, 0, error.message ?: failure, rule)
        throw error
    }
    ```
- **No call throws.** The capture filter runs on your thread. Redaction and storing run on a
    background thread, except during a burst of large bodies, when a call captures on your thread
    until the background thread catches up, as OkHttp capture does.
- In `lustro-noop`, the sink records nothing and finds no rule.
