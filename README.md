<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="art/lustro-wordmark-dark.png">
    <img src="art/lustro-wordmark.png" alt="Lustro" width="420">
  </picture>
</p>

<p align="center"><b>A browser-based, agent-friendly debugging library for Android.</b></p>

Lustro embeds a small web server in your app's debug builds and serves its tools as tabs you
open in a desktop browser — so you inspect on a full screen instead of a cramped on-device
overlay. The built-in network inspector captures traffic and lets you mock responses, throttle
connections, and replay requests, and it shows the messages of WebSocket connections. Every tab is
also a JSON API, so AI agents and scripts can drive Lustro directly rather than scraping HTML.

It's built as an extensible tab platform: the network inspector ships in the box, and you add
your own tabs against a stable plugin contract.

> **Status:** early development. Pre-1.0 and not yet published — APIs and the wire protocol may
> change between releases. Only the `0.1.0-SNAPSHOT` line exists today (Sonatype Central
> snapshots); there is no stable release yet.

## With a coding agent

A coding agent can add Lustro from this README. Give it the repository, for example "Add Lustro
to this app, following https://github.com/Twinsen81/Lustro". [`llms.txt`](llms.txt) summarizes
Lustro for agents and links the rest of the docs, and [`context7.json`](context7.json) tells
[Context7](https://context7.com) which parts of this repository to index. To use a running Lustro
from an agent or a script, see [docs/AGENTS.md](docs/AGENTS.md).

## Install

Lustro ships as two interchangeable runtime artifacts that you split by build variant:

- `io.github.twinsen81:lustro` — the real debug runtime (the embedded server, capture, and UI).
- `io.github.twinsen81:lustro-noop` — a release-safe no-op AAR that mirrors the same public
  facades with empty bodies, so your integration code compiles and runs in release with **no
  server, no capture, and no open socket**.

Both modules declare the same Gradle capability (`io.github.twinsen81:lustro-runtime`), so you
**cannot** resolve both on one configuration — you must split them by variant
(`debugImplementation` / `releaseImplementation`):

```toml
# gradle/libs.versions.toml
[versions]
lustro = "0.1.0-SNAPSHOT"

[libraries]
lustro      = { group = "io.github.twinsen81", name = "lustro",      version.ref = "lustro" }
lustro-noop = { group = "io.github.twinsen81", name = "lustro-noop", version.ref = "lustro" }
```

```kotlin
// build.gradle.kts (app module)
dependencies {
    debugImplementation(libs.lustro)
    releaseImplementation(libs.lustro.noop)
}
```

`-SNAPSHOT` versions resolve from the Sonatype Central snapshots repository, so add it (only
needed while Lustro is pre-release):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
}
```

Lustro declares the `INTERNET` permission in its manifest.

### Minimum versions

Adding Lustro does not raise the versions of your app's own dependencies. Each published
artifact declares the oldest version of a dependency that has the APIs it uses, and Gradle
keeps your newer one.

| | Minimum | Notes |
| --- | --- | --- |
| Android Gradle Plugin | 8.6 | The oldest that supports `compileSdk` 35. |
| `compileSdk` | 35 | |
| `minSdk` | 24 | |
| Kotlin | 2.0 | The artifacts carry Kotlin 2.0 metadata and need `kotlin-stdlib` 2.0.21 or newer. |
| OkHttp | 4.12.0 | 4.x or 5.x. CI runs Lustro's tests against OkHttp 5 as well. |
| `androidx.lifecycle` | 2.4.1 | `lifecycle-process` only, and only in `lustro`. |
| `kotlinx.coroutines` | none | Lustro has no coroutines dependency. `lustro-prefs` calls the app's own coroutines only for a registered DataStore. |
| `androidx.datastore` | none | `compileOnly` in `lustro-prefs` and `lustro-prefs-noop`. A registered `PrefsDataStore` needs DataStore 1.0, and a `bytes` value 1.1. |
| `androidx.sqlite` | none | `compileOnly` in `lustro-sqlite` and `lustro-sqlite-noop`. A registered `SqliteDb.support` needs `androidx.sqlite` 2.0, which Room brings. |

`lustro` also brings NanoHTTPD 2.3.1, its embedded server. CI builds a small app in
[`consumer-check/`](consumer-check/) with exactly these versions, and again on a current
toolchain, and fails when a published artifact would change any version the app has.

## Quick start

Build the runtime once (typically in your `Application`), register tabs, wire the OkHttp
interceptor, and start it:

```kotlin
lateinit var httpClient: OkHttpClient

val lustro = Lustro.builder(application)
    .addTab(
        NetworkDebugTab.create(
            // The console's Send form sends through httpClient. Lustro asks for it only when you
            // send a request, so it can be the client built below (see Send Request).
            senders = listOf(OkHttpSender.create("API", baseUrl = "https://api.example.com") { httpClient }),
        ),
    )
    .build()

httpClient = OkHttpClient.Builder()
    .addInterceptor(lustro.networkInterceptor())
    .build()

lustro.start()
```

If the app uses WebSockets, create them with `lustro.webSocketFactory(httpClient)` to see their
messages too: see [WebSocket messages](#websocket-messages).

Add every tab before `build()`. `start()` freezes the tab registry, and `addTab()` after it
throws `IllegalStateException`.
`start()` returns a `LustroStatus` (`ENABLED` once armed, `DISABLED` if it cannot start) and is
idempotent, as is `stop()`.

Call `start()` on the main thread, as `Application.onCreate()` does. It adds an observer to the
process lifecycle, which accepts observers only on the main thread, so from any other thread
`start()` returns `LustroStatus.DISABLED`.

Three things keep that snippet out of production, in order of how much they depend on you:

> **Your `DebugTab` subclasses must live in `src/debug`.** They are your code, so swapping
> `:lustro` for `:lustro-noop` cannot remove them from a release APK — whatever source set they
> sit in is compiled into that variant, taking the app internals they read with them. Lustro
> ships a published Android Lint check (`LustroDebugUsageInRelease`, severity ERROR) that
> **enforces** this: it flags a `DebugTab` subclass reachable from any source set other than
> `src/debug` (e.g. `src/main` or `src/release`). The builder call above is *not* flagged — it
> resolves to whichever facade the variant depends on. Putting the whole bootstrap under
> `src/debug/` too (as the [`:sample`](sample/) app does) is still the cleanest layout, because
> then release compiles none of it at all.

> **The release swap makes the API inert.** With `releaseImplementation(libs.lustro.noop)`, the
> `Lustro.builder(...)` / `.addTab(...)` / `start()` calls in the snippet compile unchanged and
> do nothing: no socket, no capture, `networkInterceptor()` forwards every request, and
> `webSocketFactory(client)` returns `client` itself.

> **The runtime refuses to start in a non-debuggable build.** If the real `:lustro` runtime ever
> reaches a build without `android:debuggable`, `start()` logs a WARN and returns
> `LustroStatus.DISABLED` without binding a socket — no Gradle wiring and no lint run required.
> An internal build that ships `android:debuggable="false"` but still wants the server can opt
> in with `DebugConfig.builder().allowNonDebuggableBuilds(true)`.

## Accessing the UI

The server is bound to loopback (`127.0.0.1`) on the device, so reach it from your desktop over
adb port forwarding:

1. Forward the port from your machine to the device:

   ```bash
   adb forward tcp:8080 tcp:8080
   ```

2. Open <http://localhost:8080> in a desktop browser.

3. The server is **token-authenticated** (always on). Read the token from logcat — Lustro logs
   one machine-parseable line at the `LustroToken` tag on every successful bind:

   ```bash
   adb logcat -s LustroToken
   # Lustro ready endpoint=http://127.0.0.1:8080 token=<token>
   ```

   Some devices drop Info logs: with `adb shell getprop log.tag` set to `E`, the line never
   reaches logcat. `adb shell setprop log.tag.LustroToken I` lets it through until the device
   restarts, and the line comes again when the app returns to the foreground. The token, and the
   host and port of the last bind, are also in the app's prefs, which a debuggable app lets you
   read: `adb shell run-as <package> cat shared_prefs/lustro_debug.xml`. The `lustro` CLI reads
   them on its own when the log has no line.

4. Authenticate the browser with that token, either by:
   - using the `lustro` CLI (`lustro open`, see [docs/AGENTS.md](docs/AGENTS.md)); or
   - appending `#lustro_token=<token>` to the URL **once**
     (`http://localhost:8080/#lustro_token=<token>`). The page posts the token to set an
     `HttpOnly; SameSite=Strict` cookie, then strips the fragment from the address bar.

The server runs only while the app is **foregrounded**. When the app goes to the background the
server drains in-flight requests and closes the socket; it rebinds when the app returns to the
foreground.

## OkHttp capture setup

`lustro.networkInterceptor()` returns an OkHttp **application** interceptor (despite the
"network" in the name — it is added with `addInterceptor`, not `addNetworkInterceptor`). It
feeds captured traffic into the registered `NetworkDebugTab`; if no network tab is registered it
is a pass-through.

```kotlin
OkHttpClient.Builder()
    .addInterceptor(authInterceptor)            // your interceptors that mutate URL/headers/body
    .addInterceptor(lustro.networkInterceptor()) // add Lustro AFTER them
    .build()
```

**Ordering matters.** Add Lustro's interceptor **after** any application interceptors that
rewrite the URL, headers, or body, so capture, mock matching, classification, and throttling all
see the final application-level request. As an application interceptor it sees one call, not each
request OkHttp sends for it: a redirect that OkHttp follows is one transaction, whose `url` is what
your code requested. The Network tab shows where the response came from (`finalUrl`), and each
redirect and authentication challenge on the way (`priorResponses`), with its status.

**Capture doesn't hold up the call.** The interceptor copies what it captures and returns;
redaction, classification, and storing run on a background thread, so a request shows up in the
Network tab a moment after it completes. During a burst of large bodies, once about 4 MB of
captured text is waiting, calls capture on their own thread until that one catches up, which
keeps memory bounded. A body that capture doesn't read whole, such as a download or a body past
the cap, reaches your code as it would without Lustro, and the request shows as in flight until
your code reads to its end or closes it, so its duration covers the whole body. An event stream
(`text/event-stream`) or a JSON stream (`application/x-ndjson`, JSON Lines, `application/json-seq`,
`application/stream+json`) can stay open with no end, so Lustro doesn't read it ahead: it captures
the stream as your code reads it, and the Network tab shows each event as it arrives.

**A request body is copied as OkHttp sends it.** Lustro doesn't write your request body a second
time to capture it, so the body is written as it would be without Lustro: an upload's progress
listener runs once, a file is read once, and a body that reads a stream once still works. The
request is listed with the body's size first, and its bytes once OkHttp has sent them; a request
that fails before it sends the body keeps the size alone. A mocked request isn't sent, so Lustro
writes its body once to capture it. A `FormBody` is in memory, and Lustro writes it once more
for capture, as it does the text parts of a `MultipartBody`. A request body with no
`Content-Type` is captured as text, as platform capture does: apps send a message or JSON
without one.

**Compressed bodies are captured decoded.** OkHttp inflates a gzip response on its own only when
it added `Accept-Encoding` itself. If your app sets that header, or compresses request bodies in an
interceptor added before Lustro's, the interceptor sees the compressed bytes. Lustro inflates a body
in `gzip`, `x-gzip`, or `deflate` before it stores it, and keeps at most
`DebugConfig.maxBodyCaptureBytes` of the inflated body; the size the Network tab shows for it is the
compressed size. Platform `HttpURLConnection` capture does the same. Lustro does not decode Brotli
(`br`) or any other encoding: for such a body it keeps only the size, and the Network tab says why.
To see Brotli bodies from OkHttp, add OkHttp's `BrotliInterceptor` (from `okhttp-brotli`), or the
`CompressionInterceptor` of OkHttp 5, after Lustro's interceptor: it then decodes the body before
Lustro captures it, as OkHttp does with its own gzip. Event streams are captured as they arrive, without decoding.

**Multipart uploads are captured part by part.** For an OkHttp `MultipartBody`, such as the body of
a Retrofit `@Multipart` call, Lustro stores the body as multipart text: each part's headers, the
value of each text part, and, for a file or any other part that is not text, one line with its type
and size in place of its bytes. The default redactor masks a part whose field name is sensitive.
The Network tab shows the parts as a table, and **Copy as cURL** builds a `-F` option for each part,
naming the file to send for a part whose bytes Lustro did not keep. When Lustro keeps no body at
all, as for an octet stream, the Network tab shows its size and type and says that it was not
stored.

**Leave traffic out with a capture filter.** Pass a `NetworkCaptureFilter` as `captureFilter`,
and Lustro asks it about each request before it captures anything. A request it returns `false`
for is never stored, so it costs no redaction and no part of the capture budget. Use it for
traffic you never inspect, such as calls to an analytics host, or for requests your app marks
with a header of its own:

```kotlin
NetworkDebugTab.create(
    senderClient = client,
    captureFilter = NetworkCaptureFilter.of("Analytics calls, and requests marked X-No-Capture") { request ->
        request.url.toHttpUrlOrNull()?.host != "analytics.example.com" &&
            request.headers.get("X-No-Capture") == null
    },
)
```

The description is required, because a teammate who doesn't know about your filter needs it. Once
the filter skips a request, the Network tab shows a ⚠ next to the HTTP and WebSockets buttons, with
the number skipped in its tooltip, and a click on it shows the description, how many requests the
filter skipped, and how many it failed on. The counts start again when you clear the list. The poll
`state` reports the same as `captureFilter`, for the CLI and agents.

The filter decides capture only: mock rules and the throttle still apply to a request it skips,
as they do while capture is paused. It applies to platform `HttpURLConnection` capture too. It
runs on the thread that makes the call, so keep it fast and thread-safe, and it sees the URL and
headers before redaction. A filter that throws doesn't fail the call: the request is captured,
and the first failure is logged. A marker header still goes out with the request.

To label traffic instead of leaving it out, pass a `NetworkClassifier` as `classifier`. Its labels
show as category filters in the Network tab and in each transaction's `categories`.

## WebSocket messages

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

**The hooks you already have apply to sockets.** The [capture filter](#okhttp-capture-setup) is
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

## Send Request

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

## Export

Each row of the Network tab has a checkbox at its left, which shows on hover, and on every row once
one is selected. The checkbox in the column header selects every request the filters show, and a
shift-click selects the rows from the last checkbox you clicked. While requests are selected, a bar
above the list has **Export HAR**, which saves them as a HAR 1.2 file that browser devtools and
most HTTP tools import, **Copy Markdown**, which copies them as one Markdown document for a bug
report, a pull request, or a chat, and **Clear selection**. The detail's **Markdown** button copies
one request. A filter or search change keeps only the selected requests that it still shows, so an
export has the rows on screen, and the bar says how many it deselected. Both formats have the
redacted values that the tab shows, and say when the capture cut a body short. Agents and scripts
get the HAR from `GET /api/v1/network/transactions/_/export` or `lustro net export --har FILE`.

## Mock rules

A mock rule short-circuits matching requests with a synthetic response: the interceptor answers
from the rule and the request never leaves the device. In the Network tab, **Mocks**, next to the
search field, opens the rules and the rule form in the right pane, and shows how many rules are
enabled. The rules apply to all traffic, not to the selected request. Rules answer OkHttp calls
only: [platform `HttpURLConnection` capture](#platform-httpurlconnection-capture)
records traffic, and a rule never matches it.
`urlPattern` is looked for anywhere in the request URL: as a substring, or as a regular expression
when prefixed with `regex:`. So `regex:/api/v1/statuses$` matches
`https://example.com/api/v1/statuses` and not `.../statuses/1/favourite`; anchor a pattern with `^`
when it must match from the start of the URL. A `regex:` pattern that does not compile is rejected
when you save the rule, because a rule that never matches lets the real request through.

**The first match answers.** Rules are tried from the top of the list: the first enabled rule
whose pattern and method match a request answers it. A new rule goes on top, so the rule you added
last wins when two match; editing a rule keeps its place, and `POST rules/_/sync` sets the list in
the order posted.

**Mock this request**, in the bar above a transaction's detail, opens the rules with a new rule
filled in for that request: a `regex:` pattern that matches its URL and no other, so a rule for `/items/1` doesn't also answer
`/items/1/comments`, its method, its status, its `Content-Type`, and its body. A query value that
Lustro redacted matches any value, because the app sends the real one. The body is the stored one,
so a redacted value in it is `[REDACTED]`: the form says when that is so, and you replace those
values before you save. The button is disabled for a request that
[platform `HttpURLConnection` capture](#platform-httpurlconnection-capture) recorded,
because no rule answers one.

**The detail says which rule answers the request.** When an enabled rule matches the selected
request, the detail says "Mock rule active" with the rule's name, also for a request captured
before the rule existed, and its row in the list has a dashed **MOCK** tag. A request that a rule
answered says "Mocked by rule" with that rule, and whether it still answers the next request like
it. **Matching rules**, next to **Mock this request**, shows only the rules whose pattern and method
match that request, enabled or not, and marks the one that answers it. The app matches the rules
as the interceptor does, against the URL as captured, which is redacted, and the console updates
when a rule is added, changed, enabled, or disabled. Agents read the same from each transaction's
`activeMockRuleId`, `mockRuleId`, and `matchingMockRuleIds`.

**Rules live in the app, not in the browser.** They are kept in memory unless you pass a
`MockRuleStorage` to `NetworkDebugTab.create(...)`, so without one they are gone when the process
dies:

```kotlin
NetworkDebugTab.create(
    senderClient = client,
    mockRuleStorage = SharedPreferencesMockRuleStorage(
        context.getSharedPreferences("my_app_mocks", Context.MODE_PRIVATE),
    ),
)
```

Lustro reads the storage once, when the rules are first needed, off the main thread, and writes
it on every change, in the order the rules are tried.

**A rule must be one the interceptor can serve.** Its `statusCode` has to be within 100–599, its
`responseHeaders` have to be header names and values OkHttp accepts, and a `Content-Type` among
them has to parse as a media type. A rule that isn't is rejected with an enveloped `400` naming
the offending `field`, and one already stored is dropped when it is loaded — an unservable rule
would otherwise throw inside your own HTTP call, on every request it matched.

## Platform `HttpURLConnection` capture

OkHttp capture is the default and needs no opt-in. To **also** capture platform
`HttpURLConnection` traffic, opt in explicitly:

```kotlin
@OptIn(ExperimentalPlatformCapture::class)
val tab = NetworkDebugTab.create(senderClient = client, capturePlatformHttp = true)
```

Caveats — this path is gated by `@ExperimentalPlatformCapture` because it relies on a non-public
platform detail (a process-global URL stream handler):

- **Best-effort and fail-open**: if it cannot install, capture is simply skipped; your app keeps
  working.
- **Capture only**: mock rules and the throttle apply to OkHttp calls alone. A platform request
  is never answered by a rule and never held by the throttle. The detail of a platform request
  says **HttpURLConnection**, and its **Mock this request** button is disabled.
- **Process-wide**: it installs a global handler once, affecting all `HttpURLConnection` traffic
  in the process. A [capture filter](#okhttp-capture-setup) leaves out the traffic you don't
  need, such as an analytics SDK's.
- **Not covered** by the library's binary- or behaviour-compatibility guarantees, and may
  degrade across OS/SDK versions.
- **Checked in CI on API 24, 35, and 36**: the capture creates the platform's internal
  `com.android.okhttp` URL handlers by reflection. Every release checked, from API 24 to 37,
  allows this, but Android lists these constructors as unsupported hidden APIs, so a later
  release can block them. An instrumented test fails on a release that blocks them, after that
  release is in the CI matrix. The test runs as an app that targets SDK 35, so it does not show
  a block that applies only to a higher target SDK.

## Other HTTP clients

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
  [capture filter](#okhttp-capture-setup) apply, and **Pause** stops the recording. A request that
  the filter leaves out, or that starts while capture is paused, is not recorded, and the calls
  for it do nothing.
- **The body is what you report.** Cut a large body before you report it, and set `truncated`
  and `byteSize` on the `CapturedBody`. For a body that your code reads later, such as a
  download, report the response with `.complete(false)` when the headers arrive, and again
  complete when the body ends.
- **Mock rules and the throttle are your adapter's.** `sink.findMockRule(url, method)` returns
  the rule that matches, and counts a hit on it. To answer the request from the rule, report the
  response with `.mockedBy(rule)`, so the Network tab can say which rule answered it. Nothing holds
  the request for the throttle.
- **No call throws.** The capture filter runs on your thread. Redaction and storing run on a
  background thread, except during a burst of large bodies, when a call captures on your thread
  until the background thread catches up, as OkHttp capture does.
- In `lustro-noop`, the sink records nothing and finds no rule.

## Logs

The Logs plugin serves your app's own logcat: a live tail, filters, and a plain-text export. Your
app gives it no data source: the tab runs `logcat --pid=<your app's pid>` itself. It is a pair of
artifacts, split by variant as `lustro` is:

```toml
lustro-logs      = { group = "io.github.twinsen81", name = "lustro-logs",      version.ref = "lustro" }
lustro-logs-noop = { group = "io.github.twinsen81", name = "lustro-logs-noop", version.ref = "lustro" }
```

```kotlin
dependencies {
    debugImplementation(libs.lustro.logs)
    releaseImplementation(libs.lustro.logs.noop)
}

val lustro = Lustro.builder(application)
    .addTab(NetworkDebugTab.create(senderClient = client))
    .addTab(LogsDebugTab.create())
    .build()
```

- **From the start of the process.** The tab reads logcat from `start()` to `stop()`, also while
  the app is in the background, and starts with the lines that your process wrote before
  `start()`. `stop()` ends the `logcat` process; the next `start()` goes on after the newest line.
- **A bounded buffer.** The newest lines stay in memory, at most `bufferBytes` (default 4 MiB, by
  the size of their text on the heap, about 12,000 lines of 100 characters):
  `LogsDebugTab.create(bufferBytes = 8 * 1024 * 1024)`. A line that repeats the line before folds
  into it as a count, so a flood of one line costs almost nothing.
- **A live tail in the console.** The **Logs** tab follows the newest lines, with a lowest level,
  a tag regex, and a message search, which the app applies to its whole buffer. It can pause,
  fold repeated lines, clear the buffer, copy the lines it shows as text, and show or hide the
  Time, Level, Tag, and PID / TID columns. A line with JSON in it opens indented from the toolbar
  that shows on a row after a second.
- **Routes.** `GET /api/v1/logs/entries` polls the log in the stream envelope, with `level`, `tag`,
  `q`, and `mq` filters; `GET /api/v1/logs/text` exports it as logcat's threadtime text; and
  `POST clear`, `pause`, and `compact` control it. The `lustro` CLI reaches them with `lustro api`,
  for example `lustro api GET logs/text --param level=W`. See [docs/AGENTS.md](docs/AGENTS.md#logs-tab-operations)
  and [`logs.openapi.json`](lustro-logs/src/main/assets/lustro/logs.openapi.json).
- **Not redacted.** Log lines are served as the app wrote them, tokens that an
  `HttpLoggingInterceptor` logs among them. See [SECURITY.md](SECURITY.md#threat-model).
- **Only logcat.** A message longer than logcat's limit of about 4 KB is cut, as in `adb logcat`.
  So is a tag longer than about 88 bytes: logcat prints the first part of it, and the message after.
- In `lustro-logs-noop`, the tab reads no logs.

## Preferences

The Preferences plugin reads and edits your app's SharedPreferences files and Preferences
DataStores over the API, and a poll sees the changes that the app makes. It is a pair of
artifacts, split by variant as `lustro` is:

```toml
lustro-prefs      = { group = "io.github.twinsen81", name = "lustro-prefs",      version.ref = "lustro" }
lustro-prefs-noop = { group = "io.github.twinsen81", name = "lustro-prefs-noop", version.ref = "lustro" }
```

```kotlin
dependencies {
    debugImplementation(libs.lustro.prefs)
    releaseImplementation(libs.lustro.prefs.noop)
}

val lustro = Lustro.builder(application)
    .addTab(NetworkDebugTab.create(senderClient = client))
    .addTab(
        PrefsDebugTab.create(
            application,
            files = listOf(
                PrefsFile("settings", settingsPrefs, ValuePolicy.VISIBLE),
                PrefsFile("session", encryptedPrefs, ValuePolicy.KEYS_ONLY, readOnly = true),
            ),
            dataStores = listOf(PrefsDataStore("app_state", context.appStateDataStore)),
        ),
    )
    .build()
```

- **Registered files.** A `PrefsFile` has the file's name, as you pass it to
  `getSharedPreferences`, the instance that the tab reads and edits, a value policy, and a
  `readOnly` flag. The instance can be an `EncryptedSharedPreferences` that decrypts: then the
  tab shows its plain values, as the policy lets it.
- **Registered data stores.** A `PrefsDataStore` has the store's name, as you pass it to
  `preferencesDataStore`, the app's own `DataStore<Preferences>`, a value policy, and a `readOnly`
  flag. The tab reads and writes through that instance, so the app sees an edit at once. DataStore
  allows one instance for each file in a process, so the tab never opens one of its own. DataStore
  is `compileOnly` in both artifacts: an app that registers no store needs no DataStore, and one
  that does has DataStore 1.0 or newer. Besides the SharedPreferences types, a store holds
  `double` values and, from DataStore 1.1, `bytes`.
- **Value policies.** `VISIBLE` shows every value. `MASK_SENSITIVE`, the default, leaves out the
  value of a key that looks like it holds a secret, such as `auth_token`, `apiKey`, or `password`,
  by the same key check as the Network tab's redaction (`SensitiveKeys.isSensitive`). `KEYS_ONLY`
  leaves out every value. The keys, the types, and the sizes always show.
- **Discovered files.** With `discover = true`, the default, the tab also shows the other files in
  the app's `shared_prefs` directory, such as the files of your libraries. It opens each one with
  `getSharedPreferences`, and the platform then keeps it in memory until the process ends. A
  discovered file is `MASK_SENSITIVE` and read-only, because an edit to a library's file can break
  that library; `discoveredReadOnly = false` makes the discovered files writable, except Lustro's
  own `lustro_debug`, which holds the token. A file that has the keysets of
  `EncryptedSharedPreferences`, read by a plain instance, shows its keys only and is always
  read-only: an edit would write a plain value into the encrypted file. Discovery also finds the
  other `.preferences_pb` files in `files/datastore`, such as the stores of your libraries. The
  tab reads such a file itself, masked by key, and it is always read-only: the app's DataStore
  keeps its data in memory, would not see an edit to the file, and would write over it.
- **Live updates.** Each file has a cursor, which moves when a value changes, through the tab or
  through the app. The tab sees the app's changes with an `OnSharedPreferenceChangeListener` from
  `start()` to `stop()`, also while the app is in the background. Before API 30, `clear()` calls
  no listener, so a clear by the app shows only with the next change to the file. Another process
  that writes the file is not seen. The tab follows a registered store's data over the same
  period, and it sees a change to a discovered store when the file's bytes change, on the next
  poll.
- **Routes.** `GET /api/v1/prefs/files` lists the files with their stores, policies, and cursors.
  A file's `store` is `shared_prefs` or `preferences_datastore`, and every other route takes it
  next to `file`; without it, a route picks a SharedPreferences file.
  `GET /api/v1/prefs/entries?file=` polls a file in the cursor envelope: every entry, by key, with
  its `type`, its `value` unless the policy masks it, and its size in `bytes`. A value longer than
  4096 characters is cut there, and `GET entry?file=&key=` returns it whole. `POST edit` writes
  one typed value (`string`, `int`, `long`, `float`, `boolean`, or `string_set`, and for a data
  store also `double`, and `bytes` as base64), `POST delete` removes a key, and `POST clear`
  (with `confirm: true`) empties a file. They return when the file on disk has the change, and
  answer `403` for a read-only file. The `lustro` CLI reaches them with `lustro api`, for example
  `lustro api GET prefs/entries --param file=settings`. See
  [docs/AGENTS.md](docs/AGENTS.md#preferences-tab-operations) and
  [`prefs.openapi.json`](lustro-prefs/src/main/assets/lustro/prefs.openapi.json).
- **In the console.** The **Preferences** tab shows each file as a tab, the fullest first, with
  its store (a data store's tab has a `DS` mark), its policy, whether the app registered it or the
  tab found it, and whether it is read-only. A
  key search can also search the values. A boolean, an int, or a short string changes in its row;
  any other value opens a dialog, with an indented preview of a JSON string or a string set.
  **Add key**, **Delete**, and **Clear file** ask the routes, and are off, with the reason, for a
  read-only file. Auto-refresh, on by default every 3 s, shows the app's changes: a poll reads
  the file again only when its cursor moved.
- In `lustro-prefs-noop`, the tab reads no files and no data stores.

## SQLite

The SQLite plugin shows your app's SQLite and Room databases over the API: the tables and views,
their schemas, and pages of rows, sorted and filtered. It is a pair of artifacts, split by
variant as `lustro` is:

```toml
lustro-sqlite      = { group = "io.github.twinsen81", name = "lustro-sqlite",      version.ref = "lustro" }
lustro-sqlite-noop = { group = "io.github.twinsen81", name = "lustro-sqlite-noop", version.ref = "lustro" }
```

```kotlin
dependencies {
    debugImplementation(libs.lustro.sqlite)
    releaseImplementation(libs.lustro.sqlite.noop)
}

val lustro = Lustro.builder(application)
    .addTab(NetworkDebugTab.create(senderClient = client))
    .addTab(
        SqliteDebugTab.create(
            databases = listOf(
                SqliteDb.support(
                    "app",
                    tableHints = mapOf("notes" to TableHint("NoteEntity", "A note that the user wrote.")),
                ) { appDatabase.openHelper.writableDatabase },
                SqliteDb.file("cache", application.getDatabasePath("cache.db")),
            ),
            discoverFrom = application,
        ),
    )
    .build()
```

- **The app's open handle.** `SqliteDb.support` takes a function that returns the app's
  `SupportSQLiteDatabase`: for Room, `roomDb.openHelper.writableDatabase`, which works for an
  encrypted Room database too. `SqliteDb.framework` takes one that returns a framework
  `SQLiteDatabase`, such as a `SQLiteOpenHelper`'s. The tab calls the function on a request's
  thread for each read, reads through the app's connection pool, and never closes the handle.
  Use a handle for each database that the app has open: a second connection that stays open to
  the same file can conflict with the app's, mostly in WAL mode. `androidx.sqlite` is
  `compileOnly` in both artifacts: an app that registers no `SupportSQLiteDatabase` needs none,
  and Room brings it.
- **A file.** `SqliteDb.file` takes a database file that the tab opens read-only for each
  request, and closes after it, such as the database of a library. The engine refuses every
  write on that connection, and in WAL mode it does not block the app's writer. A file in which
  SQLite finds corruption is reported and left on disk, where the platform's default error
  handler would delete it. In WAL mode,
  SQLite can leave the empty `-wal` and `-shm` files of the database after the tab reads it.
  The tab does not see the file of a live handle before it reads, so register each file once.
- **Discovery.** With `discoverFrom = context`, the tab also lists the other database files in the
  app's `databases` directory, such as those of WorkManager and other libraries, by file name.
  It lists the directory on each request, so it finds a file that the app creates later, and
  leaves out a file that a registration stands for, a `-wal`, `-shm`, or `-journal` file, and a
  file that is not a SQLite database. A discovered file is read as a `SqliteDb.file`. Discovery
  is off by default.
- **Hints and the writable flag.** `tableHints` gives a table the name of the class that maps it
  and one line on what a row is, for an agent that reads the database without your code.
  `writable` says whether a client may change the database through the tab; the routes in this
  version only read, and a file is never writable.
- **Only the SQL that the tab builds.** A client names a database, a table, and a column, and the
  tab checks each name against the database's schema before it puts it in a statement, as a
  quoted identifier. A filter value is a bound parameter. A page of rows reads each cell by its
  own type, as SQLite stores any type in any column: at most 4096 characters of a text, with the
  whole length when it is cut, and only the size of a blob, also of a blob in a `TEXT` column. A
  blob of megabytes does not fill a page or the memory. Each read stops when the request times
  out (`DebugConfig.requestTimeoutMs`, 30 s by default).
- **Routes.** `GET /api/v1/sqlite/databases` lists the databases with their source, file, size,
  and schema version (`PRAGMA user_version`). `GET tables?db=` lists the tables and views, with
  the row count of each table, and flags the internal ones (`sqlite_*`, `android_metadata`,
  `room_master_table`, and the shadow tables of a full-text index). `GET schema?db=&table=` gives
  the columns, the foreign keys, the row count, the `CREATE` statement, and the hint.
  `GET rows?db=&table=` returns a page in the pagination envelope `{ items, nextCursor }`: 50 rows
  by default, `limit` up to 500, a `filter` text that any cell can contain, and a `sort` column
  with `dir=asc|desc`. The `lustro` CLI reaches them with `lustro api`, for example
  `lustro api GET sqlite/rows --param db=app --param table=notes --param sort=created_at --param dir=desc`.
  See [docs/AGENTS.md](docs/AGENTS.md#sqlite-tab-operations) and
  [`sqlite.openapi.json`](lustro-sqlite/src/main/assets/lustro/sqlite.openapi.json).
- **No console view yet.** The tab serves its API and is not in the tab bar; `_meta` lists it.
- **Every client with the token reads every registered database.** Register only the databases
  that you want to show. See [SECURITY.md](SECURITY.md#sqlite-databases).
- In `lustro-sqlite-noop`, the tab opens no database and never calls the functions.

## Security model

Lustro deliberately surfaces app internals, so its defaults are conservative. See
[SECURITY.md](SECURITY.md) for the full threat model.

- **Loopback by default.** The server binds to `127.0.0.1`; it is reachable only from the device
  itself (and your desktop via `adb forward`).
- **Token auth, always on.** A 256-bit token is generated on first run and stored in private
  debug preferences. Programmatic clients send `Authorization: Bearer <token>`; browsers use an
  `HttpOnly; SameSite=Strict` cookie set via `/api/v1/_auth`. Before auth, only framework chrome
  is served — no tab output or captured data. The token is logged at the `LustroToken` tag.
- **Tabs never see the credentials.** The server authenticates a request before dispatching it,
  and the `DebugRequest` a tab receives carries no `Authorization` or `Cookie` header, so a tab
  that logs or echoes its request can't leak the token.
- **Content Security Policy.** Chrome and tab views ship a CSP (`default-src 'self'; script-src
  'self'; style-src 'self' 'unsafe-inline'; ...`) plus `X-Content-Type-Options: nosniff`.
  **Scripts are `'self'`-only — no inline scripts**, so tab JS loads as an external same-origin
  resource and there are no inline handlers; **styles allow `'unsafe-inline'`** so tabs can use
  inline `style=` attributes and `<style>` blocks. Every API request, whatever its method, passes
  an Origin / `Sec-Fetch-Site` check: the server's own origin is always allowed, and any other
  cross-origin caller must be listed in `DebugConfig.allowedOrigins` (other localhost ports are not
  trusted by default).
- **Capture-time redaction, best-effort.** A `Redactor` masks sensitive headers, URL/query
  params, and JSON/form body fields **before** anything is stored, so what it masks never reaches
  the API, UI, or fixtures. It matches on names, so it cannot find a secret that isn't keyed by a
  name it recognizes: see [SECURITY.md](SECURITY.md#threat-model) for the known gaps, and pass
  your own `Redactor` when your traffic needs more, or a capture filter to keep a request out of
  capture entirely. A JSON body with no sensitive field in it is stored exactly as it arrived, so
  what you inspect and copy is what was on the wire. The text of a WebSocket message is redacted as
  a body is; a binary message is stored as it arrived (see [WebSocket messages](#websocket-messages)).
- **Log lines are not redacted.** The [Logs](#logs) plugin serves the app's logcat as it is.
- **Nothing persisted to disk except mock rules.** Captured traffic and log lines live only in
  bounded in-memory ring buffers and are lost when the process dies; the sole persisted state is your mock
  rules, and only when you give the tab a `MockRuleStorage` (see [Mock rules](#mock-rules)). The
  browser keeps no copy of them.
- **Release builds are inert.** Release variants depend on `:lustro-noop`, whose runtime bodies
  are empty — no server ships to production. As a backstop for a misconfigured dependency graph,
  the real runtime also refuses to start in a build that is not marked debuggable
  (`DebugConfig.allowNonDebuggableBuilds` opts an internal build back in).

## LAN exposure and port forwarding

The default workflow is loopback + `adb forward` (see [Accessing the UI](#accessing-the-ui)); it
needs no LAN exposure.

To reach the server from another machine on the network, opt in by binding all interfaces:

```kotlin
val lustro = Lustro.builder(application)
    .config(DebugConfig.builder().bindAddress("0.0.0.0").build())
    .addTab(NetworkDebugTab.create(senderClient = client))
    .build()
```

> **Risk:** `bindAddress = "0.0.0.0"` exposes the debug server (and your app's captured traffic)
> to everyone on the same network. Token auth still applies, but you lose the loopback boundary.
> Use it only on trusted networks, and prefer `adb forward` whenever you can.

> **Browser login over LAN:** add the browser's origin to `DebugConfig.allowedOrigins`, e.g.
> `allowedOrigins(listOf("http://192.168.1.42:8080"))` for the address the browser shows. The
> `/api/v1/_auth` route is origin-checked like every other API route, and only a loopback host on
> the listening port counts as the server's own origin, so a page loaded over LAN is rejected with
> `403` until its origin is listed. Programmatic clients that send neither `Origin` nor
> `Sec-Fetch-Site`, such as `curl` or the CLI, are unaffected.

A network security config is **not** needed for any of this: it governs the connections your app
makes, not the socket Lustro listens on. Lustro serves plain HTTP and needs no
`cleartextTrafficPermitted` entry and no `usesCleartextTraffic` in either workflow.

## Custom tabs

`DebugTab` is the only public extension point. Required: `id` (must match
`[a-z][a-z0-9-]{0,30}`, validated at registration), `title`, and `icon`. Everything else has a
default — including `handle(request)` for JSON routes and `renderContent()` for optional HTML
(API-only tabs are valid).

```kotlin
class FlagsTab(private val flags: FeatureFlagRepository) : DebugTab() {
    override val id = "flags"
    override val title = "Feature Flags"
    override val icon = "🚩"

    override fun renderContent() = """<div id="flags-list"></div>"""

    // request.path is the remainder after /api/v1/flags/; null -> enveloped 404,
    // anything thrown, even TODO(), -> enveloped 500 (it never escapes into your app).
    override fun handle(request: DebugRequest): DebugResponse? = when (request.path) {
        "list" -> DebugResponse.ok(flags.toJson())
        else -> null
    }
}
```

Register it alongside the network tab:

```kotlin
Lustro.builder(application)
    .addTab(NetworkDebugTab.create(senderClient = client))
    .addTab(FlagsTab(flagsRepo))
    .build()
```

**Asset and rendering conventions:**

- Static assets live at `assets/lustro/<id>.{js,css,openapi.json}` and are resolved by id at
  runtime. Returning non-empty strings from `renderScript()` / `renderStyles()` overrides the
  static `.js` / `.css`; returning a non-null `schema()` overrides the static `.openapi.json`.
- Tab JS is loaded as an **external** script after `shared.js` (CSP: `script-src 'self'`). Use
  `data-action` attributes and event delegation — **no inline `onclick`/`<script>` handlers**.
  Submit through `fetch()`, not an HTML `<form>`: the CSP sets `form-action 'none'`.
- **Escape every value you render.** `String.escapeHtml()` makes a value safe as HTML text and
  inside a quoted attribute; the browser-side `debugEscapeHtml(text)` does the same in tab JS.
  Anything the app stores or a request carries is untrusted input to your tab.
- **Styling is free.** Every tab page loads `shared.css` — the console's design system: design
  tokens (surfaces, text ramp, semantic method/status/level/category palettes; dark + light
  themes) plus a documented `.dc-*` component library. Build on those and your tab matches the
  console in both themes with no extra CSS; see [`docs/STYLEGUIDE.md`](docs/STYLEGUIDE.md) for
  the contract and the sample flags tab for a working example.
- JSON routes go through `handle(request)`; build responses with the `DebugResponse` factories
  (`ok`, `json { ... }`, `text`, `bytes`, `notFound`, `error`). For observable list routes,
  `DebugResponse.cursorEnvelope(currentSequence, clientCursor) { /* items */ }` implements the
  cursor envelope's `reset`/`unchanged`/`delta` contract — with `CursorCodec` for the opaque
  tokens — so tabs don't hand-roll it. Advance the sequence only when the list changes, since
  each advance re-sends the whole list; other observable values go in its `state`. For a list
  that only grows at its end, such as a log, `DebugResponse.streamEnvelope(...)` implements the
  stream envelope: a poll gets only the entries after its cursor.
- **Change state only on `POST`, `PUT`, `PATCH`, or `DELETE`, never on `GET` or `HEAD`.** The
  runtime rejects browser requests from other origins on every method, but for a `GET` or `HEAD`
  it can go only by `Sec-Fetch-Site`, which browsers send only to loopback and HTTPS addresses,
  and older ones not at all. Without that header, an `<img>` on a page from another port of the
  same host sends a `GET` the runtime can't tell from the console's own, and it carries the
  console's cookie, since cookies aren't isolated by port.
- `handle()` runs off the main thread and calls may be concurrent — keep mutable tab state
  thread-safe. Blocking I/O is fine; the runtime enforces a per-request timeout.
- When a request times out, the client gets a `504` and the runtime cancels the request:
  `request.isCancelled` turns `true`, actions registered with `request.onCancel { ... }` run,
  and the handler's thread is interrupted. Use `onCancel` to abort blocking calls that ignore
  interrupts, such as cancelling the `CancellationSignal` passed to `SQLiteDatabase.rawQuery`
  or an OkHttp `Call`. A handler that ignores cancellation keeps its concurrency slot until it
  returns.
- **Ship a schema to be agent-discoverable.** Only tabs that expose a schema (a static
  `assets/lustro/<id>.openapi.json` or a dynamic `schema()`) are listed in `/api/v1/_meta`.
  Schema-less tabs work in the browser UI but are invisible to agents.

## OpenAPI and wire protocol

Every tab is a JSON API under `/api/v1/`. Framework routes:

- `GET /api/v1/_meta` — library/protocol versions and the schema-exposing tabs.
- `GET /api/v1/_schema` — JSON Schema for the shared envelopes.
- `GET /api/v1/<id>/_schema` — a tab's OpenAPI document.

Shared shapes: a uniform **error envelope** `{ error, message, code?, field?, hint? }`, a
**list pagination** envelope `{ items, nextCursor }`, and a live-polling **cursor envelope**
`{ cursor, status, items? }` where `status` is `delta` / `unchanged` / `reset` (unknown values →
`reset`) and the cursor advances when the route's list changes. A **stream envelope**
`{ cursor, status, items?, dropped? }` has the same statuses for a list that only grows at its
end: its `delta` carries only the entries after the cursor. The schemas and the SemVer
policy live in [`wire-protocol/v1/`](wire-protocol/v1/); the Network tab's contract is
[`lustro/src/main/assets/lustro/network.openapi.json`](lustro/src/main/assets/lustro/network.openapi.json),
the Logs tab's is
[`lustro-logs/src/main/assets/lustro/logs.openapi.json`](lustro-logs/src/main/assets/lustro/logs.openapi.json),
the Preferences tab's is
[`lustro-prefs/src/main/assets/lustro/prefs.openapi.json`](lustro-prefs/src/main/assets/lustro/prefs.openapi.json),
and the SQLite tab's is
[`lustro-sqlite/src/main/assets/lustro/sqlite.openapi.json`](lustro-sqlite/src/main/assets/lustro/sqlite.openapi.json),
each with a version of its own.

For driving Lustro from agents, scripts, or the `lustro` CLI, see
[docs/AGENTS.md](docs/AGENTS.md).

## Troubleshooting

- **Can't connect from the browser.** Run `adb forward tcp:8080 tcp:8080` and confirm the app is
  in the **foreground** (the server only listens while foregrounded). Check `adb logcat -s
  LustroToken` for the actual endpoint — if you set `bindFallback` and the configured port was
  taken, the server is on an OS-assigned port that the log line reports.
- **`401 unauthorized`.** The request is missing a valid token. Browsers: open via
  `#lustro_token=<token>` once (or `lustro open`). Programmatic clients: send
  `Authorization: Bearer <token>`. Get the token from `adb logcat -s LustroToken`.
- **Nothing is captured.** Make sure you added `lustro.networkInterceptor()` to the client that
  actually makes the calls, **after** any URL/header/body-mutating interceptors. Check that
  capture isn't **paused** in the Network tab. For `HttpURLConnection` traffic, you must opt in
  with `capturePlatformHttp = true`.
- **A WebSocket shows only its handshake.** The interceptor can't see messages. Create the socket
  with `lustro.webSocketFactory(httpClient)`, or give that factory to the library that creates it:
  see [WebSocket messages](#websocket-messages).

## Modules

| Module | Coordinates | What it is |
| --- | --- | --- |
| `:lustro` | `io.github.twinsen81:lustro` | Debug runtime AAR: embedded server, capture, built-in Network tab, mock storage, OkHttp adapters. |
| `:lustro-noop` | `io.github.twinsen81:lustro-noop` | Release-safe no-op AAR mirroring `:lustro`'s public facades with empty bodies. |
| `:lustro-api` | `io.github.twinsen81:lustro-api` | Pure-Kotlin public SPI (`DebugTab`, `DebugRequest`/`DebugResponse`, `Headers`, `MediaType`, network seams). |
| `:lustro-logs` | `io.github.twinsen81:lustro-logs` | The [Logs](#logs) plugin: the app's own logcat under `/api/v1/logs/`. |
| `:lustro-logs-noop` | `io.github.twinsen81:lustro-logs-noop` | Release-safe no-op half of `:lustro-logs`. |
| `:lustro-prefs` | `io.github.twinsen81:lustro-prefs` | The [Preferences](#preferences) plugin: the app's SharedPreferences and Preferences DataStores under `/api/v1/prefs/`. |
| `:lustro-prefs-noop` | `io.github.twinsen81:lustro-prefs-noop` | Release-safe no-op half of `:lustro-prefs`. |
| `:lustro-sqlite` | `io.github.twinsen81:lustro-sqlite` | The [SQLite](#sqlite) plugin: the app's SQLite and Room databases under `/api/v1/sqlite/`. |
| `:lustro-sqlite-noop` | `io.github.twinsen81:lustro-sqlite-noop` | Release-safe no-op half of `:lustro-sqlite`. |
| `lustro-cli/` | `lustro-cli` (PyPI) | Python CLI that wraps the HTTP API, installing a `lustro` command; published alongside each release. |

See also: [CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md) ·
[CHANGELOG.md](CHANGELOG.md) · [DECISIONS.md](DECISIONS.md) · [docs/AGENTS.md](docs/AGENTS.md)

## License

[Apache License 2.0](LICENSE)
