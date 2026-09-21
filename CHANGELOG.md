# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Everything below is implemented and building toward the first public release
(`0.1.0`). The remaining work is execution that needs credentials/infrastructure
(Sonatype Central publish, PyPI, the emulator matrix, external security review) —
see [DECISIONS.md](DECISIONS.md).

### Added

- **Gradle multi-module project**: `:lustro-api` (pure-Kotlin public SPI),
  `:lustro` (runtime AAR), `:lustro-noop` (release-side no-op AAR),
  `:lustro-wire-schema` (published wire-protocol artifact), `:lustro-lint`
  (release-safety lint), `:sample` (demo + cross-variant regression target), and
  the `lustro-cli/` Python CLI. JVM toolchain 21 → JVM 17 bytecode, compileSdk 35 /
  minSdk 26, `buildConfig` disabled in favour of a generated `Versions.kt`.
- **Public SPI** (`:lustro-api`): `DebugTab`, `DebugRequest`, `DebugResponse`
  (+ `ok`/`text`/`bytes`/`json`/`notFound`/`error` factories), `Headers`,
  `MediaType`, and the network seams `NetworkCaptureSink`, `NetworkSender`,
  `NetworkClassifier`, `Redactor`, `MockRule`, `NetworkSendRequest`,
  `NetworkSendResult`, `TransactionId`, `CapturedBody`, plus
  `escapeForJson`/`escapeHtml`. Explicit-API strict, BCV-validated.
- **NanoHTTPD-backed debug server** with a browser tab UI and a tab plugin model:
  `Lustro.builder(...)`, `DebugConfig`, the `DebugTabRegistry`, asset loading, and
  the `/api/v1` route surface (`_meta`, `_schema`, per-tab `_schema`, authenticated
  `_view` resources), uniform error envelope, and cursor-polling envelope.
- **Built-in Network plugin** (`NetworkDebugTab`): OkHttp application interceptor
  capture, event-stream progressive capture, opt-in platform `HttpURLConnection`
  capture (`@ExperimentalPlatformCapture`), mock rules (incl. atomic
  `rules/_/sync`), throttling, capture-only pause, overwrite mode, synchronous
  Send Request, a pluggable `NetworkClassifier`/`Redactor`, and `MockRuleStorage`.
- **Security & lifecycle**: always-on token auth (Bearer + `HttpOnly; SameSite=Strict`
  cookie, machine-parseable `LustroToken` startup log), browser fragment bootstrap,
  CSP, origin/`Sec-Fetch-Site` checks, loopback-default binding with LAN opt-in and
  port-collision/fallback policy, `ProcessLifecycleOwner` foreground/background
  binding with request draining, bounded dispatch (1 MB → 413, 16 concurrent + 64
  queue → 503, 30 s timeout → 504), and a 50 MB capture budget.
- **Release safety**: `:lustro-noop` mirrors the runtime public facades as no-ops;
  `:lustro`/`:lustro-noop` share a Gradle capability (project + published metadata)
  so consumers can't resolve both; `Lustro.start()` refuses to arm in a build that
  is not marked debuggable (`DebugConfig.allowNonDebuggableBuilds` opts an internal
  build back in); a published `LustroDebugUsageInRelease` lint check flags
  `DebugTab` subclasses outside `src/debug`; capture redacted at source and never
  persisted to disk (mock rules excepted).
- **Wire protocol & CLI**: versioned `/api/v1` protocol with JSON Schemas + the
  Network OpenAPI document + golden fixtures under `wire-protocol/v1/`, and the
  stdlib-only `lustro` CLI that drives it over HTTP (token discovery via logcat/
  `LUSTRO_TOKEN`/`run-as`, `open`/`meta`/`schema`/`net`/`mock`/`send`).
- **Tooling**: detekt, Kover, a javap-based `checkFacadeParity` gate (runtime/no-op
  parity, since BCV can't dump the AGP-built-Kotlin Android modules), Vanniktech
  publishing to Sonatype Central (signing CI-gated), Dokka v2 docs, and CI/snapshot/
  release GitHub Actions workflows.
- **Docs**: `README.md`, library `docs/AGENTS.md`, `SECURITY.md`, `CONTRIBUTING.md`,
  `CODE_OF_CONDUCT.md`, `DECISIONS.md`, and issue/PR templates + grouped Dependabot.
- **Request cancellation for tabs**: `DebugRequest.isCancelled`, `onCancel(Runnable)`, and
  `cancel()`. The runtime cancels a request when it times out or the server shuts down, so a
  handler can abort blocking work that ignores thread interrupts, such as a SQLite query
  (through its `CancellationSignal`) or an OkHttp `Call`. Unit tests can call `cancel()` to
  simulate a timeout.

### Fixed

- **Redaction false positives on public headers.** `DefaultRedactor` no longer
  masks `Access-Control-Allow-Credentials`, `WWW-Authenticate`, or
  `Proxy-Authenticate` — their names resemble secrets, but they carry CORS
  metadata / server challenges, not credentials. The fragment heuristic and the
  `Authorization`/`Cookie` set are unchanged, and a custom `Redactor` passed to
  `NetworkDebugTab.create(...)` still overrides all of it.
- **Capture stalling the app's HTTP calls on large text bodies.** `DefaultRedactor`
  masks bodies that aren't a single JSON value or a form (plain text, XML, SSE,
  NDJSON, and JSON cut off at the capture cap) on the app's own HTTP thread. Its
  regex passes were quadratic there: they backtracked on long identifier runs,
  and on Android each match cost time proportional to the whole body. A 20 KB
  `text/plain` body added about 5 s to the call, a 250 KB NDJSON body about 4 s,
  and a truncated 256 KB JSON response blocked it for over a minute. A linear
  scan now masks the same values in milliseconds.
- **Errors in tab code crashing the app.** Lustro's catch-alls caught only
  `Exception`, so an `Error` got past them. `TODO()` in a custom tab's `handle()`
  killed the app from NanoHTTPD's request thread, and the same call in `onStart()`
  crashed `Lustro.start()` at launch; a `StackOverflowError` or `OutOfMemoryError`
  did the same. A request now answers an enveloped `500` whatever a route or tab
  throws, and lifecycle callbacks and the background drain log the error and carry
  on. Connection threads also catch anything NanoHTTPD lets through, so nothing
  reaches the app's uncaught-exception handler.
- **StrictMode disk reads at app startup.** Building `Lustro` opened its token's
  SharedPreferences file on the calling thread, usually the main thread in
  `Application.onCreate`, so a StrictMode thread policy reported a
  `DiskReadViolation` from Lustro. The file now opens on first use, and the
  `LustroToken` endpoint line, which does that first read, is logged from a
  background thread instead of the main-thread lifecycle callback that binds the
  socket.
- **Timed-out handlers escaping the concurrency limit.** A request that hit the
  per-request timeout got its `504` and gave up its concurrency slot, but its
  handler kept running whenever it ignored the thread interrupt, as a slow
  SQLite query or a CPU-bound loop does. New requests took the slot, worker
  threads grew with every timeout, and the background drain closed the socket
  while those handlers still ran. Timed-out requests are now cancelled (see
  `DebugRequest.onCancel`), and a request keeps its slot until its handler
  actually returns, so `maxConcurrentRequests` caps what really runs and the
  drain waits for it. A queued request now waits at most the per-request
  timeout for a slot, then gets a `503`.
- **Connections exhausting the app's threads.** The debug server started a
  thread for every connection it accepted, before reading the request, so
  neither auth nor the request limits applied. Any local process, or any host
  on the network with `bindAddress = "0.0.0.0"`, could open connections until
  thread creation failed and the app aborted (about 7,000 within 20 seconds on
  a physical device). A client that closed partway through its request headers
  also pinned a thread at full CPU for as long as the server ran, logging a
  failed send hundreds of times a second. The server now keeps at most
  `maxConcurrentRequests + requestQueueCapacity + 16` connections open (96 by
  default) and closes any past that without a response, and a connection ends
  as soon as its client closes.
- **Stale traffic list after an app restart.** A polling cursor carried only a
  change counter that starts over in every process, while the session token
  survives restarts, so a console left open kept polling with its old cursor.
  Once the new process's counter reached the same value, the server answered
  `unchanged` and the console kept showing transactions that no longer
  existed. Cursors now also carry an epoch, a random value picked per store, so
  a cursor from before a restart gets a `reset`. `DebugResponse.cursorEnvelope`
  takes it as a new `epoch` parameter that defaults to one per process, and
  `CursorCodec` gains `encode(sequence, epoch)` and `decode(cursor, epoch)`.
- **Searching the traffic list slowing down every poll.** Whenever the list had
  changed, a poll with a search term lowercased a copy of the URL, method, and
  both bodies of every captured transaction. With 1,000 transactions on a
  physical device, that more than doubled the poll's server-side time, from
  17 ms to 42 ms when nothing matched. The search now folds case as it scans without
  copying, and each transaction keeps its result for the current search term
  until it changes, so a poll only re-checks new and updated transactions.
  That poll now takes under 1 ms, and a new search term takes 19 ms.
- **Secrets stored raw where a captured body ends.** A captured body can stop
  partway through a value: at the 256 KB capture cap, while an event stream is
  still arriving, or where a client closed the body early. `DefaultRedactor`
  masked a value only once its closing quote or close tag was captured, so the
  part of a secret before the cut was stored, served by the API, and copied
  into exports. A `password` that the cap cut through stayed in the capture for
  good, and a `token` split across two reads of an event stream showed in the
  Network tab until the rest arrived. A sensitive value that the end of the
  captured text cuts off is now masked through to the end. The `Redactor` KDoc
  now notes that bodies can end partway through a value, since custom
  redactors get them too.
- **Numbers, objects, and arrays under sensitive keys stored raw.** When a body
  parses as JSON, `DefaultRedactor` masks a sensitive key's whole value, whatever
  its type. Bodies that don't parse take a textual fallback: every JSON body cut
  off at the 256 KB capture cap, NDJSON, and JSON in SSE frames. That fallback
  masked only string values, so a numeric API key or PIN, a `session` or `auth`
  object, or an array of keys was stored raw, served by the API, and copied into
  exports. XML had the same gap: a sensitive element's text was masked, but not
  its child elements. The fallback now masks those values as `"[REDACTED]"`, as
  the structured path does, and everything inside a sensitive XML element. It
  also no longer misses a key that follows a string such as `":"` in an array.
- **Rejected requests corrupting the next request on their connection.** The
  server read a request's body only when a route used it, and NanoHTTPD never
  skips a body that's left unread. A `401`, `403`, `404`, `413`, or `503`, or
  any POST outside the API, left its body in the stream, and the next request
  on that keep-alive connection was parsed with the body in front of it. A
  browser console whose cookie went stale got a plain-text `400` for the
  request after each rejected POST, and a body that was itself a complete
  request was run and answered. A response now closes the connection whenever
  its request's body wasn't read. It first reads and drops up to 4 MB of what
  the client is still sending, so a client that's still uploading gets the
  response rather than a reset. An authenticated API request's body is read
  before routing, so a `404` or `503` keeps its connection. API requests with a
  chunked body, which the server can't read, get a `400`.
- **Slow request bodies starving the debug API.** An API request's body was
  read while it held a concurrency slot, including `POST /api/v1/_auth`'s,
  which needs no token. A client that sent its body a byte every few seconds
  kept the slot for as long as it kept sending, even after its own request got
  a `504`: a blocked socket read ignores the timeout's interrupt, and the
  socket's read timeout restarts with every byte. On a physical device, 16 such
  connections made every authenticated request wait 30 s and then get a `503`.
  The body is now read before the request takes a slot, after the checks that
  need only headers, so a slow body holds only its own connection, and
  `_auth` accepts at most 1 KB. The bodies buffered at once stay within what
  the slots allowed, `maxConcurrentRequests × maxRequestBodyBytes`: a body
  waits for room as a request waits for a slot. A client that stops sending
  partway through a body now gets no response, where the tab used to get the
  truncated body.
- **Large request headers answered forever at full CPU.** NanoHTTPD reads a
  request's line and headers into an 8 KB buffer. When they didn't fit, it
  served the part that did, then parsed the same bytes as the connection's next
  request, so the server answered that one request again and again. On a
  physical device, a GET with a 9 KB `Cookie` header got 2,767 responses in 2 s,
  and an
  authenticated `POST pause` with one toggled capture 1,296 times. Once the
  client left, its connection thread kept spinning at full CPU, logging a
  failed send on every pass, until the app went to the background. It needed
  no token, and a browser gets there on its own when other local servers set
  large cookies on the same host, since cookies aren't isolated by port. Such a
  request now gets an enveloped `400` without being routed, and its connection
  closes.
- **Capture slowing the app's own HTTP calls.** The interceptor redacted, classified,
  and stored every capture on the thread making the call, before handing back the
  response. For JSON, redaction parses and re-serializes the whole body. On a
  physical device, capture took a call with a 200 KB JSON response from 3 ms to
  32 ms, and one with a 200 KB JSON request body from 2 ms to 30 ms. That work now runs on a
  background capture thread, and a transaction is still stored only once it's
  redacted. Those calls now take 5.5 ms and 3.1 ms. `HttpURLConnection` capture and
  event-stream progress go through the same thread, and progress updates that arrive
  while one is waiting are merged into it. Once about 4 MB of captured text is
  waiting, calls capture on their own thread again until it catches up, so a burst
  can't queue unbounded text. Custom `Redactor` and `NetworkClassifier`
  implementations run on that thread too, and on the calling thread when it falls
  behind. A `Redactor` or `NetworkClassifier` that throws no longer fails the app's
  call: a body the redactor can't handle is left out of its capture, and a header is
  masked. JSON nested 20,000 levels deep used to throw `StackOverflowError` from the
  call itself.
- **A mock rule that crashed the app on every request it matched.** The rule routes
  stored whatever they were given, and the interceptor then built a real OkHttp
  response from it inside the app's own call. A `Content-Type` that isn't a media
  type, a header name or value OkHttp rejects, or a status outside 100–599 threw
  there: on a physical device, a rule with `Content-Type: not a media type` killed
  the process on the first matching request, and again after every restart, since
  the rule was persisted. Rules are now checked wherever they enter — `POST
  rules`, `POST rules/_/sync`, and rule storage — with an enveloped `400` naming the
  `field`, and a stored rule that fails is dropped when it's loaded, which also frees
  an app already looping on one. Should a rule still fail to build, the call gets an
  `IOException` like any network failure, the transaction is listed as failed, and no
  `RuntimeException` escapes into the app.
- **Mock rules coming back from the browser's storage.** The console mirrored the
  rule list into `localStorage` and posted it back whenever it found the app's list
  empty. That storage is per browser origin, so every app reached through the same
  `host:port` shared one list: rules from one app installed themselves in the next,
  and a rule deleted over the API or the CLI returned on the following page load. The
  console no longer keeps a copy; the app's `MockRuleStorage` is what makes rules
  outlive a restart, and rules are in-memory without one.
- **Editing a rule re-enabling it and dropping its response headers.** The console's
  rule form sends neither `enabled` nor `responseHeaders`, and an add replaces the
  rule with the same id wholesale, so saving an edit to a disabled rule turned it
  back on and left it with no headers. The form now sends both fields from the rule
  it is editing.

### Changed

- **Wire protocol 1.1: the transactions cursor advances only when the list
  changes.** It used to advance on every server-side mutation, so pausing,
  changing overwrite mode or the throttle, editing mock rules, and every
  mock-rule hit re-sent the whole list, up to 1,000 transactions, to every
  poller. Every poll response still carries `state`, `unchanged` ones
  included, and `GET rules` returns the rules with their hit counts, so
  clients lose no information. A client that watched the cursor for control
  or rule changes should read `state` or `GET rules` instead.
- **Console redesign — terminal theme.** The web console now uses a dark-first,
  mono-spaced terminal design: one blue accent, semantic color tokens for
  methods/statuses/levels/types/categories, flat surfaces with 1px separators,
  uppercase spaced labels, and a light theme with equal contrast. `shared.css` is
  now the design system for all tabs: design tokens (CSS custom properties on
  `:root`, light overrides under `[data-theme="light"]`), the documented `.dc-*`
  component library, the restyled shared `.debug-*` components, and aliases that
  keep the pre-redesign token names working for existing tab CSS. The Network tab
  and the framework chrome (top bar, status pill, theme toggle) are restyled to
  match; see `docs/STYLEGUIDE.md` for the tab-author contract. The chrome's
  `.content` container is now full-bleed (no built-in padding) — tabs own their
  edge padding.

### Security

- **Tabs could read the session token.** The server passed every request header
  to `DebugTab.handle()`, including `Authorization: Bearer <token>` and the
  `lustro_token` cookie, so a tab that logged or echoed its request could leak
  the token, which stays valid across app restarts. The server now strips
  `Authorization` and `Cookie` from the `DebugRequest` before dispatch. Tabs no
  longer see any cookies: browsers don't isolate cookies by port, so the header
  can also carry other local services' sessions.
- **Pages on other local ports reaching tabs with the console's session.** The
  origin check ran only on `POST` requests. Cookies aren't isolated by port,
  and `SameSite` treats other ports of the same host as the same site, so a
  page from another local server, such as a dev server on `localhost:3000`,
  gets the console's `lustro_token` cookie sent with its requests to the debug
  server. Its `GET`s reached tabs as authenticated requests: against the
  sample app on a physical device, an `<img>`, a no-cors `fetch`, and a CORS
  `fetch` from such a page in desktop Chrome each got a `200` from
  `GET transactions`. The page can't read those responses, and the built-in
  tabs change state only on `POST`, but a custom tab that changed state on a
  `GET` could be driven from it. `PUT`, `PATCH`, and `DELETE` went unchecked
  too, held back only by the CORS preflight the server never answers. Every
  API request is now origin-checked, whatever its method, and `DebugTab.handle`
  now requires tabs to change state only on `POST`, `PUT`, `PATCH`, or
  `DELETE`: browsers send `Sec-Fetch-Site` only to loopback and HTTPS
  addresses, and a cross-origin `GET` without it carries no `Origin` either.

[Unreleased]: https://github.com/Twinsen81/Lustro/compare/HEAD
