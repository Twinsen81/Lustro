# Contributing to Lustro

Thanks for your interest in improving Lustro. This guide covers building,
testing, the module layout, coding standards, the sign-off requirement, and the
pull-request process. By contributing you agree your work is licensed under the
project's [Apache License 2.0](LICENSE).

## Prerequisites

- JDK 21 (the Kotlin toolchain targets JVM 17 bytecode).
- The Android SDK with `compileSdk 35`. `minSdk` is 24.
- Use the Gradle wrapper (`./gradlew`); do not install a separate Gradle.
- Node 18+ **only** to run the debug console's JavaScript tests. It is not
  needed to build the library, and those tests are deliberately outside
  Gradle `check` so they never block a build.

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
./gradlew :build-logic:test              # the convention plugins, checkFacadeParity among them
```

Tests use JUnit 4, Robolectric for the Android pieces, and MockWebServer for
OkHttp-side tests. Wire-protocol changes must keep the contract tests and JSON
Schema validation green. CI does not require internet access — network tests
run against MockWebServer fixtures.

The modules compile against OkHttp 4.12.0, the floor an app can stay on. To run
the suite with OkHttp 5 on the test runtime classpath, as CI does:

```bash
./gradlew :lustro:testDebugUnitTest :sample:testDebugUnitTest -PlustroTestOkHttpVersion=5.5.0
```

The debug console's browser JavaScript ships inside the runtime AAR but is not
built by Gradle, so it has its own runner — the one built into Node, with no
`package.json` and no dependencies:

```bash
node --test */src/test/js/*.test.js
```

Those tests cover the code worth pinning rather than the file as a whole: the
body viewers (`debugScanJsonSource` and the JSON formatting, highlighting, and
tree on top of it, the XML and HTML printer, the line-numbered text, the form
table, and the hex dump), the HTML escaping that decides whether a captured
body renders as text or as markup, and how a search's matches are marked. DOM wiring, resizers, toasts, modals, and one-line helpers are
deliberately left untested. Please keep it that way when adding to them, and
keep the suite dependency-free.

A plugin's view has its tests in its own module, in `src/test/js` next to
`src/main/assets/lustro`, as `lustro-logs` has: they load the plugin's script
after `shared.js` with `loadTabJs(module, id)` from
`lustro/src/test/js/harness.js`, and read the wire goldens with `golden(name)`.

The platform `HttpURLConnection` capture gets the platform's URL handlers by
reflection, so it has an instrumented test that needs a device or emulator:

```bash
./gradlew connectedCheck
```

The test asserts which way the capture got the handlers, so a release that
blocks it fails the test instead of turning capture off. When a new release
fails it, update the expectation in `PlatformHttpCaptureTest`.

`.github/scripts/cli-e2e.sh` runs the CLI against the debug sample on the
connected device. It installs the sample, installs the CLI from the checkout
into a virtual environment, and checks the output of the main commands against
the CLI's wire schemas. No command passes `--token`, so it also tests discovery
through logcat. It needs `adb`, Python 3.9 or later, and a free host port 8080
for `adb forward`, or another free port in `CLI_E2E_LOCAL_PORT`; `0` lets adb
choose one. The run changes the device and does not change it back: it
replaces the sample's mock rules with one rule that mocks `GET /get`, and it
sets the device to stay awake while it charges. CI runs both on API 24, 35, and
36 emulators.

## Module layout

- **`:lustro-api`** — pure-Kotlin JAR with the public SPI only: `DebugTab`,
  `DebugRequest`, `DebugResponse`, `Headers`, `MediaType`, and the network seams
  (`NetworkCaptureSink`, `NetworkCaptureFilter`, `NetworkCaptureRequest`,
  `NetworkSender`, `NetworkClassifier`, `Redactor`, `MockRule`,
  `NetworkSendRequest`, `NetworkSendResult`, `TransactionId`, `CapturedBody`,
  `CapturedResponse`).
  Consumer-constructed types carry their concrete implementations here; no
  OkHttp dependency.
- **`:lustro`** — the runtime AAR: NanoHTTPD-backed server, tab registry, asset
  loader, the built-in Network plugin, mock-rule storage, and OkHttp adapters.
  All server/runtime code is Kotlin `internal`.
- **`:lustro-noop`** — the release-side no-op AAR. It mirrors the public facades
  of `:lustro` with empty bodies so release builds ship no server. `:lustro` and
  `:lustro-noop` declare the same Gradle capability, so a consumer cannot pull
  both.
- **`:lustro-logs`** and **`:lustro-logs-noop`**: the Logs plugin pair, the
  app's own logcat under `/api/v1/logs/` and its release-safe no-op half.
- **`:lustro-prefs`** and **`:lustro-prefs-noop`**: the Preferences plugin
  pair, the app's SharedPreferences under `/api/v1/prefs/` and its
  release-safe no-op half.
- **`:lustro-sqlite`** and **`:lustro-sqlite-noop`**: the SQLite plugin pair,
  the app's SQLite and Room databases under `/api/v1/sqlite/` and its
  release-safe no-op half.
- **`:lustro-work`** and **`:lustro-work-noop`**: the WorkManager plugin pair,
  the app's WorkManager work and the events of each run under `/api/v1/work/`,
  and its release-safe no-op half. Its unit tests run on the newest WorkManager
  of the catalog (`work`), while the modules compile against the floor
  (`workFloor`).
- **`:sample`** — demo app and public-API regression target; debug uses
  `:lustro`, `:lustro-logs`, `:lustro-prefs`, `:lustro-sqlite`, and
  `:lustro-work`, release uses their no-op halves.
- **`:lustro-cli`** — the Python CLI that drives Lustro over the HTTP API.
- **`build-logic/`**: an included build with the convention plugins that the
  published modules apply, so their build files hold only what is their own:
  - `lustro.android-library`: an Android library module. compileSdk and minSdk
    from the catalog, the `lustro_` resource prefix, no BuildConfig, toolchain
    21 with JVM 17 bytecode, explicit API, the Kotlin floor and
    `checkKotlinFloor`, and detekt. Its `lustroLibrary { pairCapability(...) }`
    declares the capability that both halves of a pair share.
  - `lustro.kotlin-library`: the same for a Kotlin/JVM module, such as
    `:lustro-api`.
  - `lustro.publishing`: the vanniktech publishing, after one of the two
    above (or `java-library` alone). The root build adds every module with it
    to the Dokka site and to `collectReleaseArtifacts`, which collects the files
    of the GitHub Release.
  - `lustro.facade-parity`: `checkFacadeParity`, at the root.

The wire protocol is versioned under `wire-protocol/v1/`; per-tab OpenAPI
fragments live at `lustro/src/main/assets/lustro/<id>.openapi.json`.

### Adding a plugin module pair

A plugin tab ships as two artifacts, `:lustro-<name>` and `:lustro-<name>-noop`
(DECISIONS.md, row 7). Both depend only on `:lustro-api`, and an app adds them
as it adds `:lustro`: `debugImplementation` for the runtime and
`releaseImplementation` for the no-op half.

1. Add both modules to `settings.gradle.kts`.
2. Give each a `gradle.properties` with `POM_NAME` and `POM_DESCRIPTION`, and a
   `build.gradle.kts` such as:

   ```kotlin
   plugins {
       id("lustro.android-library")
       id("lustro.publishing")
   }

   android {
       namespace = "io.github.twinsen81.lustro.<name>" // ".<name>.noop" for the no-op half
   }

   dependencies {
       api(project(":lustro-api"))
   }

   lustroLibrary {
       // The same name in both halves, so that an app can't resolve both.
       pairCapability("lustro-<name>-runtime")
   }
   ```

   The no-op half keeps its unused-parameter findings in `detekt-baseline.xml`
   (`./gradlew :lustro-<name>-noop:detektBaseline`), as `:lustro-noop` does.
3. Add the pair and its facade packages to the `facadeParity { }` block of the
   root `build.gradle.kts`.
4. Put the tab's assets in the runtime module: `src/main/assets/lustro/<id>.js`,
   `<id>.css`, and `<id>.openapi.json`, with its own `info.version`
   (DECISIONS.md, row 9). The server serves them from the app's merged assets,
   with no change to `:lustro`. The no-op half ships no assets.

Nothing else names the modules: the Dokka site and the GitHub Release take
every module that applies `lustro.publishing`. Until the pair has commands of
its own, `lustro api` reaches its routes, for example
`lustro api GET <id>/entries --param level=W`.

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
   or the public surface changes. When the integration steps change, update `llms.txt`
   with the README. `checkDocsVersion` fails when the Maven coordinates in them differ
   from `gradle.properties`.
5. Make sure the full local gate passes:

   ```bash
   ./gradlew test :build-logic:test detekt apiCheck checkFacadeParity checkDocsVersion
   ```

6. Open the PR, fill in the template, and confirm every commit is signed off.
   CI must be green before review.

## API compatibility expectations

Lustro follows [Semantic Versioning](https://semver.org/): **major** for
breaking changes, **minor** for additive changes, **patch** for fixes.

- **Binary Compatibility Validator (`apiCheck`) must pass.** It covers
  `:lustro-api`, the SPI both runtimes depend on, against the committed baseline
  at `lustro-api/api/lustro-api.api`. It does **not** cover the Android modules
  `:lustro` and `:lustro-noop`: BCV registers no tasks for them under AGP's
  built-in Kotlin, so they have no committed baseline (see
  [DECISIONS.md](DECISIONS.md#bcv--apicheck)). If you intentionally change the
  SPI, regenerate the dump (`./gradlew apiDump`) and explain the change in your
  PR.
- **`checkFacadeParity` must pass.** It is what catches a change to the `:lustro`
  facades (`Lustro`, `DebugConfig`, `NetworkDebugTab`, …), and to those of every
  other pair in the `facadeParity { }` block of the root build: it compares the
  public member signatures each facade itself contributes and fails on any
  difference, so a method added to one side only is rejected. Two things are outside what it
  compares, and neither is caught anywhere else, so review them by hand:
  **constructors**, which it normalizes away, and a change made identically on
  **both** facades, which passes it and passes `apiCheck` too because neither
  Android module has a baseline. The cross-variant `:sample` compile (debug →
  `:lustro`, release → `:lustro-noop`) is the third gate, and also a CI job, but
  it only exercises what the sample actually calls. Call any change to the public
  facades out in your PR.
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

Every runtime dependency of a published artifact is a floor: the oldest version
with the APIs Lustro uses, so that adding Lustro does not raise an app's own
versions (see [DECISIONS.md](DECISIONS.md#dependency-floors)). Dependabot leaves
them alone, and a bump is a change to the minimum versions README.md documents.
`./gradlew checkKotlinFloor` fails when a dependency puts a kotlin-stdlib newer
than the floor on a published module's compile classpath. Before you change a
floor, or anything the published POMs carry, publish the checkout locally and
build the app in [`consumer-check/`](consumer-check/), which is what CI does:

```bash
./gradlew publishToMavenLocal
cd consumer-check
./gradlew :app:assembleWithLustroDebug :app:assembleWithLustroRelease :app:checkLustroKeepsAppVersions
```

## Code of Conduct

By participating you agree to abide by our
[Code of Conduct](CODE_OF_CONDUCT.md).
