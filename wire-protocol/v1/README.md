# Lustro wire protocol — v1

This directory holds the **versioned, SemVer-governed** artifacts that define
Lustro's HTTP wire protocol. Non-Kotlin tooling (the `lustro` CLI, agents,
scripts) talks only to the HTTP API and is governed by these schemas — making
the protocol a stable public contract independent of the library's Kotlin API.

## Versioning

- The **major** version lives in the route path: all routes are under `/api/v1/`.
  A future incompatible revision becomes `/api/v2/`, and the old major stays
  alive for at least one major release.
- The **minor** protocol version is reported at runtime in
  `GET /api/v1/_meta` as `_meta.protocolVersion` (currently `1.10`).

## Contents

- **Global JSON Schemas** (this directory): the shared envelope shapes that every
  tab and framework route reuses.
- **Per-tab OpenAPI fragments**: live next to the tab assets at
  `lustro/src/main/assets/lustro/<id>.openapi.json` and are bundled into the AAR.
  They are served at `GET /api/v1/<id>/_schema`. A plugin tab's document lives in
  its own module, such as `lustro-logs/src/main/assets/lustro/logs.openapi.json`,
  and has a version of its own in `info.version` (see "Plugin tabs" below).
- **Golden response fixtures**: published alongside the schemas for contract
  tests between the server and the CLI.

## Shared envelope shapes

| Shape | Used by | JSON |
| --- | --- | --- |
| Error envelope | every `/api/v1/*` error | `{ error, message, code, field?, hint? }` |
| List pagination | list endpoints | `{ items: [...], nextCursor: "<opaque>" \| null }` |
| Live polling cursor | observable endpoints | `{ cursor, status: "delta" \| "unchanged" \| "reset", items? }` |
| Live polling stream | endpoints whose list only grows at its end | `{ cursor, status: "delta" \| "unchanged" \| "reset", items?, dropped? }` |
| Framework metadata | `GET /api/v1/_meta` | `{ libraryVersion, protocolVersion, tabs: [...] }` |

Cursor tokens are opaque and advance when the route's list changes; clients
treat unknown `status` values as `reset`. A cursor from before an app restart
gets a `reset`.

The two polling envelopes differ in what `delta` carries. In the cursor
envelope it is the whole current list, which fits a list whose items change. In
the stream envelope it is only the entries after the cursor, oldest first, which
fits a log: the client appends them. A stream `reset` carries the last entries
of the list, and `dropped` counts the entries the list evicted before the
client got them.

## Minor revisions

- **1.1** — A cursor advances only when its route's list changes; in 1.0, any
  server-side mutation advanced it. For the Network tab, pause, overwrite mode,
  throttle, and mock-rule changes no longer re-send the transaction list; the
  `state` object reports them on every poll response. Cursors issued before an
  app restart now get a `reset`.
- **1.2** — Network transactions, in the list and the detail alike, carry
  `requestContentType` and `responseContentType` (the media type as captured),
  `protocol` (e.g. `h2`), and `startedAt` and `completedAt` in milliseconds
  since the Unix epoch. `timestamp` keeps its meaning, a display time in the
  device's zone. Every new field is `null` when it isn't known, except
  `startedAt`, which is always set. `completedAt` is when the capture recorded
  the outcome, after reading the body up to the capture cap, so it can differ
  from `startedAt` plus `durationMs`. The detail also carries
  `requestBodyBinary` and `responseBodyBinary`, `true` when that body was kept
  as bytes (an image), and the new route
  `GET network/transactions/{id}/body/{request|response}` returns a body as it
  is stored. The poll `state` gains `captureFilter`: `null` when the app set no
  capture filter, or its `description` and how many requests it `skipped` and
  `failed` on, counted since the list was last cleared. The new route
  `GET network/transactions/_/export?format=har&ids=...` returns the
  transactions as a HAR 1.2 document, every one when `ids` is left out, with
  the transaction id and the fields HAR has no place for in `_lustro`.

- **1.3** - WebSocket connections and their messages, for the sockets an app
  creates with the factory from `Lustro.webSocketFactory`. The new route
  `GET network/websockets` lists the connections in a cursor envelope, and
  `GET network/websockets/{id}` adds the handshake headers of one. The new
  route `GET network/websockets/{id}/events` returns a connection's log, its
  messages and lifecycle events in order, in the new stream envelope, and
  `GET network/websockets/{id}/events/{seq}/payload` returns one message's
  payload as it is stored. Every transaction carries `webSocketId`: the id of
  the connection when the transaction is the handshake of a listed socket, and
  `null` otherwise. `POST network/clear` clears the connections too, and pause
  stops the capture of messages. In the HAR export, the entry of a handshake
  carries the socket's messages in `_webSocketMessages` and the connection in
  `_lustro.webSocket`, and its `_resourceType` is `websocket`.

- **1.4** - Every transaction carries `throttledMs`: how long the global
  throttle held the request before it was sent, or `null` when it wasn't
  throttled. A throttled request is listed from when the app made the call,
  so `startedAt` is the call time, and `durationMs` leaves the wait out. In
  the HAR export, the wait is `timings.blocked`, and `time` is the sum. A
  multipart request body, which was `null`, is stored as multipart text: each
  part's headers and the value of a text part, and a line with the type and
  size of any other part.

- **1.5** - Every transaction carries `finalUrl`: the URL the response came
  from when it isn't `url`, as after a redirect the client followed, or
  `null`. The detail carries `priorResponses`, the redirects and
  authentication challenges OkHttp answered with another request before the
  final response, oldest first, each with its `url` and `statusCode`.
  Platform `HttpURLConnection` capture reports `finalUrl` and no prior
  responses, which the platform doesn't show. The HAR export puts both in
  `_lustro`. A response whose body the app reads after capture, such as a
  download, is in flight (`responseComplete: false` with a `statusCode`)
  until the app reads to the end of the body or closes it, and `durationMs`
  then covers the whole body. A body that fails while the app reads it sets
  `error` and keeps `statusCode`.

- **1.6** - Every transaction carries `source`, the adapter that captured
  it: `okhttp` for Lustro's OkHttp interceptor, where mock rules and the
  throttle apply; `platform` for platform `HttpURLConnection` capture, which
  only records; or `app` for a request that the app's own adapter reports
  through `Lustro.networkCaptureSink()`, which a mock rule answers only if
  the adapter asks for it. The HAR export puts it in `_lustro`. Platform
  capture also reports `protocol`, from the `X-Android-Selected-Protocol`
  header that the platform adds, and leaves the platform's four `X-Android-*`
  headers out of `responseHeaders`.

- **1.7** - The app can register several senders, each an OkHttp client
  with a name and an optional base URL. The new route `GET network/senders`
  lists them in order, and `POST network/send` takes `sender`, the name of
  one; without it, the first one sends. A relative `url` is appended to the
  base URL of that sender, and is rejected when it has none. The send result
  gains `sender` and `url`, where the request went, and its `transactionId`
  now names the transaction that the Lustro interceptor recorded for the
  request, when the sender's client carries the interceptor; it was always
  `null` before.

- **1.8** - The `search` of `GET network/transactions` also looks at the
  request and response headers, each as the line `Name: value` as stored
  after redaction. The new parameter `regex=true` makes each word of
  `search`, up to a space, a Java regular expression found ignoring case,
  for the transactions and for `GET network/websockets/{id}/events`: every
  word must be found, each in any field, and a word that starts with `-`
  must be found in none. Without it, `search` is text, as before. A regex
  that doesn't compile, or a search that takes longer than 5 s, gets a `400`
  with `field: search`, and a `regex` other than `true` or `false` a `400`
  with `field: regex`.

- **1.9** - Each Network transaction carries `mockRuleId`, the mock rule
  that answered it, as recorded then, and `activeMockRuleId`, the rule that
  answers the next request with that method and URL, which the app works out
  when it lists the transaction, so it follows the rules as they change. The
  detail also carries `matchingMockRuleIds`, every rule whose pattern and
  method match, enabled or not, in the order they are tried. A change to the
  rules now advances the transactions cursor; a hit on a rule doesn't.
  `state` gains `mockRules`, with the number of `enabled` rules and a
  `revision` that changes with the rules.

- **1.10** - Each Network transaction carries `replacedCount`: how many
  earlier copies of the request overwrite mode removed when the app sent it,
  each counted with the copies it had replaced in turn. It is `0` when the
  transaction replaced none.

## Plugin tabs

A plugin tab ships in its own artifact pair and versions its routes in its own
OpenAPI document's `info.version`; the core protocol version above changes only
with the core wire.

- **Logs `1.0`** (`:lustro-logs`) - the app's own logcat under `/api/v1/logs/`.
  `GET entries` polls it in the stream envelope, with the filters `level`,
  `tag`, `q`, and `mq`, and `state` carries the reader's status and the
  `paused` and `compact` flags. A repeated line comes again in a later poll
  with the same `seq` and a larger `count`, and replaces the earlier copy.
  `GET text` exports the held lines as logcat's threadtime text. `POST clear`
  returns the cursor of the empty log; `POST pause` and `POST compact` set or
  toggle their flags.
- **Preferences `1.0`** (`:lustro-prefs`) - the app's SharedPreferences files
  under `/api/v1/prefs/`. `GET files` lists them with their value policies,
  read-only flags, and cursors. `GET entries?file=` polls one file in the
  cursor envelope, each entry with its `type`, its `value` unless the policy
  masks it, and its size in `bytes`; a value is cut at 4096 characters, with
  `truncated`, and `GET entry?file=&key=` returns it whole. Each file's cursor
  has an epoch of its own, so a cursor of one file is foreign to another.
  `POST edit`, `POST delete`, and `POST clear` change a file and answer `403`
  with the code `read_only` for a read-only one.
- **SQLite `1.0`** (`:lustro-sqlite`) - the app's SQLite and Room databases
  under `/api/v1/sqlite/`. `GET databases` lists them with their
  source, file, size, and `schemaVersion`; `GET tables?db=` lists the tables
  and views, and flags the internal ones; `GET schema?db=&table=` gives the
  columns, foreign keys, and row count. `GET rows?db=&table=` returns a page in
  the pagination envelope, with `limit`, `filter`, `sort`, and `dir`; each cell
  is `{ type, ... }` by its own storage class, a text is cut at 4096
  characters with its `length`, and a blob is only its `bytes`. `POST query`
  runs a client's statement on a connection that SQLite opens read-only, and
  returns `{ columns, rows, truncated }`. `POST exec`, `POST edit`, and
  `POST delete` write through the app's handle, only to a `writable` database,
  and answer `403` with the code `read_only` for any other. Both `query` and
  `exec` refuse transaction statements, `ATTACH`, `DETACH`, and `VACUUM` with
  the code `statement_refused`, and `exec` also every `PRAGMA`.

## Status

The concrete schema files (`*.schema.json`), the Network, Logs, Preferences, and
SQLite OpenAPI documents, and the golden fixtures (`golden/`) are present: envelopes + tab
schemas, and the golden response corpus (CLI contract tests). See
`SCHEMA_INDEX.md` for the full set. The golden fixtures are also vendored into
the `lustro-cli` wheel under `lustro_cli/wire/` so the installed CLI ships its
own contract corpus.
