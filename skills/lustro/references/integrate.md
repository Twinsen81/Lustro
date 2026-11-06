# Add Lustro to an Android app

Lustro goes into the debug build only. The release build gets a no-op artifact with the same API,
so the app's code compiles in both, and the release build has no server and no capture.

The facts below are for the version in the snippets. For a newer version and anything this file
leaves out, read [llms.txt](https://raw.githubusercontent.com/Twinsen81/Lustro/main/llms.txt) and
the [README](https://github.com/Twinsen81/Lustro/blob/main/README.md).

## 1. Read the project

- The app module, its build file (Kotlin or Groovy), the version catalog, the build types, and the
  product flavors.
- `minSdk`, `compileSdk`, the Android Gradle Plugin, Kotlin, and OkHttp. Lustro needs
  `compileSdk` 35, AGP 8.6, Kotlin 2.0, OkHttp 4.12.0, and, for the debug build only, `minSdk` 24
  (step 3). When the app's `compileSdk`, AGP, Kotlin, or OkHttp is older, it needs an upgrade,
  which changes its release build too: ask the user before you upgrade. Lustro's artifacts do not
  raise the versions of the app's dependencies.
- Each `OkHttpClient` that sends the app's requests, where the app builds it (often a dependency
  injection module), and its interceptors. Retrofit, Coil, and Ktor's OkHttp engine use one.
- How the app makes WebSockets, and its other HTTP clients, also those of its libraries, such as
  an image loader that uses `HttpURLConnection`.
- The `Application` class.
- Which plugin tabs have data: Logs for every app; Preferences for SharedPreferences or a
  Preferences DataStore; SQLite for Room or SQLite; WorkManager for WorkManager 2.12.0 or newer.
  With an older WorkManager, the tab answers `work_unsupported`. A newer WorkManager changes the
  release build too, so ask the user before you raise it, or leave the tab out.

## 2. Add the dependencies

Add each runtime with `debugImplementation` and its `-noop` half with `releaseImplementation`.
They declare the same Gradle capability, so one configuration cannot have both. Each other build
type needs one of the two as well, such as `stagingImplementation(libs.lustro.noop)`. Add only the
plugin pairs whose tabs you register.

```toml
# gradle/libs.versions.toml
[versions]
lustro = "0.1.0-SNAPSHOT"

[libraries]
lustro             = { group = "io.github.twinsen81", name = "lustro",             version.ref = "lustro" }
lustro-noop        = { group = "io.github.twinsen81", name = "lustro-noop",        version.ref = "lustro" }
lustro-logs        = { group = "io.github.twinsen81", name = "lustro-logs",        version.ref = "lustro" }
lustro-logs-noop   = { group = "io.github.twinsen81", name = "lustro-logs-noop",   version.ref = "lustro" }
lustro-prefs       = { group = "io.github.twinsen81", name = "lustro-prefs",       version.ref = "lustro" }
lustro-prefs-noop  = { group = "io.github.twinsen81", name = "lustro-prefs-noop",  version.ref = "lustro" }
lustro-sqlite      = { group = "io.github.twinsen81", name = "lustro-sqlite",      version.ref = "lustro" }
lustro-sqlite-noop = { group = "io.github.twinsen81", name = "lustro-sqlite-noop", version.ref = "lustro" }
lustro-work        = { group = "io.github.twinsen81", name = "lustro-work",        version.ref = "lustro" }
lustro-work-noop   = { group = "io.github.twinsen81", name = "lustro-work-noop",   version.ref = "lustro" }
```

```kotlin
// app/build.gradle.kts
dependencies {
    debugImplementation(libs.lustro)
    releaseImplementation(libs.lustro.noop)
    debugImplementation(libs.lustro.logs)
    releaseImplementation(libs.lustro.logs.noop)
}
```

```groovy
// app/build.gradle: Groovy, without a version catalog
dependencies {
    debugImplementation "io.github.twinsen81:lustro:0.1.0-SNAPSHOT"
    releaseImplementation "io.github.twinsen81:lustro-noop:0.1.0-SNAPSHOT"
}
```

A `-SNAPSHOT` version resolves only from the snapshots repository. Add it to
`dependencyResolutionManagement { repositories { } }`: in `settings.gradle.kts`,
`maven("https://central.sonatype.com/repository/maven-snapshots/")`; in `settings.gradle`,
`maven { url = "https://central.sonatype.com/repository/maven-snapshots/" }`.

## 3. A minSdk below 24

Do not raise the app's own `minSdk`. Raise it for the debug build only, in the app module (the same
text in Groovy):

```kotlin
androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variant ->
        variant.minSdk = 24
    }
}
```

## 4. Start Lustro

Put the Lustro code in an object with the same name in `src/debug` and in `src/release`, as the
sample app and the README's WorkManager section do. The two files have the same code: the debug
one compiles against the runtime, and the release one against the no-op artifacts, which do
nothing. `src/main` calls only the object's functions, and names no Lustro type. Each other build
type needs the file in `src/<build type>` too. A custom tab, a `DebugTab` subclass, goes only into
the `src/debug` file.

```kotlin
// src/debug/kotlin/<package>/LustroBootstrap.kt, and the same file in src/release
import android.app.Application
import io.github.twinsen81.lustro.Lustro
import io.github.twinsen81.lustro.logs.LogsDebugTab
import io.github.twinsen81.lustro.network.NetworkDebugTab
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.WebSocket

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

Add every tab before `build()`: after `start()`, `addTab()` throws. The other types are in
`io.github.twinsen81.lustro` (`ExperimentalPlatformCapture`), `.network` (`OkHttpSender`),
`.prefs` (`PrefsDebugTab`, `PrefsFile`, `ValuePolicy`, `PrefsDataStore`), `.sqlite`
(`SqliteDebugTab`, `SqliteDb`), and `.work` (`WorkDebugTab`).

- **Capture.** Add `LustroBootstrap.networkInterceptor()` with `addInterceptor`, not
  `addNetworkInterceptor`, to each `OkHttpClient` that sends the app's requests, after the app's
  interceptors that change the URL, the headers, or the body. A client made with `newBuilder()`
  runs the copied client's interceptors first, so Lustro's interceptor there does not see what
  the new client's own interceptors change.
- **WebSockets.** The interceptor shows only the handshake. Make the sockets with
  `LustroBootstrap.webSocketFactory(client).newWebSocket(request, listener)` to see their
  messages.
- **The Send form.** `NetworkDebugTab.create(senders = listOf(OkHttpSender.create("API", baseUrl = "https://api.example.com", client = client)))`,
  where `client` is a `() -> OkHttpClient`, lets the console send requests through the app's
  client. Lustro calls it only at the first send.
- **Other clients.** For `HttpURLConnection`, create the tab with
  `NetworkDebugTab.create(senderClient = null, capturePlatformHttp = true)`, or with `senders` in
  place of `senderClient`, and `@OptIn(ExperimentalPlatformCapture::class)`. The overloads that take
  `capturePlatformHttp` have no default for the first parameter. For another client, report each
  request to `lustro.networkCaptureSink()`.
- **Plugin tabs.**
  - Preferences: `PrefsDebugTab.create(app)` lists every file, masked and read-only. Register a
    file to see its values and edit them: `files = listOf(PrefsFile("settings", prefs, ValuePolicy.VISIBLE))`.
  - SQLite: `SqliteDebugTab.create(databases = listOf(SqliteDb.support("app") { db.openHelper.writableDatabase }), discoverFrom = app)`
    for a Room database `db`. The tab only reads it. With `writable = true`, the tab and an agent
    can change it: then also pass `onWrite = { db.invalidationTracker.refreshVersionsAsync() }`,
    so that the app's Room observers see the changes.
  - WorkManager: the app initializes WorkManager on demand (`Configuration.Provider`, and
    `WorkManagerInitializer` removed in the manifest, which needs `xmlns:tools`), and passes its
    `Configuration.Builder` through an object function that calls the tab's `configure()`.
    Follow the README's
    [WorkManager](https://github.com/Twinsen81/Lustro/blob/main/README.md#workmanager) section.

## 5. Check the integration

A build that compiles does not show that capture works. Run each check, and fix what fails. With
product flavors, use the names of one variant, such as `assembleFreeDebug` and
`freeReleaseRuntimeClasspath`.

1. `./gradlew :app:assembleDebug :app:assembleRelease`.
2. `./gradlew -q :app:dependencies --configuration releaseRuntimeClasspath | grep -E 'twinsen81|nanohttpd'`
   lists only `lustro-api` and the `-noop` artifacts.
3. Install the debug build and start the app: `lustro meta` lists the tabs. If it cannot connect,
   see "Connect" in [SKILL.md](../SKILL.md).
4. Make the app send a request: `lustro net list --last 5` shows it. If not, the interceptor is on
   another client than the one that sends.
5. For each plugin tab: `lustro api GET logs/text --param limit=20`, `lustro api GET prefs/files`,
   `lustro api GET sqlite/databases` (no `error`), and `lustro api GET work/items | jq -c .state`
   (`"listeners":"installed"`).
6. If you wrote a `DebugTab`, it is in `src/debug`, and `./gradlew :app:lintDebug` reports no
   `LustroDebugUsageInRelease`.

Report what you checked, and the result of each step.
