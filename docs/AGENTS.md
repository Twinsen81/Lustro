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
  throttle, replay). Its contract is `lustro/src/main/assets/lustro/network.openapi.json`.

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
```

Each row of `net list` starts with a short transaction id: the first 8 characters of the id, or more
when two listed ids start with the same 8. `net get` and `net body` take the short id, the full id,
or any start of an id that only one transaction has. When more than one id starts with it, they
print the matches and exit 2; when no id does, they exit 1. JSON output has the full id, which the
wire routes in the curl examples need. The filters `--url`, `--method`, `--status` (a code such as
`404`, or a class such as `5xx`), and `--errors` combine with each other and with `--search`, which
the server runs over the URL, the method, and the bodies. Global flags, such as `--json` and
`--port`, go before or after the command.

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

`lustro net poll` prints transactions as they arrive. When a request that was in flight finishes
or fails, it prints its row again, marked `[update]`; with `--json`, the later line for an id
replaces the earlier one.

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

This is the single source of truth for host, port, and token. Lustro logs it again after each
bind, so the last line is the current one. `-d` prints the log and exits instead of waiting for
new lines:

```bash
adb logcat -d -s LustroToken | tail -1 | sed -n 's/.*endpoint=\([^ ]*\) token=\([^ ]*\).*/\1 \2/p'
```

Conventions a client should follow:
- Honor a `LUSTRO_TOKEN` environment variable when present, falling back to the parsed log line.
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

## Network tab operations

Base path `/api/v1/network`. The authoritative contract is `network.openapi.json`; the table
below summarizes it. All routes are token-authenticated and use the shared error envelope.

| Operation | Route | Notes |
| --- | --- | --- |
| Poll transactions | `GET transactions?cursor=&search=` | Cursor envelope. First poll (no/invalid cursor) → `reset` with the full list; cursor unchanged → `unchanged` (items omitted); after a change → `delta`. `search` filters case-insensitively over URL, method, and bodies. Carries a top-level `state` `{ paused, overwriteMode, throttleDelayMs, captureFilter }`. |
| Transaction detail | `GET transactions/{id}` | Full object (headers + bodies, with truncation flags). Enveloped `404` if missing. |
| Transaction body | `GET transactions/{id}/body/{request\|response}` | One body as it is stored: the bytes of an image, or the redacted text as UTF-8. Not JSON. Enveloped `404` when no body was kept. See below. |
| Clear | `POST clear` | Clears the captured list and starts the capture filter's counts again; mock rules and settings are preserved. |
| List rules | `GET rules` | `{ items: [MockRule...] }`. |
| Add / upsert rule | `POST rules` | Body `MockRuleInput` (`urlPattern` required). Supplying a stable `id` makes the write **idempotent** (upsert by id); omitting it generates one. Returns `{ status: "ok", id }`. |
| Sync rules | `POST rules/_/sync` | **Atomic** full replacement: posts an array; the resulting set exactly equals it, with no empty window observed by the interceptor. Returns `{ status: "ok", count }`. |
| Delete rule | `POST rules/delete` | Body `{ id }`. |
| Toggle rule | `POST rules/toggle` | Body `{ id }`; flips `enabled`. |
| Pause capture | `POST pause` | Toggles capture-only pause. While paused, mocks and throttle **still apply**; only recording into the list stops. Returns `{ status: "ok", paused }`. |
| Overwrite mode | `POST overwrite-mode` | Toggles overwrite mode (a new request evicts earlier **completed** transactions with the same method + URL path; in-flight ones are never evicted). Returns `{ status: "ok", overwriteMode }`. |
| Throttle | `POST throttle` | Body `{ delayMs }` (≥ 0); a global pre-request sleep applied to mocked and real requests alike. Returns `{ status: "ok", delayMs }`. |
| Send request | `POST send` | **Synchronous** dispatch through the configured `NetworkSender`. See below. |

**Mock rule semantics.** `urlPattern` is a substring match, or a regular expression when prefixed
with `regex:`. `method` is `null` to match any method. `hitCount` is a runtime-only counter (not
persisted).

**Rules are validated on the way in.** `statusCode` must be within 100–599, `responseHeaders` must
be header names and values OkHttp accepts, and a `Content-Type` among them must parse as a media
type — the interceptor builds a real response from the rule inside the app's own call. A rule that
fails any of these gets an enveloped `400` whose `field` names what to fix (`urlPattern`, `id`,
`statusCode`, or `responseHeaders`); for `rules/_/sync` the message also carries the array index,
and the whole batch is rejected. `POST rules` replaces a rule with the same `id` wholesale, so send
`enabled` and `responseHeaders` when editing one or they fall back to `true` and empty.

**Send request semantics.** Body `{ url, method?="GET", headers?, body? }`. Blocks until the
sender returns, within the per-request timeout. Relative URLs resolve against the app server base
the developer configured (rejected if unset). The response is `{ transactionId?, statusCode?,
ok, error? }`; `transactionId` is currently **always `null`** because the send path does not
correlate its call with a capture. The response body is never returned, and the
sender reads at most `maxBodyCaptureBytes` of it, so large or endless responses are safe to send.
A send that outlives the per-request timeout gets the enveloped `504`, and its call is cancelled.
Requests to the debug server's **own** bind host:port are rejected. **Send is only available when
a `NetworkSender` is configured** — if not, the route returns an enveloped `404` and the panel is
hidden.

**What advances the cursor.** Only changes to the captured list advance the transactions cursor: a
new request, its response or streaming progress, a failure, an eviction, or `clear`. Pause,
overwrite mode, throttle, mock-rule changes, and the capture filter's counts don't. Every poll
response carries the current `state`, `unchanged` ones included, and `GET rules` returns the
rules with their hit counts.

**Times, protocol, and content types.** Every transaction, in the list and in the detail, has
`startedAt` and `completedAt` in milliseconds since the Unix epoch. Sort and correlate by those,
with logcat or a server log: `timestamp` is an `HH:mm:ss.SSS` display time in the device's zone.
`completedAt` is `null` while the request is in flight. It is when the capture recorded the
outcome, after reading the response body up to the capture cap, so it can differ from `startedAt`
plus `durationMs`; take a request's duration from `durationMs`. `protocol` (`http/1.1`, `h2`, `h3`) is
`null` before a response, for a mocked one, and for platform `HttpURLConnection` capture.
`requestContentType` and `responseContentType` give the media type as captured, so a client can
tell JSON from an image without reading the headers. These fields arrived in protocol 1.2; a 1.1
server leaves them out.

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

**Compressed bodies.** A body sent or received with `Content-Encoding: gzip`, `x-gzip`, or
`deflate` is stored inflated: `requestBody` and `responseBody` hold the text, and
`...BodyTruncated` says whether the inflated body passed the capture cap. The headers still name
the encoding, and `requestBodyBytes` and `responseBodyBytes` are the size on the wire, before
inflating, so drop `Content-Encoding` when you send a stored body again. A body in another
encoding, such as `br`, is not stored: its body is `null`, and its size is kept. Event streams are
stored as they arrive, without decoding.

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

**Replay a request**

```bash
curl -s -X POST "$BASE/api/v1/network/send" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"url":"https://api.example.com/v1/orders","method":"GET"}'
```

A replay is captured, as its own transaction, only when the client the developer passed as
`senderClient` carries `lustro.networkInterceptor()`. In the README's quick-start wiring it does
not: the sender client is built before the interceptor exists, so a replay runs but never reaches
the traffic list. When it is captured, find it by polling `GET transactions`; the send response's
`transactionId` does not point at it.

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
  no sender configured.
- **Connection refused / no response** — the app is backgrounded (the server only listens while
  foregrounded) or `adb forward` isn't set up. Re-check the `LustroToken` log line for the live
  endpoint. A connection closed without a response can also mean the server is at its
  open-connection limit (concurrency + queue + 16, 96 by default); close idle connections and retry.
  The CLI reports both cases as `connection_failed`, with a hint.
