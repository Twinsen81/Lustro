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
connections, and replay requests. Every tab is also a JSON API, so AI agents and scripts can
drive Lustro directly rather than scraping HTML.

It's built as an extensible tab platform: the network inspector ships in the box, and you add
your own tabs against a stable plugin contract.

> **Status:** early development. Pre-1.0 and not yet published — APIs and the wire protocol may
> change between releases. Only the `0.1.0-SNAPSHOT` line exists today (Sonatype Central
> snapshots); there is no stable release yet.

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

Lustro requires **minSdk 26** and declares the `INTERNET` permission in its manifest.

## Quick start

Build the runtime once (typically in your `Application`), register tabs, wire the OkHttp
interceptor, and start it:

```kotlin
val client = OkHttpClient() // your app's client, used to power "Send Request"

val lustro = Lustro.builder(application)
    .addTab(NetworkDebugTab.create(senderClient = client))
    .build()

val httpClient = OkHttpClient.Builder()
    .addInterceptor(lustro.networkInterceptor())
    .build()

lustro.start()
```

`start()` freezes the tab registry; tabs registered after `start()` are not picked up.
`start()` returns a `LustroStatus` (`ENABLED` once armed, `DISABLED` if it cannot start) and is
idempotent, as is `stop()`.

> **Keep registration in `src/debug`.** The recommended pattern is to put your `DebugTab`
> subclasses and all `Lustro.builder(...)` / `.addTab(...)` wiring under `src/debug/`, so none
> of it compiles into release. Lustro ships a published Android Lint check
> (`LustroDebugUsageInRelease`, severity ERROR) that **enforces** this: it flags those
> `DebugTab` subclasses and `Lustro` builder/`addTab` calls when they are reachable from any
> source set other than `src/debug` (e.g. `src/main` or `src/release`). The `:lustro-noop` swap
> shown above is the release **safety net** — it makes the API inert in release builds even if
> something slips through — not a license to register Lustro from `src/main`; debug-only
> placement is still required to keep the lint check green.

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

4. Authenticate the browser with that token, either by:
   - using the `lustro` CLI (`lustro open`, ships in a later release — see [docs/AGENTS.md](docs/AGENTS.md)); or
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
see the final application-level request. (As an application interceptor it does not observe
OkHttp's automatic retries or redirects the way a network interceptor would — that is the
intended trade-off.)

## Send Request

The Network tab's **Send Request** panel dispatches an arbitrary request through a configured
`NetworkSender` and is **synchronous**: the HTTP call blocks until the sender returns the final
result (within the per-request timeout), so you get a single round-trip outcome instead of having
to poll.

Pass `senderClient` to `NetworkDebugTab.create(...)` to enable it — the client is wrapped in an
`OkHttpSender`. When no sender is configured, the Send panel and its route are hidden. Use the
client that already has the Lustro interceptor installed so replayed requests show up in the
traffic list. Relative URLs resolve against `DebugConfig.appServerBaseUrl` (rejected when it is
unset); requests aimed at the debug server's own bind host:port are rejected.

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
- **Process-wide**: it installs a global handler once, affecting all `HttpURLConnection` traffic
  in the process.
- **Not covered** by the library's binary- or behaviour-compatibility guarantees, and may
  degrade across OS/SDK versions.

## Security model

Lustro deliberately surfaces app internals, so its defaults are conservative. See
[SECURITY.md](SECURITY.md) for the full threat model.

- **Loopback by default.** The server binds to `127.0.0.1`; it is reachable only from the device
  itself (and your desktop via `adb forward`).
- **Token auth, always on.** A 256-bit token is generated on first run and stored in private
  debug preferences. Programmatic clients send `Authorization: Bearer <token>`; browsers use an
  `HttpOnly; SameSite=Strict` cookie set via `/api/v1/_auth`. Before auth, only framework chrome
  is served — no tab output or captured data. The token is logged at the `LustroToken` tag.
- **Content Security Policy.** Chrome and tab views ship a CSP (`default-src 'self'; script-src
  'self'; style-src 'self' 'unsafe-inline'; ...`) plus `X-Content-Type-Options: nosniff`.
  **Scripts are `'self'`-only — no inline scripts**, so tab JS loads as an external same-origin
  resource and there are no inline handlers; **styles allow `'unsafe-inline'`** so tabs can use
  inline `style=` attributes and `<style>` blocks. Origin / `Sec-Fetch-Site` checks are driven by
  `DebugConfig.allowedOrigins` (loopback is auto-allowed).
- **Capture-time redaction.** A `Redactor` removes sensitive headers, URL/query params, and
  JSON/form body fields **before** anything is stored, so redacted values never reach the API,
  UI, or fixtures.
- **Nothing persisted to disk except mock rules.** Captured traffic lives only in a bounded
  in-memory ring buffer and is lost when the process dies; the sole persisted state is your mock
  rules.
- **Release builds are inert.** Release variants depend on `:lustro-noop`, whose runtime bodies
  are empty — no server ships to production.

## LAN exposure and port forwarding

The default workflow is loopback + `adb forward` (see [Accessing the UI](#accessing-the-ui)); no
LAN exposure and no cleartext changes are needed for it.

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

Because the UI is served over plain HTTP on localhost, your **debug** build needs a network
security config that permits cleartext for the loopback host. Lustro does **not** modify your
`networkSecurityConfig`; add a debug-only one yourself:

```xml
<!-- src/debug/res/xml/network_security_config.xml -->
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">localhost</domain>
        <domain includeSubdomains="true">127.0.0.1</domain>
    </domain-config>
</network-security-config>
```

```xml
<!-- src/debug/AndroidManifest.xml -->
<application android:networkSecurityConfig="@xml/network_security_config" />
```

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
    // a thrown exception -> enveloped 500 (it never escapes into your app).
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
- JSON routes go through `handle(request)`; build responses with the `DebugResponse` factories
  (`ok`, `json { ... }`, `text`, `bytes`, `notFound`, `error`).
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
`reset`) and every mutation advances the cursor. The schemas and the SemVer policy live in
[`wire-protocol/v1/`](wire-protocol/v1/); the Network tab's contract is
[`lustro/src/main/assets/lustro/network.openapi.json`](lustro/src/main/assets/lustro/network.openapi.json).

For driving Lustro from agents, scripts, or the forthcoming `lustro` CLI, see
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

## Modules

| Module | Coordinates | What it is |
| --- | --- | --- |
| `:lustro` | `io.github.twinsen81:lustro` | Debug runtime AAR: embedded server, capture, built-in Network tab, mock storage, OkHttp adapters. |
| `:lustro-noop` | `io.github.twinsen81:lustro-noop` | Release-safe no-op AAR mirroring `:lustro`'s public facades with empty bodies. |
| `:lustro-api` | `io.github.twinsen81:lustro-api` | Pure-Kotlin public SPI (`DebugTab`, `DebugRequest`/`DebugResponse`, `Headers`, `MediaType`, network seams). |
| `lustro-cli/` | — | Python CLI that wraps the HTTP API (ships in a later phase). |

See also: [CONTRIBUTING.md](CONTRIBUTING.md) · [SECURITY.md](SECURITY.md) ·
[CHANGELOG.md](CHANGELOG.md) · [DECISIONS.md](DECISIONS.md) · [docs/AGENTS.md](docs/AGENTS.md)

## License

[Apache License 2.0](LICENSE)
