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
  so consumers can't resolve both; a published `LustroDebugUsageInRelease` lint
  check flags Lustro usage outside `src/debug`; capture redacted at source and never
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
  a Pixel 6 Pro). A client that closed partway through its request headers
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
  both bodies of every captured transaction. With 1,000 transactions on a Pixel
  6 Pro, that more than doubled the poll's server-side time, from 17 ms to
  42 ms when nothing matched. The search now folds case as it scans without
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

[Unreleased]: https://github.com/Twinsen81/Lustro/compare/HEAD
