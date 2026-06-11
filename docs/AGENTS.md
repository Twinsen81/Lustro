# Driving Lustro from agents and automation

Lustro is a browser-based Android debug toolkit, but every tab is also a JSON API under
`/api/v1/`, so AI agents, scripts, and the `lustro` CLI can drive it directly instead of scraping
HTML. This document is the operational guide for non-browser clients. The HTTP wire protocol —
not the Kotlin API — is the stable contract; it is versioned and SemVer-governed under
[`wire-protocol/v1/`](wire-protocol/v1/).

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

This is the single source of truth for host, port, and token. Parse it:

```bash
adb logcat -s LustroToken | sed -n 's/.*endpoint=\(\S*\) token=\(\S*\).*/\1 \2/p'
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

- From a desktop, forward the device port first:

  ```bash
  adb forward tcp:8080 tcp:8080
  ```

  Then talk to `http://localhost:8080`.

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
  `reset`** and re-sync the full list. Every server-side mutation advances the cursor.

## Network tab operations

Base path `/api/v1/network`. The authoritative contract is `network.openapi.json`; the table
below summarizes it. All routes are token-authenticated and use the shared error envelope.

| Operation | Route | Notes |
| --- | --- | --- |
| Poll transactions | `GET transactions?cursor=&search=` | Cursor envelope. First poll (no/invalid cursor) → `reset` with the full list; cursor unchanged → `unchanged` (items omitted); after a change → `delta`. `search` filters case-insensitively over URL, method, and bodies. Carries a top-level `state` `{ paused, overwriteMode, throttleDelayMs }`. |
| Transaction detail | `GET transactions/{id}` | Full object (headers + bodies, with truncation flags). Enveloped `404` if missing. |
| Clear | `POST clear` | Clears the captured list; mock rules and settings are preserved. |
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

**Send request semantics.** Body `{ url, method?="GET", headers?, body? }`. Blocks until the
sender returns, within the per-request timeout. Relative URLs resolve against the app server base
the developer configured (rejected if unset). The response is `{ transactionId?, statusCode?,
ok, error? }` (`transactionId` may be `null`). Requests to the debug server's **own** bind
host:port are rejected. **Send is only available when a `NetworkSender` is configured** — if not,
the route returns an enveloped `404` and the panel is hidden.

**Mutations advance the cursor.** Capture, mock changes, pause, throttle, overwrite, and send all
bump the transactions cursor, so a poller observing `transactions` sees the effect.

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

**Replay a request**

```bash
curl -s -X POST "$BASE/api/v1/network/send" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"url":"https://api.example.com/v1/orders","method":"GET"}'
```

The replayed request also flows through the interceptor and appears as its own transaction.

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
- **`403 forbidden`** — Origin / `Sec-Fetch-Site` rejected (a cross-origin, non-allowed Origin;
  only the server's own origin and configured `allowedOrigins` pass). Missing Origin/`Sec-Fetch-Site`
  headers are accepted, so plain CLI clients are unaffected.
- **`413 payload_too_large`** — request body exceeds the configured max (1 MB by default).
- **`503 unavailable`** — the server is at its concurrency + queue limit; back off and retry.
- **`504 timeout`** — a handler (e.g. a slow `send`) exceeded the per-request timeout.
- **`404 not_found`** — unknown route, missing transaction, or `send` with no sender configured.
- **Connection refused / no response** — the app is backgrounded (the server only listens while
  foregrounded) or `adb forward` isn't set up. Re-check the `LustroToken` log line for the live
  endpoint.

## The `lustro` CLI

A Python `lustro` CLI lives at `lustro-cli/` and ships in a later phase. It wraps these
endpoints (token discovery from the `LustroToken` log line, `lustro open`, etc.). The CLI is a
convenience over the HTTP API — **the wire protocol described here is the stable contract**, so
agents can target the endpoints directly without waiting for the CLI.
