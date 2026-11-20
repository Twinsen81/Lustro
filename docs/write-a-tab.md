# Write a tab

This tutorial builds a Feature Flags tab step by step. The tab keeps a list of flags, shows a switch
for each flag in the console, and adds the flags of a flags file that a client posts. Agents and
scripts use the same flags through three JSON routes. It is the
[sample app's flags tab](https://github.com/Twinsen81/Lustro/blob/main/sample/src/debug/kotlin/io/github/twinsen81/lustro/sample/SampleFlagsTab.kt),
without the parts that connect the flags to the sample's own screens, and without the sample's
upload dialog: here a flags file comes only through the `upload` route.

[Custom tabs](custom-tabs.md) gives the rules for a tab, and the [styleguide](STYLEGUIDE.md) gives
the console's components. This page shows where each rule applies, and does not repeat them.
[How Lustro works](concepts/how-it-works.md#the-anatomy-of-a-tab) shows how the parts of a tab fit
together.

The finished tab has these routes:

| Route | What it does |
| --- | --- |
| `GET /api/v1/flags/snapshot` | The flags, in the cursor envelope, for polling. |
| `POST /api/v1/flags/toggle` | Turns one flag on or off. Body: `{"id": "<flag id>"}`. |
| `POST /api/v1/flags/upload` | Adds the flags of a flags file, and replaces the flags with the same ids. |

## 1. Put the files in `src/debug`

A tab is your code, and it reads your app's internals. Put it in the `debug` source set, so a
release build does not compile it. The release lint fails the build on a `DebugTab` subclass in
`src/main` or `src/release`: see [Release safety](concepts/release-safety.md#the-lint-check).

The tab has one Kotlin file and two assets:

```text
app/src/debug/
├── kotlin/com/example/debug/FlagsTab.kt
└── assets/lustro/
    ├── flags.js
    └── flags.css
```

The server finds the assets by the tab's id, so their names start with the id. The tab's OpenAPI
document comes from its route declarations ([step 10](#10-describe-the-routes)), so it needs no
`flags.openapi.json`.

## 2. Subclass `DebugTab`

A tab needs an `id`, a `title`, and an `icon`. The id is the part of the routes after `/api/v1/`,
and it must match `[a-z][a-z0-9-]{0,30}`. `addTab()` throws an `IllegalArgumentException` for an
id that does not match.

The id must also be different from the ids of the other tabs, including `network`, `logs`,
`prefs`, `sqlite`, `work`, and `screen`. `addTab()` does not check this. When two tabs have the
same id, `start()` does not throw: it logs the warning `Failed to arm the debug server` at the
`Lustro` tag, with an exception whose message is `Duplicate debug tab id '<id>'`, does not open
the server, and returns `LustroStatus.DISABLED`. See
[Troubleshooting](reference/troubleshooting.md#start-returns-disabled).

```kotlin
data class Flag(val id: String, val description: String, val enabled: Boolean)

@OptIn(ExperimentalDebugRoutes::class)
class FlagsTab : DebugTab() {
    override val id = "flags"
    override val title = "Feature Flags"
    override val icon = "🚩"

    // After the Network tab and the plugins, which are 10 to 60.
    override val order = Int.MAX_VALUE
}
```

The server escapes the title and the icon before it puts them in the tab bar.

The tab declares its routes with an API whose shape can still change, in a way that breaks the
code that uses it. So the API is marked `@ExperimentalDebugRoutes`, and the compiler stops a tab
that does not accept this with `@OptIn(ExperimentalDebugRoutes::class)`. A tab is debug code that
the app builds with the same Lustro version that it runs, so such a change shows up as a compile
error in the tab, when you update Lustro.

A tab with only routes, for agents and scripts, sets `showInTabBar = false`. It is then headless:
its routes work, but it has no link in the tab bar and no page, so `/tab/<id>` answers `404`, as
for an unknown tab. It must still set `title` and `icon`. `GET /api/v1/_meta` lists the `title`
when the tab has an OpenAPI document, and nothing shows the `icon`, so `""` is enough. See
[Headless tabs](custom-tabs.md#headless-tabs).

## 3. Keep the state thread-safe

The server calls the tab on its own worker threads, never on the main thread, and it can call it
for two requests at the same time. So the state of the tab needs a lock, or a thread-safe type:

```kotlin
    private val lock = Any()
    private val byId = linkedMapOf<String, Flag>() // Guarded by lock.

    // Counts the changes of the list. The cursor of the snapshot route encodes it.
    private val sequence = AtomicLong(0)

    init {
        put(Flag("home-intro", "Intro on the home screen", enabled = true))
        put(Flag("offline-sync", "Offline sync", enabled = true))
    }

    private fun put(flag: Flag) {
        synchronized(lock) { byId[flag.id] = flag }
    }
```

When the app also reads the flags, give it a thread-safe view of them. The sample exposes a
`StateFlow` that the tab updates under the same lock.

## 4. Declare the routes

`routes(routes)` declares each route of the tab: its method, its path after `/api/v1/flags/`, its
parameters, and the handler that answers it. Lustro calls it once, for the first request:

```kotlin
    override fun routes(routes: DebugRoutes) {
        routes.version = "1.0"
        routes.get("snapshot") {
            summary = "Poll the flags with an opaque cursor."
            val cursor = query.string("cursor") { description = "The cursor of the previous response." }
            handle { call -> snapshot(call[cursor]) }
        }
        routes.post("toggle") {
            summary = "Turn one flag on or off."
            acceptsForm = true
            val flagId = body.string("id") { minLength = 1 }.required()
            errors(404)
            handle { call -> toggle(call[flagId]) }
        }
        routes.post("upload") {
            summary = "Add the flags of a flags file."
            rawBody("application/json", """{"type": ["array", "object"]}""")
            handle { call -> upload(call.request.bodyAsString().orEmpty()) }
        }
    }
```

Each parameter declaration returns a handle, and the handler reads the value with it:
`call[cursor]` is a `String?`, because the cursor is optional, and `call[flagId]` is a `String`,
because `.required()` makes the id required. The block runs once; the handler runs for each
request, off the main thread.

From these declarations, Lustro answers the client's mistakes before the handler runs:

- a path that no route declares, such as `GET flags/nothing`, gets `404`;
- a declared path with another method, such as `GET flags/toggle`, gets `405` with `Allow: POST`;
- a missing or wrong parameter gets `400` with the parameter's name in `field`, such as
    `{"error":"bad_request","message":"id is required","field":"id"}`, and a body that is not a
    JSON object gets `field: "body"`.

Two kinds of declaration are not in the flags tab: a route at the tab root, with the path `""`,
such as `routes.get("")` for `GET /api/v1/flags`, and a decimal parameter, such as
`query.double("ratio") { min = 0.0; max = 1.0 }`, which takes `0.35`. The router ignores one slash
at the end of a path, so `POST flags/toggle/` reaches `toggle`.
[Custom tabs](custom-tabs.md#declared-routes) lists the types and the rules of the paths.

Only `snapshot` is a `GET`, because it changes nothing. A route that changes state must take
`POST`, `PUT`, `PATCH`, or `DELETE`: a page on another site can make the browser send a `GET` that
the server cannot refuse. [Custom tabs](custom-tabs.md) explains why.

When a handler throws, the client gets a `500`, and the app does not crash.

A mistake in the declarations, such as `"items/{id}"` without `path.string("id")`, or two routes
with the same method and path, throws `IllegalStateException` from the first request.
[Step 13](#13-test-it) finds it in a unit test.

## 5. Answer a poll with the cursor envelope

The console polls `snapshot` every 1.5 s. With the
[cursor envelope](wire-protocol.md), a poll gets the list only when it changed since the last
poll. `DebugResponse.cursorEnvelope` compares the client's cursor with the current sequence, and
calls the lambda only when the list must go out:

```kotlin
    private fun snapshot(cursor: String?): DebugResponse =
        DebugResponse.cursorEnvelope(
            currentSequence = sequence.get(),
            clientCursor = cursor,
        ) {
            val flags = synchronized(lock) { byId.values.toList() }
            flags.forEachIndexed { index, flag ->
                if (index > 0) append(",")
                append("{\"id\":\"").append(flag.id.escapeForJson())
                append("\",\"description\":\"").append(flag.description.escapeForJson())
                append("\",\"enabled\":").append(flag.enabled).append("}")
            }
        }
```

The response is `{"cursor": "...", "status": "reset", "items": [...]}` for the first poll,
`"unchanged"` without `items` while nothing changes, and `"delta"` with the whole list after a
change. The lambda writes only the items: the factory writes the brackets around them.

Advance the sequence only when the list changes, because each advance sends the whole list to
every poller again. A value that is not part of the list goes in the envelope's `state`. For a list
that only grows at its end, such as a log, use `DebugResponse.streamEnvelope`: a poll then gets
only the new entries.

Make every cursor that a client sends back with these two helpers, not with a number of the tab's
own. The page keeps its cursor when the app restarts, and sends it with the next poll. A cursor of
the helpers carries the epoch of the process, so the new process sees a cursor from before the
restart as foreign and answers `reset` with the whole list. A tab that sends its own sequence
number, such as `?since=42`, gets the old 42 from the page, while the new process counts from 1
again: the page shows none of the new process's first 42 items. The helpers pick the epoch once for
each process. A store that the app can make again within one process passes its own `epoch`, made
when it is created, for example with `Random.nextLong()`.

## 6. Change state on `POST`

`toggle` gets the id that the router read from the body, changes one flag, and advances the
sequence. The `DebugResponse` factories make the error envelope: `error(...)` is a `400` by
default, and `notFound(...)` is a `404`.

```kotlin
    private fun toggle(flagId: String): DebugResponse {
        val updated =
            synchronized(lock) {
                val flag = byId[flagId] ?: return DebugResponse.notFound("Unknown flag: $flagId")
                flag.copy(enabled = !flag.enabled).also { byId[flagId] = it }
            }
        sequence.incrementAndGet()
        return DebugResponse.json {
            append("{\"status\":\"ok\",\"id\":\"").append(updated.id.escapeForJson())
            append("\",\"enabled\":").append(updated.enabled).append("}")
        }
    }
```

The other error types follow from the status, such as `conflict` for `409`,
`unprocessable_entity` for `422`, and `too_many_requests` for `429`. [Custom tabs](custom-tabs.md)
lists them all. To end a request with an error from a helper deep in the handler's code, throw
`DebugResponseException(DebugResponse.error(...))`: the client gets that response.

The router reads the body fields from a JSON object, `{"id": "offline-sync"}`. With
`acceptsForm = true`, `toggle` also takes them as a form, as `curl -d id=offline-sync` sends it.
Turn it on only for the routes that need it.

`upload` reads its whole body itself, because a flags file is not a set of fields: `rawBody(...)`
declares it, and the router answers an empty body with a `400` for `body`. `upload` takes a JSON
array of flags, or an object with a `flags` array. It puts each flag, and advances the sequence
once when it added at least one:

```kotlin
    private fun upload(body: String): DebugResponse {
        val array =
            try {
                val text = body.trim()
                if (text.startsWith("[")) JSONArray(text) else JSONObject(text).optJSONArray("flags") ?: JSONArray()
            } catch (e: JSONException) {
                return DebugResponse.error("Invalid flags file: ${e.message}")
            }
        var imported = 0
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val flagId = obj.optString("id")
            if (flagId.isBlank()) continue
            put(Flag(flagId, obj.optString("description", flagId), obj.optBoolean("enabled", false)))
            imported++
        }
        if (imported > 0) sequence.incrementAndGet()
        return DebugResponse.json { append("{\"status\":\"ok\",\"imported\":").append(imported).append("}") }
    }
```

The server reads at most [`maxRequestBodyBytes`](reference/configuration.md) of a body, 1 MiB by
default, and answers a larger one with a `413` before the tab sees it. The limit is the same for
every tab: a tab that needs more raises it in `DebugConfig`, for the whole server.

These handlers return at once. A route that waits, for a network call or a long query, must stop
when the request times out: the client then has a `504`, and the request still holds one of the
server's slots. Check `request.isCancelled` in a loop, and give a blocking call a way to stop with
`request.onCancel`:

```kotlin
    val call = client.newCall(exportRequest)
    request.onCancel { call.cancel() }
    call.execute().use { response -> /* ... */ }
```

Work that takes longer than the timeout, 30 s by default, does not fit in one request. Start it in
the background from one route, answer at once with an id, and report its progress from a second
route: [Custom tabs](custom-tabs.md#long-work) shows the pattern.

## 7. Render the view

`renderContent()` returns the HTML of the tab's content area. The browser gets it only after the
token is checked. Use the `.dc-*` components of the [styleguide](STYLEGUIDE.md), so the tab looks
like the console in the dark theme and the light theme:

```kotlin
    override fun renderContent(): String =
        """
        <div class="dc-toolbar">
            <h3 class="dc-mono-label">Feature Flags</h3>
            <span id="flags-count" class="flags-count">0 flags</span>
        </div>
        <div id="flags-list" class="flags-list"></div>
        """.trimIndent()
```

The view has no `onclick` and no `<script>`: the Content Security Policy blocks inline scripts.
The buttons and switches get a `data-action` attribute, and the tab's script handles them. Escape
every value that you put in the HTML from Kotlin with `String.escapeHtml()`. This view has none.

## 8. Write the script

The console loads `flags.js` after `shared.js`, so the script can use the helpers of `shared.js`:

- `lustroApiUrl(path)` gives the URL of a route of this tab, such as `/api/v1/flags/snapshot`.
- `lustroOnContentReady(fn)` runs `fn` when the view is in the page.
- `debugPoll(urlOrFunction, intervalMs, callback, options)` polls a route, and shows the
    connection state in the top bar. `options.onConnectionChange(connected)` runs when that state
    changes, so the tab can react when the app goes away.
- `debugFetch(url, options)` rejects on a status that is not `2xx`. `debugErrorMessage(err)`
    gives a Promise of the text to show for the rejection: the `message` of the error envelope,
    or else `err.message`.
- `debugModal(id, { onHide })` shows and hides a dialog of the tab's markup. `onHide` runs when
    the dialog is hidden, also by Escape or a click on the scrim.
- `debugEscapeHtml(text)` escapes a value for HTML.
- `debugToast(message, type)` shows a short message at the bottom right for 3 s. `type` is
    `success`, `error`, `info`, or `warning`.

```js
(function() {
    var flags = [];
    var cursor = null;

    function snapshotUrl() {
        var url = window.lustroApiUrl('snapshot');
        return cursor == null ? url : url + '?cursor=' + encodeURIComponent(cursor);
    }

    function onSnapshot(data) {
        if (data.status !== 'unchanged') {
            flags = data.items || [];
            render();
        }
        cursor = data.cursor;
    }

    function render() {
        document.getElementById('flags-list').innerHTML = flags.map(function(f) {
            return '<label class="dc-switch">'
                + '<input type="checkbox"' + (f.enabled ? ' checked' : '')
                + ' data-action="toggleFlag" data-flag-id="' + window.debugEscapeHtml(f.id) + '">'
                + '<span class="dc-switch__track"></span></label>'
                + '<span class="flag-id">' + window.debugEscapeHtml(f.id) + '</span>';
        }).join('');
    }

    function onChange(ev) {
        var el = ev.target.closest('[data-action="toggleFlag"]');
        if (!el) return;
        window.debugFetch(window.lustroApiUrl('toggle'), {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ id: el.dataset.flagId })
        }).catch(function(e) {
            render(); // The browser already moved the switch: put it back.
            window.debugErrorMessage(e).then(function(message) {
                window.debugToast('Failed to toggle: ' + message, 'error');
            });
        });
    }

    // While the app does not answer, a toggle would only fail.
    function onConnectionChange(connected) {
        document.querySelectorAll('[data-action="toggleFlag"]').forEach(function(el) {
            el.disabled = !connected;
        });
    }

    window.lustroOnContentReady(function() {
        document.getElementById('lustro-tab-content').addEventListener('change', onChange);
        window.debugPoll(snapshotUrl, 1500, onSnapshot, { onConnectionChange: onConnectionChange });
    });
})();
```

The listener is on `#lustro-tab-content`, the content area, and not on each switch. Then it keeps
working when `render()` replaces the switches. When the toggle fails, the script renders the list
again, because the browser moved the switch before the request, and the next polls are
`unchanged`. The toast shows the `message` of the error envelope, such as `Unknown flag: x`, and
not only `HTTP 404`. The script treats a status that it does not know as
`reset`, as the envelope's rules require. The fetch sends the console's cookie, so the script
needs no token.

## 9. Style it

`flags.css` adds only what the components do not have. Use the design tokens, not colors, so the
tab follows the theme:

```css
.flags-list {
    display: flex;
    flex-direction: column;
    gap: 8px;
    padding: 14px 22px;
}
.flags-count {
    font-size: 12px;
    color: var(--t3);
}
```

The [styleguide](STYLEGUIDE.md) lists the tokens and the components.

## 10. Describe the routes

The declarations of [step 4](#4-declare-the-routes) are also the tab's OpenAPI document, which
`GET /api/v1/flags/_schema` serves. Agents and tools read it, and `GET /api/v1/_meta` lists the
tab only because it has one. The document has each route's path, method, parameters, and body,
the shared error envelope, and the error statuses: `400` for a route with parameters or a body,
the ones that the server answers for any route, and the ones of `errors(...)`.

`routes.version` is the document's `info.version`, which `_meta` gives as the tab's version:
change it when the routes change. A tab that declares a route must set it. A request with a query
parameter that its route does not declare gets a `Lustro-Warning` header that names it, because
the route ignores it.

Descriptions and schemas make the document useful to an agent:

```kotlin
        routes.description = "The app's feature flags."
        routes.component("Flag", """{"type": "object", "properties": {"id": {"type": "string"}, "enabled": {"type": "boolean"}}}""")
        routes.get("snapshot") {
            summary = "Poll the flags with an opaque cursor."
            description = "status=reset has every flag; status=unchanged has none."
            val cursor = query.string("cursor") { description = "The cursor of the previous response." }
            response("The flags, in the cursor envelope.", """{"type": "object", "properties": {"items": {"type": "array", "items": ${DebugRoutes.ref("Flag")}}}}""")
            handle { call -> snapshot(call[cursor]) }
        }
```

`response(...)` gives the success response's JSON Schema, `component(...)` adds a schema under
`components/schemas`, and `DebugRoutes.ref(name)` refers to one. For a response that is not JSON,
such as the bytes of an export, a block after `response(...)` names its content type and its
headers: `response("The export.") { content("text/csv"); header("Content-Disposition", "attachment.") }`.
A route that answers another success status, such as `201` for a route that creates an item, says
so in the block, so that the document lists it in place of `200`:
`response("The new flag.", DebugRoutes.ref("Flag")) { status = 201 }`. The handler still answers it,
with `DebugResponse.ok(json, status = 201)`. A parameter's `schema` replaces the one made from its
type in the document, such as `{"const": true}`. The sample's
[flags tab](https://github.com/Twinsen81/Lustro/blob/main/sample/src/debug/kotlin/io/github/twinsen81/lustro/sample/SampleFlagsTab.kt)
declares all three routes, with their bodies, responses, and errors.

A tab that has its own document overrides `schema()` and returns it; the declared routes still
route the requests. `super.schema()` returns the generated document, for a tab that changes it.

## 11. Register the tab

Add the tab to the builder before `build()`. After `start()`, `addTab()` throws.

```kotlin
// app/src/debug/kotlin/com/example/debug/LustroBootstrap.kt
object LustroBootstrap {
    fun start(app: Application) {
        Lustro.builder(app)
            .addTab(NetworkDebugTab.create())
            .addTab(FlagsTab())
            .build()
            .start()
    }
}
```

`src/release` gets a `LustroBootstrap` with the same signatures, so the app's `Application` in
`src/main` compiles for both build types and calls `LustroBootstrap.start(this)` in `onCreate()`:

```kotlin
// app/src/release/kotlin/com/example/debug/LustroBootstrap.kt
object LustroBootstrap {
    fun start(app: Application) {}
}
```

The sample's release bootstrap calls the no-op halves instead, with the same calls as its debug
bootstrap but without the flags tab: then the release build also checks that the two halves have
the same API.

## 12. Try it

Install the debug build and keep the app in the foreground:

```bash
lustro meta                                                # flags is in the list
lustro api GET flags/snapshot                              # the flags, with "status": "reset"
lustro api POST flags/toggle --body '{"id":"offline-sync"}'
lustro api POST flags/toggle --body '{}'                   # 400, "field": "id"
lustro api GET flags/_schema                               # the document of the declared routes
lustro open                                                # the console, with the Feature Flags tab
```

A toggle in the console and a toggle from the CLI both show in the console within 1.5 s.

## 13. Test it

`DebugRequest` has a public constructor, so a unit test can call `handle` without a server: the
declared routes route the request, and check its parameters, as they do in the app. Put the test
in `src/testDebug`, because the tab is in `src/debug`. The router reads JSON without `org.json`,
so a test of `toggle` needs no Robolectric; a test of `upload`, which uses `org.json`, does:

```kotlin
class FlagsTabTest {
    @Test
    fun `a toggle of an unknown flag is a 404`() {
        val request = DebugRequest(path = "toggle", method = "POST", body = """{"id":"no-such-flag"}""".toByteArray())

        assertEquals(404, FlagsTab().handle(request)?.status)
    }

    @Test
    fun `the declarations have no mistake`() {
        // Throws IllegalStateException for a mistake in routes().
        assertNotNull(FlagsTab().schema())
    }
}
```

To test a timeout, call `request.cancel()`: `isCancelled` turns `true`, and the `onCancel`
actions run.

A test of the form body gives the request a `contentType`:

```kotlin
class FlagsTabFormTest {
    @Test
    fun `a form body toggles a flag`() {
        val request =
            DebugRequest(
                path = "toggle",
                method = "POST",
                body = "id=offline-sync".toByteArray(),
                contentType = MediaType.parse("application/x-www-form-urlencoded"),
            )

        assertEquals(200, FlagsTab().handle(request)?.status)
    }
}
```
