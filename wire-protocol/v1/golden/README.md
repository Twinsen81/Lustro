# Wire protocol v1 — golden fixtures

Realistic recorded responses for the Lustro HTTP wire protocol. They are the
**contract-test corpus**: the `lustro` CLI and any other wire-protocol client
validate against these to prove they parse the exact shapes the server emits,
and the fixtures themselves are validated against the JSON Schemas in the parent
directory (`../*.schema.json`) and the Network tab's OpenAPI components
(`lustro/src/main/assets/lustro/network.openapi.json`).

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
| `send-result.json` | `POST network/send` synchronous result | OpenAPI `sendRequest` 200 |
| `export-har.json` | `GET network/transactions/_/export` HAR document | OpenAPI `HarDocument` |
| `export-har-websocket.json` | `GET network/transactions/_/export` of a WebSocket's handshake | OpenAPI `HarDocument` |
| `websockets-reset.json` | `GET network/websockets` first poll (`status: reset`) | `cursor-envelope.schema.json` + OpenAPI `WebSocketCursorEnvelope` |
| `websocket.json` | `GET network/websockets/{id}` detail | OpenAPI `WebSocketConnection` |
| `stream-reset.json` | `GET network/websockets/{id}/events` first poll (`status: reset`) | `stream-envelope.schema.json` + OpenAPI `WebSocketEventStreamEnvelope` |
| `stream-delta.json` | `GET network/websockets/{id}/events` after new events (`status: delta`) | `stream-envelope.schema.json` + OpenAPI `WebSocketEventStreamEnvelope` |
| `stream-unchanged.json` | `GET network/websockets/{id}/events` no new event (`status: unchanged`, items omitted) | `stream-envelope.schema.json` + OpenAPI `WebSocketEventStreamEnvelope` |

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
- **Send result.** `transactionId` may be `null` when the runtime cannot
  correlate the dispatched request to a captured transaction.
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

When the server's emitted shapes change, update these fixtures in the same
change that bumps the protocol version, and keep the schema validation green.
