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
  never reaches the network.
- **Binary bodies.** `transaction-image.json` models a PNG response, which the
  capture keeps as bytes: `responseBodyBinary` is `true` and `responseBody` is
  `null`. The bytes themselves come from
  `GET network/transactions/{id}/body/response`, which is not JSON and so has
  no fixture.

When the server's emitted shapes change, update these fixtures in the same
change that bumps the protocol version, and keep the schema validation green.
