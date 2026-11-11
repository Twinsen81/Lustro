# Troubleshooting

- **Can't connect from the browser.** Run `adb forward tcp:8080 tcp:8080` and confirm the app is
    in the **foreground** (the server only listens while foregrounded). Check `adb logcat -s
    LustroToken` for the actual endpoint — if you set `bindFallback` and the configured port was
    taken, the server is on an OS-assigned port that the log line reports. Without `bindFallback`,
    a taken port, such as one that another app with Lustro still holds while it goes to the
    background, gets a try again every 250 ms for 10 s, and then `adb logcat -s Lustro` says
    `is taken and bindFallback=false; not starting` until the app comes to the foreground again.
- **`401 unauthorized`.** The request is missing a valid token. Browsers: open via
    `#lustro_token=<token>` once (or `lustro open`). Programmatic clients: send
    `Authorization: Bearer <token>`. Get the token from `adb logcat -s LustroToken`.
- **Nothing is captured.** Make sure you added `lustro.networkInterceptor()` to the client that
    actually makes the calls, **after** any URL/header/body-mutating interceptors. Check that
    capture isn't **paused** in the Network tab. For `HttpURLConnection` traffic, you must opt in:
    pass `capturePlatformHttp = true` to `NetworkDebugTab.create`, under
    `@OptIn(ExperimentalPlatformCapture::class)` (see
    [HttpURLConnection](../network/httpurlconnection.md)). It is not a `DebugConfig` option.
- **A WebSocket shows only its handshake.** The interceptor can't see messages. Create the socket
    with `lustro.webSocketFactory(httpClient)`, or give that factory to the library that creates it:
    see [WebSocket messages](../network/websockets.md).
- **`Dependency verification failed` for `org.nanohttpd`.** An app with Gradle dependency
    verification (`gradle/verification-metadata.xml`) must add Lustro's artifacts and NanoHTTPD,
    which `lustro` brings. NanoHTTPD 2.3.1 is signed with key `6132AAE95D8E9FE0`, which no key
    server has, so the signature can't be checked. Let Gradle write the checksums, for the key that
    it can't get, and check the diff before you commit it:
    `./gradlew --write-verification-metadata pgp,sha256 --export-keys :app:assembleDebug`.
