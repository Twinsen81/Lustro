# Driving Lustro from agents and automation

Lustro is a browser-based Android debug toolkit, but every tab is also a JSON API under
`/api/v1/`, so AI agents, scripts, and the `lustro` CLI can drive it directly instead of scraping
HTML. This document is the operational guide for non-browser clients. The HTTP wire protocol —
not the Kotlin API — is the stable contract; it is versioned and SemVer-governed under
[`wire-protocol/v1/`](wire-protocol/v1/).

This document is about using a running Lustro. To add Lustro to an app, follow the README's
[Install](../README.md#install) and [Quick start](../README.md#quick-start) sections.

> **Status:** pre-1.0. The protocol may still change between snapshots. Always read `_meta` and
> the per-tab `_schema` at runtime rather than hard-coding shapes.

## The agent's view

- **Discoverable tabs.** `GET /api/v1/_meta` lists every tab that ships a schema. A tab is
  agent-discoverable **only if** it exposes one (a static `assets/lustro/<id>.openapi.json` or a
  dynamic `schema()`); schema-less tabs are usable in the browser but absent from `_meta`.
- **Self-describing.** Fetch `GET /api/v1/<id>/_schema` for a tab's OpenAPI document and
  `GET /api/v1/_schema` for the shared envelope schemas. Drive operations from those, not from
  hard-coded paths.
- **Built-in tab.** v1 ships exactly one built-in tab: `network` (capture, inspect, mock,
  throttle, replay, and the messages of WebSocket connections). Its contract is
  `lustro/src/main/assets/lustro/network.openapi.json`.
- **Plugin tabs.** An app can also register plugin tabs, such as `logs`, the app's own logcat
  (see [Logs tab operations](#logs-tab-operations)), and `prefs`, the app's SharedPreferences
  and Preferences DataStores (see [Preferences tab operations](#preferences-tab-operations)), and `sqlite`, the app's SQLite and Room databases
  (see [SQLite tab operations](#sqlite-tab-operations)). `_meta` lists the ones the app registered,
  and each plugin's `_schema` has a version of its own in `info.version`.

## Use the `lustro` CLI

Agents should drive Lustro with the `lustro` CLI. It is a Python client in `lustro-cli/`, and each
release publishes it to PyPI; until the first release, install it from a checkout with
`pip install ./lustro-cli`. It reads the token and the endpoint from the `LustroToken` log line,
and it prints much less than the routes return: `GET transactions` returns every captured
transaction with all its fields, up to 1,000 of them, and `lustro net list` prints 50 short rows.
The wire protocol in the sections below stays the stable contract, and the curl examples use it
directly.

```bash
lustro open --print-only          # forward the port; exits 1 when adb forward fails
lustro net list                   # the newest 50 transactions, newest first
lustro net get <id> --no-body     # one transaction, without its bodies
lustro net wait --url /v1/orders -- adb shell input tap 540 1200
lustro net export --har out.har   # every transaction as a HAR file
lustro net ws list                # the WebSocket connections, newest first
lustro net ws events <id> --last 20   # the last 20 messages and lifecycle events of one
lustro api GET network/transactions --param search=orders   # any route; prints the response
```

`lustro api METHOD PATH` reaches every route, also those of a tab that has no commands of its own,
with the same discovery and the same error output: it prints the response body, and for an error
the error from the envelope on stderr, with exit status 1. A PATH without a leading `/` is under
`/api/v1/`. `--param KEY=VALUE` adds a query parameter, `--body JSON` or `--input FILE` (`-` for
stdin) sends a JSON body. `lustro schema <tabId>` prints the tab's OpenAPI document, which
describes its routes.

Each row of `net list` starts with a short transaction id: the first 8 characters of the id, or more
when two listed ids start with the same 8. `net get`, `net body`, and `net export --ids` take the short id, the full id,
or any start of an id that only one transaction has. When more than one id starts with it, they
print the matches and exit 2; when no id does, they exit 1. JSON output has the full id, which the
wire routes in the curl examples need. The filters `--url`, `--method`, `--status` (a code such as
`404`, or a class such as `5xx`), and `--errors` combine with each other and with `--search`, which
the server runs over the URL, the method, the headers, and the bodies, as text or, with `--regex`,
as a regular expression (see [Search](#search)). Global flags, such as `--json` and `--port`, go
before or after the command.

`lustro open` forwards a local port to the app's port on the device. When another process holds
local port 8080, `--local-port 0` lets adb choose a free local port, and `--local-port N` forwards
port N. The other commands find the forward in `adb forward --list`, also one that you made
yourself, so they need no `--port`.

### Keep the output small

An agent reads all of the output, so ask for only what you need:

- `lustro net list --errors --last 20`: the 20 newest requests that failed or got a status of 400
  or higher.
- `lustro net get <id> --no-body`: one transaction with its headers, without its bodies. Without
  `--no-body`, `net get` cuts each body at 2 KB and ends it with a marker such as
  `[... 18251 more bytes: lustro net body <id> response]`. `--full` prints the bodies whole.
- `lustro net body <id> | head -c 4000`: the first 4,000 bytes of the response body.

`net list` says on stderr how many transactions it left out, and `--last N` or `--all` prints more.
With `--json`, a list prints as JSON Lines, one compact object per transaction, so `head` and `jq`
can cut it, and `--fields id,statusCode,url` keeps only those keys. A single JSON object prints
compact when stdout is not a terminal. `lustro net state` prints the capture state: pause,
overwrite mode, throttle, and the capture filter.

### Wait for a request

`lustro net wait` takes the same filters as `net list`. It polls until a matching request
finishes, prints it, and exits 0, or exits 1 after `--timeout` seconds (30 by default). It ignores
the requests that finished before it started, and a mocked request finishes in a few
milliseconds. So give the command that makes the app send the request after `--`, as above:
`net wait` runs it after its first poll. When the action is not a shell command, start
`lustro net wait` in the background before the action.

### Search

`--search TEXT` finds the transactions in which the text is found, ignoring case: in the URL, the
final URL, the method, a request or response header (as the line `Name: value`), or a body. The
text has no syntax: `a|b`, `-1`, and `/api/v1/` are found as typed. Headers and bodies are
searched as stored, so a redacted value is found as `[REDACTED]`, not as the secret.

`--regex` makes each word of the text, up to a space, a Java regular expression found ignoring
case. Every word must be found, each in any of those fields, and a word that starts with `-` must
be found in none:

| `--search` with `--regex` | Finds |
| --- | --- |
| `'featured\|popular'` | either word |
| `'lists json'` | both words, each in any field |
| `'podcasts -analytics'`, `'-analytics'` | the first, but not the second; not the word |
| `'^content-type:\s.*json'` | a header with a value (a space in a regex is `\s`) |
| `'/v\d+/ -status/5'` | a versioned path, without the 5xx status paths |

A regex that doesn't compile makes the command print the reason and exit 2, and so does a
search that takes longer than 5 s. `--url` is always plain text. `net ws events --search` and
`--regex` work the same way over the text of the messages. The Network tab's search fields have a
**Regex** button that does the same, and a **?** button that lists common regular expressions. The header search
and `regex` arrived in protocol 1.8.

`lustro net poll` prints transactions as they arrive. When a request that was in flight finishes
or fails, it prints its row again, marked `[update]`; with `--json`, the later line for an id
replaces the earlier one.

### WebSocket messages

`lustro net ws list` prints one row for each connection: a short id, the start time, the state
(`open`, `closed 1000`, `failed 403`, `canceled`), the counts of sent and received messages, and
the URL. `lustro net ws get <id>` prints one connection with the headers of its handshake.

`lustro net ws events <id>` prints the connection's log, oldest first, the last 50 events by
default:

```
    1  14:22:17.243  --  open 101
    2  14:22:17.250  ->  text         36B  {"type":"auth","token":"[REDACTED]"}
    3  14:22:17.391  <-  text         16B  {"type":"ready"}
    4  14:22:18.004  ->  binary        4B  deadbeef
```

`->` is a message the app sent, and `<-` one it received. `--` is a lifecycle event: `open`,
`close sent` (the app called `close()`), `close received` (the server's close frame), `closed`,
`cancel`, and `failure`. A sent message means that OkHttp queued it; it does not show that the
server received it. `[not sent]` marks a message that `send()` refused. A long text ends with a
marker that names the command for the rest, such as
`[... 299961 more bytes: lustro net ws payload 3d2a7f10 5]`, and `lustro net ws payload <id> <seq>
[-o FILE]` prints or saves a payload as it is stored. `--sent`, `--received`, and `--search TEXT`
(with `--regex`, a regular expression) select events on the server, `--last N` or `--all` sets
how many, and `--follow` then prints each new event as it comes. `--json` prints JSON Lines, and
`--fields seq,kind,preview` keeps only those keys.

A connection is listed only when the app creates its socket with the factory from
`Lustro.webSocketFactory`. For any other socket, `lustro net list` shows the handshake, as a
request with the status `101`.

## Auth and token discovery

Token auth is **always on**. Every `/api/v1/*` route except `/api/v1/_auth` requires a valid
token; unauthenticated requests get an enveloped `401`.

**Programmatic clients** send the token as a Bearer header:

```
Authorization: Bearer <token>
```

**Discovering the token and endpoint.** On every successful bind, Lustro logs exactly one
machine-parseable line at logcat tag `LustroToken`, level INFO:

```
Lustro ready endpoint=http://<host>:<port> token=<token>
```

It is the first place to look for host, port, and token. Lustro logs it again after each
bind, so the last line is the current one, unless the device dropped the newer lines (see below). `-d` prints the log and exits instead of waiting for
new lines:

```bash
adb logcat -d -s LustroToken | tail -1 | sed -n 's/.*endpoint=\([^ ]*\) token=\([^ ]*\).*/\1 \2/p'
```

**When the log has no ready line.** A device can drop Info logs: with `adb shell getprop log.tag`
set to `E`, the line never reaches logcat (`adb shell setprop log.tag.LustroToken I` lets it
through until the device restarts). The token, and the host and port of the app's last bind, are
also in the app's prefs file, which a debuggable app lets you read:

```bash
adb shell run-as <package> cat shared_prefs/lustro_debug.xml
# <string name="lustro_token">…</string> <string name="lustro_host">127.0.0.1</string> <int name="lustro_port" value="8080" />
```

Lustro listens only while its app is in the foreground, so the package is that of the resumed
activity (`adb shell "dumpsys activity activities | grep ResumedActivity"`). The last line in the
log can also be stale, from an earlier install of the app, when the device dropped the newer one:
a `401` to a token from the log means read the prefs.

Conventions a client should follow:
- Honor a `LUSTRO_TOKEN` environment variable when present, falling back to the parsed log line,
  then to the app's prefs.
- The port can differ from the configured one: if `bindFallback` is enabled and the configured
  port was taken, the server binds an OS-assigned port — the `endpoint=` field reports the actual
  one, so always trust the log line over assumptions.

(Browsers authenticate differently — via the `HttpOnly; SameSite=Strict` `lustro_token` cookie
set by `POST /api/v1/_auth`, or by opening with a `#lustro_token=<token>` URL fragment. Agents
should use the Bearer header.)

## Endpoint and host/device

The server binds to `127.0.0.1:8080` by default and only listens while the app is **foregrounded**.

- From a desktop, forward the device port first. `--no-rebind` stops adb from taking over a
  forward that a different tool set up for a different device on the same local port:

  ```bash
  adb forward --no-rebind tcp:8080 tcp:8080
  ```

  Then talk to `http://localhost:8080`. If the local port is taken ("Address already in use", or
  "cannot rebind existing socket" for a forward that `adb forward --list` doesn't show for your
  device), forward a free local port to the same device port, for example
  `adb forward --no-rebind tcp:18080 tcp:8080`, and use `http://localhost:18080`. The `lustro`
  CLI finds such a forward without `--port`.
- With more than one device connected, use the device in `ANDROID_SERIAL`, or pass
  `adb -s <serial>`; don't choose one that other tools may be using. Don't clear the device log
  (`adb logcat -c`) to find the `LustroToken` line: read it with `-d` and take the last one.

- If you can't reach the default port, parse the `endpoint=` field from the `LustroToken` log
  line (see above) to learn the real host and port, and forward that port instead.
- LAN exposure (`bindAddress = "0.0.0.0"`) is an opt-in the app developer sets; do not assume it.

## Wire protocol

All routes are under `/api/v1/`. The major version lives in the path; the minor version is
reported as `_meta.protocolVersion`.

**Framework routes**

| Route | Purpose |
| --- | --- |
| `GET /api/v1/_meta` | `{ libraryVersion, protocolVersion, tabs: [{ id, title, schemaUrl, version }] }` — only schema-exposing tabs are listed. |
| `GET /api/v1/_schema` | JSON Schema for the shared envelopes. |
| `GET /api/v1/<id>/_schema` | A tab's OpenAPI document (enveloped `404` if it has none). |

**Shared envelopes**

- **Error** (any failing route): `{ error, message, code?, field?, hint? }`. `error` is a stable
  machine type derived from the status — `bad_request` (400), `unauthorized` (401), `forbidden`
  (403), `not_found` (404), `method_not_allowed` (405), `payload_too_large` (413),
  `internal_error` (500), `unavailable` (503), `timeout` (504).
- **Pagination** (collection routes): `{ items: [...], nextCursor: "<opaque>" | null }`.
- **Cursor** (observable routes): `{ cursor, status, items? }` where `status` is `delta`,
  `unchanged`, or `reset`. Echo `cursor` back on the next poll; **treat any unknown `status` as
  `reset`** and re-sync the full list. The cursor advances when the route's list changes, and a
  cursor from before an app restart gets a `reset`.
- **Stream** (routes whose list only grows at its end): `{ cursor, status, items?, dropped? }`,
  with the same statuses. Here a `delta` carries only the entries after the cursor, oldest first:
  append them. A `reset` carries the last entries of the list: replace what you have. `dropped`
  counts the entries the list evicted before you got them. Treat any unknown `status` as `reset`.

## Network tab operations

Base path `/api/v1/network`. The authoritative contract is `network.openapi.json`; the table
below summarizes it. All routes are token-authenticated and use the shared error envelope.

| Operation | Route | Notes |
| --- | --- | --- |
| Poll transactions | `GET transactions?cursor=&search=&regex=` | Cursor envelope. First poll (no/invalid cursor) → `reset` with the full list; cursor unchanged → `unchanged` (items omitted); after a change → `delta`. `search` filters case-insensitively over the URL, the method, the headers (each as the line `Name: value`), and the bodies; with `regex=true`, it is a Java regular expression, and one that doesn't compile gets a `400` with `field: search`. Carries a top-level `state` `{ paused, overwriteMode, throttleDelayMs, captureFilter }`. |
| Transaction detail | `GET transactions/{id}` | Full object (headers + bodies, with truncation flags). Enveloped `404` if missing. |
| Transaction body | `GET transactions/{id}/body/{request\|response}` | One body as it is stored: the bytes of an image, or the redacted text as UTF-8. Not JSON. Enveloped `404` when no body was kept. See below. |
| Export as HAR | `GET transactions/_/export?format=har&ids=` | A HAR 1.2 document of the transactions in `ids` (comma-separated), or of every one. See below. |
| Poll WebSocket connections | `GET websockets?cursor=` | Cursor envelope, newest first, with the same `state` as the transactions poll. See below. |
| WebSocket connection detail | `GET websockets/{id}` | One connection with `requestHeaders`, `responseHeaders`, and `protocol`. Enveloped `404` if missing. |
| Poll a connection's events | `GET websockets/{id}/events?cursor=&limit=&direction=&search=&regex=` | Stream envelope: the messages and lifecycle events, in order. See below. |
| Message payload | `GET websockets/{id}/events/{seq}/payload` | One message's payload as it is stored: bytes, or the redacted text as UTF-8. Not JSON. |
| Clear | `POST clear` | Clears the captured list and the WebSocket connections, and starts the capture filter's counts again; mock rules and settings are preserved. |
| List rules | `GET rules` | `{ items: [MockRule...] }`, in the order the rules are tried. |
| Add / upsert rule | `POST rules` | Body `MockRuleInput` (`urlPattern` required). Supplying a stable `id` makes the write **idempotent** (upsert by id); omitting it generates one. A new rule goes first; a rule replaced by id keeps its place. Returns `{ status: "ok", id }`. |
| Sync rules | `POST rules/_/sync` | **Atomic** full replacement: posts an array; the resulting list exactly equals it, in that order, with no empty window observed by the interceptor. Returns `{ status: "ok", count }`. |
| Delete rule | `POST rules/delete` | Body `{ id }`. |
| Toggle rule | `POST rules/toggle` | Body `{ id }`; flips `enabled`. |
| Pause capture | `POST pause` | Toggles capture-only pause. While paused, mocks and throttle **still apply**; only recording into the list stops, for requests and for WebSocket messages. Returns `{ status: "ok", paused }`. |
| Overwrite mode | `POST overwrite-mode` | Toggles overwrite mode (a new request evicts earlier **completed** transactions with the same method + URL path; in-flight ones are never evicted). The new transaction's `replacedCount` says how many copies it evicted, with the ones those had evicted (protocol 1.10). Returns `{ status: "ok", overwriteMode }`. |
| Throttle | `POST throttle` | Body `{ delayMs }` (≥ 0); a global pre-request sleep applied to mocked and real OkHttp requests alike; platform `HttpURLConnection` requests are not held. A throttled transaction is listed while it waits and records the wait in `throttledMs`. Returns `{ status: "ok", delayMs }`. |
| List senders | `GET senders` | `{ items: [{ name, baseUrl }] }`: the OkHttp clients the app registered for `send`, in order; the first is the default. Empty when there are none. |
| Send request | `POST send` | **Synchronous** send through one of the senders. See below. |

**Mock rule semantics.** `urlPattern` is looked for anywhere in the URL: a substring, or a regular
expression when prefixed with `regex:` (found, not matched against the whole URL, so anchor it with
`^` and `$` when it must match all of it). `method` is `null` to match any method. The rules are
tried in the order `GET rules` lists them, and the first enabled rule that matches answers the
request: a new rule goes first, so the rule added last wins when two match, a rule replaced by id
keeps its place, and a sync sets the order as posted. `hitCount` is a runtime-only counter (not
persisted). Rules answer OkHttp calls only; a platform `HttpURLConnection` request is captured, but
no rule matches it, so a rule for one keeps `hitCount` at 0. A transaction's `source` says which
adapter captured it (see "Capture source" below).

**Which rule answers a request.** Don't match the rules yourself: the app does, as the interceptor
does, and puts the result in each transaction. `activeMockRuleId`, in the list and the detail, is
the rule that answers the next request with that method and URL, or `null`; it follows the rules as
they change, so it is set on a request captured before its rule existed. The detail's
`matchingMockRuleIds` lists every rule whose pattern and method match, enabled or not, in the order
they are tried. `mockRuleId` is the rule that answered a mocked transaction, recorded when it did;
that rule can have changed or been deleted since. The rules are matched against the URL as
captured, which is redacted, so a pattern that names a redacted value doesn't match there. A
platform transaction has no matching rule. `state.mockRules` has `enabled`, the number of enabled
rules, and `revision`, which changes with every change to the rules but a hit: fetch `GET rules`
again when it does. These fields arrived in protocol 1.9.

**Rules are validated on the way in.** A `regex:` pattern must compile and must not be empty,
`statusCode` must be within 100–599, `responseHeaders` must
be header names and values OkHttp accepts, and a `Content-Type` among them must parse as a media
type — the interceptor builds a real response from the rule inside the app's own call. A rule that
fails any of these gets an enveloped `400` whose `field` names what to fix (`urlPattern`, `id`,
`statusCode`, or `responseHeaders`); for `rules/_/sync` the message also carries the array index,
and the whole batch is rejected. `POST rules` replaces a rule with the same `id` wholesale, so send
`enabled` and `responseHeaders` when editing one or they fall back to `true` and empty.

**Send request semantics.** Body `{ url, sender?, method?="GET", headers?, body? }`. `sender` is
a name from `GET senders`; without it, the first sender sends, and an unknown name is a `400` with
`field: "sender"`. Each sender is one of the app's OkHttp clients, so pick the one whose
interceptors (auth, for one) and host fit the request. An absolute `url` is sent as given; a
relative one is appended to the sender's `baseUrl`, path and all, and is a `400` with
`field: "url"` when the sender has none. Blocks until the sender returns, within the per-request
timeout. The response is `{ transactionId, sender, url, statusCode, ok, error? }`: `url` is where
the request went, and `ok` is `true` whenever a response came back, a `401` included, so read
`statusCode`. `transactionId` names the transaction the Lustro interceptor recorded for the
request, and is `null` when the sender's client has no interceptor or the request wasn't captured.
The response body is never returned, and the sender reads at most `maxBodyCaptureBytes` of it, so
large or endless responses are safe to send. A send that outlives the per-request timeout gets the
enveloped `504`, and its call is cancelled. Requests to the debug server's **own** bind host:port
are rejected. **Send is only available when the app registered a sender**: if not, `GET senders`
is empty, `POST send` returns an enveloped `404`, and the console has no Send form. Senders,
`sender`, the send result's `sender` and `url`, and a set `transactionId` arrived in protocol 1.7.

**What advances the cursor.** Changes to the captured list advance the transactions cursor: a
new request, its response or streaming progress, a failure, an eviction, or `clear`. So does a
change to the mock rules, because each transaction says which rule answers it; a hit on a rule
doesn't. Pause, overwrite mode, throttle, and the capture filter's counts don't. Every poll
response carries the current `state`, `unchanged` ones included, and `GET rules` returns the
rules with their hit counts.

**Times, protocol, and content types.** Every transaction, in the list and in the detail, has
`startedAt` and `completedAt` in milliseconds since the Unix epoch. Sort and correlate by those,
with logcat or a server log: `timestamp` is an `HH:mm:ss.SSS` display time in the device's zone.
`completedAt` is `null` while the request is in flight. A response with a status can still be in
flight (`responseComplete: false`): the app is still reading a body that capture didn't read
whole, such as a download, a body past the capture cap, or a stream. It completes when the app
reads to the end of the body or closes it, and `durationMs` then covers the whole body, and
`responseBodyBytes` is counted when the response had no `Content-Length`. A body that fails while
the app reads it sets `error` and keeps `statusCode`. Take a request's duration from
`durationMs`: `completedAt` can be later by the time the capture took to record it. `protocol` (`http/1.1`, `h2`, `h3`) is
`null` before a response and for a mocked one. Platform `HttpURLConnection` capture reads it from
`X-Android-Selected-Protocol`, a header that the platform adds to the response, and leaves the
platform's `X-Android-*` headers out of `responseHeaders`, since the server didn't send them.
`requestContentType` and `responseContentType` give the media type as captured, so a client can
tell JSON from an image without reading the headers. These fields arrived in protocol 1.2; a 1.1
server leaves them out.

**Throttled requests.** When the global throttle holds a request, the transaction is listed from
the moment the app made the call, so `startedAt` is the call time, and `throttledMs` says how long
the throttle held it before it was sent. `durationMs` leaves that wait out, and `completedAt`
comes at least `throttledMs` plus `durationMs` after `startedAt`. `throttledMs` is `null` for a
request that wasn't throttled. In the HAR export, the wait is the entry's `timings.blocked`, and
`time` is the sum. This field arrived in protocol 1.4.

**Redirects.** OkHttp follows a redirect inside the call, so a redirected request is one
transaction: `url` is what the app requested, and `finalUrl` is where the response came from,
`null` when that is `url`. The detail's `priorResponses` lists the responses OkHttp answered with
another request, oldest first: each redirect, and each authentication challenge that an
`Authenticator` answered, with its `url` and `statusCode`. Search finds a transaction by its
`finalUrl` too. Platform `HttpURLConnection` capture reports `finalUrl` but no prior responses, which
the platform doesn't show. These fields arrived in protocol 1.5.

**Capture source.** Every transaction has `source`, the adapter that captured it. `okhttp` is
Lustro's OkHttp interceptor: mock rules and the throttle apply to its requests. `platform` is
platform `HttpURLConnection` capture, which only records: no rule answers the request and the
throttle doesn't hold it, so don't make a mock rule from one. `app` is the app's own adapter,
through `Lustro.networkCaptureSink()`: a rule answers its request only if the adapter asks for it
with `findMockRule`, and the throttle doesn't hold it. Treat a value you don't know as one that no
rule or throttle reaches. The CLI row ends with `[platform]` or `[app]`. This field arrived in
protocol 1.6.

**Multipart request bodies.** An OkHttp `MultipartBody` is captured as text: the parts in order,
each with its headers (`Content-Disposition`, and `Content-Type` and `Content-Length` when known),
then the value of a text part. A part that is not text, such as a file, is not read: its content is
one line, `[Lustro did not store this part: <type>, <n> bytes]`, or `size unknown`. The
`requestContentType` carries the boundary. `requestBodyBytes` is the size of the whole body on the
wire, and the stored text is cut at the capture cap like any other body. The default redactor masks
a part whose field name is sensitive, redacts any other part as a body of its own type, and masks a
part's own headers as it masks a request's. A file part with no type is not read either.

**Image bodies and the body route.** A body is captured as text, or, for an image, as bytes; SVG
is text. The detail's `requestBodyBinary` and `responseBodyBinary` are `true` when that body was
kept as bytes, and its `requestBody` or `responseBody` is then `null`.
`GET transactions/{id}/body/{request|response}` returns either kind as it is stored, so it is also
how to save a large text body to a file. A body cut at the capture cap comes back as the part that
was kept, and the detail's `...BodyTruncated` flag says when. `Content-Type` is the captured media
type without its parameters, plus `charset=utf-8` for text. The route answers an enveloped `404`
when the request or response had no body, or when capture kept none: a one-shot or duplex request
body, or a binary type other than an image. The redactor never sees an image, so treat it as
unredacted. `lustro net body <id> [request|response] -o FILE` wraps the route. These fields and
the route are part of protocol 1.2.

**Export as HAR.** `GET transactions/_/export?format=har&ids=<id>,<id>` returns the transactions as a
HAR 1.2 document, oldest first, which browser devtools, proxies, and HAR viewers import. It is
built from the store, so it has the same redacted values as the detail. Without `ids` it has every
transaction; an id the app no longer has is left out. Each entry's `_lustro` has the transaction
`id`, `isMocked`, `source`, `categories`, `requestBodyTruncated`, `responseBodyTruncated`,
`responseComplete`, and `error`. The capture has no phase timings, so each entry spends its whole
`durationMs` in `timings.wait` and the throttle's wait in `timings.blocked`, and `_resourceType`
(`fetch`, or `image` for an image) tells Chrome DevTools how to file it. A body kept as bytes is base64: `content.encoding` says so for a
response, and `postData._encoding` for a request. The request line and its headers must stay
under 8 KB, so send many ids in batches; `lustro net export --har FILE [--ids ID ...]` does that,
and `--har -` writes the document to stdout. This route is part of protocol 1.2. From protocol 1.3,
the entry of a WebSocket's handshake has `_resourceType: "websocket"`, the socket's messages in
`_webSocketMessages` (`type` `send` or `receive`, `time` in seconds, `opcode` 1 for text and 2 for
base64 bytes, `data`), which Chrome DevTools reads, and the connection in `_lustro.webSocket`.

**WebSocket connections.** `GET websockets` lists the sockets that the app creates with the
factory from `Lustro.webSocketFactory`, in a cursor envelope; the cursor advances when a connection
is listed, changes state, or gets an event. A connection has `url` (with the `ws` or `wss` scheme,
redacted), `state` (`connecting`, `open`, `closing`, `closed`, `failed`), `startedAt`, `openedAt`,
and `closedAt` in epoch milliseconds, `statusCode` of the handshake response, `closeCode`,
`closeReason`, and `closedBy` (`app` or `server`: whose close frame came first), `canceled`,
`error` (the exception of a failed socket), and the counts `sentCount`, `sentBytes`,
`receivedCount`, and `receivedBytes`. `transactionId` is the handshake's transaction, when the
interceptor on the same client captured it, and that transaction has `webSocketId`: the
transactions list shows the same handshake with an `https` URL, as OkHttp holds it. A socket
created in another way has no connection, only the handshake transaction, with `webSocketId: null`.

**WebSocket events.** `GET websockets/{id}/events` is the connection's log in a stream envelope.
Poll it first without a cursor: `reset` has the last `limit` events (100 by default). Then echo
the cursor: `delta` has only the events after it, oldest first, and `unchanged` has none. Each event
has `seq`, `at` (epoch milliseconds), `timestamp`, and `kind`, and only the other keys of its kind:

- `message`: `direction` (`sent` or `received`), `type` (`text` or `binary`), `payloadBytes` (the
  whole payload), `truncated` (the capture cap cut it), `stored`, and `preview` with
  `previewComplete` for text (the first 512 characters, redacted) or `hexPreview` for binary (the
  first 32 bytes). A sent message has `enqueued`, the return value of `send()`: `true` means that
  OkHttp queued it, which does not show that the server received it, and `false` means that it was
  not sent.
- `open`: `statusCode`. `close`: `direction`, `code`, `reason`, and `enqueued` for the app's own
  `close()`. `closed`: `code`, `reason`. `cancel`: nothing more. `failure`: `error`, `statusCode`.

`seq` orders the events and names one in the payload route; a number can be missing. `direction`
and `search` (over the stored text, case-insensitive, or a regular expression with `regex=true`) leave events out of `items`. When
`previewComplete` is `false`, `GET websockets/{id}/events/{seq}/payload` returns the stored text;
for a binary message it returns the bytes. A connection keeps its last 1000 events by default:
`evictedEvents` on the connection counts the ones it no longer has, `dropped` in a `delta` counts
the ones evicted before you got them, and `droppedEvents` counts messages that were never stored
because capture was behind. While capture is paused, no message is recorded and no new socket is
listed. `POST clear` removes the connections; a socket that is still open is listed again with its
next message, with a new log, so a cursor from before the clear gets a `reset`. These routes and
`webSocketId` are part of protocol 1.3.

**Compressed bodies.** A body sent or received with `Content-Encoding: gzip`, `x-gzip`, or
`deflate` is stored inflated: `requestBody` and `responseBody` hold the text, and
`...BodyTruncated` says whether the inflated body passed the capture cap. The headers still name
the encoding, and `requestBodyBytes` and `responseBodyBytes` are the size on the wire, before
inflating, so drop `Content-Encoding` when you send a stored body again. A body in another
encoding, such as `br`, is not stored: its body is `null`, and its size is kept. Event streams are
stored as they arrive, without decoding, and so are JSON streams (`application/x-ndjson`,
`application/ndjson`, JSON Lines, `application/json-seq`, `application/stream+json`): poll the
transaction while it is in flight to see each line as the app reads it.

**Request bodies arrive after the request.** OkHttp capture copies a request body while OkHttp
sends it, so a transaction is listed first with `requestBodyBytes` alone and gets its
`requestBody` once the body is sent; a request that fails before that keeps the size alone. A
request body with no `Content-Type` is stored as text, and its `requestContentType` is `null`.

## Logs tab operations

Base path `/api/v1/logs`, when the app registers the Logs plugin (`LogsDebugTab.create()`; without
it, the routes are an enveloped `404 Unknown tab: logs`). The authoritative contract is
`lustro-logs/src/main/assets/lustro/logs.openapi.json`, version `1.0`. The tab runs
`logcat -v threadtime -v UTC --pid=<the app's pid>` while Lustro is started, also in the background, from
the start of the app's process, and holds the newest lines in a buffer of a fixed size (4 MiB by
default). The CLI has no `logs` commands: use `lustro api`.

| Operation | Route | Notes |
| --- | --- | --- |
| Poll the log | `GET entries?cursor=&level=&tag=&q=&mq=&limit=` | Stream envelope. The first poll gets `reset` with the last `limit` lines (default 500, at most 5000) that pass the filters; a poll with the cursor gets `delta` with the lines after it, oldest first, in pages of `limit`, or `unchanged`. `state` is `{ reader, readerError?, paused, compact }` on every response. |
| Export as text | `GET text?cursor=&level=&tag=&q=&mq=&limit=` | `text/plain`: the held lines as `adb logcat -v threadtime` prints them, oldest first, every one that passes the filters, or the last `limit`. A repeated line prints once with ` [×N]`. With a cursor from `entries`, only the lines after it. |
| Clear | `POST clear` | Empties the buffer and returns `{ status: "ok", cursor }`: poll with that cursor to get only the later lines, with no `dropped` count. A cursor from before the clear gets a `reset`. |
| Pause | `POST pause` | Body `{ "paused": true }` or `false`; without a body it toggles. While paused, the tab drops new lines. Returns `{ status: "ok", paused }`. |
| Compact | `POST compact` | Body `{ "compact": true }` or `false`; without a body it toggles. On by default: a line that repeats the line before (same level, tag, pid, tid, and message) folds into it. Returns `{ status: "ok", compact }`. |

**An item.** `{ seq, at, timestamp, pid, tid, level, tag, message }`, and `count` and `lastAt` for a
repeated line. `at` and `lastAt` are epoch milliseconds, and `timestamp` is `at` as logcat prints
it, `MM-dd HH:mm:ss.SSS` in the device's time zone. `seq` identifies the line. **A repeated line
comes again** in a later `delta` with the same `seq` and a larger `count`: replace the row you
have with that `seq`, do not add a second one.

**Filters.** `level` is the lowest level, one of `V D I W E F` (`W` gives W, E, and F). `tag` and
`mq` are Java regular expressions found in the tag and in the message, case-sensitive (prefix
`(?i)` to ignore case, anchor with `^` and `$`), each at most 200 characters. `q` is text the
message contains, ignoring case. They combine. The cursor moves past the lines they leave out, so
keep the same filters while you follow one cursor. A bad value gets a `400` with `field` set to
the parameter. The filters check the time between lines and stop with a `400` after 5 s; one match
can't be stopped, so a regex that backtracks a lot, such as `(a+)+$`, can hold the request for
long. Keep `tag` and `mq` simple.

**Reader status.** `state.reader` is `running`; `restarting`, for the second after logcat ended
before the tab starts it again; `failed`, after logcat ended four times in a row without a line,
with the reason in `readerError` (the buffer keeps what it has, and `entries` still serves it); or
`stopped`, between `Lustro.stop()` and `start()`.

## Preferences tab operations

Base path `/api/v1/prefs`, when the app registers the Preferences plugin
(`PrefsDebugTab.create(context, ...)`; without it, the routes are an enveloped
`404 Unknown tab: prefs`). The authoritative contract is
`lustro-prefs/src/main/assets/lustro/prefs.openapi.json`, version `1.0`. The CLI has no `prefs`
commands: use `lustro api`.

| Operation | Route | Notes |
| --- | --- | --- |
| List the files | `GET files` | `{ items: [{ name, store, registered, policy, readOnly, encrypted, keyCount, cursor, error? }] }`: the SharedPreferences files and then the Preferences DataStores that the app registered, in its order, then the files that the tab found in `shared_prefs` and `files/datastore`, by name. `store` is `shared_prefs` or `preferences_datastore`. `cursor` is the file's cursor now; compare it with the one you polled with to see that the file changed. `error` says why a file's instance can't read it. |
| Poll a file | `GET entries?store=&file=&cursor=` | Cursor envelope. `reset` or `delta` carries every entry of the file, by key; `unchanged` carries none. A cursor of another file, or from before an app restart, gets a `reset`. |
| Read one value whole | `GET entry?store=&file=&key=` | `{ item }`, after the value policy, never cut. `404` with `field: "key"` when the file has no such key. |
| Write a value | `POST edit` | Body `{ store?, file, key, type, value }`. Returns `{ status: "ok", item }`: the entry as the file now holds it. |
| Remove a key | `POST delete` | Body `{ store?, file, key }`. Returns `{ status: "ok", deleted }`, `false` when the key was not there. |
| Empty a file | `POST clear` | Body `{ store?, file, confirm: true }`; without `confirm: true`, a `400` with `field: "confirm"`. Returns `{ status: "ok", removed }`. |

**Stores.** A SharedPreferences file and a Preferences DataStore can have the same name, so
`store` picks the kind: `shared_prefs`, the default when a request leaves it out, or
`preferences_datastore`. Any other value is a `400` with `field: "store"`. The tab reads and
writes a registered DataStore through the app's own instance. A DataStore that the tab found in
`files/datastore` is read from its file, and it is always read-only.

**An item.** `{ key, type, value?, bytes, masked?, truncated? }`. `type` is `string`, `int`,
`long`, `float`, `double`, `boolean`, `string_set`, `bytes`, or `unknown` (a value that only a
custom SharedPreferences implementation can hold). `double` and `bytes` come only from a
DataStore. `value` is a JSON string, number, boolean, or, for `string_set`, a sorted array of
strings; a float or a double that is not finite is the string `"NaN"`, `"Infinity"`, or
`"-Infinity"`, and `bytes` is padded base64. `bytes` (the field) is the size of the whole value:
a string's UTF-8 bytes, the sum of those of a set's strings, a byte array's length, or 4, 8, 4,
8, and 1 for `int`, `long`, `float`, `double`, and `boolean`.
`masked: true` comes instead of `value` when the policy leaves it out. In `entries`, a value longer
than 4096 characters (for a set, all its strings together) is cut and has `truncated: true`; read
it with `GET entry`.

**Policies.** `policy` is `visible` (every value), `mask_sensitive` (every value but those of keys
that look like they hold a secret, such as `auth_token`, `apiKey`, or `password`, by the network
redaction's key check), or `keys_only` (no value). The keys always show. A discovered file is
`mask_sensitive`. `encrypted` is `true` when the file on disk is encrypted: the app registered an
`EncryptedSharedPreferences`, which shows plain values as its policy lets it, or the file has the
keysets of one and a plain instance reads it, which is always `keys_only` and read-only.

**Writing.** `type` picks the setter: `string`, `int`, `long`, `float`, `boolean`, or
`string_set`, and for a DataStore also `double` and `bytes` (base64; a DataStore before 1.1
can't write it, and answers a `500`). Another type is a `400` with `field: "type"`. For a
number or a boolean, `value` can also be a string, as a text field holds it (`"42"`, `"true"`);
`int` and `long` take whole numbers in their range, `float` a number in a float's range or
`"NaN"`, `"Infinity"`, `"-Infinity"`, `double` the same in a double's range, and `string_set` a
JSON array of strings. A wrong value is a `400` with `field: "value"`. An edit can give a key
another type: the app's getter of the old type then throws `ClassCastException`. A masked value can still be written. All
three mutations return when the file on disk has the change, and move its cursor when they
change it. A read-only file answers
`403` with `code: "read_only"` and `field: "file"`: a file that the app registered with
`readOnly = true`, a discovered file unless the app made them writable, Lustro's own
`lustro_debug`, an encrypted file read plain, and every discovered DataStore. A `500` after a
write means the disk write failed: a SharedPreferences instance's memory holds the change, and a
DataStore keeps what it had.

**What moves a cursor.** The tab's own changes, at once, and the app's changes, through a
listener, while Lustro is started. Before API 30, `clear()` calls no listener, so a clear by the
app shows only with the next change to the file. Another process that writes the file is not seen.
A registered DataStore moves its cursor when its data changes, while Lustro is started, and a
discovered one when the bytes of its file change, on the next poll.
A `file` that names no file is a `404`, and one with `/` or `\` a `400`, both with
`field: "file"`; the tab never creates a file.

## SQLite tab operations

Base path `/api/v1/sqlite`, when the app registers the SQLite plugin
(`SqliteDebugTab.create(databases, discoverFrom)`; without it, the routes are an enveloped
`404 Unknown tab: sqlite`). The authoritative contract is
`lustro-sqlite/src/main/assets/lustro/sqlite.openapi.json`, version `1.0`. The CLI has no
`sqlite` commands: use `lustro api`. Every route only reads.

| Operation | Route | Notes |
| --- | --- | --- |
| List the databases | `GET databases` | `{ items: [{ name, registered, source, writable, path, sizeBytes, schemaVersion, error? }] }`: the databases that the app registered, in its order, then the files that the tab found in the app's `databases` directory, by file name. `source` is `support` (the app's `SupportSQLiteDatabase`, such as Room's), `framework` (the app's `SQLiteDatabase`), or `file` (opened read-only for each request). `schemaVersion` is `PRAGMA user_version`; Room and `SQLiteOpenHelper` set it to the schema's version. `path` and `sizeBytes` are `null` for a database in memory; `sizeBytes` leaves out the `-wal` file. `error` says why a database does not open. |
| List the tables | `GET tables?db=` | `{ database, writable, items: [{ name, type, internal, hasRowId, rowCount, entity?, purpose?, error? }] }`, by name. `type` is `table` or `view`. A view has no `rowCount`: counting runs its query. `internal` flags `sqlite_*`, `android_metadata`, `room_master_table`, and the shadow tables of a full-text or R*Tree index. `entity` and `purpose` are the app's hint. |
| Read a schema | `GET schema?db=&table=` | `{ database, table, type, internal, hasRowId, rowCount, sql, entity?, purpose?, columns: [{ name, type, notNull, defaultValue, primaryKey }], foreignKeys: [{ table, from, to, onUpdate, onDelete }] }`. `primaryKey` is the column's place in the key, from 1, or 0. A key over several columns has them in order in `from` and `to`; a `null` in `to` stands for the other table's primary key. |
| Read rows | `GET rows?db=&table=&limit=&cursor=&filter=&sort=&dir=` | Pagination envelope `{ items, nextCursor }`. 50 rows by default, `limit` 1 to 500. Pass `nextCursor` as `cursor` for the next page; it is `null` on the last one. |

**A row.** `{ rowid?, cells: { <column>: cell } }`, with a cell for each column of `GET schema`,
in its order. `rowid` is a string, so that a JavaScript client keeps every digit; a view and a
table `WITHOUT ROWID` have none. SQLite stores any type in any column, so each cell says its
own: `{ type: "null" }`, `{ type: "integer", value }`, `{ type: "real", value }`,
`{ type: "text", value, truncated?, length? }`, or `{ type: "blob", bytes }`. An integer beyond
2^53 - 1 either way is a string of its digits, and an infinite real the string `"Infinity"` or
`"-Infinity"`. A text longer than 4096 characters is cut, with `truncated: true` and the whole
`length` in characters. A blob is only its size, in any column, also in a `TEXT` one.

**Order, sort, and filter.** Without `sort`, a table's rows come by `rowid`, or by the primary key
for a table `WITHOUT ROWID`, and a view's as SQLite gives them; `dir=desc` turns the order
around. `sort` names a column as `GET schema` does (another case finds it too), or `rowid` when
the table has one and no column has that name; a sort also sorts by the row's key, so rows with
the same value keep their order from page to page. `filter` keeps the rows where a cell, as text,
contains the filter: `%` and `_` are plain characters, case does not matter for ASCII letters,
and a blob never matches. A cursor holds a place in the rows: when the app adds or removes rows
between two pages, the next page can repeat or skip a row.

**What the tab runs.** Only statements that it builds: each `db`, `table`, and `sort` is checked
against the database's schema and quoted, and the filter is a bound parameter. A `file` database
is opened with `OPEN_READONLY` for one request and closed after it. `writable` is the app's
choice for later write routes; no route writes in this version. Each read stops at the request
timeout, and the client then gets a `504`.

## Common workflows

**Mock a 500 for an endpoint**

```bash
TOKEN=...; BASE=http://localhost:8080
curl -s -X POST "$BASE/api/v1/network/rules" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"id":"orders-500","urlPattern":"/v1/orders","method":"GET","statusCode":500,
       "responseHeaders":{"Content-Type":"application/json"},
       "responseBody":"{\"error\":\"boom\"}"}'
```

Reusing the same `id` updates the rule in place (idempotent). Remove it with
`POST rules/delete {"id":"orders-500"}`.

**Capture and inspect**

1. Poll `GET transactions` with no cursor → `reset` snapshot; keep the returned `cursor`.
2. Re-poll with `?cursor=<cursor>`; `unchanged` means no new traffic, `delta`/`reset` means
   re-read `items`.
3. Fetch `GET transactions/<id>` for full headers and bodies (values are already redacted).

A request is listed once its capture is redacted, which happens off the app's call: usually
milliseconds after the call returns, longer for large bodies. Right after making a request, keep
polling rather than reading the list once. `lustro net wait` does this.

The app can also leave requests out with a capture filter, set in its code. Such a request is
never listed, but mock rules and the throttle still apply to it. `state.captureFilter` says
whether the app set one: `null` when it didn't, or `{ description, skipped, failed }`, where
`skipped` counts the requests it left out and `failed` the ones it threw on, which were captured.
Both count since the list was last cleared. So when a request never shows up while capture isn't
paused, check `skipped` and read `description`.

**Watch a WebSocket**

1. `lustro net ws list` (or `GET websockets`) and take the id of the connection.
2. `lustro net ws events <id> --follow`, or poll `GET websockets/<id>/events` with the cursor,
   and append each `delta`.
3. For a message whose preview is cut, `lustro net ws payload <id> <seq>`.

To test how the app handles a refused connection, add a mock rule for the handshake: its URL has
`https` for `wss`, and any status but `101` makes OkHttp fail the socket.

**Send a request**

```bash
lustro senders                                    # the app's senders, the default first
lustro send --url /v1/orders --sender "API (signed in)"
curl -s -X POST "$BASE/api/v1/network/send" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"url":"/v1/orders","sender":"API (signed in)","method":"GET"}'
```

Check `url` in the result before you trust the status: a `401` or a `404` often means that the
request went through a sender with the wrong auth or the wrong base URL. A sent request is captured,
as its own transaction, only when the sender's client carries `lustro.networkInterceptor()`; then
`transactionId` names it, and `GET transactions/{id}` has the response.

**Tail the app's log**

```bash
lustro api GET logs/entries --param level=W --param limit=50 > page.json   # the last 50 warnings and errors
cursor=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["cursor"])' < page.json)
lustro api GET logs/entries --param level=W --param cursor="$cursor"       # only what came since
```

Keep the same filters with a cursor. Poll again while a `delta` is a full page of `limit` lines. A
`reset` means the log was cleared or the app restarted: replace what you have.

**Export the log for a bug report**

```bash
lustro api POST logs/clear                                  # start from an empty log
adb shell input tap 540 1200                                # reproduce the problem
lustro api GET logs/text > repro.txt                        # every line since the clear
lustro api GET logs/text --param tag='^(OkHttp|SyncWorker)$' --param level=I
```

**Read and change a SharedPreferences or DataStore value**

```bash
lustro api GET prefs/files                                   # names, policies, read-only flags
lustro api GET prefs/entries --param file=settings > page.json
cursor=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["cursor"])' < page.json)
lustro api POST prefs/edit --body '{"file":"settings","key":"onboarding_done","type":"boolean","value":false}'
lustro api GET prefs/entries --param file=settings --param cursor="$cursor"   # delta: the file again
lustro api GET prefs/entries --param store=preferences_datastore --param file=app_state
lustro api POST prefs/edit --body '{"store":"preferences_datastore","file":"app_state","key":"zoom","type":"double","value":1.5}'
```

The app reads the new value at its next `get`. Code that read the value before, such as a cached
field, keeps the old one until the app reads it again; restart the app when in doubt
(`adb shell am force-stop <package>`): the value is on disk.

**Find a table and page through its rows**

```bash
lustro api GET sqlite/databases                              # names, sources, schema versions
lustro api GET sqlite/tables --param db=app                  # tables, row counts, hints
lustro api GET sqlite/schema --param db=app --param table=notes
lustro api GET sqlite/rows --param db=app --param table=notes --param sort=created_at --param dir=desc --param limit=20 > page.json
next=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["nextCursor"] or "")' < page.json)
lustro api GET sqlite/rows --param db=app --param table=notes --param sort=created_at --param dir=desc --param limit=20 --param cursor="$next"
lustro api GET sqlite/rows --param db=app --param table=notes --param filter=invoice
```

Keep the same `sort`, `dir`, `filter`, and `limit` from page to page: a cursor is a place in one
order. To read a text cell whole, or a blob's bytes, use `adb shell run-as <package>` with the
database file that `GET databases` names as `path`.

**Atomically sync a rule set** (declarative — the resulting set equals exactly what you post):

```bash
curl -s -X POST "$BASE/api/v1/network/rules/_/sync" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '[{"id":"a","urlPattern":"/a","statusCode":200,"responseBody":"{}"},
       {"id":"b","urlPattern":"/b","statusCode":404,"responseBody":"{}"}]'
```

## Failure modes

Errors use the shared envelope; key statuses:

- **`401 unauthorized`** — missing/invalid token. Send `Authorization: Bearer <token>`.
- **`403 forbidden`** — Origin / `Sec-Fetch-Site` rejected, on any method (a cross-origin,
  non-allowed Origin; only the server's own origin and configured `allowedOrigins` pass). Missing
  Origin/`Sec-Fetch-Site` headers are accepted, so plain CLI clients are unaffected.
- **`400 bad_request`** — invalid input. Also a body sent with a `Transfer-Encoding` or a malformed
  `Content-Length`: chunked request bodies are not supported. Also any request, on any route, whose
  request line and headers exceed 8 KB (`Request headers too large`); its connection then closes.
  Browsers get there with large cookies that other local servers set on the same host.
- **`413 payload_too_large`** — request body exceeds the configured max (1 MB by default; 1 KB for
  `POST /api/v1/_auth`).
- **`503 unavailable`** — the server is at its concurrency + queue limit; back off and retry.
- **`504 timeout`** — a handler (e.g. a slow `send`) exceeded the per-request timeout.
- **`404 not_found`** — unknown route, missing transaction, a body that wasn't kept, or `send` with
  no sender configured. Also a WebSocket connection that the app cleared or evicted, and a message
  with no stored payload. Also every `logs/` route when the app did not register the Logs plugin
  (`Unknown tab: logs`), every `prefs/` route without the Preferences plugin
  (`Unknown tab: prefs`), and every `sqlite/` route without the SQLite plugin
  (`Unknown tab: sqlite`). On `sqlite/` routes, also a `db` that the tab does not list, and a
  `table` that the database does not have, each with its `field`.
- **`403 forbidden` with `code: "read_only"`** on `prefs/edit`, `delete`, or `clear`: the file is
  read-only. `GET prefs/files` says which files are, and the app decides it in
  `PrefsDebugTab.create(...)`.
- **`500` with `code: "open_failed"`** on a `sqlite/` route: the database did not open. The
  message has the reason, such as a file that is not there yet, or the exception of the app's
  function that returns the handle. `GET sqlite/databases` lists such a database with `error`.
  **`500` with `code: "sqlite_error"`**: SQLite refused a statement, such as on a corrupt file or
  a view whose table is gone; the message is SQLite's.
- **No log lines**: check `state.reader` in `GET logs/entries`: `failed` with `readerError` when
  logcat could not run, `stopped` when Lustro is stopped, and `state.paused` when the capture is
  paused. Lines from other processes are never shown: the tab reads the app's own process only.
- **Connection refused / no response** — the app is backgrounded (the server only listens while
  foregrounded) or `adb forward` isn't set up. Re-check the `LustroToken` log line for the live
  endpoint. A connection closed without a response can also mean the server is at its
  open-connection limit (concurrency + queue + 16, 96 by default); close idle connections and retry.
  The CLI reports both cases as `connection_failed`, with a hint.
