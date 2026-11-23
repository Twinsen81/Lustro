# Release safety

Lustro gives the app's internals to anyone who has the token, so it must never be in a build that
users get. Four protections keep it out of a release build. The first two depend on how the app
adds the dependencies, the third on where the app puts its own tabs, and the fourth needs nothing
from the app. Each section says how to check the protection in your app. An app can also leave
the no-op halves out, so that its release build has no Lustro class: see
[Without the no-op halves](#without-the-no-op-halves).

| Protection | What it stops | Where it acts |
| --- | --- | --- |
| [The no-op halves](#the-no-op-halves) | The server and the capture in a release build | Gradle dependencies |
| [The shared capability](#the-shared-capability) | The runtime and the no-op half on one configuration | Gradle resolution |
| [The lint check](#the-lint-check) | Your `DebugTab` classes in a release build | Lint, which `assembleRelease` runs |
| [The debuggable check](#the-debuggable-check) | A server, and the capture, in a build that is not debuggable | The app, at `start()` and in the network functions |

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

The no-op halves ship no keep rule, so R8 handles their classes as it handles the app's own code.
A minified release build keeps only the classes that the app's release code calls, such as the
pass-through interceptor, and the classes that a keep rule of the app matches. When the app's
release code does not call Lustro and no rule of the app matches a Lustro class, the build has no
Lustro class at all. A common rule matches three of them: see
[An app rule that keeps exceptions](#an-app-rule-that-keeps-exceptions). When R8 obfuscates, as it
does by default, it also gives the classes that the release code calls short names outside the
Lustro package.

**Check it:** `./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath | grep -E 'twinsen81|nanohttpd'`
lists only `lustro-api` and the `-noop` artifacts. Each build type other than `debug` needs the
no-op halves too, such as `stagingImplementation(libs.lustro.noop)`, unless the app uses the
[layout without the no-op halves](#without-the-no-op-halves). This is step 2 of [Check the integration](../get-started/check.md).
This is the check that matters: the release build has no runtime and no NanoHTTPD.

To see whether a class in the APK still has a Lustro name, build the release with
`./gradlew :app:assembleRelease`, then search the mapping file of R8: `grep ' -> io\.github\.twinsen81\.lustro\.' app/build/outputs/mapping/release/mapping.txt`.
With product flavors, use the variant's task and folder, such as `assembleFreeRelease` and
`mapping/freeRelease/mapping.txt`: a `No such file` error is not a result. When R8 obfuscates, the
command prints nothing, unless the app has the rule of the next section. When the app's rules keep
names, for example with `-dontobfuscate`, `-keepnames`, or `-keeppackagenames`, it prints the no-op
classes that the release code calls. These classes do nothing, so that is not a problem.

### An app rule that keeps exceptions

Many apps keep every public exception class with its name, so that crash reports show the app's
own exception names. The Firebase Crashlytics setup suggests this rule:

```text
-keep public class * extends java.lang.Exception
```

The rule acts on every class in the build, also on the classes of `lustro-api`, which the no-op
halves bring to the release classpath. So it keeps three Lustro classes, also when no release code
calls Lustro, and the mapping search above prints them:

```text
io.github.twinsen81.lustro.DebugResponseException -> io.github.twinsen81.lustro.DebugResponseException:
io.github.twinsen81.lustro.JsonException -> io.github.twinsen81.lustro.JsonException:
io.github.twinsen81.lustro.RouteTable$BadInput -> io.github.twinsen81.lustro.RouteTable$BadInput:
```

These classes are not a problem. The rule keeps no member, so in the APK each of them is empty:
it has a name and a superclass, but no field and no method. So they hold no server and no
capture, and no code uses them. `JsonException` and `RouteTable$BadInput` are internal to Lustro,
but Kotlin `internal` is public in bytecode, so the rule keeps them too. `DebugResponseException`
is public API.

When a release audit must find no Lustro class at all, use the
[layout without the no-op halves](#without-the-no-op-halves).

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

The check takes the source set from the path of the file below the module directory:
`src/<set>/java/...` or `src/<set>/kotlin/...`. Folders above the module, such as `~/src/` in
`~/src/MyApp/app/`, and package folders do not change the result. A class in a folder without this
layout, such as an extra source folder `app/debug-extra/`, is reported.

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

The network functions make the same check, also when the app never calls `start()`:
`networkInterceptor()` returns an interceptor that only passes each request on,
`webSocketFactory()` returns its delegate, and `networkCaptureSink()` returns a sink that records
nothing. So such a build does not keep its traffic in memory, and mock rules and the throttle do
not change its requests, also a rule that a `MockRuleStorage` kept from a debuggable build.

This check needs no Gradle setup and no lint run. It stops the server even when the dependencies
are wrong. It does not remove Lustro's code from the APK: only the no-op halves do that.

An internal build, such as a QA build, can ship `android:debuggable="false"` and still want the
server. It opts in with `DebugConfig.builder().allowNonDebuggableBuilds(true)`. Never opt in for a
build that users get.

**Check it:** install the release build and open the app. `adb logcat -d -s LustroToken` prints
no `Lustro ready` line. When `adb logcat -d -s Lustro` prints the `Refusing to start` warning, the
release build has the real runtime: correct the dependencies, then check
[the no-op halves](#the-no-op-halves) again.

## Without the no-op halves

An app can also leave the no-op halves out. It adds the runtime and the plugins with
`debugImplementation` only, and keeps every Lustro call in `src/debug`. The code in `src/main`
calls an object or an interface of the app's own, and a release implementation of it does
nothing:

```kotlin
// build.gradle.kts (app module): no releaseImplementation line
dependencies {
    debugImplementation(libs.lustro)
    debugImplementation(libs.lustro.logs)
}
```

```kotlin
// src/debug/kotlin/<package>/LustroBootstrap.kt: it calls Lustro
object LustroBootstrap {
    private lateinit var lustro: Lustro

    // First in Application.onCreate(), on the main thread, before the app builds its clients.
    fun start(app: Application) {
        lustro = Lustro.builder(app)
            .addTab(NetworkDebugTab.create())
            .addTab(LogsDebugTab.create())
            .build()
        lustro.start()
    }

    fun networkInterceptor(): Interceptor = lustro.networkInterceptor()

    fun webSocketFactory(client: OkHttpClient): WebSocket.Factory = lustro.webSocketFactory(client)
}
```

```kotlin
// src/release/kotlin/<package>/LustroBootstrap.kt: the same functions, and no Lustro type
object LustroBootstrap {
    fun start(app: Application) = Unit

    fun networkInterceptor(): Interceptor = Interceptor { chain -> chain.proceed(chain.request()) }

    fun webSocketFactory(client: OkHttpClient): WebSocket.Factory = client
}
```

`src/main` calls only these functions, such as `LustroBootstrap.networkInterceptor()` where it
builds its OkHttp client. A custom tab goes into the `src/debug` file only, as in
[Register the tab](../write-a-tab.md#11-register-the-tab). Each other build type needs the release
implementation in its own source set, such as `src/staging`, and no Lustro artifact.

The release classpath then has no Lustro artifact, so the release build has no Lustro class, also
with [the rule that keeps exceptions](#an-app-rule-that-keeps-exceptions). The guard is the
compiler: the release build compiles `src/main` without Lustro, so a Lustro call or a `DebugTab`
subclass in `src/main` fails the release build, with `Unresolved reference` in Kotlin and
`cannot find symbol` in Java. So this layout needs no lint check, and the release lint does not
load one: only `lustro` and `lustro-noop` ship it. The [debuggable check](#the-debuggable-check)
still stops the server when the runtime is in a release build by mistake.

Two dependencies of the app need care:

- The no-op halves bring OkHttp to the release classpath. An app that sends with OkHttp, but does
    not declare it, fails the release build: add `implementation(libs.okhttp)`.
- A library module of the app that depends on `lustro-api`, such as one with a custom tab, brings
    `lustro-api` back to the release classpath. Add it with `debugImplementation` too.

Choose this layout when no code in `src/main` names a Lustro type. Keep the no-op halves when it
does, such as a call to `lustro.networkInterceptor()` where the app builds its OkHttp client in
`src/main`, as the [dependency injection module](../get-started/app-code.md#dependency-injection)
does: the no-op halves exist for such calls, so that the same code compiles in both build types.
The cost of this layout is the release implementation, which the app writes and keeps up to date
itself. With the no-op halves, the `src/release` file can make the same Lustro calls as the
`src/debug` file, without its custom tabs.

**Check it:** `./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath | grep -E 'twinsen81|nanohttpd'`
prints nothing. After `./gradlew :app:assembleRelease`,
`grep '^io\.github\.twinsen81\.lustro\.' app/build/outputs/mapping/release/mapping.txt` prints
nothing: no class in the APK comes from Lustro, with its name or renamed.
