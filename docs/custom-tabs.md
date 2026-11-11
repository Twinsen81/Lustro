# Custom tabs

`DebugTab` is the only public extension point. Required: `id` (must match
`[a-z][a-z0-9-]{0,30}`, validated at registration), `title`, and `icon`. Everything else has a
default — including `handle(request)` for JSON routes and `renderContent()` for optional HTML
(API-only tabs are valid).

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

Register it alongside the network tab:

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
- Tab JS is loaded as an **external** script after `shared.js` (CSP: `script-src 'self'`). Use
    `data-action` attributes and event delegation — **no inline `onclick`/`<script>` handlers**.
    Submit through `fetch()`, not an HTML `<form>`: the CSP sets `form-action 'none'`.
- **Escape every value you render.** `String.escapeHtml()` makes a value safe as HTML text and
    inside a quoted attribute; the browser-side `debugEscapeHtml(text)` does the same in tab JS.
    Anything the app stores or a request carries is untrusted input to your tab.
- **Styling is free.** Every tab page loads `shared.css` — the console's design system: design
    tokens (surfaces, text ramp, semantic method/status/level/category palettes; dark + light
    themes) plus a documented `.dc-*` component library. Build on those and your tab matches the
    console in both themes with no extra CSS; see the [custom tab styleguide](STYLEGUIDE.md) for
    the contract and the sample flags tab for a working example.
- JSON routes go through `handle(request)`; build responses with the `DebugResponse` factories
    (`ok`, `json { ... }`, `text`, `bytes`, `notFound`, `error`). For observable list routes,
    `DebugResponse.cursorEnvelope(currentSequence, clientCursor) { /* items */ }` implements the
    cursor envelope's `reset`/`unchanged`/`delta` contract — with `CursorCodec` for the opaque
    tokens — so tabs don't hand-roll it. Advance the sequence only when the list changes, since
    each advance re-sends the whole list; other observable values go in its `state`. For a list
    that only grows at its end, such as a log, `DebugResponse.streamEnvelope(...)` implements the
    stream envelope: a poll gets only the entries after its cursor.
- **Change state only on `POST`, `PUT`, `PATCH`, or `DELETE`, never on `GET` or `HEAD`.** The
    runtime rejects browser requests from other origins on every method, but for a `GET` or `HEAD`
    it can go only by `Sec-Fetch-Site`, which browsers send only to loopback and HTTPS addresses,
    and older ones not at all. Without that header, an `<img>` on a page from another port of the
    same host sends a `GET` the runtime can't tell from the console's own, and it carries the
    console's cookie, since cookies aren't isolated by port.
- `handle()` runs off the main thread and calls may be concurrent — keep mutable tab state
    thread-safe. Blocking I/O is fine; the runtime enforces a per-request timeout.
- When a request times out, the client gets a `504` and the runtime cancels the request:
    `request.isCancelled` turns `true`, actions registered with `request.onCancel { ... }` run,
    and the handler's thread is interrupted. Use `onCancel` to abort blocking calls that ignore
    interrupts, such as cancelling the `CancellationSignal` passed to `SQLiteDatabase.rawQuery`
    or an OkHttp `Call`. A handler that ignores cancellation keeps its concurrency slot until it
    returns.
- **Ship a schema to be agent-discoverable.** Only tabs that expose a schema (a static
    `assets/lustro/<id>.openapi.json` or a dynamic `schema()`) are listed in `/api/v1/_meta`.
    Schema-less tabs work in the browser UI but are invisible to agents.
