# Release safety

Lustro gives the app's internals to anyone who has the token, so it must never be in a build that
users get. Four protections keep it out of a release build. The first two depend on how the app
adds the dependencies, the third on where the app puts its own tabs, and the fourth needs nothing
from the app. Each section says how to check the protection in your app.

| Protection | What it stops | Where it acts |
| --- | --- | --- |
| [The no-op halves](#the-no-op-halves) | The server and the capture in a release build | Gradle dependencies |
| [The shared capability](#the-shared-capability) | The runtime and the no-op half on one configuration | Gradle resolution |
| [The lint check](#the-lint-check) | Your `DebugTab` classes in a release build | Lint, which `assembleRelease` runs |
| [The debuggable check](#the-debuggable-check) | A server in a build that is not debuggable | The app, at `start()` |

## The no-op halves

Each artifact has a no-op half with the same public API: `lustro-noop` for `lustro`, and
`lustro-<name>-noop` for each plugin. The app adds the runtime with `debugImplementation` and the
no-op half with `releaseImplementation`, so the same code compiles for both build types. In the
no-op half, the calls do nothing:

- `Lustro.builder(...)`, `addTab(...)`, and `build()` compile and keep nothing.
- `start()` opens no socket and returns `LustroStatus.DISABLED`.
- `networkInterceptor()` passes every request on unchanged, `webSocketFactory(client)` returns
    `client` itself, and `networkCaptureSink()` records nothing.
- The plugin factories, such as `PrefsDebugTab.create(...)`, read nothing, and do not call the
    lambdas that the app gives them, such as the database supplier of `SqliteDb.support`.

**Check it:** `./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath | grep -E 'twinsen81|nanohttpd'`
lists only `lustro-api` and the `-noop` artifacts. Each build type other than `debug` needs the
no-op halves too, such as `stagingImplementation(libs.lustro.noop)`. This is step 2 of [Check the integration](../get-started/check.md).

## The shared capability

The runtime and its no-op half declare the same Gradle capability, so Gradle refuses to put both
on one configuration. Gradle then fails the build with a capability conflict, and does not pick
one of the two:

| Pair | Capability |
| --- | --- |
| `lustro`, `lustro-noop` | `io.github.twinsen81:lustro-runtime` |
| `lustro-logs`, `lustro-logs-noop` | `io.github.twinsen81:lustro-logs-runtime` |
| `lustro-prefs`, `lustro-prefs-noop` | `io.github.twinsen81:lustro-prefs-runtime` |
| `lustro-sqlite`, `lustro-sqlite-noop` | `io.github.twinsen81:lustro-sqlite-runtime` |
| `lustro-work`, `lustro-work-noop` | `io.github.twinsen81:lustro-work-runtime` |
| `lustro-screen`, `lustro-screen-noop` | `io.github.twinsen81:lustro-screen-runtime` |

For example, `implementation(libs.lustro)` with `releaseImplementation(libs.lustro.noop)` puts both
on the release classpath, and the release build fails.

The capability does not stop the runtime alone on a release configuration, such as
`implementation(libs.lustro)` with no no-op half. The check of the previous section finds that
mistake, and the [debuggable check](#the-debuggable-check) stops the server at run time.

**Check it:** the debug and the release builds both build: `./gradlew :app:assembleDebug :app:assembleRelease`.

## The lint check

The no-op swap removes Lustro's code from a release build, but not yours. A `DebugTab` subclass is
app code: it is compiled into every variant whose source sets contain it, with the app internals
that it reads. So put your tabs in `src/debug`.

`lustro` and `lustro-noop` both ship the lint check `LustroDebugUsageInRelease`, so the debug lint
and the release lint both load it. It reports a `DebugTab` subclass in a source set whose name does
not end with `debug`, such as `src/main` or `src/release`. Its severity is fatal, and
`assembleRelease` and `bundleRelease` run the release lint (`lintVitalRelease`), so a release build
with such a class fails.

What the check does not report:

- A tab in `src/debug` or in `src/<flavor>Debug`.
- Test sources, such as `src/test` and `src/androidTest`, so a fake tab in a test is not reported.
- The calls to `Lustro.builder(...)`, `addTab(...)`, and `start()`. They compile against the no-op
    half in a release build. The cleanest layout still puts the whole bootstrap in `src/debug`, with
    a `src/release` copy that has the same signatures, as the
    [sample app](https://github.com/Twinsen81/Lustro/tree/main/sample) does. Then a release build
    compiles none of it.
- A tab in a module that depends on neither `lustro` nor `lustro-noop`, such as a library module
    that depends only on `lustro-api`. The check runs only in a module that has one of the two. Add
    such a library module with `debugImplementation`.
- An app that turns the release lint off with `lint { checkReleaseBuilds = false }`.

An internal build that ships `android:debuggable="false"` and uses the
[opt-in below](#the-debuggable-check) has its tabs in its own source set, such as `src/internal`.
The check reports them: add `@Suppress("LustroDebugUsageInRelease")` (in Java,
`@SuppressLint("LustroDebugUsageInRelease")`) to each of those tab classes.

**Check it:** `./gradlew :app:lintRelease` reports no `LustroDebugUsageInRelease`. To see the
check work, move a tab to `src/main` for a moment: `./gradlew :app:assembleRelease` then fails.
This is step 6 of [Check the integration](../get-started/check.md).

## The debuggable check

When the real runtime is in a build without `android:debuggable`, `start()` does not open a socket.
It logs a warning at the `Lustro` tag and returns `LustroStatus.DISABLED`:

```text
Refusing to start: this app is not marked debuggable. ...
```

This check needs no Gradle setup and no lint run. It stops the server even when the dependencies
are wrong. It does not remove Lustro's code from the APK: only the no-op halves do that.

An internal build, such as a QA build, can ship `android:debuggable="false"` and still want the
server. It opts in with `DebugConfig.builder().allowNonDebuggableBuilds(true)`. Never opt in for a
build that users get.

**Check it:** install the release build and open the app. `adb logcat -d -s LustroToken` prints
no `Lustro ready` line. When `adb logcat -d -s Lustro` prints the `Refusing to start` warning, the
release build has the real runtime: correct the dependencies, then check
[the no-op halves](#the-no-op-halves) again.
