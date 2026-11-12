# Glossary

The terms that these docs use, in alphabetical order. Each term links the page that explains it.

`_meta`
:   The route `GET /api/v1/_meta`. It gives the library version, the protocol version, and the tabs
    that have an OpenAPI document. Agents start here. See
    [OpenAPI and wire protocol](../wire-protocol.md).

Capture
:   The Network tab's record of an HTTP request and its response, or of a WebSocket connection and
    its messages. The app's OkHttp interceptor, `webSocketFactory`, or `networkCaptureSink()` makes
    it. Captures stay in memory and end with the process. See
    [OkHttp capture setup](../network/okhttp.md).

Capture budget
:   The memory that the Network tab can use for the bodies that it keeps: 50 MiB by default, set with
    `captureBudgetBytes`. Past it, the oldest finished transactions go first. WebSocket payloads
    have a budget of their own, `webSocketCaptureBudgetBytes`. See
    [Configuration](configuration.md#network-tab-capture).

Capture filter
:   A `NetworkCaptureFilter` that the app gives to `NetworkDebugTab.create`. The Network tab does
    not record a request that the filter leaves out. See [OkHttp capture setup](../network/okhttp.md).

Chrome
:   The parts of the console that the server sends without a token: the tab bar, the empty content
    area, `shared.css`, and `shared.js`. It has no tab content and no captured data. See
    [How Lustro works](../concepts/how-it-works.md#the-anatomy-of-a-tab).

Console
:   The web page at `http://localhost:8080` that shows the tabs in a desktop browser. It is a client
    of the same JSON API that agents use. See [Accessing the UI](../get-started/console.md).

Cursor
:   An opaque string in a response of a polled route. The client sends it back with its next poll,
    and the server answers with what changed since then. A client must not parse it. See
    [OpenAPI and wire protocol](../wire-protocol.md).

Cursor envelope
:   The response shape of a polled list: `{ cursor, status, items?, state? }`. `status` is
    `reset` or `delta` with the whole list in `items`, or `unchanged` without `items`. A tab makes
    it with `DebugResponse.cursorEnvelope`. See [OpenAPI and wire protocol](../wire-protocol.md)
    and [Write a tab](../write-a-tab.md#5-answer-a-poll-with-the-cursor-envelope).

`DebugTab`
:   The class that every tab extends: the Network tab, the plugins, and your own tabs. See
    [Custom tabs](../custom-tabs.md).

Debuggable check
:   The check in `Lustro.start()` that refuses to open the server in a build without
    `android:debuggable`. See [Release safety](../concepts/release-safety.md#the-debuggable-check).

Error envelope
:   The response shape of every error: `{ error, message, code?, field?, hint? }`. See
    [OpenAPI and wire protocol](../wire-protocol.md).

Headless tab
:   A tab with `showInTabBar = false`. It has no link in the tab bar, but its routes work, and
    `_meta` lists it when it has an OpenAPI document. See
    [How Lustro works](../concepts/how-it-works.md#the-anatomy-of-a-tab).

Lint check
:   `LustroDebugUsageInRelease`, which `lustro` and `lustro-noop` ship. It fails a release build that
    has a `DebugTab` subclass outside `src/debug`. See
    [Release safety](../concepts/release-safety.md#the-lint-check).

`LustroToken` line
:   The log line `Lustro ready endpoint=http://127.0.0.1:8080 token=<token>`, at the `LustroToken`
    tag. The server logs it after each bind. The CLI and agents read the address and the token
    from it. See [Accessing the UI](../get-started/console.md).

Mock rule
:   A rule of the Network tab that answers a matching request with the response that the rule
    gives. The request does not leave the device. See [Mock rules](../network/mocks.md).

No-op half
:   The release artifact of a pair, such as `lustro-noop` for `lustro`, or `lustro-logs-noop` for
    `lustro-logs`. It has the same public API with empty bodies, so the app's code compiles in a
    release build and does nothing. See [Release safety](../concepts/release-safety.md#the-no-op-halves).

OpenAPI document
:   The JSON file that describes a tab's routes: `assets/lustro/<id>.openapi.json`, or the return
    value of `DebugTab.schema()`. The server serves it at `/api/v1/<id>/_schema`. Only a tab with a
    document is in `_meta`. See [OpenAPI and wire protocol](../wire-protocol.md).

Origin check
:   The check that refuses a browser request from another web origin with a `403`, on every method.
    The server's own origin, and the origins in `allowedOrigins`, pass it. See
    [Security model](../concepts/security.md).

Pair capability
:   The Gradle capability that the runtime and its no-op half both declare, such as
    `io.github.twinsen81:lustro-runtime`. Gradle refuses to put both halves on one configuration.
    See [Release safety](../concepts/release-safety.md#the-shared-capability).

Plugin
:   A tab in an artifact of its own, with a no-op half: Logs, Preferences, SQLite, WorkManager, and
    Screen. The Network tab is in `lustro` itself. See [Modules](modules.md).

Port forward
:   An `adb forward tcp:8080 tcp:8080`, which connects a port on the computer to the same port on
    the device. The server binds a loopback address, so the computer reaches it only this way.
    `lustro open` makes the forward. See [Accessing the UI](../get-started/console.md) and
    [LAN exposure and port forwarding](../concepts/lan.md).

Redactor
:   A `Redactor` masks the sensitive headers, query parameters, and body fields of a request before
    the Network tab stores it. It goes by name, so it cannot find a secret under a name that it does
    not know. See [Security model](../concepts/security.md).

Runtime
:   The debug artifact of a pair, such as `lustro` or `lustro-logs`, which the app adds with
    `debugImplementation`. See [Modules](modules.md).

Sender
:   A named client that the Network tab's Send form sends through, made with
    `OkHttpSender.create`. See [Send Request](../network/send-request.md).

Stream envelope
:   The response shape of a polled list that only grows at its end, such as a log:
    `{ cursor, status, items?, dropped?, state? }`. A `delta` has only the entries after the
    client's cursor, and `dropped` counts the entries that the list removed before the client got
    them. A tab makes it with `DebugResponse.streamEnvelope`. See
    [OpenAPI and wire protocol](../wire-protocol.md).

Tab
:   One tool of the console, such as Network or SQLite, and its JSON routes under
    `/api/v1/<id>/`. A tab is a `DebugTab` in the app's process. See
    [How Lustro works](../concepts/how-it-works.md#the-anatomy-of-a-tab).

Token
:   The random 256-bit secret that every API request needs, as `Authorization: Bearer <token>` or
    as the console's `lustro_token` cookie. The app makes it once and keeps it until its data is
    cleared. See [How Lustro works](../concepts/how-it-works.md#the-flow-of-the-token).

Value policy
:   How much of a value the Preferences and SQLite tabs show. `VISIBLE` shows every value.
    `MASK_SENSITIVE`, the default, masks the values whose key or column name looks like a secret,
    such as `password` or `access_token`. The Preferences tab also has `KEYS_ONLY`, which shows no
    values. See [Preferences](../plugins/prefs.md#safety-options) and
    [SQLite](../plugins/sqlite.md#safety-options).

Wire protocol
:   The shapes that every tab shares: the error envelope, the list pagination envelope, the cursor
    envelope, and the stream envelope, with their JSON Schemas and versioning rules. See
    [OpenAPI and wire protocol](../wire-protocol.md).
