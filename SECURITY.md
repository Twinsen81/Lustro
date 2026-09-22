# Security Policy

Lustro is a **debug-only** Android library: a small on-device web server that
exposes app internals to a developer's browser and to local tooling. Because it
deliberately surfaces application data, its security posture is taken seriously.
This document describes what is supported, how to report a vulnerability, the
threat model, and the process for handling the vendored NanoHTTPD dependency.

## Supported Versions

Lustro is pre-1.0 and under active development. Until `1.0.0`, only the latest
published version (currently the `0.1.0-SNAPSHOT` line) receives security fixes.
After `1.0.0`, the most recent minor release of the current major will be
supported.

| Version | Supported |
| ------- | --------- |
| `0.1.0-SNAPSHOT` (latest) | Yes |
| Older snapshots | No |

## Reporting a Vulnerability

**Please do not open a public GitHub issue for security problems.** Public
disclosure before a fix is available puts every consumer at risk.

Report privately through **GitHub Security Advisories**:

1. Go to <https://github.com/Twinsen81/Lustro/security/advisories/new>.
2. Describe the issue, affected versions, and a reproduction if you have one.
3. We will acknowledge the report and coordinate a fix and disclosure with you.

If you cannot use Security Advisories, open a minimal private channel with the
repository maintainer via GitHub rather than posting details publicly. Do **not**
include working exploit payloads, tokens, or captured traffic in any public
location.

## Threat Model

Lustro's security rests on the principle that it must never reach a production
build, and that even in debug builds it stays bound to the local device.

- **Debug-only by construction, with a runtime backstop.** Release builds depend
  on `:lustro-noop`, whose runtime bodies are empty — no server, no capture, and
  no open socket ship to production. Independently of that Gradle wiring, the
  real runtime reads `ApplicationInfo.FLAG_DEBUGGABLE` at startup: in a build
  that is not marked debuggable, `Lustro.start()` logs a WARN and returns
  `LustroStatus.DISABLED` without binding a socket, unless the consumer opts in
  with `DebugConfig.allowNonDebuggableBuilds(true)` for an internal build. A
  published lint check (`LustroDebugUsageInRelease`, severity ERROR) additionally
  flags `DebugTab` subclasses — consumer code the no-op swap cannot remove from
  an APK — outside a `debug` source set. Lustro does not inspect the consumer's
  dependency graph; the shared `io.github.twinsen81:lustro-runtime` capability
  only prevents `:lustro` and `:lustro-noop` landing on the same configuration.
- **Loopback-bound by default.** The server binds to `127.0.0.1` and is
  reachable only while the app is foregrounded; the socket is torn down on
  background after draining in-flight requests. LAN exposure is strictly opt-in
  through `DebugConfig.bindAddress`.
- **Token auth is always on.** A token is generated on first run and stored in
  private debug preferences. Programmatic clients authenticate with
  `Authorization: Bearer <token>`; browsers use an `HttpOnly; SameSite=Strict`
  cookie. The token is logged at server start under the tag `LustroToken`. It is
  generated once and then persists: there is no rotation API, so the same token
  stays valid until the app's data is cleared or the app is reinstalled. Before
  authentication, the server serves only framework chrome — no tab-authored
  output or captured data.
- **Credentials stop at the server.** The server authenticates a request before
  dispatching it to a tab and strips the `Authorization` and `Cookie` headers
  from the `DebugRequest` that `DebugTab.handle()` receives. Tab code, including
  third-party tabs, never sees the token, so a tab that logs or echoes its
  request headers cannot leak it.
- **Content Security Policy.** Chrome and tab views are served with
  `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
  connect-src 'self'; img-src 'self' data:; form-action 'none'; object-src
  'none'; base-uri 'none'`. Every API request, whatever its method, passes an
  Origin / `Sec-Fetch-Site` check driven by `DebugConfig.allowedOrigins`. A
  browser can still send a cross-origin `GET` with neither header, so tabs must
  not change state on `GET` or `HEAD`.
- **Capture-time redaction, best-effort.** A `Redactor` SPI runs at capture
  time, before anything is stored, so whatever it masks never enters the
  in-memory capture store and cannot leak through the API, the UI, or fixtures.
  The default implementation matches on *names*: sensitive request/response
  headers, and URL query parameters, JSON object fields, and form fields whose
  name contains a fragment such as `token`, `key`, `secret`, `password`, `auth`,
  or `signature`. A name-based rule cannot see a secret that is not keyed by a
  name it recognizes, so the default redactor reduces exposure rather than
  guaranteeing there is none. Known gaps, where the value is stored as it
  arrived:
  - a credential inside a string value under an ordinary key, such as a `url`,
    `next`, or `download` field holding a presigned URL, a paging link, or a
    query string carrying an `access_token`;
  - a credential in a URL's **path** or **fragment**
    (`/password-reset/<token>`, `#access_token=...`). Only the query string is
    scanned, and only by parameter name, so a path segment keeps its value even
    where the segment before it is a sensitive name;
  - userinfo in a URL (`https://user:password@host/...`);
  - URL-valued headers such as `Location` and `Referer`, including any
    credential in their query string;
  - a field in a `multipart/form-data` body, whose name and value sit on
    different lines;
  - the error text of a failed request, which is stored verbatim. This one is
    not a heuristic miss: the `Redactor` SPI has no hook for an error, so a
    custom redactor cannot cover it either. It matters because the platform puts
    the full request URL into some `HttpURLConnection` exception messages.

  Treat a capture as sensitive. For everything above except the error text, a
  stricter `Redactor` passed to `NetworkDebugTab.create(...)` closes the gap for
  traffic whose secrets the name heuristic will not find.
- **Nothing persisted to disk except mock rules.** Captured traffic lives only
  in a bounded in-memory ring buffer and is lost when the process dies. The sole
  persisted state is user-authored mock rules.
- **Exceptions are contained.** Library exceptions do not escape into the host
  app; server-level errors are logged through `android.util.Log` at WARN.

An external review of the auth, CSP, and capture implementation is part of the
work remaining before the first public release (`0.1.0`), alongside the
publishing and emulator-matrix items listed in `CHANGELOG.md`.

## NanoHTTPD Vendor-Patch Process

Lustro's server engine is **NanoHTTPD, pinned at version 2.3.1**. NanoHTTPD is
effectively unmaintained upstream, so we treat it as a vendored dependency and
own its security lifecycle:

- **Pinning.** The version is pinned exactly (no version ranges) in
  `gradle/libs.versions.toml`, and NanoHTTPD never appears in Lustro's public
  API — it stays `internal` behind `LustroServer`. This keeps the attack
  surface narrow and the dependency swappable.
- **Tracking.** NanoHTTPD CVEs and security discussions (its repository, the
  GitHub Advisory Database, and OSV) are monitored. Dependabot is configured for
  the Gradle ecosystem so any upstream release or advisory is surfaced promptly.
- **Patching.** When a relevant security fix is needed:
  1. If upstream ships a patched release, we bump the pin and run the
     wire-protocol contract tests and emulator matrix before publishing.
  2. If upstream is unresponsive (the expected case), we apply the fix to a
     **vendored / shadowed copy** of the affected source maintained in this
     repository, keep it pinned to the audited revision, and document the patch
     in `CHANGELOG.md` with a link to the originating advisory.
- **Containment.** Because the engine is internal, loopback-bound, behind token
  auth, and absent from release builds, the practical exposure of any NanoHTTPD
  defect is limited to a developer's local debug session.

## Disclosure Policy

We follow coordinated disclosure:

- We aim to acknowledge a report within **3 business days** and to provide an
  initial assessment within **10 business days**.
- We will agree on a disclosure timeline with the reporter, targeting a fix and
  public advisory within **90 days** of the report, sooner for actively
  exploited issues.
- Fixes are released in a new version, documented in `CHANGELOG.md`, and
  announced through a published GitHub Security Advisory that credits the
  reporter unless anonymity is requested.
