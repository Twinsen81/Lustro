# Troubleshooting

Each section starts with what you see, then gives the causes and what to do, in the order to try
them.

## The browser cannot connect

Start with `lustro ready --package <your.app.id>`. It waits until the server answers and prints
its endpoint, or it names the cause and exits with its code: no device (3), the app is not
installed (4), the build has no Lustro runtime (5), or the app is not running, not in the
foreground, or its port does not answer (6). [Exit codes](../AGENTS.md#exit-codes) says what to do
for each. When it names no cause that helps, check these steps by hand:

1. Keep the app in the **foreground**. The server listens only while the app is in the
    foreground, and closes its socket when the app goes to the background.
2. Forward the port: `adb forward tcp:8080 tcp:8080`. `lustro open` does it for you.
3. Read the address of the last bind from the log:

    ```bash
    adb logcat -d -s LustroToken
    # Lustro ready endpoint=http://127.0.0.1:8080 token=<token>
    ```

    With `bindFallback`, a server whose port was taken binds a port that the system chooses, and
    this line gives it. Without `bindFallback`, the server tries the taken port again every
    250 ms for 10 s. A port can be taken by another app with Lustro that still holds it while it
    goes to the background. After 10 s, `adb logcat -d -s Lustro` has
    `is taken and bindFallback=false; not starting`, and the server tries again only when the app
    comes to the foreground again.
4. When the log has no `LustroToken` line, the device can drop Info logs. See
    [Accessing the UI](../get-started/console.md) for the prefs file that has the same values.

## `401 unauthorized`

The request has no valid token.

- **In a browser:** open the console once with `#lustro_token=<token>` at the end of the URL, or
    with `lustro open`.
- **In a script:** send `Authorization: Bearer <token>`. Get the token with
    `adb logcat -d -s LustroToken`, or with `lustro open --print-only --json`, which prints
    `token` and `url`.
- **After the app's data is cleared:** the app makes a new token. The last `LustroToken` line has
    it.
- **After the app restarts, with LAN exposure:** when `DebugConfig.bindAddress` is not loopback,
    each process of the app makes a new token. An open console tab shows "Signed out": append
    `#lustro_token=<token>` with the token of the last `LustroToken` line to its URL. The CLI
    finds the new token through adb, but not when `--token` or `LUSTRO_TOKEN` gives it one: pass
    the new token, or remove the variable. See
    [LAN exposure](../concepts/lan.md#a-new-token-with-each-process).

## `start()` returns DISABLED

`Lustro.start()` returns `LustroStatus.DISABLED`, does not open the server, and logs a warning at
the `Lustro` tag. It does not throw. Read the cause with `adb logcat -d -s Lustro`:

| Cause | Warning | What to do |
| --- | --- | --- |
| `start()` ran on a thread that is not the main thread. | `Failed to arm the debug server`, with an exception from the process lifecycle. | Call `start()` on the main thread, such as in `Application.onCreate()`. |
| The build is not debuggable. | `Refusing to start: this app is not marked debuggable.` | Use a debug build. For an internal build that must ship `android:debuggable="false"`, see [Release safety](../concepts/release-safety.md#the-debuggable-check). |
| Two tabs have the same id. | `Failed to arm the debug server`, with `Duplicate debug tab id '<id>'`. | Give each tab its own id. The ids of Lustro's tabs are `network`, `logs`, `prefs`, `sqlite`, `work`, and `screen`. |

`addTab()` checks the form of an id at once, but it does not check that the id is unique: only
`start()` does. See [Custom tabs](../custom-tabs.md).

## Nothing is captured

1. Add `lustro.networkInterceptor()` to the client that sends the requests, with
    `addInterceptor`, and **after** the interceptors that change the URL, the headers, or the body.
    See [OkHttp capture setup](../network/okhttp.md).
2. Make sure that capture is not paused. While it is paused, the Network tab's button says
    **▶ Resume**, and `lustro net state` says that it is paused.
3. For `HttpURLConnection` traffic, opt in: pass `capturePlatformHttp = true` to
    `NetworkDebugTab.create`, under `@OptIn(ExperimentalPlatformCapture::class)`. See
    [Platform HttpURLConnection capture](../network/httpurlconnection.md). It is not a
    `DebugConfig` option.
4. For another HTTP client, write an adapter: see [Other HTTP clients](../network/other-clients.md).

## The capture filter left out a request

A request that the app's `NetworkCaptureFilter` leaves out is never in the list. Mock rules and
the throttle still apply to it.

- **In the console:** a ⚠ shows next to the **HTTP** and **WebSockets** buttons. Its tooltip has
    the number of requests that the filter left out. A click on it shows the filter's
    description, and how many requests it left out and how many it threw on.
- **From the CLI:** `lustro net state` prints the same. In the API, the poll's `state.captureFilter`
    has `description`, `skipped`, and `failed`.

The counts start again at 0 when you clear the list. To capture the request, change the filter in
the app's code. See [OkHttp capture setup](../network/okhttp.md).

## A WebSocket shows only its handshake

The interceptor does not see the messages of a WebSocket. Create the socket with
`lustro.webSocketFactory(httpClient)`, or give that factory to the library that creates it. See
[WebSocket messages](../network/websockets.md).

## The CLI or the browser reaches the wrong device

The app on each device and emulator listens on port 8080 of that device, but the computer has
only one local port 8080. With several devices connected, `http://localhost:8080` goes to the
device that the forward of local port 8080 goes to. `adb forward --list` shows the forwards of
every device.

Select the device for each CLI command, with `--device SERIAL` or the `ANDROID_SERIAL`
environment variable:

```bash
adb devices                                       # the serials
lustro --device emulator-5554 net list --last 5
ANDROID_SERIAL=emulator-5554 lustro open          # the console of that device
```

The CLI then uses a forward to that device, on whatever local port the forward has, or makes one.
Without a selected device and with more than one connected, adb does not know which device to
use. The CLI then cannot read the token from the log, and stops with
`no token: set --token or LUSTRO_TOKEN, ...`. When `--token` or `LUSTRO_TOKEN` gives the token,
it stops at the forward instead, with `Pass --device SERIAL when more than one device is
connected.`

## The WorkManager tab has no events

The console says `No events: the tab's listeners are not installed`, and the API's `state` has
`listeners: "missing"`. WorkManager runs with a `Configuration` that did not go through
`WorkDebugTab.configure()`, so the tab gets no events of the runs. `state.reason` in each
response says which case it is:

- **`WorkManagerInitializer` is still in the manifest.** It initializes WorkManager with the
    default configuration at startup, and never asks your `Configuration.Provider`. Remove it, as
    [Register the tab](../plugins/work.md#register-the-tab) shows.
- **The app set a listener after `configure()`, or built another `Configuration`.** Call
    `configure()` last, just before `build()`, on the configuration that WorkManager gets.

## `lustro` is a different program

The PyPI package `lustro` is a different project that also installs a `lustro` command. When
`lustro --help` does not list `net`, `screen`, and `open`, that project is installed. Remove it,
and install `lustro-cli` from [Install](../get-started/install.md#the-lustro-cli). Do not run
`uvx lustro`: it runs that project.

## `Dependency verification failed` for `org.nanohttpd`

An app with Gradle dependency verification (`gradle/verification-metadata.xml`) must add Lustro's
artifacts and NanoHTTPD, which `lustro` brings. NanoHTTPD 2.3.1 is signed with the key
`6132AAE95D8E9FE0`, which no key server has, so Gradle cannot check the signature. Let Gradle
write the checksums for that artifact, and read the diff before you commit it:

```bash
./gradlew --write-verification-metadata pgp,sha256 --export-keys :app:assembleDebug
```
