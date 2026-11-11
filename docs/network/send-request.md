# Send Request

The Network tab can send a request through the app's own OkHttp clients. **+ New request**, next
to the search field, opens an empty Send form in the right pane. **Edit and resend**, in the detail
of a request, opens it filled from that request: method, URL, headers, and body. The form says
where the request will go before you send it, and shows the status when the response comes back.
Sending is **synchronous**: the HTTP call blocks until the sender returns the final result (within
the per-request timeout), so you get a single round-trip outcome instead of having to poll.

**Senders.** The app registers each client the form can send through, with a name and an optional
base URL. An app often has several clients, with their own interceptors (one that signs requests
in, one for downloads) and their own hosts, and a request sent through the wrong one goes to the
wrong host or lacks its auth:

```kotlin
NetworkDebugTab.create(
    senders = listOf(
        OkHttpSender.create("API (signed in)", baseUrl = "https://api.example.com/v1") { apiClient },
        OkHttpSender.create("CDN", baseUrl = "https://cdn.example.com") { cdnClient },
        OkHttpSender.create("Any host") { plainClient },
    ),
)
```

The form lists them in that order, and the first is the default. An absolute URL is sent as
entered. A relative one is appended to the base URL of the chosen sender, path and all, so `/items`
through "API (signed in)" goes to `https://api.example.com/v1/items`; a sender without a base URL
takes absolute URLs only. Names must be unique.

**Lustro asks for each client only when you send.** The lambda runs at the first send through
that sender, off the main thread, and Lustro keeps the client it returns. So a sender can be a
client that carries `lustro.networkInterceptor()`, although that client has to be built after
Lustro, and the requests you send are then captured like the app's own. The form then has a
**Show the response** link to the new row; through a client without the interceptor, you get the
status only.

`senderClient = client` is the short form for one sender, named "Default", whose relative URLs
resolve against `DebugConfig.appServerBaseUrl`. With neither, the Send form and its route are
hidden. Requests aimed at the debug server's own bind host:port are rejected.

**What the form promises.** Lustro controls only which client sends. Which interceptors run
(auth, logging, capture) depends on how the app built that client. A sender whose client signs
requests in lets anyone who has the Lustro token send requests as the signed-in user. The server
is loopback-only and needs the token, but register such a sender only in builds you trust with
that.

**Edit and resend** leaves out the headers that OkHttp sets itself, such as `Host`,
`Content-Length`, and `Accept-Encoding`: sent by hand, an `Accept-Encoding` turns off OkHttp's gzip
decoding. A value that Lustro redacted is `[REDACTED]` in the form, and the form says so: replace
it before you send.

The form reports only the status and outcome, so the sender reads at most
`DebugConfig.maxBodyCaptureBytes` of the response body and then closes the response: a large
download or an endless stream cannot exhaust the app's heap. A send still running when the
per-request timeout answers `504` has its call cancelled right after.
