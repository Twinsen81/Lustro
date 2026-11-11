# Platform `HttpURLConnection` capture

OkHttp capture is the default and needs no opt-in. To **also** capture platform
`HttpURLConnection` traffic, opt in explicitly:

```kotlin
@OptIn(ExperimentalPlatformCapture::class)
val tab = NetworkDebugTab.create(senderClient = client, capturePlatformHttp = true)
```

Caveats — this path is gated by `@ExperimentalPlatformCapture` because it relies on a non-public
platform detail (a process-global URL stream handler):

- **Best-effort and fail-open**: if it cannot install, capture is simply skipped; your app keeps
    working.
- **Capture only**: a platform request is never answered by a mock rule and never held by the
    throttle. Rules answer OkHttp calls, and the calls of an
    [app adapter](other-clients.md) that asks for a rule. The detail of a platform request
    says **HttpURLConnection**, and its **Mock this request** button is disabled.
- **Process-wide**: it installs a global handler once, affecting all `HttpURLConnection` traffic
    in the process. A [capture filter](okhttp.md) leaves out the traffic you don't
    need, such as an analytics SDK's.
- **Not covered** by the library's binary- or behaviour-compatibility guarantees, and may
    degrade across OS/SDK versions.
- **Checked in CI on API 24, 35, and 36**: the capture creates the platform's internal
    `com.android.okhttp` URL handlers by reflection. Every release checked, from API 24 to 37,
    allows this, but Android lists these constructors as unsupported hidden APIs, so a later
    release can block them. An instrumented test fails on a release that blocks them, after that
    release is in the CI matrix. The test runs as an app that targets SDK 35, so it does not show
    a block that applies only to a higher target SDK.
