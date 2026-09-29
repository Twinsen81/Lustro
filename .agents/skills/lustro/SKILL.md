---
name: lustro
description: Adds Lustro, a browser-based, agent-friendly debugging library for Android, to an Android app. Covers the debug and no-op artifacts split by build type, the snapshot repository, starting the embedded debug server in the Application, where the OkHttp capture interceptor goes, custom debug tabs in src/debug, and opening the debug console in a desktop browser over adb. Use when a developer asks to add or set up Lustro in an Android app, to add a network inspector, OkHttp traffic capture, response mocking, or a browser-based debug console to an app, or when an app's Lustro setup does not work (nothing captured, 401, cannot connect).
---

# Add Lustro to an Android app

Lustro runs a small web server inside the debug builds of an Android app. A desktop browser opens
its tabs over `adb forward`. The built-in Network tab captures OkHttp traffic, and can mock
responses, throttle connections, and replay requests. Every tab is also a JSON API under
`/api/v1/`, so agents and scripts can use it too.

Do the steps in order. The code comes from Lustro's
[README](https://github.com/Twinsen81/Lustro/blob/main/README.md) and its sample app. Lustro's
types are in two packages: `io.github.twinsen81.lustro` (`Lustro`, `DebugConfig`, `DebugTab`,
`DebugRequest`, `DebugResponse`) and `io.github.twinsen81.lustro.network` (`NetworkDebugTab` and
the capture options).

## Rules

- Add `lustro` to debug builds only, and `lustro-noop` to every other build type. Never put both
  on one configuration: they declare the same Gradle capability, so dependency resolution fails.
- Build Lustro once per process, in `Application.onCreate()`, and call `start()` there. `start()`
  must run on the main thread: from another thread it returns `LustroStatus.DISABLED`.
- Register every tab before `start()`. Lustro ignores tabs added after it.
- Add `lustro.networkInterceptor()` with `addInterceptor`, not `addNetworkInterceptor`, and add it
  after the app's own interceptors.
- The client you pass as `senderClient` exists before `lustro` does, so it cannot have Lustro's
  interceptor.
- Put `DebugTab` subclasses in `src/debug`.
- Ask the developer before you raise the app's `minSdk`, suppress the `LustroDebugUsageInRelease`
  lint check, or set `allowNonDebuggableBuilds(true)`.

## Step 1: Examine the app

1. Find the app module: the module that applies `com.android.application`. Lustro needs `minSdk`
   26 or higher. If the app's `minSdk` is lower, stop and ask the developer.
2. Find the build types in `android { buildTypes { ... } }`. Each build type needs one of the two
   artifacts.
3. Find the `Application` subclass: the `android:name` of `<application>` in
   `AndroidManifest.xml`. The app may not have one.
4. Find each place that builds an `OkHttpClient`: `OkHttpClient()`, `OkHttpClient.Builder()`,
   `newBuilder()`, and DI modules. Retrofit uses OkHttp. Lustro captures the traffic of each
   client that has its interceptor. For HTTP calls that do not use OkHttp, see
   [Traffic outside OkHttp](#traffic-outside-okhttp).
5. Find where the project declares repositories: `dependencyResolutionManagement` in
   `settings.gradle(.kts)`, or `allprojects { repositories { ... } }` in the root
   `build.gradle(.kts)`. Note whether the project has a version catalog
   (`gradle/libs.versions.toml`).

## Step 2: Add the dependencies

With a version catalog:

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

Without a version catalog:

```kotlin
// build.gradle.kts (app module)
dependencies {
    debugImplementation("io.github.twinsen81:lustro:0.1.0-SNAPSHOT")
    releaseImplementation("io.github.twinsen81:lustro-noop:0.1.0-SNAPSHOT")
}
```

Add `lustro-noop` to each other build type the same way, for example
`"stagingImplementation"(libs.lustro.noop)` in the Kotlin DSL. Use `lustro` for such a build type
only if the developer wants the console there, and the build type is debuggable.

A `-SNAPSHOT` version resolves only from the Sonatype Central snapshots repository. For such a
version, add that repository next to the existing ones, where the project declares them:

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://central.sonatype.com/repository/maven-snapshots/")
    }
}
```

In the Groovy DSL, the line is `maven { url 'https://central.sonatype.com/repository/maven-snapshots/' }`.

Lustro's manifest declares the `INTERNET` permission. Lustro needs no network security config and
no `usesCleartextTraffic`: they control the connections the app makes, not the socket that Lustro
listens on.

## Step 3: Build and start Lustro in the Application

If the app has no `Application` subclass, create one in `src/main`, and set it as the
`android:name` of `<application>` in `AndroidManifest.xml`.

In `onCreate()`, start an `OkHttpClient.Builder` with the app's own interceptors. Build the sender
client from it, then build Lustro, and add Lustro's interceptor last:

```kotlin
import android.app.Application
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.network.NetworkDebugTab
import okhttp3.OkHttpClient

class App : Application() {
    lateinit var httpClient: OkHttpClient
        private set

    override fun onCreate() {
        super.onCreate()

        val builder = OkHttpClient.Builder()
        // Add the app's own interceptors to the builder here, before Lustro's.
        val lustro = Lustro.builder(this)
            .addTab(NetworkDebugTab.create(senderClient = builder.build()))
            .build()
        httpClient = builder.addInterceptor(lustro.networkInterceptor()).build()
        lustro.start()
    }
}
```

The sender client powers the Send Request panel of the Network tab. A replay through it goes
through the app's interceptors, but Lustro does not capture it.

This code compiles in every build type. `lustro-noop` has the same API, and it does nothing: no
server, no capture, and `networkInterceptor()` passes each request through unchanged.

If the app builds its `OkHttpClient` in a different place, such as a DI module or a singleton:

- Still build Lustro and call `start()` in `Application.onCreate()`. Keep the `lustro` instance
  where that code can get it, for example in a property of the `Application`.
- In that code, add `lustro.networkInterceptor()` with the last `addInterceptor` call.
- Lustro must exist before that code builds the client. Code that runs in `super.onCreate()`, such
  as Hilt field injection into the `Application`, runs before the rest of `onCreate()`.
- Add the interceptor once. A client that `newBuilder()` makes from a client with the interceptor
  already has it.

## Step 4: Optional additions

### Custom tabs

A custom tab is a `DebugTab` subclass. Put it in `src/debug`: the no-op artifact cannot remove the
app's own code from a release build. The published lint check `LustroDebugUsageInRelease` reports
a `DebugTab` subclass in any other source set.

```kotlin
class FlagsTab(private val flags: FeatureFlagRepository) : DebugTab() {
    override val id = "flags"
    override val title = "Feature Flags"
    override val icon = "🚩"

    override fun renderContent() = """<div id="flags-list"></div>"""

    // request.path is the remainder after /api/v1/flags/; null -> enveloped 404,
    // anything thrown, even TODO(), -> enveloped 500 (it never escapes into your app).
    override fun handle(request: DebugRequest): DebugResponse? = when (request.path) {
        "list" -> DebugResponse.ok(flags.toJson())
        else -> null
    }
}
```

Code in `src/main` cannot refer to a class that exists only in `src/debug`. So register custom tabs
from a bootstrap that has one copy for each build type, as Lustro's sample app does:

1. Move the Lustro code from Step 3 into `src/debug/kotlin/<package>/LustroBootstrap.kt`, and
   register the custom tabs there. Use `java/` instead of `kotlin/` if the app keeps its Kotlin
   files there.
2. Add `src/release/kotlin/<package>/LustroBootstrap.kt` with the same signature and without the
   custom tabs. Add a copy for each other build type too.
3. Call the bootstrap from the `Application`.

```kotlin
// src/debug/kotlin/<package>/LustroBootstrap.kt
object LustroBootstrap {
    fun start(app: Application, senderClient: OkHttpClient): Interceptor {
        val lustro = Lustro.builder(app)
            .addTab(NetworkDebugTab.create(senderClient = senderClient))
            .addTab(FlagsTab(flagsRepo)) // the app's custom tabs
            .build()
        val interceptor = lustro.networkInterceptor()
        lustro.start()
        return interceptor
    }
}
```

```kotlin
// src/release/kotlin/<package>/LustroBootstrap.kt
object LustroBootstrap {
    fun start(app: Application, senderClient: OkHttpClient): Interceptor {
        val lustro = Lustro.builder(app)
            .addTab(NetworkDebugTab.create(senderClient = senderClient))
            .build()
        val interceptor = lustro.networkInterceptor()
        lustro.start()
        return interceptor
    }
}
```

```kotlin
// Application.onCreate() in src/main
val builder = OkHttpClient.Builder()
val client = builder.build()
val interceptor = LustroBootstrap.start(this, client)

httpClient = builder.addInterceptor(interceptor).build()
```

Tab rules:

- `id` must match `[a-z][a-z0-9-]{0,30}`. `title` and `icon` are required.
- `handle()` runs off the main thread, and calls can run at the same time. Keep the tab's mutable
  state thread-safe.
- Change state only on `POST`, `PUT`, `PATCH`, or `DELETE`, never on `GET` or `HEAD`.
- Escape every value that you render: `String.escapeHtml()` in Kotlin, `debugEscapeHtml(text)` in
  tab JavaScript. Tab JavaScript loads as an external script, so use no inline `<script>` or
  `onclick` handlers.
- An agent finds a tab in `/api/v1/_meta` only if the tab has a schema: an OpenAPI document at
  `src/debug/assets/lustro/<id>.openapi.json`, or an override of `schema()`.

The README's "Custom tabs" section and `docs/STYLEGUIDE.md` have the rest.

### Traffic outside OkHttp

- Ktor with the OkHttp engine: add the interceptor in the engine configuration,
  `HttpClient(OkHttp) { engine { addInterceptor(lustro.networkInterceptor()) } }`.
- `HttpURLConnection` traffic, for example from Volley or from an SDK: the experimental platform
  capture. It installs one hook for the whole process, and it skips capture if it cannot install.
  Use it only if the developer wants that traffic:

  ```kotlin
  @OptIn(ExperimentalPlatformCapture::class)
  val tab = NetworkDebugTab.create(senderClient = client, capturePlatformHttp = true)
  ```

- Lustro does not capture HTTP clients that use neither OkHttp nor `HttpURLConnection`, such as
  Ktor's CIO engine.

### Other options

- `NetworkDebugTab.create(...)` also takes a `captureFilter` (leave requests out, for example
  analytics calls), a `classifier` (label traffic), a `redactor` (mask more sensitive data), and a
  `mockRuleStorage` (keep mock rules after a restart, for example
  `SharedPreferencesMockRuleStorage`).
- `Lustro.builder(app).config(DebugConfig.builder()...build())` sets the port (`serverPort`,
  default 8080), `bindFallback`, and `appServerBaseUrl`, the base of relative Send Request URLs.
  `bindAddress("0.0.0.0")` makes the app's captured traffic available to the whole network: set it
  only if the developer asks for it.

## Step 5: Verify

1. Build both variants, with the name of the app module: `./gradlew :app:assembleDebug
   :app:assembleRelease`.
2. Run the app's lint, for example `./gradlew :app:lintDebug`, and make sure that it reports no
   `LustroDebugUsageInRelease` issue.
3. Choose the device. If `ANDROID_SERIAL` is set, adb uses that device. If it is not set and more
   than one device is connected, ask the developer which device to use, because other tools can
   be using the other devices. Then add `-s <serial>` to each `adb` command.
4. Install and start the debug build, and keep the app in the foreground: the server listens only
   while the app is in the foreground.
5. Read the endpoint and the token. With `-d`, logcat prints the log and exits. Do not clear the
   log: the last line is the current one.

   ```bash
   adb logcat -d -s LustroToken
   # Lustro ready endpoint=http://127.0.0.1:8080 token=<token>
   ```

   Lustro logs this line each time the server starts to listen. If it is missing, see
   [Troubleshooting](#troubleshooting).
6. Forward the port from the endpoint, and call the API with the token:

   ```bash
   adb forward tcp:8080 tcp:8080
   curl -s -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/_meta
   ```

   The response lists the `network` tab. If `adb forward` fails with "Address already in use",
   another program uses that local port. Forward a free local port to the same device port, for
   example `adb forward tcp:18080 tcp:8080`, and use `http://localhost:18080`. Current browsers
   open the console on that port too.
7. Make the app send a request, then read the captured traffic. A request shows up a moment after
   it completes, so read again if it is not there yet:

   ```bash
   curl -s -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/network/transactions
   ```

8. Tell the developer how to open the console: after `adb forward`, open
   `http://localhost:8080/#lustro_token=<token>` once in a desktop browser. The page keeps the
   token in a cookie and removes it from the address bar. If the `lustro` CLI is installed,
   `lustro open` forwards the port and opens the page.

## Troubleshooting

- **Dependency resolution fails.** For a `-SNAPSHOT` version, add the snapshots repository (Step
  2). A capability conflict between `lustro` and `lustro-noop` means that both are on one
  configuration, for example `implementation(libs.lustro)` with
  `releaseImplementation(libs.lustro.noop)`.
- **The manifest merger fails on `minSdk`.** Lustro needs `minSdk` 26. Ask the developer, and do
  not raise it on your own.
- **Lint reports `LustroDebugUsageInRelease`.** A `DebugTab` subclass is outside `src/debug`. Move
  it there, and register it as [Custom tabs](#custom-tabs) shows.
- **There is no `LustroToken` line.** Run `adb logcat -d -s Lustro` to find the reason:
  - "Refusing to start": the build is not debuggable.
  - "Failed to arm the debug server": read the exception. `start()` ran on a thread other than the
    main thread, or two tabs have the same id.
  - "is taken and bindFallback=false": a different process uses the port. Set a different
    `serverPort`, or set `bindFallback(true)` and read the port from the `LustroToken` line.

  If `Lustro` logged nothing, make sure that the code calls `start()` and that the app is in the
  foreground. The line can also be gone from the log buffer: put the app in the background and
  back in the foreground, and Lustro logs it again.
- **Cannot connect.** Run `adb forward tcp:8080 tcp:8080`, and make sure that the app is in the
  foreground. Use the device port from `endpoint=` in the `LustroToken` line. If the local port is
  in use, forward a different local port (Step 5).
- **`401 unauthorized`.** The request has no valid token. In a browser, open the page with
  `#lustro_token=<token>` once, or use `lustro open`. Other clients send
  `Authorization: Bearer <token>`. The token stays the same until the app's data is cleared.
- **Nothing is captured.** Add `lustro.networkInterceptor()` to the client that makes the calls,
  after the interceptors that change the URL, the headers, or the body. Make sure that capture is
  not paused in the Network tab. `HttpURLConnection` traffic needs `capturePlatformHttp = true`. If
  the app sets a capture filter, `state.captureFilter.skipped` in the transactions response counts
  the requests that it left out.

## After the integration

To use a running Lustro from an agent (authentication, `_meta`, schemas, the Network routes, mock
rules, replays), read [docs/AGENTS.md](https://github.com/Twinsen81/Lustro/blob/main/docs/AGENTS.md).
