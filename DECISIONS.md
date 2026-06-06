# Decisions

This document records the resolved design decisions for Lustro. Each entry states
the **provisional** decision and its rationale. Every decision is reversible: if you
maintain a fork or your own distribution, you can change any of these — the notes
below explain how.

| # | Decision | Provisional choice | Rationale | How to change |
| - | -------- | ------------------ | --------- | ------------- |
| 1 | Maven group / package root | Group `io.github.twinsen81`; Kotlin package root `io.github.twinsen81.lustro` | `io.github.<user>` is automatically verifiable on the Sonatype Central Portal via the matching GitHub account ([github.com/Twinsen81](https://github.com/Twinsen81)), so no DNS/TXT proof is needed. The package root mirrors the group for clean, predictable imports. | Pick a group you can verify (a domain you own, or `io.github.<your-user>`), then rename the Maven coordinates in `gradle/libs.versions.toml` / the publishing block, the `android.namespace` of each module, and the `package` declarations under `io.github.twinsen81.*`. Asset paths (`assets/lustro/`) and route prefixes (`/api/v1`) are independent of the group and need no change. |
| 2 | Repository location | This repository **is** the dedicated, public Lustro repo | Lustro is a standalone library with a neutral identity, not a module of any host app. | n/a — this is the canonical home. |
| 3 | Standalone identity | Lustro is a **clean, standalone library** | The debug toolkit was extracted from an existing app, but Lustro carries no upstream code, types, or integration — it is fully self-contained. | n/a. The verification consumer is the `:sample` app in this repo. |
| 4 | Unversioned-route compatibility | **No** legacy/compat routes — clean `/api/v1` only | Lustro ships its API fresh; there are no pre-existing public clients pinned to unversioned legacy routes to keep working. A single versioned surface keeps the server, schemas, and CLI simple. | Future majors live side by side under `/api/v2`, `/api/v3`, …; an old major stays alive for at least one major release. There is intentionally no unversioned alias. |
| 5 | Overwrite mode | **Keep** as a first-class Network v1 feature | Overwrite mode (capturing and re-serving / mutating responses for a matched request) is part of the network inspector's value and is already specified in the Network tab's OpenAPI schema, so agents can discover and drive it. | It is a documented part of the Network wire protocol; removing it would be a breaking protocol change (new major). Leave it on. |
| 6 | Send Request contract | **Synchronous** | A Send Request call blocks until the configured `NetworkSender` returns the final `NetworkSendResult`, within the per-request timeout (30s). The HTTP response carries the captured **transaction id** and the **final status** (success/failure + status code). It returns a single round-trip result rather than a fire-and-forget `{"status":"ok"}` reply that would force clients to poll. Synchronous is simpler for agents and scripts and gives a single round-trip result. | The synchronous shape is part of the v1 wire protocol; changing it is a breaking protocol change. The sender itself still runs off the main thread; the synchronicity is between the HTTP caller and the captured result, bounded by the request timeout (504 on timeout). |
| 7 | Post-v1 plugins | Logs / SharedPreferences / SQLite are **out of v1 scope** | v1 ships only the Network plugin (Non-Goals: "Built-in tabs beyond Network"). Additional plugins follow the `:lustro-<name>` + `:lustro-<name>-noop` artifact pattern once a second built-in plugin justifies the split. These plugins stay out of scope until a second built-in plugin justifies the split. | Add them later as separate runtime artifacts against the existing `DebugTab` SPI; no `:lustro-api` change is required unless the SPI lacks a needed capability. |

## BCV / apiCheck

Binary Compatibility Validator (`org.jetbrains.kotlinx.binary-compatibility-validator`)
is applied at the root build with `apiValidation { ignoredProjects.add("sample") }`.

**Current coverage:** BCV registers its `apiDump`/`apiCheck` tasks for the
pure-Kotlin `:lustro-api` module only. It does **not** register them for the
Android library modules `:lustro` and `:lustro-noop`. The reason is the AGP 9 /
built-in-Kotlin setup: these modules apply only `com.android.library` (never
`org.jetbrains.kotlin.android`), and BCV 0.18.1 keys its task registration off
the Kotlin Gradle plugin / a `KotlinProjectExtension`, which AGP's built-in
Kotlin does not surface in the form BCV looks for. So `./gradlew apiCheck`
validates `:lustro-api` and there is no committed `.api` baseline for the Android
modules.

**What this means for guarantees today:**

- `:lustro-api` (the frozen public SPI that both runtimes depend on) **is**
  BCV-validated; its baseline lives at `lustro-api/api/lustro-api.api` and
  `apiCheck` fails on any unintended change to that surface.
- Runtime/no-op facade parity (`:lustro` vs `:lustro-noop`) is gated by **two**
  independent checks:
  1. The **cross-variant `:sample` compile** (debug → `:lustro`, release →
     `:lustro-noop`): a signature drift between the two facades fails one of the
     two variants.
  2. A dedicated **`checkFacadeParity`** Gradle task
     (`gradle/facade-parity.gradle.kts`, applied from the root build), the
     BCV-Android follow-up suggested in this note. It runs `javap` over each
     module's compiled **release** classes, extracts the public signatures of the
     `io.github.twinsen81.lustro` + `io.github.twinsen81.lustro.network` packages,
     normalizes them (drops `internal`/synthetic classes, Kotlin-internal mangled
     members, constructors, and members inherited/overridden from a supertype so
     only the members each facade type itself contributes remain), and **fails on
     any difference**. It passes today (the two facades expose an identical
     contributed surface) and fails if a public facade member is added to only one
     side — verified by injecting a one-sided method and observing the failure.
     CI runs it as an explicit job; it does not need BCV or a committed baseline.

  Combined with the `:lustro-api`-level BCV check, these gate the full public
  surface both runtimes share.

**Revisit:** if/when a BCV version (or configuration) cooperates with AGP
built-in Kotlin, replace or back `checkFacadeParity` with a committed BCV ABI
dump for `:lustro`/`:lustro-noop` (`lustro/api/*.api`, `lustro-noop/api/*.api`).
Until then, `checkFacadeParity` is the canonical Android-facade ABI gate.

## Kover coverage (same AGP-built-in-Kotlin limitation)

`org.jetbrains.kotlinx.kover` 0.9.1 is applied to `:lustro`. `./gradlew
:lustro:koverXmlReport` / `:lustro:koverHtmlReport` produce a structurally valid
report, and CI uploads it as an artifact.

Kover's **Android** support — auto-registering `debug`/`release` report variants
and attaching its coverage agent to the variant unit-test task — keys off the
**standalone** Kotlin Android plugin, exactly like BCV. Because `:lustro` applies
only `com.android.library` (AGP 9 built-in Kotlin), Kover does not instrument
`testDebugUnitTest`, so the report currently reports zero collected coverage even
though the report task itself works. Real line/branch data for `:lustro` is
deferred until a Kover release cooperates with AGP built-in Kotlin (or the unit
test task is instrumented manually). This is documented in `lustro/build.gradle.kts`
next to the `kover { }` block. `:lustro-api` has no unit tests, so Kover is not
applied there.

## Release-safety lint

A custom Android Lint check ships with `:lustro` and warns when debug-only Lustro
usage leaks into a non-debug source set.

**Works (not a fallback):**

- New pure-JVM `:lustro-lint` module (`kotlin("jvm")`, no Android plugin),
  building against `com.android.tools.lint:lint-api` / `lint-checks` **32.2.1**
  (`compileOnly`). Lint versions are AGP-coupled (`lint = AGP major + 23`), so
  AGP 9.2.1 ⇒ lint 32.2.1, whose `CURRENT_API` is **16**. The version is pinned in
  `gradle/libs.versions.toml` (`lint = "32.2.1"`).
- `LustroIssueRegistry` (one issue, `LustroDebugUsageInRelease`, severity ERROR,
  category SECURITY, with a `Vendor`) registered via the
  `META-INF/services/com.android.tools.lint.client.api.IssueRegistry` resource.
- `LustroDebugLeakDetector` (UAST `Detector.UastScanner`) flags (a) `DebugTab`
  subclasses and (b) `Lustro.builder(...)` / `.addTab(...)` calls when the
  containing file is NOT in a `debug` source set. Source-set membership is derived
  from the file path (`.../src/<sourceSet>/...`); anything outside `src/debug`
  (i.e. `main`/`release`/etc.) is reported. `builder(...)` is a companion-object
  function, so the detector matches any declaring class under the `Lustro` type to
  tolerate `@JvmStatic`/`Companion` UAST resolution.
- Bundled into the `:lustro` AAR via `lintPublish(project(":lustro-lint"))`. The
  built `lustro-debug.aar` contains `lint.jar` with the detector, registry, and
  service file. Verified end-to-end: `:sample:lintDebug` loads the published check
  and reports `[LustroDebugUsageInRelease from io.github.twinsen81:lustro-lint]`.
- Four `lint-tests` unit tests (`LustroDebugLeakDetectorTest`) cover DebugTab
  subclass + builder/addTab in `src/main` (flagged) vs `src/debug` (clean); all
  pass.

**Notes / scoped exceptions:**

- AGP's `lintPublish` consumes exactly ONE jar from the module's runtime classpath.
  The Kotlin Gradle plugin would add `kotlin-stdlib` (+ `org.jetbrains:annotations`)
  to the published `runtimeElements`, which trips AGP's "more than one jar" check.
  The lint runtime already provides the Kotlin stdlib at analysis time, so those two
  are excluded from `:lustro-lint`'s `runtimeElements`.
- `:lustro-lint` is added to BCV's `ignoredProjects` (it ships a lint-check jar, not
  a consumable Kotlin/Java API).
- The `:sample` app now follows the recommended pattern, so the check runs
  **enabled with no suppression** there. All Lustro registration is variant-split
  into `src/debug/.../LustroBootstrap.kt` (real `:lustro`: builds Lustro, registers
  the Network tab + the custom `SampleFlagsTab` `DebugTab`, starts the server, and
  returns the capturing interceptor) and a mirror `src/release/.../LustroBootstrap.kt`
  (no-op `:lustro-noop`, SAME signatures, no custom tab). `src/main`
  (`SampleApplication`/`MainActivity`) mentions **no** Lustro type — it only calls
  `LustroBootstrap.start(...)` and reads the returned interceptor — so the detector
  finds nothing to flag in `main`/`release` and `:sample:lintDebug` is clean
  WITHOUT a `lint { disable += "LustroDebugUsageInRelease" }`. The cross-variant
  parity gate is preserved because BOTH `LustroBootstrap`s have identical
  signatures and compile against their respective facade (`:lustro` in debug,
  `:lustro-noop` in release): a signature drift fails one variant. `JavaUsage.java`
  stays in `src/main` as a Java-interop compile check — it calls only factory
  methods (`NetworkDebugTab.create`, `DebugConfig.builder`, `Headers.of`,
  `DebugResponse.ok`), never a `DebugTab` subclass or `Lustro.builder`/`.addTab`,
  so it is not flagged. This is the canonical example: consumers should place
  Lustro registration (and any custom `DebugTab`s) under `src/debug/`.

**Secondary detector (deferred):** flagging `:lustro` reachable from a `release` /
`implementation` configuration is left to the existing Gradle capability +
per-variant deps (the `io.github.twinsen81:lustro-runtime` capability already makes
`:lustro` and `:lustro-noop` mutually exclusive on a configuration). The source-set
detector above was prioritised per the task brief.

For canonical package, version, and module facts, see the project README,
CONTRIBUTING, and the module build files.
