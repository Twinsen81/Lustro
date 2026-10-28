# Wire protocol v1 — golden fixtures

Realistic recorded responses for the Lustro HTTP wire protocol. They are the
**contract-test corpus**: the `lustro` CLI and any other wire-protocol client
validate against these to prove they parse the exact shapes the server emits,
and the fixtures themselves are validated against the JSON Schemas in the parent
directory (`../*.schema.json`) and the OpenAPI components of the Network tab
(`lustro/src/main/assets/lustro/network.openapi.json`), the Logs tab
(`lustro-logs/src/main/assets/lustro/logs.openapi.json`), the Preferences
tab (`lustro-prefs/src/main/assets/lustro/prefs.openapi.json`), the SQLite
tab (`lustro-sqlite/src/main/assets/lustro/sqlite.openapi.json`), and the
WorkManager tab (`lustro-work/src/main/assets/lustro/work.openapi.json`).

These files are bundled into the `lustro-cli` wheel under
`lustro_cli/wire/golden/`, so the installed CLI ships its own contract corpus
with no source checkout required.

| Fixture | Route / shape | Validated against |
| --- | --- | --- |
| `meta.json` | `GET /api/v1/_meta` | `meta.schema.json` |
| `error-envelope.json` | any failing `/api/v1/*` route | `error-envelope.schema.json` |
| `cursor-reset.json` | `GET network/transactions` first poll (`status: reset`) | `cursor-envelope.schema.json` + OpenAPI `TransactionCursorEnvelope` |
| `cursor-delta.json` | `GET network/transactions` after a change (`status: delta`) | `cursor-envelope.schema.json` + OpenAPI `TransactionCursorEnvelope` |
| `cursor-unchanged.json` | `GET network/transactions` no change (`status: unchanged`, items omitted) | `cursor-envelope.schema.json` + OpenAPI `TransactionCursorEnvelope` |
| `transaction.json` | `GET network/transactions/{id}` detail | OpenAPI `Transaction` |
| `transaction-image.json` | `GET network/transactions/{id}` detail of an image response | OpenAPI `Transaction` |
| `rules-list.json` | `GET network/rules` | OpenAPI `listMockRules` 200 (`items: [MockRule]`) |
| `senders-list.json` | `GET network/senders` | OpenAPI `listSenders` 200 |
| `send-result.json` | `POST network/send` synchronous result | OpenAPI `sendRequest` 200 |
| `export-har.json` | `GET network/transactions/_/export` HAR document | OpenAPI `HarDocument` |
| `export-har-websocket.json` | `GET network/transactions/_/export` of a WebSocket's handshake | OpenAPI `HarDocument` |
| `websockets-reset.json` | `GET network/websockets` first poll (`status: reset`) | `cursor-envelope.schema.json` + OpenAPI `WebSocketCursorEnvelope` |
| `websocket.json` | `GET network/websockets/{id}` detail | OpenAPI `WebSocketConnection` |
| `stream-reset.json` | `GET network/websockets/{id}/events` first poll (`status: reset`) | `stream-envelope.schema.json` + OpenAPI `WebSocketEventStreamEnvelope` |
| `stream-delta.json` | `GET network/websockets/{id}/events` after new events (`status: delta`) | `stream-envelope.schema.json` + OpenAPI `WebSocketEventStreamEnvelope` |
| `stream-unchanged.json` | `GET network/websockets/{id}/events` no new event (`status: unchanged`, items omitted) | `stream-envelope.schema.json` + OpenAPI `WebSocketEventStreamEnvelope` |
| `logs-reset.json` | `GET logs/entries` first poll (`status: reset`) | `stream-envelope.schema.json` + Logs OpenAPI `LogStreamEnvelope` |
| `logs-delta.json` | `GET logs/entries` after new lines (`status: delta`) | `stream-envelope.schema.json` + Logs OpenAPI `LogStreamEnvelope` |
| `logs-unchanged.json` | `GET logs/entries` no new line, capture paused (`status: unchanged`) | `stream-envelope.schema.json` + Logs OpenAPI `LogStreamEnvelope` |
| `prefs-files.json` | `GET prefs/files` | Preferences OpenAPI `listPrefsFiles` 200 |
| `prefs-reset.json` | `GET prefs/entries?file=settings` first poll (`status: reset`) | `cursor-envelope.schema.json` + Preferences OpenAPI `PrefsCursorEnvelope` |
| `prefs-delta.json` | `GET prefs/entries?file=settings` after the app changed a value (`status: delta`) | `cursor-envelope.schema.json` + Preferences OpenAPI `PrefsCursorEnvelope` |
| `prefs-unchanged.json` | `GET prefs/entries?file=settings` no change (`status: unchanged`) | `cursor-envelope.schema.json` + Preferences OpenAPI `PrefsCursorEnvelope` |
| `sqlite-databases.json` | `GET sqlite/databases` | SQLite OpenAPI `listSqliteDatabases` 200 |
| `sqlite-tables.json` | `GET sqlite/tables?db=app` | SQLite OpenAPI `listSqliteTables` 200 |
| `sqlite-schema.json` | `GET sqlite/schema?db=app&table=notes` | SQLite OpenAPI `SqliteSchema` |
| `sqlite-rows.json` | `GET sqlite/rows?db=app&table=notes&limit=3&dir=desc` | `pagination.schema.json` + SQLite OpenAPI `SqliteRowsPage` |
| `sqlite-query.json` | `POST sqlite/query` with a `SELECT` on `app` | SQLite OpenAPI `SqliteQueryResult` |
| `sqlite-exec.json` | `POST sqlite/exec` with an `UPDATE` on `app` | SQLite OpenAPI `SqliteExecResult` |
| `work-items.json` | `GET work/items` first poll (`status: reset`) | `cursor-envelope.schema.json` + WorkManager OpenAPI `WorkCursorEnvelope` |
| `work-item.json` | `GET work/items/{id}` | WorkManager OpenAPI `getWork` 200 |
| `work-events.json` | `GET work/events?limit=12` first poll (`status: reset`) | `stream-envelope.schema.json` + WorkManager OpenAPI `WorkStreamEnvelope` |
| `work-metrics.json` | `GET work/metrics?class=StepWorker` | WorkManager OpenAPI `listWorkMetrics` 200 |

## Notes on the shapes

- **Cursor envelope.** `cursor` is opaque; clients echo it on the next poll and
  treat any unknown `status` as `reset`. The Network tab extends the generic
  envelope with a top-level `state` object (`paused`, `overwriteMode`,
  `throttleDelayMs`, `captureFilter`). `cursor-unchanged.json` deliberately
  omits `items`, and models an app that set a capture filter; the other two
  model one that didn't (`captureFilter: null`).
- **Redaction.** Captured values are redacted at capture time; the
  `transaction.json` `Authorization` request header shows `<redacted>` to model
  this — clients must never assume bodies/headers are raw.
- **Send result.** `transactionId` names the transaction that the Lustro
  interceptor recorded for the request. It is `null` when the sender's client
  has no Lustro interceptor, or the request wasn't captured. `url` is where the
  request went, after the relative `/me` was appended to the base URL of the
  `API` sender in `senders-list.json`.
- **Times.** `startedAt` and `completedAt` are epoch milliseconds, and
  `timestamp` is `startedAt` as the device's local time; the fixtures model a
  device in UTC. The mocked `tx_77e2c014` has no `protocol`, because a mock
  never reaches the network. It was also held 1,000 ms by the global throttle
  (`throttledMs`), which `durationMs` leaves out, so `completedAt` comes 1,041 ms
  after `startedAt`; in `export-har.json` that wait is `timings.blocked`.
- **Binary bodies.** `transaction-image.json` models a PNG response, which the
  capture keeps as bytes: `responseBodyBinary` is `true` and `responseBody` is
  `null`. The bytes themselves come from
  `GET network/transactions/{id}/body/response`, which is not JSON and so has
  no fixture.

- **HAR export.** `export-har.json` is the export of five transactions: a POST
  with a JSON body, the mocked `tx_77e2c014`, a GIF kept as bytes (base64 with
  `encoding`) that platform `HttpURLConnection` capture recorded
  (`_lustro.source: platform`), a response cut at the capture cap
  (`_lustro.responseBodyTruncated`), and a failed request (status `0`, with
  the error in `response._error` and `_lustro.error`). A unit test in
  `:lustro` checks that the export writes exactly this document for those
  transactions.

- **WebSockets.** `websockets-reset.json` lists three connections, newest
  first: one that is open, with its handshake captured as `tx_a81c3f09`; one
  that the app closed, whose log passed its limit (`evictedEvents`); and one
  whose upgrade the server refused, on a client without the interceptor, so it
  has no `transactionId`. `websocket.json` is the detail of the first.
  `stream-reset.json` is the log of the first: an `open`, then messages in both
  directions, one of them binary and one cut at the capture cap.
  `stream-delta.json` is a later poll of a longer log: `dropped` says that the
  log evicted three events before this client got them, and the items are a
  close the app started, a message `send()` refused after it (`enqueued: false`),
  the server's close frame, and the end. An event has only the keys of its
  kind. A message's payload comes from
  `GET network/websockets/{id}/events/{seq}/payload`, which is not JSON and so
  has no fixture. `export-har-websocket.json` is the HAR export of the same
  socket's handshake, with its messages in `_webSocketMessages`; a unit test in
  `:lustro` checks that the export writes exactly this document.

- **Logs.** `logs-reset.json` is the first poll of an app's log: six lines,
  the last of them a `tick` that came twice in a row, so it carries `count`
  and `lastAt`. `logs-delta.json` is the next poll, after two more ticks and
  two new lines: the tick comes again with its `seq` 5 and `count` 4, which
  replaces the row the client has, and the new lines follow. The `seq` of a
  line is where it came first, so the seqs have gaps. `logs-unchanged.json`
  is a poll after `POST pause`: no new line, and `state.paused` is `true`. The
  fixtures model a device in UTC. A unit test in `:lustro-logs` checks that
  the route writes these fixtures, except the cursors, which are random.

- **Preferences.** `prefs-files.json` lists four files: `settings` and
  `session`, which the app registered, the second keys-only and read-only;
  `sdk_cache`, which the tab found on disk, so it is masked and read-only; and
  `secure_store`, a file with the keysets of `EncryptedSharedPreferences` that
  a plain instance reads, so it is encrypted, keys-only, and read-only.
  `prefs-reset.json` is the first poll of `settings`, a `mask_sensitive` file:
  every entry, by key, one of each type, with `auth_token` masked.
  `prefs-delta.json` is the next poll, after the app wrote `launch_count`: the
  whole file again. `prefs-unchanged.json` is a poll with no change since. The
  cursor of `settings` in the file list is the cursor of the first poll. A unit
  test in `:lustro-prefs` checks that the routes write these fixtures, except
  the cursors, which are random.

- **SQLite.** `sqlite-databases.json` lists `app`, a Room-like database that the
  app registered through its open handle (`source: support`) and made
  writable; `catalog`, a file that the app registered; and `sdk_cache.db`, a
  file that discovery found. `sqlite-tables.json` lists the tables and views of
  `app`, with the internal ones flagged and a view without a row count.
  `sqlite-schema.json` is the schema of `notes`. `sqlite-rows.json` is the first
  page of `notes` in descending order, with 3 rows and a `nextCursor`: a rowid
  beyond 2^53 - 1, which is a string in a cell too, a blob in the `TEXT` column
  `body`, a text cut at 4096 characters with its length, and a blob read by its
  size. `sqlite-query.json` is the result of
  `SELECT id, title, attachment, score, length(body) AS body_chars FROM notes ORDER BY id DESC`:
  each row an array of cells in the order of `columns`, a blob by its size, and
  `truncated` false. `sqlite-exec.json` is the result of
  `UPDATE notes SET score = 5 WHERE folder_id = 1`, which changed 2 rows. The
  paths and the sizes model a device. A unit test in `:lustro-sqlite` checks
  that the routes write these fixtures, except the paths and the sizes.

- **WorkManager.** The app registered the unique name `sync` and passed a
  work-analytics repository. `work-items.json` lists four work requests: `sync`,
  which succeeded with an output that has a long beyond 2^53 - 1 (a string), a
  double, and a string array; `cleanup`, periodic work that waits for an
  unmetered network and a battery that is not low, with its next run;
  an upload that asked for a retry, `ENQUEUED` with its next run; and a worker
  that threw, `FAILED`. `work-item.json` is `sync` with its four events and its
  period in work-analytics. `work-events.json` is the last 12 events: the
  `finished` events with `success` and `retry`, and an `exception` event with a
  stack trace. `work-metrics.json` is the history of the `StepWorker` class: the
  succeeded period of `sync` and the pending one of `cleanup`. A unit test in
  `:lustro-work` checks that the routes write these fixtures, except the
  cursors, the stack trace, and the times of work-analytics, which come from
  the clock.

When the server's emitted shapes change, update these fixtures in the same
change that bumps the protocol version, and keep the schema validation green.
