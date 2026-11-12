# Configuration

`DebugConfig` holds the options of the server and of the Network tab's capture. Build one with
`DebugConfig.builder()` and give it to the Lustro builder. An option that you do not set keeps its
default:

```kotlin
val lustro = Lustro.builder(application)
    .config(
        DebugConfig.builder()
            .serverPort(8081)
            .captureBudgetBytes(100L * 1024 * 1024)
            .build(),
    )
    .addTab(NetworkDebugTab.create())
    .build()
```

Without `config(...)`, Lustro uses `DebugConfig.DEFAULT`. `lustro-noop` has the same
`DebugConfig`, so this code compiles in a release build too.

## Server

| Option | Type | Default | Effect |
| --- | --- | --- | --- |
| `allowNonDebuggableBuilds` | `Boolean` | `false` | When `true`, `start()` opens the server in a build without `android:debuggable`. Only for internal builds: see [Release safety](../concepts/release-safety.md#the-debuggable-check). |
| `serverPort` | `Int` | `8080` | The port that the server binds on the device. Forward the same port: `adb forward tcp:8081 tcp:8081`. The CLI reads the port from the `LustroToken` line. |
| `bindAddress` | `String` | `"127.0.0.1"` | The address that the server binds. `"0.0.0.0"` makes it reachable from the network: see [LAN exposure](../concepts/lan.md). |
| `bindFallback` | `Boolean` | `false` | When `serverPort` is taken: with `true`, the server binds a port that the system picks, and the `LustroToken` line gives it. With `false`, it tries `serverPort` again for 10 s, then waits for the next foreground. |
| `allowedOrigins` | `List<String>` | empty | More origins that a browser can call the API from, on every method, such as `"http://192.168.1.42:8080"`. The server's own origin is always allowed. Other ports of `localhost` are not. See [Security model](../concepts/security.md). |

## Requests to the server

These options limit the requests to `/api/v1/`, the routes of every tab. The page and its
assets, such as `/tab/<id>` and `/shared.js`, are outside the limits.

| Option | Type | Default | Effect |
| --- | --- | --- | --- |
| `maxRequestBodyBytes` | `Long` | 1 MiB (`1L * 1024 * 1024`) | The largest request body. A larger one gets a `413`, and the server does not read it. |
| `maxConcurrentRequests` | `Int` | `16` | How many requests the tabs handle at the same time. A request that timed out keeps its slot until its handler returns. |
| `requestQueueCapacity` | `Int` | `64` | How many requests wait for a slot. A request that waits longer than `requestTimeoutMs`, or that finds the queue full, gets a `503`. The server keeps at most `maxConcurrentRequests + requestQueueCapacity + 16` connections open. |
| `requestTimeoutMs` | `Long` | `30000` | How long a tab can take for one request. A request that runs longer gets a `504`, and the server cancels it: see [Custom tabs](../custom-tabs.md). The Network tab's Send form also stops a request that it sends after this time. |

## Network tab capture

`build()` gives these options to the [Network tab](../network/okhttp.md). They have no effect on
the other tabs.

| Option | Type | Default | Effect |
| --- | --- | --- | --- |
| `maxCaptureTransactions` | `Int` | `1000` | How many HTTP transactions the tab keeps. Past it, the oldest go first. |
| `captureBudgetBytes` | `Long` | 50 MiB (`50L * 1024 * 1024`) | The memory for the bodies that the tab keeps. A body that the tab does not keep, such as a media download, costs nothing. Past the budget, the oldest finished transactions go first. |
| `maxBodyCaptureBytes` | `Long` | 256 KiB (`256L * 1024`) | The bytes kept of each request body, each response body, and each WebSocket message. The Send form also reads at most this much of a response: see [Send Request](../network/send-request.md). |
| `maxCaptureWebSockets` | `Int` | `100` | How many WebSocket connections the tab keeps. See [WebSocket messages](../network/websockets.md). |
| `maxWebSocketEvents` | `Int` | `1000` | How many messages and lifecycle events the tab keeps for each WebSocket connection. |
| `webSocketCaptureBudgetBytes` | `Long` | 16 MiB (`16L * 1024 * 1024`) | The memory for all the WebSocket message payloads that the tab keeps. |
| `appServerBaseUrl` | `String?` | `null` | The base URL for a relative URL in the Send form, when the tab sends through `NetworkDebugTab.create(senderClient = ...)`. A sender from `OkHttpSender.create` has its own base URL. See [Send Request](../network/send-request.md). |

## The options of each tab

The other options belong to a tab, and its factory takes them, such as the redactor and the mock
rule storage of the Network tab, or the value policy of a Preferences file. Each tab's page lists
them:

| Tab | Factory | Options |
| --- | --- | --- |
| Network | `NetworkDebugTab.create(...)` | [OkHttp capture setup](../network/okhttp.md), [Send Request](../network/send-request.md), [Mock rules](../network/mocks.md), [Platform HttpURLConnection capture](../network/httpurlconnection.md) |
| Logs | `LogsDebugTab.create(...)` | [Register the tab](../plugins/logs.md#register-the-tab), [Safety options](../plugins/logs.md#safety-options) |
| Preferences | `PrefsDebugTab.create(...)` | [Register the tab](../plugins/prefs.md#register-the-tab), [Safety options](../plugins/prefs.md#safety-options) |
| SQLite | `SqliteDebugTab.create(...)` | [Register the tab](../plugins/sqlite.md#register-the-tab), [Safety options](../plugins/sqlite.md#safety-options) |
| WorkManager | `WorkDebugTab.create(...)` | [Register the tab](../plugins/work.md#register-the-tab), [Safety options](../plugins/work.md#safety-options) |
| Screen | `ScreenDebugTab.create(...)` | [Register the tab](../plugins/screen.md#register-the-tab), [Safety options](../plugins/screen.md#safety-options) |

The [Kotlin API reference](https://twinsen81.github.io/Lustro/api/) has the KDoc of `DebugConfig`
and of each factory.
