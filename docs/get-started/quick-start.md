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

Four protections keep Lustro out of a release build: the no-op halves, the shared Gradle
capability, a lint check, and a check at `start()` that refuses a build that is not debuggable.
One of them depends on you: **put your `DebugTab` subclasses in `src/debug`.** They are your code,
so the no-op swap cannot remove them, and the release build fails on one in `src/main` or
`src/release`. [Release safety](../concepts/release-safety.md) explains each protection and how to
check it.
