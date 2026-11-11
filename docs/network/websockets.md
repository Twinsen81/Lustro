# WebSocket messages

The interceptor shows only the handshake of a WebSocket, as a request with the status `101`,
because OkHttp sends nothing else through interceptors. To see the messages, create your sockets
with the factory that `lustro.webSocketFactory(...)` returns:

```kotlin
val sockets: WebSocket.Factory = lustro.webSocketFactory(httpClient)

val socket = sockets.newWebSocket(request, listener)
```

`OkHttpClient` is a `WebSocket.Factory`, and the returned factory wraps the one you pass. The
Network tab's **WebSockets** view then lists each connection with its state, its close code or the
exception it failed with, and a log of its events in order: each message, text or binary, sent or
received, and the open, the close frames, `cancel()`, and the failure. A click on a message shows its
payload in the viewers that bodies use, or as a hex dump for a binary one. When `httpClient` also
carries `lustro.networkInterceptor()`, the connection and its handshake request link to each other.
In release, the no-op artifact returns `httpClient` itself.

**A socket from the factory behaves as one from the client.** Your listener gets the same calls,
with the socket that `newWebSocket` returned. `send`, `close`, and `queueSize` return what OkHttp
returns, and what your listener throws reaches OkHttp. Capture adds a small, constant cost to each
message on the thread that sends or receives it: it cuts, redacts, and stores the payload on a
thread of its own. When that thread is behind, capture drops messages and counts them, and the view
says how many. It never makes the socket wait.

**A sent message is not a delivered message.** `send()` returns `true` when OkHttp put the message
in its queue. The log shows that return value, and it marks a message that `send()` refused as not
sent.

**Give the factory to a library that takes one.** These accept a `WebSocket.Factory`:

| Library | Where the factory goes |
| --- | --- |
| Ktor client, OkHttp engine | `HttpClient(OkHttp) { engine { preconfigured = httpClient; webSocketFactory = sockets } }` |
| Apollo Kotlin 3 and 4 | `ApolloClient.Builder().webSocketEngine(DefaultWebSocketEngine(sockets))` |
| Apollo Kotlin 5 | `WebSocketNetworkTransport.Builder().webSocketEngine(WebSocketEngine(sockets))`, passed to `subscriptionNetworkTransport(...)` after `okHttpClient(...)` |
| socket.io-client-java | `IO.Options().apply { webSocketFactory = sockets; callFactory = httpClient }` |
| Scarlet 0.1.x | `OkHttpWebSocket.Factory(establisher)`, where your `OkHttpWebSocket.ConnectionEstablisher` calls `sockets.newWebSocket(request, listener)` |

A library that takes only an `OkHttpClient`, or that builds its own, can't use the factory. Lustro
then shows only the handshake, when that client carries the interceptor.

**The hooks you already have apply to sockets.** The [capture filter](okhttp.md) is
asked once for each socket, when the app creates it, with the request you passed to
`newWebSocket`; a socket it skips is not listed, and neither is its handshake. The classifier
labels a connection by its URL. **Pause** stops the recording of messages, and **Clear** removes
the connections. A socket lives for a long time, so one that is still open is listed again with
its next message, after a clear and after a pause. A close or a failure of a listed connection is
recorded while capture is paused, so a connection never shows as open after it closed.

**Redaction.** The URL and the handshake headers go through the `Redactor`, as a request's do. The
text of a message goes through `redactBody`, with no content type, so the default redactor masks a
`token` or a `password` field in a JSON message. Then `Redactor.redactWebSocketText` gets the
result, and can mask more or keep the payload out of the store:

```kotlin
object MyRedactor : Redactor by DefaultRedactor {
    override fun redactWebSocketText(text: String, message: WebSocketMessageInfo): String? =
        if (message.isOutgoing && text.startsWith("AUTH ")) null else text // null stores only the size
}
```

A binary message is stored as it arrived, because a redactor that matches on names can't read it.
Override `redactWebSocketBinary` to change its bytes, or return `null` to store only its size.

**Limits.** A long-lived socket must not fill the heap. A payload is cut at
`DebugConfig.maxBodyCaptureBytes` (256 KB), and the log keeps its whole size. A connection keeps its
last `maxWebSocketEvents` events (1000), Lustro keeps `maxCaptureWebSockets` connections (100), and
all stored payloads together stay under `webSocketCaptureBudgetBytes` (16 MB): past it, the
connection that holds the most loses its oldest events. The view says how many events a log no
longer has.

**Not covered.** Lustro records complete messages, as OkHttp's public API gives them. It does not
show frames, fragmentation, compression, or the ping and pong frames that OkHttp sends on its own,
and it records only OkHttp sockets.
