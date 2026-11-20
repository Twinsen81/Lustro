# Custom tabs

`DebugTab` is the only public extension point for adding a tab. Other public types, such as
`Redactor`, `MockRuleStorage`, and the sink from `Lustro.networkCaptureSink()`, change how the
Network tab works, but they add no tab. A tab must have an `id`, a `title`, and an `icon`. All the
other members have a default, such as `routes(routes)` for the JSON routes and `renderContent()`
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
@OptIn(ExperimentalDebugRoutes::class)
class FlagsTab(private val flags: FeatureFlagRepository) : DebugTab() {
    override val id = "flags"
    override val title = "Feature Flags"
    override val icon = "🚩"

    override fun renderContent() = """<div id="flags-list"></div>"""

    // GET /api/v1/flags/list. Another method gets 405, another path 404, and anything
    // thrown, even TODO(), an enveloped 500 (it never escapes into your app).
    override fun routes(routes: DebugRoutes) {
        routes.version = "1.0"
        routes.get("list") {
            summary = "The flags."
            handle { DebugResponse.ok(flags.toJson()) }
        }
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

## Declared routes

A tab declares its routes in `routes(routes)`: for each route, its method, its path, its
parameters and body, its response, and its handler. Lustro routes each request, checks its
parameters, and makes the tab's OpenAPI document from the same declarations, so the routes and
the document can't drift apart, and `GET /api/v1/_meta` lists the tab.

```kotlin
@OptIn(ExperimentalDebugRoutes::class)
override fun routes(routes: DebugRoutes) {
    routes.version = "1.0" // the info.version of the document; change it when a route changes
    routes.get("items/{id}") {
        summary = "One item."
        val id = path.long("id")
        val full = query.boolean("full").withDefault(false)
        errors(404)
        handle { call -> item(call[id], call[full]) }
    }
    routes.post("toggle") {
        acceptsForm = true // also id=... from curl -d
        val id = body.string("id") { minLength = 1 }.required()
        handle { call -> toggle(call[id]) }
    }
}
```

- **The API needs an opt-in.** Its shape can still change in a way that breaks the code that
    uses it, so it is marked `@ExperimentalDebugRoutes`, and the compiler stops a tab without
    `@OptIn(ExperimentalDebugRoutes::class)`. It is a Kotlin API: Java is not a target.
- **Parameters.** `path`, `query`, and `body` declare them, as a `string`, `int`, `long`, or
    `boolean`, and in a body also as `json`, which gives the handler the value's JSON text. Each
    returns a handle, and the handler reads the value with it: `call[id]`. A parameter is optional
    (`null` when absent) until `.required()` or `.withDefault(value)`. Options: `description`,
    `minLength`, `maxLength`, and `values` for a string, `min` and `max` for a number, and
    `schema`, a JSON Schema that describes it in the document in place of the one made from its
    type, such as `DebugRoutes.ref("Store")`.
- **Empty values.** In the query or a form, an empty int, long, or boolean is absent, and an
    empty string is a string, which `minLength = 1` refuses.
- **Bodies.** Body fields come from a JSON object, with strict JSON types, and `null` counts as
    absent. With `acceptsForm = true`, a route also takes them as an
    `application/x-www-form-urlencoded` form. A route with a required field gets a `400` for an
    empty body. For a body that is not a set of fields, such as an uploaded file, use
    `rawBody(contentType, schema)` and read `call.request.body` in the handler.
- **Answers.** A path that no route declares gets `404`. A declared path with another method gets
    `405 method_not_allowed` with an `Allow` header. A missing or wrong parameter gets
    `400 bad_request` with the parameter's name in `field`, and a body that is not a JSON object
    gets `field: "body"`. For an error that depends on data, such as an unknown id, the handler
    returns `DebugResponse.error(...)`, or a helper deep in its code throws
    `DebugResponseException(response)`. The server answers that exception for a tab that routes
    by hand too.
- **The document.** It has the paths, methods, parameters, bodies, and responses, the shared
    `ErrorEnvelope`, and the descriptions: `summary`, `description`, `bodyDescription`, and the
    parameters' `description`. `response(description, schema)` adds the success response's
    JSON Schema, `component(name, schema)` adds a schema under `components/schemas`, and
    `DebugRoutes.ref(name)` refers to it. `errors(404, 409)` lists the error statuses that the
    handler answers.
- **Your own document or routing.** Override `schema()` to give your own document; the declared
    routes still route. `super.schema()` gives the generated one, to change. Override `handle()`
    to route some paths by hand, and call `super.handle(request)` for the declared ones.
- **Mistakes.** A mistake in the declarations, such as a path parameter that the route does not
    declare, two routes with the same method and path, a schema that is not valid JSON, or a
    missing `version`, throws `IllegalStateException` from the first `handle()` or `schema()`
    call. A unit test that calls `tab.schema()` finds it. The server then leaves the tab out of
    `_meta` and answers its routes with `500`.
- **Release builds.** `routes()` runs only when the runtime calls `handle()` or `schema()` for
    the first time, never in a no-op build, and the declarations need no reflection and no keep
    rules.

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
- A tab that routes by hand in `handle(request)` returns `null` for a method that a route does
    not take, as for a route that the tab does not have. When the tab's OpenAPI document declares
    the path with other methods, the client gets a `405` with an `Allow` header that lists them,
    and otherwise a `404`.
- Make the responses with the `DebugResponse`
    factories (`ok`, `json { ... }`, `text`, `bytes`, `notFound`, `error`). `error(message,
    status)` gives the error envelope, and its `error` type follows from the status:
    `bad_request` (400), `unauthorized` (401), `forbidden` (403), `not_found` (404),
    `method_not_allowed` (405), `conflict` (409), `payload_too_large` (413),
    `unprocessable_entity` (422), `too_many_requests` (429), `internal_error` (500),
    `unavailable` (503), `timeout` (504), and `error` for any other status.
- A tab that routes by hand reads a JSON body with `request.bodyAsString()`. For an `application/x-www-form-urlencoded`
    body, as `curl -d key=value` and old clients send it, `request.formParams()` gives each name
    with its values, decoded as the query parameters are. It is empty for another content type.
    Unlike `org.json`, it needs no Robolectric in a JVM unit test.
- For a list that clients poll, `DebugResponse.cursorEnvelope(currentSequence, clientCursor) { /* items */ }` gives the
    cursor envelope's `reset`, `unchanged`, and `delta` answers, and uses `CursorCodec` for the
    opaque cursors, so a tab does not write that code itself. Increase the sequence only when the
    list changes, because each increase sends the whole list again. Put the other values that
    clients poll in its `state`. For a list that only grows at its end, such as a log,
    `DebugResponse.streamEnvelope(...)` gives the stream envelope: a poll gets only the entries
    after its cursor.
- **Make every cursor with these helpers, also a tab's own `since` or page cursor.** A page
    keeps its cursor when the app restarts, and sends it again. A cursor of the helpers carries
    the epoch of the process, so a cursor from before the restart is foreign and gets a `reset`.
    A tab that sends its own sequence number, such as `?since=42`, gets the old number from the
    page, while the new process counts from 1 again: the page then skips the new process's first
    42 items. `cursorEnvelope` and `streamEnvelope` pick the
    epoch once for each process; a store that the app can make again within one process passes
    its own `epoch`, made when it is created.
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
    returns. For work that takes longer than the timeout, see [Long work](#long-work).
- **Give the tab an OpenAPI document, so that agents find it.** `/api/v1/_meta` lists only the
    tabs that have one: the document of the declared routes, a static
    `assets/lustro/<id>.openapi.json`, or a `schema()` that returns one. A tab without a document
    works in the console, but agents do not see it in `_meta`. The document declares each query
    parameter that a route reads (`"in": "query"`): the server names every other one in a
    `Lustro-Warning` response header, and the CLI prints it.

## Headless tabs

A tab with `showInTabBar = false` is headless: it has routes, and no page. It has no link in the
tab bar, and `/tab/<id>` answers `404`, as for an unknown tab. Its routes, its `_schema`, and its
`_view.js` and `_view.css` work. A headless tab must still set `title` and `icon`, because they
are abstract. `GET /api/v1/_meta` lists the `title` when the tab has an OpenAPI document, so give
it a name that tells an agent what the routes are for. Nothing shows the `icon`, so any value,
such as `""`, works.

## Long work

The server answers a request that runs longer than `requestTimeoutMs`, 30 s by default, with a
`504`, and cancels it. For work that takes longer, such as seeding hundreds of items or labeling a
photo library, do not make the request wait for it. Use two routes:

1. `POST start` starts the work on a thread or a coroutine of the tab, and answers at once with
    an id for the job, such as `{"jobId":"7"}`. When a job is already running and the tab runs one
    at a time, answer `409` (`DebugResponse.error(message, status = 409)` gives the `conflict`
    type).
2. `GET jobs/<id>` reports the progress, and the result when the job is done, such as
    `{"state":"running","done":120,"total":500}`. The console polls it with `debugPoll`, and an
    agent polls it with `lustro api GET <tab>/jobs/<id>`.

The job runs outside the request, so the server does not cancel it, and it does not hold a
concurrency slot. Keep the jobs in the tab, give each one a way to stop, and drop old jobs so that
they do not stay in memory.

`maxRequestBodyBytes` and `requestTimeoutMs` are global: they apply to every tab. A tab that needs
a larger body or a longer timeout raises the value in `DebugConfig` for the whole server (see
[Configuration](reference/configuration.md#requests-to-the-server)). The server holds at most
`maxConcurrentRequests` x `maxRequestBodyBytes` of request bodies in memory at a time, so a
larger body limit also raises that bound.

## The console's helpers

The tab's script runs after `shared.js`, which gives these helpers:

- `debugPoll(urlOrFunction, intervalMs, callback, options)` polls a route, and shows the
    connection state in the top bar. `options.onConnectionChange(connected)` runs when the state
    changes: `true` at the first answer and at the first answer after a lost connection, and
    `false` after three failed polls in a row. Use it to disable a form or show that the app is
    gone. `options.onRejected(status, envelope)` gets a response that is not `2xx`, and returns
    `true` when it handled it. The fourth argument can also be `onRejected` itself, as before.
    The result has `stop()`.
- `debugFetch(url, options)` rejects on a status that is not `2xx`, with `err.status` and
    `err.response`. `debugErrorMessage(err)` gives a Promise of the text to show: the `message`
    of the error envelope, or else `err.message`.
- `debugModal(id, { onHide })` shows and hides a `.dc-modal-scrim` of the tab's markup. `onHide`
    runs each time the dialog is hidden: by `hide()`, by Escape, or by a click on the scrim. Use it
    to clear the state of the dialog, such as the id of the item being edited.
- `debugEscapeHtml(text)`, `debugToast(message, type)`, `lustroApiUrl(path)`, and
    `lustroOnContentReady(fn)`: see [Write a tab](write-a-tab.md#8-write-the-script).
