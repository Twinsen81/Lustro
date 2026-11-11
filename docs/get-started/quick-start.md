# Quick start

Build the runtime once (typically in your `Application`), register tabs, wire the OkHttp
interceptor, and start it:

```kotlin
lateinit var httpClient: OkHttpClient

val lustro = Lustro.builder(application)
    .addTab(
        NetworkDebugTab.create(
            // The console's Send form sends through httpClient. Lustro asks for it only when you
            // send a request, so it can be the client built below (see Send Request).
            senders = listOf(OkHttpSender.create("API", baseUrl = "https://api.example.com") { httpClient }),
        ),
    )
    .build()

httpClient = OkHttpClient.Builder()
    .addInterceptor(lustro.networkInterceptor())
    .build()

lustro.start()
```

If the app uses WebSockets, create them with `lustro.webSocketFactory(httpClient)` to see their
messages too: see [WebSocket messages](../network/websockets.md).

Add every tab before `build()`. `start()` freezes the tab registry, and `addTab()` after it
throws `IllegalStateException`.
`start()` returns a `LustroStatus` (`ENABLED` once armed, `DISABLED` if it cannot start) and is
idempotent, as is `stop()`.

Call `start()` on the main thread, as `Application.onCreate()` does. It adds an observer to the
process lifecycle, which accepts observers only on the main thread, so from any other thread
`start()` returns `LustroStatus.DISABLED`.

Three things keep that snippet out of production, in order of how much they depend on you:

> **Your `DebugTab` subclasses must live in `src/debug`.** They are your code, so swapping
> `:lustro` for `:lustro-noop` cannot remove them from a release APK — whatever source set they
> sit in is compiled into that variant, taking the app internals they read with them. Lustro
> ships a published Android Lint check (`LustroDebugUsageInRelease`, severity FATAL) in both
> `:lustro` and `:lustro-noop`, so the debug and the release lint load it. It flags a `DebugTab`
> subclass in a source set whose name does not end with `debug`, such as `src/main` or
> `src/release`. `assembleRelease` and `bundleRelease` run the release lint (`lintVitalRelease`),
> so a release build with such a class fails. The builder call above is
> *not* flagged — it resolves to whichever facade the variant depends on. Putting the whole
> bootstrap under `src/debug/` too (as the [`:sample`](https://github.com/Twinsen81/Lustro/tree/main/sample) app does) is still the cleanest layout, because
> then release compiles none of it at all.

> **The release swap makes the API inert.** With `releaseImplementation(libs.lustro.noop)`, the
> `Lustro.builder(...)` / `.addTab(...)` / `start()` calls in the snippet compile unchanged and
> do nothing: no socket, no capture, `networkInterceptor()` forwards every request, and
> `webSocketFactory(client)` returns `client` itself.

> **The runtime refuses to start in a non-debuggable build.** If the real `:lustro` runtime ever
> reaches a build without `android:debuggable`, `start()` logs a WARN and returns
> `LustroStatus.DISABLED` without binding a socket — no Gradle wiring and no lint run required.
> An internal build that ships `android:debuggable="false"` but still wants the server can opt
> in with `DebugConfig.builder().allowNonDebuggableBuilds(true)`. When its custom tabs are in
> the source set of its build type or product flavor, such as `src/internal`, its build fails on
> the lint check above: add `@Suppress("LustroDebugUsageInRelease")` (in Java, `@SuppressLint`)
> to each of those tab classes. A tab in `src/<flavor>Debug` is not reported.
