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
| 5 | Overwrite mode | **Keep** as a first-class Network v1 feature | Overwrite mode is list compaction: while it is on, a newly started request evicts any earlier *completed* transaction with the same method and URL path, so a polled or repeated endpoint keeps one row instead of hundreds. In-flight requests are never evicted. It is part of the network inspector's value and is already specified in the Network tab's OpenAPI schema (`POST overwrite-mode`, `overwriteMode` in the tab state), so agents can discover and drive it. | It is a documented part of the Network wire protocol; removing it would be a breaking protocol change (new major). Leave it on. |
| 6 | Send Request contract | **Synchronous** | A Send Request call blocks until the configured `NetworkSender` returns the final `NetworkSendResult`, within the per-request timeout (30s). The HTTP response carries the **final status** (success/failure + status code). Its `transactionId` names the transaction that the Lustro interceptor recorded for the request: the request carries an OkHttp tag that the interceptor fills in. It is `null` when the sender's client has no Lustro interceptor, or the request wasn't captured. It returns a single round-trip result rather than a fire-and-forget `{"status":"ok"}` reply that would force clients to poll. Synchronous is simpler for agents and scripts and gives a single round-trip result. | The synchronous shape is part of the v1 wire protocol; changing it is a breaking protocol change. The sender itself still runs off the main thread; the synchronicity is between the HTTP caller and the captured result, bounded by the request timeout (504 on timeout). |
| 7 | Post-v1 plugins | Logs / SharedPreferences / SQLite are **out of v1 scope** | v1 ships only the Network plugin (Non-Goals: "Built-in tabs beyond Network"). Additional plugins follow the `:lustro-<name>` + `:lustro-<name>-noop` artifact pattern once a second built-in plugin justifies the split. These plugins stay out of scope until a second built-in plugin justifies the split. | Add them later as separate runtime artifacts against the existing `DebugTab` SPI; no `:lustro-api` change is required unless the SPI lacks a needed capability. |
| 8 | WebSocket inspection | A wrapped `WebSocket.Factory` in the Network plugin; complete messages only | OkHttp sends only the handshake through interceptors. Its public API shows a socket's messages in two places, the `WebSocketListener` and `WebSocket.send`, so `Lustro.webSocketFactory(client)` wraps both, and the app creates its sockets with the returned factory. Capture runs inside the app, so `wss://` needs no proxy. It is part of `:lustro`, not a plugin pair: it needs only OkHttp, shares the redactor, the capture filter, and the pause toggle with HTTP capture, and its no-op mirror is one method that returns its argument. The cost is that a library which takes only an `OkHttpClient` can't use the factory. Frames, fragmentation, compression, and OkHttp's own ping and pong frames are not visible at this level. | A capture below the listener, of the upgraded socket's bytes, would cover every client of an `OkHttpClient` and show frames. It needs a frame parser and an inflater, and OkHttp exposes the upgraded socket to interceptors only in recent 5.x releases (5.3 does, 5.1 does not), while Lustro builds against 4.12. It would be a second capture path next to the factory, not a replacement for the wire shapes. |
| 9 | Plugin schema versions | Each plugin tab's `<id>.openapi.json` has its own `info.version`; the core `protocolVersion` changes only when the core wire changes | The core protocol version (`_meta.protocolVersion`, the Network schema's `info.version`) is generated into an internal constant of `:lustro`, and a plugin depends only on `:lustro-api`, so it cannot read or follow it. A plugin's routes also change at their own pace: a change to one tab's wire says nothing about the core routes or another tab. A client reads the version of a tab from `GET /api/v1/<id>/_schema`. | To share one version, move the protocol version into a public constant of `:lustro-api` and let each plugin schema use it. Then every change to a plugin's wire also changes the core protocol version. |
| 10 | Logs plugin: what it reads and how it folds | The tab runs `logcat -v threadtime -v UTC --pid=<pid>` from the start of the process; a buffer of 4 MiB by default; a repeated line folds into the line before, as a count, on the server; log lines are not redacted | `--pid` exists from API 24, the minSdk, and limits logcat to the app's own lines. Reading from the process start, with `-T`, gives the startup lines before `Lustro.start()` and leaves out an earlier process that had the same pid; a later run goes on after the newest line read. Times are read in UTC, so a change of the device's time zone, or the hour that repeats when daylight saving time ends, can't shift them. 4 MiB, by the size of the text on the heap, holds about 12,000 lines and keeps the app's heap use small. The stream envelope only appends, so a fold can't change a row in place: while no response read the newest entry, a repeat changes it in its slot, and after one did, the entry moves to a new slot with the same `seq` and a larger `count`, and the client replaces its row. A flood of one line then costs a slot for each poll, not a copy of the line for each repeat, which a client-only fold would cost. The lines are not redacted because `adb logcat` reads them already and the server is loopback-bound by default; SECURITY.md says what LAN access exposes. | Fold only in the client (simpler; the buffer keeps every repeat), or send each line through a redactor; skip the startup lines with `-T 1`. Each is a change to the Logs schema's version. |
| 11 | Preferences plugin: what it shows and what it may change | Registered files with a value policy (`VISIBLE`, `MASK_SENSITIVE` by default, `KEYS_ONLY`) and a `readOnly` flag; discovery of the other `shared_prefs` files on by default, masked and read-only; a file with the keysets of `EncryptedSharedPreferences`, read plain, is keys-only and read-only; a per-file cursor that the tab's own changes and an `OnSharedPreferenceChangeListener` move; the key check shared with the network redaction through `SensitiveKeys` in `:lustro-api` | Discovery makes the tab useful with no registration, for the files of the app's libraries, and the key check hides the obvious tokens. Discovered files are read-only because an edit to a library's file can break the library, and an `EncryptedSharedPreferences` file opened with plain `getSharedPreferences` shows ciphertext: a plain value written into it breaks it for the app. The policy says what a client sees and `readOnly` what it may change, so a file can be keys-only and writable, for example to clear a session. The listener holds the app's changes; the tab's own changes move the cursor at once, because the platform calls the listener later when the write is off the main thread. For a registered instance that is not the platform's instance of its file, such as an `EncryptedSharedPreferences`, the tab also listens to the platform's instance: that editor's `clear()` removes the keys through the platform's editor and calls none of its own listeners, on every API level. A decrypting instance is found by its class name or, after R8 renamed it, by its refusal of a keyset's key. Before API 30, `clear()` calls no listener, so a clear by the app shows only with the next change: a fingerprint of every file on every poll would close that gap, at the cost of a copy of the file per poll. One public key check keeps the tabs from drifting apart as its word lists get fixes. Each entries response is the whole file, so a value is cut at 4096 characters and `GET entry` returns it whole. | Discovery off by default (safer, but the tab then shows nothing without registration); `KEYS_ONLY` implies read-only; a private copy of the key check in the plugin. Each is a change to the Preferences schema's version. |
| 12 | SQLite plugin: how it reads, and what it leaves out | Registrations through factories: `SqliteDb.support` and `SqliteDb.framework` take a function that returns the app's open handle, and `SqliteDb.file` a file that the tab opens with `OPEN_READONLY` for one request; `writable` exists, but only a live handle can have it (decision 13 says what it allows); discovery of the other files in `databases` is off by default and lists the directory on each request; no `excludeTables`; the routes run only SQL that the tab builds; each cell carries its own storage class; `GET rows` is the pagination envelope with an offset in the cursor | The app's own handle reads through its connection pool, so it sees what the app sees, also an encrypted database, and a reader does not conflict with the app's WAL writer. A second connection that stays open to the same file can, so a file is opened per request and closed. With factories instead of a public source type, a file can't be registered writable, which its read-only connection could never honour. Discovery lists the directory per request, because a list made when the app builds Lustro misses the files that the app or a library creates later, all of them on a first start, and it leaves out the files that a live handle stands for, which only a read can find out. It leaves them out before it looks at any file, and it asks the platform's SQLite whether a file is a database instead of reading its header: on Android, the close of any descriptor of a file drops the POSIX locks that the process holds on it, and SQLite works around that only for its own descriptors. For the same reason, a file that the app opens with another copy of SQLite (Room's bundled driver, SQLCipher) can't be read as a file; the docs say so, because the tab can't detect it. `excludeTables` was left out: the next routes add free-form SQL, which can read any table, so a list that only hides a table from browsing would look like a protection that it is not; to keep a database's data private, do not register it. Any column can hold any type, so the tab reads `typeof()` for each cell, and a blob in a `TEXT` column is a size, not a failed page. An integer beyond 2^53 - 1 is a string so that a JavaScript client does not round it, and a rowid, an identity, is always one. The pagination envelope allows nothing beside `items` and `nextCursor`, so the row count is in `GET schema`, and the cursor is a place in the rows that a change between two pages can move. | Make `excludeTables` a predicate on the registration, enforced by the read routes and by refusing free-form SQL on such a database; list the discovered files once, at `create`; send every cell as a plain JSON value, with a separate type list. Each is a change to the SQLite schema's version or to the public API. |
| 13 | SQLite plugin: free-form SQL and writes | `POST query` runs a client's statement on a connection that SQLite opens with `OPEN_READONLY` to the database's file, for one request, never on the app's handle; `POST exec`, `edit`, and `delete` write through the app's handle, each in a transaction of its own, and only to a `writable` database, which the route checks before it reads the statement; both refuse `BEGIN`, `COMMIT`, `END`, `ROLLBACK`, `SAVEPOINT`, `RELEASE`, `ATTACH`, `DETACH`, and `VACUUM`, and `exec` refuses every `PRAGMA`; `exec` rolls back a statement that changes the `TEMP` schema; a `query` on a database out of WAL mode stops after 2 s | Android's `rawQuery` runs any statement: a `DELETE` that it is given deletes when the cursor is stepped, also inside a `WITH`, and `PRAGMA user_version(7)` sets the version. A statement classifier misses forms such as ``WITH `x(` AS (SELECT 1) DELETE FROM t``, so SQLite is the read-only boundary, on a connection that can't write. The other ways were tried on a physical device and in Robolectric, and each failed: a transaction that always rolls back holds the write lock for the whole read, keeps a `PRAGMA query_only(1)` after the rollback, and leaks the outer transaction when the statement is a `BEGIN`; `beginTransactionReadOnly()` (API 35) runs the write and blocks the app's writers until the end; `PRAGMA query_only` on the app's connection blocks the app's own writes, and on a second connection `query_only=OFF` turns writes on again. The statement check finds only the first keyword, past comments, white space, empty statements, and `EXPLAIN`, because it does not decide what is a read: it refuses the statements that leave state on a connection. A `BEGIN` leaves a transaction on a pooled request thread, which blocks the app's writes while other requests still succeed; a pragma changes the app's connection, and SQLite applies some pragmas while it compiles them, also under `EXPLAIN`, so `exec` refuses every `PRAGMA` and a client reads a pragma with `query`. `ATTACH` through `execSQL` turns WAL off for the app's database, and `ATTACH` and `VACUUM INTO` can create files. A `TEMP` object belongs to the connection, and a name such as `temp.t` creates one without the word `TEMP`, so `exec` compares the `TEMP` schema before and after, in its transaction. Out of WAL mode, the app's writer waits for a reader's lock, and Android's SQLite fails a write that waited 2.5 s with `SQLiteDatabaseLockedException`, so the request's timeout, 30 s by default, does not protect the app; 2 s does. A live `SupportSQLiteDatabase` can be another copy of SQLite, such as SQLCipher's, whose locks the platform's connection would not see: `query` compares the two builds' `sqlite_source_id()` and compile options, and refuses a handle whose build differs. A write's transaction lets `exec` count the rows of its own statement with `total_changes()` and `changes()` on one connection, and lets a write that outlived the request's timeout roll back. | Allow a list of pragmas that only set a database's state in `exec`; give a registration a flag that allows `query` on a live handle of another SQLite; leave the 2 s limit to the request timeout. Each is a change to the SQLite schema or to the public API. |

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
  2. A dedicated **`checkFacadeParity`** Gradle task (the
     `lustro.facade-parity` plugin in `build-logic/`, applied at the root), the
     BCV-Android follow-up suggested in this note. It compares each runtime and
     no-op pair that the `facadeParity { }` block of the root build lists; for
     `:lustro` and `:lustro-noop`, the facade packages are
     `io.github.twinsen81.lustro` and `io.github.twinsen81.lustro.network`. It
     runs `javap` over each module's compiled **release** classes, extracts the
     public signatures of the facade packages, normalizes them (drops `internal`/synthetic classes, Kotlin-internal mangled
     members, constructors, and members inherited/overridden from a supertype so
     only the members each facade type itself contributes remain), and **fails on
     any difference**. It passes today (the two facades expose an identical
     contributed surface) and fails if a public facade member is added to only one
     side — verified by injecting a one-sided method and observing the failure,
     and by the unit tests in `build-logic/`, which compile a fixture pair with a
     member and a type on one side only. CI runs it as an explicit job; it does
     not need BCV or a committed baseline. A plugin pair adds an entry to the
     block.

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

## Release safety: the runtime guard and the lint check

Two independent mechanisms keep the real runtime out of production, on top of the
`:lustro` / `:lustro-noop` variant split.

**Runtime guard (always on).** `Lustro` reads `ApplicationInfo.FLAG_DEBUGGABLE`
once at construction. When the host app is not marked debuggable, `start()` logs a
WARN and returns `LustroStatus.DISABLED` **before** freezing the registry,
registering the lifecycle observer, or binding a socket. This is the only
mechanism that does not depend on the consumer's Gradle wiring or on them running
lint, which matters because a lone `releaseImplementation(lustro)` resolves
cleanly — the shared `io.github.twinsen81:lustro-runtime` capability only fires
when both artifacts land on ONE configuration. `DebugConfig.allowNonDebuggableBuilds`
(default `false`, mirrored in `:lustro-noop`, covered by `checkFacadeParity`) opts
an internal/QA build that ships `android:debuggable="false"` back in. Robolectric's
default `ApplicationInfo` DOES carry `FLAG_DEBUGGABLE`, so the existing lifecycle
tests needed no setup change; `LustroDebuggableGuardTest` drives the flag in both
directions explicitly.

**Release-safety lint.** A custom Android Lint check ships with `:lustro` and
flags a `DebugTab` subclass that lives outside a `debug` source set.

**Works (not a fallback):**

- New pure-JVM `:lustro-lint` module (`kotlin("jvm")`, no Android plugin),
  building against `com.android.tools.lint:lint-api` / `lint-checks` **32.2.1**
  (`compileOnly`). Lint versions are AGP-coupled (`lint = AGP major + 23`), so
  AGP 9.2.1 ⇒ lint 32.2.1, whose `CURRENT_API` is **16**. The version is pinned in
  `gradle/libs.versions.toml` (`lint = "32.2.1"`).
- `LustroIssueRegistry` (one issue, `LustroDebugUsageInRelease`, severity ERROR,
  category SECURITY, with a `Vendor`) registered via the
  `META-INF/services/com.android.tools.lint.client.api.IssueRegistry` resource.
- `LustroDebugLeakDetector` (UAST `Detector.UastScanner`) flags `DebugTab`
  subclasses when the containing file is NOT in a `debug` source set. Source-set
  membership is derived from the file path (`.../src/<sourceSet>/...`); anything
  outside `src/debug` (i.e. `main`/`release`/etc.) is reported.
- **Scoped to `DebugTab` subclasses only.** The detector deliberately does NOT
  flag `Lustro.builder(...)` / `.addTab(...)` calls. Those resolve to whichever
  facade the variant depends on, and `:lustro-noop` makes them inert in release,
  so flagging them at severity ERROR would fail the README quick start (which
  wires Lustro from the app's `Application`, i.e. `src/main`) and forced the
  `:sample` release mirror to carry a `@Suppress`. A `DebugTab` subclass is the
  opposite case: it is the consumer's own code, compiled into whatever variant its
  source set belongs to, and it typically reaches into app internals — the no-op
  swap cannot remove it from the APK, so it is the thing worth an ERROR.
- Bundled into the `:lustro` AAR via `lintPublish(project(":lustro-lint"))`. The
  built `lustro-debug.aar` contains `lint.jar` with the detector, registry, and
  service file. Verified end-to-end: `:sample:lintDebug` loads the published check
  and reports `[LustroDebugUsageInRelease from io.github.twinsen81:lustro-lint]`.
- Five `lint-tests` unit tests (`LustroDebugLeakDetectorTest`) cover a `DebugTab`
  subclass in `src/main` and `src/release` (flagged) vs `src/debug` (clean), the
  quick-start layout of builder/`addTab` in `src/main` with the tab in `src/debug`
  (clean), and builder + subclass both in `src/main` (only the subclass is
  reported); all pass.

**Notes / scoped exceptions:**

- AGP's `lintPublish` consumes exactly ONE jar from the module's runtime classpath.
  The Kotlin Gradle plugin would add `kotlin-stdlib` (+ `org.jetbrains:annotations`)
  to the published `runtimeElements`, which trips AGP's "more than one jar" check.
  The lint runtime already provides the Kotlin stdlib at analysis time, so those two
  are excluded from `:lustro-lint`'s `runtimeElements`.
- `:lustro-lint` is added to BCV's `ignoredProjects` (it ships a lint-check jar, not
  a consumable Kotlin/Java API).
- The `:sample` app follows the recommended pattern, so the check runs **enabled
  with no suppression** there — including on the release mirror, which no longer
  carries `@Suppress("LustroDebugUsageInRelease")` now that the detector ignores
  builder/`addTab`. All Lustro registration is variant-split
  into `src/debug/.../LustroBootstrap.kt` (real `:lustro`: builds Lustro, registers
  the Network tab + the custom `SampleFlagsTab` `DebugTab`, starts the server, and
  returns the capturing interceptor) and a mirror `src/release/.../LustroBootstrap.kt`
  (no-op `:lustro-noop`, SAME signatures, no custom tab). `src/main`
  (`SampleApplication`/`MainActivity`) mentions **no** Lustro type — it only calls
  `LustroBootstrap.start(...)` and reads the returned interceptor — so the detector
  finds nothing to flag in `main`/`release`, and both `:sample:lintDebug` and
  `:sample:lintVitalRelease` are clean
  WITHOUT a `lint { disable += "LustroDebugUsageInRelease" }`. The cross-variant
  parity gate is preserved because BOTH `LustroBootstrap`s have identical
  signatures and compile against their respective facade (`:lustro` in debug,
  `:lustro-noop` in release): a signature drift fails one variant. `JavaUsage.java`
  stays in `src/main` as a Java-interop compile check — it calls only factory
  methods (`NetworkDebugTab.create`, `DebugConfig.builder`, `Headers.of`,
  `DebugResponse.ok`), never a `DebugTab` subclass or `Lustro.builder`/`.addTab`,
  so it is not flagged. This is the canonical example: consumers should place
  Lustro registration (and any custom `DebugTab`s) under `src/debug/`.

**Secondary detector (dropped, not deferred):** a check that flags `:lustro`
reachable from a `release` / `implementation` configuration is not planned. The
Gradle capability only makes `:lustro` and `:lustro-noop` mutually exclusive on ONE
configuration, so it never sees a lone `releaseImplementation(lustro)`; the runtime
`FLAG_DEBUGGABLE` guard above covers that case at the only point where it is
certain, and unlike a lint check it does not require the consumer to run lint. Any
public text describing a dependency-graph lint check is wrong and should be
corrected.

For canonical package, version, and module facts, see the project README,
CONTRIBUTING, and the module build files.

## Dependency floors

**Decision:** every dependency of a published artifact is a floor, the oldest
version that has the APIs Lustro uses, not the version this repository builds
with. Gradle resolves a module to the highest version requested, so an app keeps
its own, newer versions, and adding Lustro does not change the build the
developer wants to inspect.

**Why:** the artifacts used to require the catalog's current versions. In an app
on androidx.lifecycle 2.10.0 and compileSdk 36, the lifecycle 2.11.0 that
`lustro` required moved every lifecycle artifact of the app to 2.11.0 through
the group's own alignment constraints, the Compose ones included; those need
compileSdk 37 and AGP 9.1, and the debug build failed in
`checkDebugAarMetadata`. The other dependencies carried the same risk:
kotlin-stdlib 2.3.21 compiled in a Kotlin 2.2 app only because a compiler reads
the metadata of the next minor version, and an app with Gradle dependency
verification needs a checksum for every artifact a raise brings in. A debug tool
must not need a `resolutionStrategy` workaround in the app.

**How it is done:**

- `gradle/libs.versions.toml` holds the floors: `kotlinFloor` 2.0.21, OkHttp
  4.12.0, `lifecycle-process` 2.4.1 (the oldest release line with
  `DefaultLifecycleObserver` in `lifecycle-common`), and NanoHTTPD 2.3.1.
  `lifecycle-runtime-ktx`, coroutines, and `androidx.annotation` are no longer
  dependencies of `lustro`: nothing used them. `androidx.annotation` stays
  `compileOnly` in `lustro-api`, for its CLASS-retention annotations.
- DataStore and coroutines are `compileOnly` in `lustro-prefs` and
  `lustro-prefs-noop`, at the floors `datastoreFloor` 1.0.0 and
  `coroutinesFloor` 1.5.0 (the coroutines that DataStore 1.0.0 depends on). An
  app that registers a `PrefsDataStore` has both; one that does not never
  loads the classes that call them, and the consumer rules carry `-dontwarn`
  for their packages. Only `DataStoreSource` calls them, with the APIs that
  every release since those floors has, so a newer DataStore of the app runs
  the same calls. A separate `lustro-prefs-datastore` pair was the other
  option: it would add two artifacts and a public store SPI in `lustro-prefs`
  to keep stable.
- `androidx.sqlite` is `compileOnly` in `lustro-sqlite` and
  `lustro-sqlite-noop`, at the floor `sqliteFloor` 2.0.0, the first androidx
  release, for `SqliteDb.support`. An app that registers a
  `SupportSQLiteDatabase` has it, mostly through Room; one that does not never
  loads the class that calls it, and the consumer rules carry `-dontwarn
  androidx.sqlite.**`. The tab calls only `query(SupportSQLiteQuery,
  CancellationSignal)` and `getPath()`, which every release has. A separate
  `lustro-sqlite-room` helper artifact was the other option: two more artifacts
  for one function type.
- The published Kotlin modules set `coreLibrariesVersion`, `languageVersion`,
  and `apiVersion` from `kotlinFloor`, through the `lustro.android-library` and
  `lustro.kotlin-library` convention plugins in `build-logic/`. Whatever compiler the catalog pins, they
  compile against kotlin-stdlib 2.0.21, use only its API, and carry Kotlin 2.0
  metadata. The compiler deprecates a language version before it drops it (2.3
  deprecates 2.0), so `-Xsuppress-version-warnings` keeps that warning out of
  every build; dropping the version is a compile error, and the moment the
  floor must move. `kotlin.io.encoding.Base64` is stable since Kotlin 2.2 but
  experimental in stdlib 2.0, hence the opt-in in `:lustro` and `:lustro-api`.
  A dependency can still put a newer stdlib on a module's compile classpath,
  which Gradle keeps while the POM still names the floor, so each published
  module runs `checkKotlinFloor` (`CheckKotlinFloorTask` in `build-logic/`), in `check`
  and in CI: it fails when the compile classpath resolves kotlin-stdlib to
  anything but the floor. `:lustro-api` excludes `org.jetbrains.kotlin` from
  its `compileOnly` androidx.annotation for that reason.
- Dependabot ignores the floors (`.github/dependabot.yml`); they move by hand.
- The `consumer` CI job publishes the checkout to the local Maven repository
  and builds [`consumer-check/`](consumer-check/), an app on the oldest
  supported toolchain (AGP 8.6.1, Kotlin 2.0.21, compileSdk 35) whose own
  dependencies are the floors, and once more on a current toolchain with
  OkHttp 5 and the lifecycle Compose artifacts. Its `checkLustroKeepsAppVersions`
  task fails on any module whose version differs between the app with and
  without Lustro. The unit tests also run with OkHttp 5 on the test runtime
  classpath (`-PlustroTestOkHttpVersion`).
- README.md documents the minimum versions, and CONTRIBUTING.md the commands.

**How to change:** to raise a floor, change it in `gradle/libs.versions.toml`,
in `consumer-check/gradle.properties`, and in README.md together, and say so in
CHANGELOG.md: it is a requirement every app has to meet. OkHttp 5 as the floor
would be that decision for the public API types, and is not planned while apps
on 4.x exist. The Kotlin floor is the one the toolchain can force: when the
catalog's compiler no longer supports language version 2.0, `kotlinFloor` moves
to the oldest version it does support. An app on the previous version still
reads the metadata, since a compiler reads one minor version ahead, but gets the
newer stdlib.
