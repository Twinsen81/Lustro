# Custom tabs

`DebugTab` is the only public extension point for adding a tab. Other public types, such as
`Redactor`, `MockRuleStorage`, and the sink from `Lustro.networkCaptureSink()`, change how the
Network tab works, but they add no tab. A tab must have an `id`, a `title`, and an `icon`. All the
other members have a default, such as `handle(request)` for the JSON routes and `renderContent()`
for the HTML view. A tab without a view is valid: agents and scripts use its routes.

The `id` is the part of the routes after `/api/v1/`. Two rules apply to it:

- **It must match `[a-z][a-z0-9-]{0,30}`.** `Lustro.Builder.addTab()` throws an
    `IllegalArgumentException` at once for an id that does not match.
- **It must be different from the id of every other tab.** The builder does not check this. Then
    `start()` finds two tabs with the same id, does not open the server, logs the warning
    `Failed to arm the debug server` at the `Lustro` tag, with an exception whose message is
    `Duplicate debug tab id '<id>'`, and returns `LustroStatus.DISABLED`. It does not throw. The ids of Lustro's own
    tabs are `network`, `logs`, `prefs`, `sqlite`, `work`, and `screen`, so do not use them.

A tab is your code, and it reads your app's internals, so put it in `src/debug`. The release build
fails on a `DebugTab` subclass in `src/main` or `src/release`: see
[Release safety](concepts/release-safety.md#the-lint-check). [Write a tab](write-a-tab.md) builds
a complete tab step by step.

```kotlin
class FlagsTab(private val flags: FeatureFlagRepository) : DebugTab() {
    override val id = "flags"
    override val title = "Feature Flags"
    override val icon = "🚩"

    override fun renderContent() = """<div id="flags-list"></div>"""

    // request.path is the remainder after /api/v1/flags/; null -> enveloped 404,
    // anything thrown, even TODO(), -> enveloped 500 (it never escapes into your app).
    override fun handle(request: DebugRequest): DebugResponse? = when (request.path) {
        "list" -> DebugResponse.ok(flags.toJson())
        else -> null
    }
}
```

Register it with the other tabs:

```kotlin
Lustro.builder(application)
    .addTab(NetworkDebugTab.create(senderClient = client))
    .addTab(FlagsTab(flagsRepo))
    .build()
```

**Asset and rendering conventions:**

- Static assets live at `assets/lustro/<id>.{js,css,openapi.json}` and are resolved by id at
    runtime. Returning non-empty strings from `renderScript()` / `renderStyles()` overrides the
    static `.js` / `.css`; returning a non-null `schema()` overrides the static `.openapi.json`.
- The console loads the tab's JS as an **external** script after `shared.js` (CSP:
    `script-src 'self'`). Use `data-action` attributes and one event listener on a parent element.
    **Do not use inline `onclick` handlers or `<script>` elements.** Send data with `fetch()`, not
    with an HTML `<form>`: the CSP sets `form-action 'none'`.
- **Escape every value you render.** `String.escapeHtml()` makes a value safe as HTML text and
    inside a quoted attribute; the browser-side `debugEscapeHtml(text)` does the same in tab JS.
    Anything the app stores or a request carries is untrusted input to your tab.
- **Use the console's styles.** Every tab page loads `shared.css`, the console's design system.
    It has design tokens (surfaces, text colors, and the colors of methods, statuses, log levels,
    and categories, in the dark and the light theme) and the `.dc-*` components. A tab that uses
    them looks like the console in both themes, without CSS of its own. The
    [custom tab styleguide](STYLEGUIDE.md) gives the rules, and the sample's flags tab is a
    working example.
- JSON routes go through `handle(request)`. Make the responses with the `DebugResponse`
    factories (`ok`, `json { ... }`, `text`, `bytes`, `notFound`, `error`). For a list that clients
    poll, `DebugResponse.cursorEnvelope(currentSequence, clientCursor) { /* items */ }` gives the
    cursor envelope's `reset`, `unchanged`, and `delta` answers, and uses `CursorCodec` for the
    opaque cursors, so a tab does not write that code itself. Increase the sequence only when the
    list changes, because each increase sends the whole list again. Put the other values that
    clients poll in its `state`. For a list that only grows at its end, such as a log,
    `DebugResponse.streamEnvelope(...)` gives the stream envelope: a poll gets only the entries
    after its cursor.
- **Change state only on `POST`, `PUT`, `PATCH`, or `DELETE`, never on `GET` or `HEAD`.** The
    runtime rejects browser requests from other origins on every method, but for a `GET` or `HEAD`
    it can go only by `Sec-Fetch-Site`, which browsers send only to loopback and HTTPS addresses,
    and older ones not at all. Without that header, an `<img>` on a page from another port of the
    same host sends a `GET` the runtime can't tell from the console's own, and it carries the
    console's cookie, since cookies aren't isolated by port.
- `handle()` runs off the main thread, and the calls can run at the same time. Keep the tab's
    mutable state thread-safe. Blocking I/O is permitted: the runtime stops each request at its
    timeout.
- When a request times out, the client gets a `504` and the runtime cancels the request:
    `request.isCancelled` turns `true`, actions registered with `request.onCancel { ... }` run,
    and the handler's thread is interrupted. Use `onCancel` to abort blocking calls that ignore
    interrupts, such as cancelling the `CancellationSignal` passed to `SQLiteDatabase.rawQuery`
    or an OkHttp `Call`. A handler that ignores cancellation keeps its concurrency slot until it
    returns.
- **Give the tab an OpenAPI document, so that agents find it.** `/api/v1/_meta` lists only the
    tabs that have one: a static `assets/lustro/<id>.openapi.json`, or a `schema()` that returns
    one. A tab without a document works in the console, but agents do not see it in `_meta`.
