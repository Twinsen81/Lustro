# Write a tab

This tutorial builds a Feature Flags tab step by step. The tab keeps a list of flags, shows a switch
for each flag in the console, and takes a flags file as an upload. Agents and scripts use the same
flags through three JSON routes. It is the
[sample app's flags tab](https://github.com/Twinsen81/Lustro/blob/main/sample/src/debug/kotlin/io/github/twinsen81/lustro/sample/SampleFlagsTab.kt),
without the parts that connect the flags to the sample's own screens.

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

The tab has one Kotlin file and three assets:

```text
app/src/debug/
├── kotlin/com/example/debug/FlagsTab.kt
└── assets/lustro/
    ├── flags.js
    ├── flags.css
    └── flags.openapi.json
```

The server finds the assets by the tab's id, so their names start with the id.

## 2. Subclass `DebugTab`

A tab needs an `id`, a `title`, and an `icon`. The id is the part of the routes after `/api/v1/`,
and it must match `[a-z][a-z0-9-]{0,30}`. `addTab()` throws an `IllegalArgumentException` for an
id that does not match.

```kotlin
data class Flag(val id: String, val description: String, val enabled: Boolean)

class FlagsTab : DebugTab() {
    override val id = "flags"
    override val title = "Feature Flags"
    override val icon = "🚩"

    // After the Network tab and the plugins, which are 10 to 60.
    override val order = Int.MAX_VALUE
}
```

The server escapes the title and the icon before it puts them in the tab bar.

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

## 4. Route the requests

`handle(request)` gets every request to `/api/v1/flags/...`, with `request.path` set to the part
after `flags/`. Return `null` for a route that the tab does not have: the client gets a `404` in the
error envelope.

```kotlin
    override fun handle(request: DebugRequest): DebugResponse? {
        val method = request.method.uppercase()
        return when {
            request.path == "snapshot" && method == "GET" -> snapshot(request)
            request.path == "toggle" && method == "POST" -> toggle(request.bodyAsString())
            request.path == "upload" && method == "POST" -> upload(request.bodyAsString())
            else -> null
        }
    }
```

Only `snapshot` is a `GET`, because it changes nothing. A route that changes state must take
`POST`, `PUT`, `PATCH`, or `DELETE`: a page on another site can make the browser send a `GET` that
the server cannot refuse. [Custom tabs](custom-tabs.md) explains why.

When `handle` throws, the client gets a `500`, and the app does not crash.

## 5. Answer a poll with the cursor envelope

The console polls `snapshot` every 1.5 s. With the
[cursor envelope](wire-protocol.md), a poll gets the list only when it changed since the last
poll. `DebugResponse.cursorEnvelope` compares the client's cursor with the current sequence, and
calls the lambda only when the list must go out:

```kotlin
    private fun snapshot(request: DebugRequest): DebugResponse =
        DebugResponse.cursorEnvelope(
            currentSequence = sequence.get(),
            clientCursor = request.queryParam("cursor"),
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

## 6. Change state on `POST`

`toggle` reads the body, changes one flag, and advances the sequence. The `DebugResponse` factories
make the error envelope: `error(...)` is a `400` by default, and `notFound(...)` is a `404`.

```kotlin
    private fun toggle(body: String?): DebugResponse {
        val json =
            try {
                JSONObject(body ?: "{}")
            } catch (e: JSONException) {
                return DebugResponse.error("Invalid JSON: ${e.message}")
            }
        val flagId = json.optString("id")
        if (flagId.isBlank()) return DebugResponse.error("id is required", field = "id")
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

`upload` works the same way. It parses a JSON array of flags, or an object with a `flags` array,
puts each flag, and advances the sequence once when it added at least one. The server reads at most
[`maxRequestBodyBytes`](reference/configuration.md) of a body, 1 MiB by default, and answers a
larger one with a `413` before the tab sees it.

These handlers return at once. A route that waits, for a network call or a long query, must stop
when the request times out: the client then has a `504`, and the request still holds one of the
server's slots. Check `request.isCancelled` in a loop, and give a blocking call a way to stop with
`request.onCancel`:

```kotlin
    val call = client.newCall(exportRequest)
    request.onCancel { call.cancel() }
    call.execute().use { response -> /* ... */ }
```

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
- `debugPoll(urlOrFunction, intervalMs, callback)` polls a route, and shows the connection state in
    the top bar.
- `debugFetch(url, options)` rejects on a status that is not `2xx`.
- `debugEscapeHtml(text)` escapes a value for HTML.

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
        }).catch(function(e) { window.debugToast('Failed to toggle: ' + e.message, 'error'); });
    }

    window.lustroOnContentReady(function() {
        document.getElementById('lustro-tab-content').addEventListener('change', onChange);
        window.debugPoll(snapshotUrl, 1500, onSnapshot);
    });
})();
```

The listener is on `#lustro-tab-content`, the content area, and not on each switch. Then it keeps
working when `render()` replaces the switches. The script treats a status that it does not know as
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

## 10. Describe the routes in OpenAPI

`flags.openapi.json` describes the routes for agents and tools. Without it, the tab works in the
console, but `GET /api/v1/_meta` does not list it, so an agent does not find it:

```json
{
  "openapi": "3.1.0",
  "info": { "title": "Feature Flags debug tab", "version": "1.0" },
  "paths": {
    "/api/v1/flags/snapshot": {
      "get": {
        "operationId": "snapshotFlags",
        "summary": "Poll the flags with an opaque cursor.",
        "parameters": [
          { "name": "cursor", "in": "query", "required": false, "schema": { "type": "string" } }
        ],
        "responses": { "200": { "description": "Cursor envelope." } }
      }
    }
  }
}
```

`_meta` gives `info.version` as the tab's version: change it when the routes change. The sample's
[`flags.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/sample/src/debug/assets/lustro/flags.openapi.json)
has all three routes, with their bodies, responses, and errors.

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
lustro open                                                # the console, with the Feature Flags tab
```

A toggle in the console and a toggle from the CLI both show in the console within 1.5 s.

## 13. Test it

`DebugRequest` has a public constructor, so a unit test can call `handle` without a server. Put the
test in `src/testDebug`, because the tab is in `src/debug`. The tab uses `org.json`, which needs
Robolectric in a local unit test:

```kotlin
@RunWith(RobolectricTestRunner::class)
class FlagsTabTest {
    @Test
    fun `a toggle of an unknown flag is a 404`() {
        val request = DebugRequest(path = "toggle", method = "POST", body = """{"id":"no-such-flag"}""".toByteArray())

        assertEquals(404, FlagsTab().handle(request)?.status)
    }
}
```

To test a timeout, call `request.cancel()`: `isCancelled` turns `true`, and the `onCancel`
actions run.
