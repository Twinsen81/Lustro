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

- **Debug-only by construction.** Release builds depend on `:lustro-noop`, whose
  runtime bodies are empty — no server, no capture, and no open socket ship to
  production. A published lint check warns if `:lustro` is reachable from a
  release variant or pulled in as a non-debug dependency, and flags `DebugTab`
  subclasses or registrations reachable from release/`main` source.
- **Loopback-bound by default.** The server binds to `127.0.0.1` and is
  reachable only while the app is foregrounded; the socket is torn down on
  background after draining in-flight requests. LAN exposure is strictly opt-in
  through `DebugConfig.bindAddress`.
- **Token auth is always on.** A token is generated on first run and stored in
  private debug preferences. Programmatic clients authenticate with
  `Authorization: Bearer <token>`; browsers use an `HttpOnly; SameSite=Strict`
  cookie. The token is logged at server start under the tag `LustroToken` and
  rotates on explicit reset, app-data clear, or fresh install. Before
  authentication, the server serves only framework chrome — no tab-authored
  output or captured data.
- **Content Security Policy.** Chrome and tab views are served with
  `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline';
  connect-src 'self'; img-src 'self' data:; form-action 'self'; object-src
  'none'; base-uri 'none'`. Origin / `Sec-Fetch-Site` checks are driven by
  `DebugConfig.allowedOrigins`.
- **Capture-time redaction.** A `Redactor` SPI redacts sensitive headers,
  URL/query parameters, JSON body fields, and form fields **at capture time**.
  Redacted values never enter the in-memory capture store, so they cannot leak
  through the API, the UI, or fixtures.
- **Nothing persisted to disk except mock rules.** Captured traffic lives only
  in a bounded in-memory ring buffer and is lost when the process dies. The sole
  persisted state is user-authored mock rules.
- **Exceptions are contained.** Library exceptions do not escape into the host
  app; server-level errors are logged through `android.util.Log` at WARN.

An external review of the auth, CSP, and capture implementation is required
before the first public (`1.0.0`) release.

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
