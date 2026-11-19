# Use Lustro from your app's code

The other pages connect to Lustro from a computer, through `adb forward`. This page is about the
code in your app that works together with Lustro: a dependency injection graph that builds it, a
server picker that changes where the app sends its requests, a developer screen that shows how to
connect, and an instrumentation test that reads the captured requests.

## Dependency injection

Build Lustro in `Application.onCreate()`, before the first activity starts. A tab that follows the
app's activities, such as the [Screen](../plugins/screen.md) tab, misses the activity that is
already on the screen when Lustro is built later, on first use.

With Hilt or Dagger, the graph looks like a cycle: `lustro.networkInterceptor()` needs a built
`Lustro`, and a sender of the Send form needs the app's `OkHttpClient`. It is not a cycle, because
`OkHttpSender.create(...) { client }` takes the client as a function and calls it only at the first
send. So the `Lustro` provider takes a `Provider<OkHttpClient>` (or a `dagger.Lazy<OkHttpClient>`),
and the client's provider takes `Lustro`:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object LustroModule {
    @Provides
    @Singleton
    fun lustro(app: Application, client: Provider<OkHttpClient>): Lustro =
        Lustro.builder(app)
            .addTab(
                NetworkDebugTab.create(
                    // client.get() runs at the first send, after the graph is built.
                    senders = listOf(OkHttpSender.create("API", baseUrl = "https://api.example.com") { client.get() }),
                ),
            )
            .build()

    @Provides
    @Singleton
    fun okHttpClient(lustro: Lustro): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(lustro.networkInterceptor())
            .build()
}

@HiltAndroidApp
class App : Application() {
    @Inject lateinit var lustro: Lustro

    override fun onCreate() {
        super.onCreate() // Hilt injects the fields here.
        lustro.start()
    }
}
```

Do not call `client.get()` in the `Lustro` provider itself: Dagger then reports the cycle, or
builds the client before `Lustro`. `NetworkDebugTab.create(senderClient = client)` takes the client
itself, not a function, so it does not break the cycle: use `senders` as above.

The module can stay in `src/main`: `lustro-noop` has the same API, so the release build compiles it
against the no-op halves. Your own `DebugTab` subclasses must still be in `src/debug` (see
[Release safety](../concepts/release-safety.md)).

## A server that changes at runtime

When the app can switch its server while it runs, such as with a server picker in a debug menu,
give the sender's base URL as a function. The Network tab calls it for each send and each time the
Send form opens:

```kotlin
OkHttpSender.create("API", baseUrlProvider = { serverSettings.currentBaseUrl }) { client.get() }
```

See [Send Request](../network/send-request.md#a-base-url-that-changes).

## Show how to connect

`lustro.endpoint()` returns where the server listens and its token, or `null` when it does not
listen: before `start()`, after `stop()`, while the app is in the background, and when the bind
failed. A developer screen in the app can show the `adb forward` command, and the Wi-Fi URL when
the app opts into [LAN exposure](../concepts/lan.md):

```kotlin
val endpoint = withContext(Dispatchers.IO) { lustro.endpoint() } // The first call can read a file.
if (endpoint != null) {
    adbCommand = "adb forward tcp:${endpoint.port} tcp:${endpoint.port}"
    wifiUrls = endpoint.lanUrls // Empty unless DebugConfig.bindAddress opts into LAN exposure.
}
```

`endpoint.host` and `endpoint.url` are for a client on the device, or on a computer through
`adb forward`: `127.0.0.1`, also when the server listens on all interfaces. `endpoint.port` is the
port in use, which differs from `DebugConfig.serverPort` after a fallback bind.

The token gives full access to the app's data through the server. Show it only in a debug build,
and do not send it off the device. `lustro-noop` returns `null`. Read the endpoint this way, not
from Lustro's private prefs file, whose format can change.

## Instrumentation tests

A test in the app's process can call the HTTP API on `127.0.0.1` with the token from
`lustro.endpoint()`. With `since`, it gets only the requests that started after a step of the test.
The server listens only while the app is in the foreground, so launch an activity first:

```kotlin
@Test
fun refreshSendsOneFeedRequest() {
    ActivityScenario.launch(MainActivity::class.java).use {
        val lustro = ApplicationProvider.getApplicationContext<App>().lustro
        val endpoint = checkNotNull(lustro.endpoint()) { "Lustro does not listen" }
        val since = System.currentTimeMillis()

        onView(withId(R.id.refresh)).perform(click())

        // A client without the Lustro interceptor, so that these calls are not captured.
        val client = OkHttpClient()
        val request =
            Request.Builder()
                .url("${endpoint.url}/api/v1/network/transactions?since=$since&search=/feed")
                .header("Authorization", "Bearer ${endpoint.token}")
                .build()
        val items = client.newCall(request).execute().use { JSONObject(it.body!!.string()).getJSONArray("items") }
        assertEquals(1, items.length())
    }
}
```

`since` compares with each transaction's `startedAt`, which the device's clock sets. A test in the
app's process has the same clock. A client on a computer takes the time from the device, such as the
`startedAt` of a transaction it already has. A request that is still in flight is listed with
`responseComplete: false`; poll again until it is `true` when the test checks the response.

**Port 8080 and a mock server in the test.** Lustro's default port, 8080, is also the default port
of WireMock, which UI tests often start in the app's process. An app that calls `start()` in
`Application.onCreate()` then takes the port first, and the mock server fails to start, or the
other way around. Choose one of these:

- Give Lustro another port in the build that the tests run, with `DebugConfig.serverPort(...)`.
  `serverPort(0)` lets the system choose a free port, and `endpoint().port` gives it.
- Let Lustro move when the port is taken, with `DebugConfig.bindFallback(true)`. It then binds a
  port that the system chooses. This does not help when the mock server starts after Lustro.
- Do not call `start()` under tests, when the tests do not use Lustro.
- Start the mock server on another port.
