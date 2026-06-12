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

### Changed

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

[Unreleased]: https://github.com/Twinsen81/Lustro/compare/HEAD
