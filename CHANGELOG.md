# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Everything below is implemented and building toward the first public release
(`0.1.0`). The remaining work is execution that needs credentials/infrastructure
(Sonatype Central publish, PyPI, external security review) —
see [DECISIONS.md](DECISIONS.md).

### Added

- **Debug console JavaScript tests**: a dependency-free suite run by Node's own
  test runner (`node --test lustro/src/test/js/*.test.js`) with its own CI job,
  kept out of Gradle `check` so building the library still needs no Node. It
  pins the JSON viewer — that the grammar `debugScanJsonSource` accepts matches
  `JSON.parse`'s in both directions, and that formatting a captured body adds
  whitespace and nothing else — and the HTML escaping on the same path, including
  the search query that is compiled into a `RegExp` and spliced into the result.

- **Gradle multi-module project**: `:lustro-api` (pure-Kotlin public SPI),
  `:lustro` (runtime AAR), `:lustro-noop` (release-side no-op AAR),
  `:lustro-wire-schema` (published wire-protocol artifact), `:lustro-lint`
  (release-safety lint), `:sample` (demo + cross-variant regression target), and
  the `lustro-cli/` Python CLI. JVM toolchain 21 → JVM 17 bytecode, compileSdk 35 /
  minSdk 26, `buildConfig` disabled in favour of a generated `Versions.kt`.
- **Public SPI** (`:lustro-api`): `DebugTab`, `DebugRequest`, `DebugResponse`
  (+ `ok`/`text`/`bytes`/`json`/`notFound`/`error` factories), `Headers`,
  `MediaType`, and the network seams `NetworkCaptureSink`, `NetworkSender`,
  `NetworkCaptureFilter`, `NetworkCaptureRequest`, `NetworkClassifier`,
  `Redactor`, `MockRule`, `NetworkSendRequest`, `NetworkSendResult`,
  `TransactionId`, `CapturedBody`, `CapturedResponse`, plus
  `escapeForJson`/`escapeHtml`. Explicit-API strict, BCV-validated.
- **NanoHTTPD-backed debug server** with a browser tab UI and a tab plugin model:
  `Lustro.builder(...)`, `DebugConfig`, the `DebugTabRegistry`, asset loading, and
  the `/api/v1` route surface (`_meta`, `_schema`, per-tab `_schema`, authenticated
  `_view` resources), uniform error envelope, and cursor-polling envelope.
- **Built-in Network plugin** (`NetworkDebugTab`): OkHttp application interceptor
  capture, event-stream progressive capture, opt-in platform `HttpURLConnection`
  capture (`@ExperimentalPlatformCapture`), mock rules (incl. atomic
  `rules/_/sync`), throttling, capture-only pause, overwrite mode, synchronous
  Send Request, a pluggable `NetworkCaptureFilter`/`NetworkClassifier`/`Redactor`,
  and `MockRuleStorage`.
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
- **Image bodies, and a route that serves any captured body.** The capture
  used to keep a body only as text, so an image kept nothing but its size. The
  OkHttp and platform `HttpURLConnection` adapters now keep image request and
  response bodies as bytes, up to `maxBodyCaptureBytes` and marked truncated
  past it, as they keep text; SVG stays text, and other binary bodies are still
  dropped. `CapturedBody` gains `bytes` for them: at most one of `text` and
  `bytes` is set, and the `Redactor`, which reads text, never sees `bytes`, so
  an image body is stored as it arrived. The bytes count toward
  `captureBudgetBytes` like text. `GET network/transactions/{id}/body/{request|response}`
  returns a body as it is stored, bytes or redacted text, so it is also how to
  download a large text body; it answers an enveloped `404` when no body was
  kept. It serves PNG, JPEG, GIF, and WebP inline, and every other type, SVG
  and HTML included, as an attachment with the policy
  `default-src 'none'; sandbox`, so a captured page can't run script under the
  console's origin. The transaction detail gains `requestBodyBinary` and
  `responseBodyBinary` (wire protocol 1.2). The console shows an image body in
  the detail, and `lustro net body <id> [request|response] -o FILE` saves a
  body to a file.
- **A capture filter, to leave traffic out of the Network tab.**
  `NetworkCaptureFilter` joins `NetworkClassifier` and `Redactor` in
  `:lustro-api`, with `NoOpNetworkCaptureFilter` as the default that captures
  everything, and both `NetworkDebugTab.create` overloads take it as
  `captureFilter`. Its `shouldCapture` gets a read-only `NetworkCaptureRequest`
  with the URL, method, and headers, so a later field doesn't change the
  signature. A filter has a required `description`;
  `NetworkCaptureFilter.of(description) { request -> ... }` builds one from a
  lambda. Once the filter skips a request, the Network tab shows a ⚠ next to
  the request count, with the number skipped in its tooltip, and a click shows
  the description and how many requests the filter skipped and failed on. The
  poll `state` carries the same as `captureFilter` (`null` when the app set no
  filter; wire protocol 1.2), `lustro net list` prints it, and `POST clear`
  starts the counts again. The OkHttp interceptor and platform `HttpURLConnection` capture
  ask it before they capture anything, so a request it skips is never stored,
  redacted, or counted toward `captureBudgetBytes`, and platform capture
  doesn't copy its bodies. The filter decides capture only: mock rules and the
  throttle still apply to a skipped request, as they do while capture is
  paused. It runs on the thread that makes the call. A filter that throws
  doesn't fail the call: the request is captured, and the first failure is
  logged. The sample skips an analytics host and requests marked with an
  `X-No-Capture` header.
- **Request cancellation for tabs**: `DebugRequest.isCancelled`, `onCancel(Runnable)`, and
  `cancel()`. The runtime cancels a request when it times out or the server shuts down, so a
  handler can abort blocking work that ignores thread interrupts, such as a SQLite query
  (through its `CancellationSignal`) or an OkHttp `Call`. Unit tests can call `cancel()` to
  simulate a timeout.
- **Docs for coding agents.** `llms.txt` summarizes Lustro and links its docs, and
  `context7.json` sets what Context7 indexes. `docs/AGENTS.md` now points to the
  README for adding Lustro to an app, reads the `LustroToken` line without
  waiting for new lines, with a `sed` expression that also works on macOS, and
  forwards with `adb forward --no-rebind`. The `checkDocsVersion` Gradle task,
  which CI runs, fails when the Maven coordinates in the README, `llms.txt`, or
  `context7.json` differ from `GROUP` and `VERSION_NAME` in `gradle.properties`.
  The README and the `start()` KDoc now say that `start()` must run on the main
  thread, and the README says that `addTab()` after `start()` throws.
- **Tests on emulators.** The emulator CI job ran no tests, and only on pushes
  to `main`. It now runs on every PR and push, on API 26, 35, and 36. An
  instrumented test installs the platform `HttpURLConnection` capture on the
  device's own HTTP stack and checks that a GET and a POST to a local
  MockWebServer reach the capture sink with their method, URL, status, headers,
  and bodies. A GET over HTTPS must reach the sink too, through a connection
  that the app can still cast to `HttpsURLConnection` to set its trust and read
  the server's certificate. The test also asserts which way the capture got the
  platform's URL handlers: every release checked, from API 26 to 37, allows the
  `com.android.okhttp` handler class, so a release that blocks it fails the test
  instead of turning capture off with only a log line. Then
  `.github/scripts/cli-e2e.sh` installs the debug sample, runs `lustro open`,
  `meta`, `schema network`, `mock sync`, `mock list`, `net list`, `net state`,
  `net wait`, and `net get` against it with discovery through logcat, and checks
  each output against the CLI's wire schemas. Launched with a `request` extra that names a button, the
  sample fires that button's request; a mock rule serves it, so the run needs no
  internet. The job uploads the test reports, the CLI outputs, and logcat. One
  more job, `Instrumented tests`, passes only when the emulator job passed on
  every API level, so a required check can use a name that does not change with
  the matrix.
- **CLI output that agents can afford to read.** With 1,000 captured
  transactions, `lustro --json net list` printed about 600 KB of indented JSON,
  and `lustro net get` printed both bodies whole, up to 256 KB each. Now each
  table row starts with a short transaction id, the first 8 characters of the
  id, and shows the duration. `lustro net get` and `lustro net body` take the
  short id, or any start of an id that only one transaction has, and print the
  matches when more than one id starts with it. JSON output keeps the full id.
  `lustro net list` prints the newest 50 transactions and says on stderr how
  many it left out; `--last N` and `--all` print more. The filters `--url`,
  `--method`, `--status` (a code such as `404`, or a class such as `5xx`), and
  `--errors` combine with each other and with `--search`. With `--json`, a list
  prints as JSON Lines, one compact object per transaction, and
  `--fields id,statusCode,url` keeps only those keys. Other JSON prints compact
  when stdout is not a terminal. `lustro net get` cuts each body at 2 KB and
  ends it with a marker that names the `lustro net body` command for the rest;
  `--max-body`, `--no-body`, and `--full` change that. The capture state that
  `net list` printed above its rows moves to `lustro net state`. The new
  `lustro net wait` polls until a matching request finishes, prints it, and
  exits 0, or exits 1 at `--timeout`. It ignores requests that finished before
  it started, so it runs a command given after `--`, such as
  `adb shell input tap`, after its first poll, and a mocked request that
  finishes in milliseconds can't finish first. The global flags, such as
  `--json`, now also work after the command. `docs/AGENTS.md` recommends the
  CLI before the curl examples and shows how to keep its output small.
- **A free local port for `lustro open`.** `lustro open` forwarded the app's
  port to the same port number on the computer, so it failed while another
  process, such as a local development server, held that port. `--port` did not
  help, because it set both ports. `lustro open --local-port N` forwards local
  port N to the app's port on the device, and `--local-port 0` lets adb choose a
  free port. The other commands find the forward in `adb forward --list` and
  connect to its local port without `--port`, also after a manual
  `adb forward`. `open` keeps an existing forward to the app, and runs
  `adb forward --no-rebind`, so it no longer takes over a local port that a
  forward of another device holds. `.github/scripts/cli-e2e.sh` takes its local
  port from `CLI_E2E_LOCAL_PORT`.
- **A viewer for each kind of body in the Network tab.** The detail showed a
  body as highlighted JSON, or as plain text when it was not JSON. It now picks
  a viewer by the body's content type: a tree for JSON that folds by object and
  array; the fields of a form body, decoded, in a table; XML and HTML indented,
  with whitespace added and nothing else, as the JSON view does, and an element
  that holds text shown as it arrived; the image for PNG, JPEG, GIF, and WebP,
  with its size and pixel dimensions; and line numbers for other text. A body
  that is a valid JSON object or array gets the tree whatever its content type
  says, since servers send JSON as text/html. In the tree, Alt-click folds
  everything inside a node, and a folded node that holds a search match is
  highlighted. Each viewer has a Raw view: the body as captured with line
  numbers, or an image's bytes as a hex dump. The browser remembers the view
  chosen for each content type. Copy copies the body as captured in every view,
  so for JSON it no longer adds indentation; the Copy button of the whole
  transaction still does. Download saves the body from the body route.
  `shared.css` gains `.dc-tree`, `.dc-markup`, `.dc-lines`, `.dc-kv`, and
  `.dc-seg--sm`, and `shared.js` the functions that build them, documented in
  `docs/STYLEGUIDE.md`. The JavaScript tests cover the escaping of each viewer.
  The sample has a request for each viewer, a PNG and a BMP that it sends as
  request bodies, and a request that sends a 250 KB JSON body.
- **Export captured traffic as HAR and Markdown.** A transaction left Lustro
  only as JSON from the API, as a cURL command, or as plain text, one at a
  time. `GET network/transactions/_/export?format=har&ids=...` returns the
  transactions as a HAR 1.2 document, oldest first, and every transaction when
  `ids` is left out (wire protocol 1.2). It is built from the store, so it has
  the redacted values. Each entry spends its `durationMs` in `timings.wait`,
  `_resourceType` lets Chrome DevTools list it under Fetch/XHR or Img, a body
  kept as bytes is base64, and `_lustro` carries the transaction id,
  `isMocked`, `categories`, the truncation flags, `responseComplete`, and
  `error`. `lustro net export --har FILE [--ids ID ...]` saves it, and sends
  many ids in batches, because a request line must stay under 8 KB. In the
  Network tab, **Select** adds a checkbox to each row, a checkbox in the header
  that selects every row the filters show, and a bar with the count, **Export
  HAR**, which saves a file, and **Copy Markdown**. A filter change keeps only
  the selected rows it still shows. The detail's new **Markdown** button copies
  one transaction: the method and URL as a heading, the headers in `http`
  blocks, and each body in a block tagged with a language for its content type,
  JSON indented without changing a value. Both formats mark a body that the
  capture cut short. The OpenAPI document describes the route and the HAR
  shape, the golden fixture `export-har.json` shows it, a unit test checks that
  the export writes exactly that fixture, and the CLI end-to-end run exports
  the sample's mocked request.
- **WebSocket messages.** Lustro showed a WebSocket only as its handshake, a
  request with the status `101`, because OkHttp sends nothing else through
  interceptors. `Lustro.webSocketFactory(okHttpClient)` now returns a
  `WebSocket.Factory` that creates its sockets with the client and records
  them: each message, text or binary, sent or received, and the open, the close
  frames, `cancel()`, and the failure, in one ordered log for each connection.
  The app's listener gets the same calls with the socket that `newWebSocket`
  returned, the return values of `send`, `close`, and `queueSize` are OkHttp's,
  and what the listener throws reaches OkHttp. A call on the socket only puts a
  task in a queue: a thread of its own cuts the payload at
  `maxBodyCaptureBytes`, redacts it, and stores it, and while that thread is
  behind, messages are dropped and counted. `lustro-noop` returns the client
  itself. The capture filter is asked once for each socket and also decides for
  its handshake, the classifier labels the connection, pause stops the
  recording of messages, and clear removes the connections; a socket that is
  still open is listed again with its next message. The text of a message goes
  through `Redactor.redactBody`, and then through the new
  `Redactor.redactWebSocketText`, which can return `null` to store only the
  size; `Redactor.redactWebSocketBinary` does the same for a binary message,
  which is stored as it arrived by default. Both have defaults, so a `Redactor`
  written before them still compiles. `DebugConfig` gains
  `maxCaptureWebSockets` (100), `maxWebSocketEvents` (1000 for each
  connection), and `webSocketCaptureBudgetBytes` (16 MB for all payloads).
  Wire protocol 1.3 adds `GET network/websockets` (cursor envelope),
  `GET network/websockets/{id}`, `GET network/websockets/{id}/events`, and
  `GET network/websockets/{id}/events/{seq}/payload`, and `webSocketId` on the
  transaction of a handshake. The events route uses the new **stream
  envelope**, `{ cursor, status, items?, dropped? }`, for a list that only
  grows at its end: a poll gets only the entries after its cursor.
  `stream-envelope.schema.json` describes it, and
  `DebugResponse.streamEnvelope(...)` builds it for any tab. In the Network
  tab, a switch in the list toolbar opens the **WebSockets** view: the
  connections, and for one of them its summary, the handshake headers, the log
  with a direction filter and a search, and the payload of a message in the
  JSON tree, as text, or as a hex dump. A `WS` badge on the handshake's row
  opens its connection. **Markdown** copies a connection with its last events.
  The HAR export puts a socket's messages in `_webSocketMessages` on the entry
  of its handshake, where Chrome DevTools reads them. The CLI gains
  `lustro net ws list`, `net ws get`, `net ws events` (with `--follow`), and
  `net ws payload`. The sample has a WebSocket section against an echo server,
  and the CLI end-to-end run checks a socket whose handshake a mock rule
  refuses.

### Fixed

- **A multipart upload showed "No request body".** Lustro kept no multipart
  body, so the Network tab said `// No request body` under a header that
  showed a 278.5 KB `multipart/form-data` upload. An OkHttp `MultipartBody` is
  now stored as multipart text: each part's headers, the value of each text
  part, and one line with the type and size of a file or any other part that
  is not text, whose bytes are not read. The default redactor masks a part
  whose field name is sensitive and redacts any other part as a body of its
  own type. The Network tab shows the parts as a table, **Copy as cURL**
  builds a `-F` option for each part, and a body that Lustro did not keep at
  all now shows its size and type and says that it was not stored.
- **The default redactor masked values that are not secret.** It looked for
  a sensitive fragment anywhere in a name, so it masked `author_name`,
  `author_url`, and `authors` in every link preview (they contain `auth`),
  the `Idempotency-Key` header, and public keys such as `vapid_key`. A
  fragment inside an ordinary word (`author`, `design`, `signup`, `assignee`,
  `keyword`, and a few more) no longer makes a name sensitive, and
  `Idempotency-Key`, `public_key`, and `vapid_key` are kept in any spelling.
  Everything else is masked as before: `authorization`, `accessToken`, and
  `presigned_url` still are.
- **Docs described behaviour the code doesn't have.** SECURITY.md said the auth
  token "rotates on explicit reset" (there is no rotation API: the token is
  generated once and lives until the app's data is cleared) and that redacted
  values "never enter the in-memory capture store" as though redaction were
  exhaustive; it now says the default `Redactor` matches on names, is
  best-effort, and lists the known gaps (credentials inside a string value under
  an ordinary key, a credential in a URL's path or fragment, URL userinfo,
  URL-valued headers such as `Location` and `Referer`, `multipart/form-data`
  fields, and the verbatim error text of a failed request, which no custom
  `Redactor` can reach because the SPI has no hook for it). SECURITY.md and this file disagreed about which release the
  external security review precedes; both now say `0.1.0`. The README told
  consumers to add a debug `networkSecurityConfig` permitting cleartext for
  loopback, which does nothing for a listening socket, and now says so; it also
  documents that a browser cannot log in over LAN until its origin is in
  `DebugConfig.allowedOrigins`, because `/api/v1/_auth` is origin-checked like
  every other API route. The README, `docs/AGENTS.md`, the Send Request tooltip
  and DECISIONS.md said a replayed request appears in the traffic list; it does
  so only when the configured sender client carries the Lustro interceptor, and
  the send response's `transactionId` is currently always `null`. CONTRIBUTING
  said `apiCheck` covers `:lustro` and `:lustro-noop` (only `:lustro-api` has a
  baseline; `checkFacadeParity` is what gates the facades, and is now part of the
  documented local gate). DECISIONS.md described overwrite mode as re-serving or
  mutating responses; it is list compaction. The CLI was described as shipping
  "in a later phase" while it is implemented and published to PyPI by the release
  workflow.
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
- **JSON bodies shown as the parser rewrote them, not as they arrived.** Capture
  parsed every JSON body and wrote the tree back out, whether or not anything in it
  was sensitive, and the console then parsed that text again to display and copy it.
  On a physical device, a response body of
  `{ "id": 12345678901234567890, "price": 1.10, "ratio": 1e2, "big": 9007199254740993, "dup": 1, "dup": 2 }`
  was stored as
  `{"id":1.2345678901234567E19,"price":1.1,"ratio":100,"big":9007199254740993,"dup":2}`
  and shown in the Network tab as `"id": 12345678901234567000`,
  `"big": 9007199254740992`: integers past the parser's range lost digits, `1.10`
  and `1e2` normalized, the repeated key collapsed to its last value, `\uXXXX`
  escapes decoded, and whitespace was rewritten — exactly the details an inspector
  is opened for. `DefaultRedactor` now returns a body unchanged, byte for byte,
  when its own text is strict JSON with no sensitive key in it, and the console
  indents a body without re-encoding a value of it, so the panel and the Copy
  button show what was on the wire. Any other body still takes the path it took
  before — rebuilt from the parse tree, or masked as text when it isn't one
  complete JSON value — which is what masking a value needs, and what a body the
  parser reads more loosely than the JSON grammar needs too: text it never puts in
  the tree, a secret in a comment or under a key a later duplicate shadows, is
  dropped by the rebuild rather than stored. The Send Request and mock-rule editors' **Format**
  buttons stopped rewriting values too — formatting a body no longer changes what
  it sends or serves.
- **The CLI's `run-as` token fallback never matched a device.** The last-resort
  discovery step reads the app's private preferences over
  `adb shell run-as <pkg>` and searched them for a `token` entry, but the runtime
  stores the token under `lustro_token`, so the step returned nothing on every
  real device and the CLI fell back to an error. Its unit test built a fixture
  with the wrong key, which is why it stayed green. The fallback now matches the
  key the runtime writes, and the fixture is the file as a device writes it. This
  matters beyond a dead fallback: the `run-as` read is the only discovery channel
  another app on the device cannot write to — any app can print a line under the
  `LustroToken` log tag, and the CLI takes the last one it finds.
- **CLI failures that looked like success, or ended in a traceback.**
  `lustro net poll` printed each transaction only the first time it saw it, so
  a request in flight at that poll stayed `...` and its outcome never appeared.
  It now prints the row again, marked `[update]`, when the request finishes or
  fails, and it no longer marks every request in flight as `[streaming]`.
  `lustro open` ignored a failed `adb forward` unless `--device` was given, so
  when another process held the local port, it printed a URL that reached that
  process and exited 0. It now prints adb's error and exits 1; a missing adb is
  still only a warning. A connection that closed without a response, which
  `adb forward` gives while the app is in the background, ended in a Python
  traceback (`http.client.RemoteDisconnected`) instead of the
  `connection_failed` error and its hint. `net poll` kept its rows in a buffer
  when stdout was a pipe or a file, and a reader that closed the pipe early, as
  `head` does, got a traceback.
- **Every API response repeated its status code in the status line.** It read
  `HTTP/1.1 200 200 OK`, so a client that shows the reason phrase showed
  `200 OK` as it. The status line now reads `HTTP/1.1 200 OK`, and the `504` a
  request timeout gets reads `504 Gateway Timeout`, a reason phrase NanoHTTPD
  doesn't supply.
- **Platform capture could record a POST as a GET.** The platform sends a GET
  with `doOutput` set as a POST, but switches the method only when it connects.
  An app that called `connect()` before writing the body had the request
  recorded before the switch, so it was listed as a GET. It is now recorded,
  and shown to a capture filter, as the POST that goes on the wire.
- **Compressed bodies were stored as unreadable text.** Capture decoded a text
  body straight from its bytes and never read `Content-Encoding`. OkHttp
  inflates a gzip response on its own only when it added `Accept-Encoding`
  itself, so when an app set that header, or compressed request bodies in an
  interceptor added before Lustro's, the Network tab showed the compressed
  bytes as text where the JSON should be. The OkHttp and platform
  `HttpURLConnection` adapters now inflate a `gzip`, `x-gzip`, or `deflate`
  body, and a body with no encoding that starts with the gzip magic bytes,
  before they decode it. The inflated output stops at `maxBodyCaptureBytes`,
  so a small body that inflates to far more is cut there and marked truncated
  instead of being inflated in full. A body in an encoding they can't decode,
  such as Brotli (`br`), is captured without text but with its size, and the
  Network tab says why. `CapturedBody.byteSize` stays the size on the wire,
  before decoding, and `truncated` refers to the decoded body. Platform capture
  now takes that size from `Content-Length` when there is one, and reports no
  size, instead of the cap, for a body cut at the cap whose full size it
  doesn't know. Because an inflated body can be far larger than its compressed
  size, the capture budget now counts a body as the larger of the two, with the
  kept text counted in UTF-8 bytes. "Copy as cURL" leaves out a request's
  `Content-Encoding` and `Content-Length`, because the body it sends is the
  decoded and redacted one. Event streams are still captured as they arrive,
  without decoding. The
  sample gains buttons for gzip, deflate, and `br` responses, a gzip request
  body, and a gzip response over `HttpURLConnection`.

### Changed

- **Wire protocol 1.4: a throttled request shows how long the throttle held
  it.** The global throttle waited before the capture began, so a request
  held for 3 s was listed only after the wait, as a 390 ms request, with
  nothing to say it had waited. Capture now begins before the wait: the
  request is listed while it waits, `startedAt` is when the app made the
  call, and the new `throttledMs` on every transaction says how long the
  throttle held it (`null` when it didn't). `durationMs` still leaves the
  wait out. The Network tab shows it after the duration, as `+3s`, the CLI
  marks the row `[throttled 3000ms]`, and the HAR export puts it in
  `timings.blocked`.
- **A `regex:` mock pattern is found anywhere in the URL, like a substring
  one.** It had to match the whole URL, which the docs didn't say, so a
  pattern such as `regex:/api/v1/statuses$` never matched and the real
  request went to the server. Anchor a pattern with `^` to match from the
  start of the URL. A `regex:` pattern that is empty or does not compile is
  now rejected with an enveloped `400` (`field: urlPattern`) instead of being
  saved as a rule that never matches.
- **Wire protocol 1.1: the transactions cursor advances only when the list
  changes.** It used to advance on every server-side mutation, so pausing,
  changing overwrite mode or the throttle, editing mock rules, and every
  mock-rule hit re-sent the whole list, up to 1,000 transactions, to every
  poller. Every poll response still carries `state`, `unchanged` ones
  included, and `GET rules` returns the rules with their hit counts, so
  clients lose no information. A client that watched the cursor for control
  or rule changes should read `state` or `GET rules` instead.
- **Wire protocol 1.2: transactions carry content types, the protocol, and
  epoch times.** Every transaction, in the list and in the detail, now has
  `requestContentType` and `responseContentType` (the media type as captured,
  e.g. `application/json; charset=utf-8`), `protocol` (e.g. `http/1.1`, `h2`,
  or `h3`, as OkHttp reports it), and `startedAt` and `completedAt` in
  milliseconds since the Unix epoch. `timestamp` stays what it was, an
  `HH:mm:ss.SSS` display time in the device's zone, which can't be sorted
  across midnight or matched against logcat or a server log; `startedAt` can.
  The new fields are `null` when they aren't known: `completedAt` while a
  request is in flight, `protocol` for a mocked response and for platform
  `HttpURLConnection` capture. `requestContentType` is what OkHttp sends: the
  body's media type, or the `Content-Type` header the app set when the body
  declares none. Both content types pass through the `Redactor` like any
  header value. The console shows the protocol and the content types in the
  detail header.
- **`NetworkCaptureSink.completeRequest` takes a `CapturedResponse`.** A
  response's status, headers, body, duration, mocked and complete flags, and
  now its protocol arrive as one object built with `CapturedResponse.Builder`
  instead of as seven parameters. A field added in a later release becomes one
  more optional builder call, so a capture adapter keeps compiling and linking.
  An adapter that called the old signature moves its arguments to the builder.
- **A tab's `Content-Security-Policy` is enforced alongside the server's.** The
  server used to replace a policy a tab set on its response with its own. It
  now sends both in one header, and a browser enforces each, so a tab can
  restrict a response further but never loosen the server's policy.
- **Console redesign — terminal theme.** The web console now uses a dark-first,
  mono-spaced terminal design: one blue accent, semantic color tokens for
  methods/statuses/levels/types/categories, flat surfaces with 1px separators,
  uppercase spaced labels, and a light theme with equal contrast. `shared.css` is
  now the design system for all tabs: design tokens (CSS custom properties on
  `:root`, light overrides under `[data-theme="light"]`) and the documented
  `.dc-*` component library. The Network tab, the sample tab and the framework
  chrome (top bar, status pill, theme toggle) are built from those components;
  see `docs/STYLEGUIDE.md` for the tab-author contract. The chrome's content
  area is now full-bleed (no built-in padding), so tabs own their edge padding.

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
- **`escapeHtml()` left both quote characters unescaped.** It is the only HTML
  escaper the public API offers to `renderContent()` authors, and it replaced
  `&`, `<`, and `>` only — so a tab that rendered a stored value into a quoted
  attribute, `<input value="${x.escapeHtml()}">`, could be broken out of with a
  bare `"`. Verified on a physical device: a tab rendering a hostile value that
  way gained an attacker-chosen attribute in the live console. `escapeHtml()` now
  escapes `"` and `'` as well, so its output is safe in HTML text and in a quoted
  attribute value, and the console's own `debugEscapeHtml` helper escapes `'`
  too. The served CSP tightens `form-action` from `'self'` to `'none'`,
  which closes the one route injected markup had to a state-changing `POST`
  without running any script — the console itself drives every state change
  through `fetch()`, and the built-in UI has no `<form>` element. A tab that
  submits an HTML form to the debug server, rather than calling `fetch()`, needs
  to move to `fetch()`.

[Unreleased]: https://github.com/Twinsen81/Lustro/compare/HEAD
