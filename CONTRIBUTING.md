# Contributing to Lustro

Thanks for your interest in improving Lustro. This guide covers building,
testing, the module layout, coding standards, the sign-off requirement, and the
pull-request process. By contributing you agree your work is licensed under the
project's [Apache License 2.0](LICENSE).

## Prerequisites

- JDK 21 (the Kotlin toolchain targets JVM 17 bytecode).
- The Android SDK with `compileSdk 35`. `minSdk` is 26.
- Use the Gradle wrapper (`./gradlew`); do not install a separate Gradle.

## Building

Build the runtime library AAR:

```bash
./gradlew :lustro:assembleDebug
```

Other useful targets:

```bash
./gradlew :lustro-api:compileKotlin          # public SPI module
./gradlew :lustro-noop:compileDebugKotlin     # no-op release artifact
./gradlew :sample:assembleDebug :sample:assembleRelease   # cross-variant sample
```

## Running tests

```bash
./gradlew :lustro:testDebugUnitTest      # unit + Robolectric tests for :lustro
./gradlew test                           # all module unit tests
```

Tests use MockK (preferred over Mockito), Turbine for Flow assertions, and
MockWebServer for OkHttp-side tests. Wire-protocol changes must keep the
contract tests and JSON Schema validation green. CI does not require internet
access — network tests run against MockWebServer fixtures.

## Module layout

- **`:lustro-api`** — pure-Kotlin JAR with the public SPI only: `DebugTab`,
  `DebugRequest`, `DebugResponse`, `Headers`, `MediaType`, and the network seams
  (`NetworkCaptureSink`, `NetworkSender`, `NetworkClassifier`, `Redactor`,
  `MockRule`, `NetworkSendRequest`, `NetworkSendResult`, `TransactionId`).
  Consumer-constructed types carry their concrete implementations here; no
  OkHttp dependency.
- **`:lustro`** — the runtime AAR: NanoHTTPD-backed server, tab registry, asset
  loader, the built-in Network plugin, mock-rule storage, and OkHttp adapters.
  All server/runtime code is Kotlin `internal`.
- **`:lustro-noop`** — the release-side no-op AAR. It mirrors the public facades
  of `:lustro` with empty bodies so release builds ship no server. `:lustro` and
  `:lustro-noop` declare the same Gradle capability, so a consumer cannot pull
  both.
- **`:sample`** — demo app and public-API regression target; debug uses
  `:lustro`, release uses `:lustro-noop`.
- **`:lustro-cli`** — the Python CLI that drives Lustro over the HTTP API.

The wire protocol is versioned under `wire-protocol/v1/`; per-tab OpenAPI
fragments live at `lustro/src/main/assets/lustro/<id>.openapi.json`.

## Coding standards

- **Explicit API mode.** The library is compiled with
  `-Xexplicit-api=strict`: every public declaration needs an explicit
  visibility and an explicit return type.
- **No public `data class`**, no public sealed types, no public `value class` in
  signatures, no public suspending functions, and no public `inline reified`
  APIs. Library-constructed types are read-only interfaces or opaque final
  classes; consumer-constructed types get factories in `:lustro-api`.
- **No third-party types in public signatures** except the committed OkHttp
  convenience facades. No NanoHTTPD, Hilt, router, or server-engine types are
  ever public.
- **KDoc on every public member.** Document behavior, threading, and nullability.
- **`internal` / `private` for everything not in the published surface.**
  `@RestrictTo(LIBRARY_GROUP)` is only for cross-module helpers consumers should
  not call; use `@RequiresOptIn` for unstable SPI.
- **detekt** runs in CI with the library rule set (explicit return types, no
  public `data class`, public-API documentation). Run it locally before
  pushing:

  ```bash
  ./gradlew detekt
  ```

## Developer Certificate of Origin (DCO)

All commits must be signed off. Lustro uses the
[Developer Certificate of Origin 1.1](https://developercertificate.org/): by
adding a `Signed-off-by` line you certify that you wrote the contribution or
otherwise have the right to submit it under the project's open-source license.

Sign off when committing:

```bash
git commit -s -m "Your message"
```

This appends a trailer like:

```
Signed-off-by: Your Name <you@example.com>
```

Use your real name and an email address you can be reached at. The full text of
the DCO (version 1.1):

> By making a contribution to this project, I certify that:
>
> (a) The contribution was created in whole or in part by me and I have the
>     right to submit it under the open source license indicated in the file; or
>
> (b) The contribution is based upon previous work that, to the best of my
>     knowledge, is covered under an appropriate open source license and I have
>     the right under that license to submit that work with modifications,
>     whether created in whole or in part by me, under the same open source
>     license (unless I am permitted to submit under a different license), as
>     indicated in the file; or
>
> (c) The contribution was provided directly to me by some other person who
>     certified (a), (b) or (c) and I have not modified it.
>
> (d) I understand and agree that this project and the contribution are public
>     and that a record of the contribution (including all personal information
>     I submit with it, including my sign-off) is maintained indefinitely and
>     may be redistributed consistent with this project or the open source
>     license(s) involved.

Every commit in a pull request must carry a sign-off line.

## Pull request process

1. Fork and branch from `main`. Keep changes focused; one logical change per PR.
2. Add or update tests for your change.
3. Update [`CHANGELOG.md`](CHANGELOG.md) under `## [Unreleased]`.
4. Update docs (KDoc, README, `docs/AGENTS.md`, or wire-protocol files) when behavior
   or the public surface changes.
5. Make sure the full local gate passes:

   ```bash
   ./gradlew test detekt apiCheck
   ```

6. Open the PR, fill in the template, and confirm every commit is signed off.
   CI must be green before review.

## API compatibility expectations

Lustro follows [Semantic Versioning](https://semver.org/): **major** for
breaking changes, **minor** for additive changes, **patch** for fixes.

- **Binary Compatibility Validator (`apiCheck`) must pass.** It runs against both
  `:lustro` and `:lustro-noop`, whose public facades must mirror each other, and
  the cross-variant `:sample` compile is a CI gate. If you intentionally change
  the public surface, regenerate the API dump (`./gradlew apiDump`) and explain
  the change in your PR.
- Do not promote public functions to `inline` or rename public default-value
  parameters.
- Deprecate for a full minor cycle (with `ReplaceWith` where the migration is
  mechanical) before removing public API.
- The wire-protocol major version lives in the route path (`/api/v1`); an old
  major stays alive for at least one major release.

## Dependency and license audit

Lustro ships with a deliberately small dependency set. Before adding or bumping a
dependency, run the audit baseline and confirm the new dependency's license is
compatible with Apache-2.0:

```bash
# Resolved dependency tree for the published runtime artifact:
./gradlew :lustro:dependencies --configuration releaseRuntimeClasspath

# Public-facing (api) dependencies that leak into consumer POMs:
./gradlew :lustro:dependencies --configuration releaseApiElements
```

A `licensee`-based automated license check is wired into CI; until it is
active, the commands above plus a manual license review are the gate. New runtime
dependencies must be justified in the PR description.

## Code of Conduct

By participating you agree to abide by our
[Code of Conduct](CODE_OF_CONDUCT.md).
