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
  `GET /api/v1/_meta` as `_meta.protocolVersion` (currently `1.4`).

## Contents

- **Global JSON Schemas** (this directory): the shared envelope shapes that every
  tab and framework route reuses.
- **Per-tab OpenAPI fragments**: live next to the tab assets at
  `lustro/src/main/assets/lustro/<id>.openapi.json` and are bundled into the AAR.
  They are served at `GET /api/v1/<id>/_schema`.
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
  the HAR export, the wait is `timings.blocked`, and `time` is the sum.

## Status

The concrete schema files (`*.schema.json`), the Network OpenAPI document, and
the golden fixtures (`golden/`) are present: envelopes + Network tab schemas,
and the golden response corpus (CLI contract tests). See
`SCHEMA_INDEX.md` for the full set. The golden fixtures are also vendored into
the `lustro-cli` wheel under `lustro_cli/wire/` so the installed CLI ships its
own contract corpus.
