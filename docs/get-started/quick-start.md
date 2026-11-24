# Quick start

First add the two halves of `lustro`, as [Install](install.md) shows. Then build Lustro once, usually in your `Application`, add the tabs,
add the interceptor to your OkHttp client, and start Lustro:

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

Add every tab to the builder before `build()`. `start()` fixes the list of tabs: a call to the
builder's `addTab()` after `start()` throws `IllegalStateException`. `start()` returns a
`LustroStatus`: `ENABLED` when the server is ready to open, or `DISABLED` when it cannot start
(see [Troubleshooting](../reference/troubleshooting.md#start-returns-disabled)). A call of
`start()` after it returned `ENABLED`, or of `stop()` after Lustro stopped, has no effect.

Call `start()` on the main thread, as `Application.onCreate()` does. It adds an observer to the
process lifecycle, which accepts observers only on the main thread, so from any other thread
`start()` returns `LustroStatus.DISABLED`.

Four protections keep Lustro out of a release build: the no-op halves, the shared Gradle
capability, a lint check, and a check at `start()` that refuses a build that is not debuggable.
One of them depends on you: **put your `DebugTab` subclasses in `src/debug`.** They are your code,
so the no-op swap cannot remove them, and the release build fails on one in `src/main` or
`src/release`. [Release safety](../concepts/release-safety.md) explains each protection and how to
check it.

## Open the console

Run the debug build and keep the app in the foreground. Then open the console:

```bash
lustro open
```

The [`lustro` CLI](install.md#the-lustro-cli) forwards the port, reads the token, and opens the
console in your browser with it. To do the same without the CLI, see
[Accessing the UI](console.md). [Check the integration](check.md) shows that the capture works
and that the release build has no runtime.
