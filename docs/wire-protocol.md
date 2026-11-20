# OpenAPI and wire protocol

Every tab is a JSON API under `/api/v1/`. Framework routes:

- `GET /api/v1/_meta`: the library version, the protocol version, and the tabs that have an
    OpenAPI document.
- `GET /api/v1/_schema`: the JSON Schema of the shared envelopes.
- `GET /api/v1/<id>/_schema`: a tab's OpenAPI document.
- `POST /api/v1/_auth` with `{"token": "<token>"}`: the only route that needs no token. With the
    right token, it answers `{"ok": true}` and sets the `HttpOnly; SameSite=Strict` cookie that
    the browser console uses; with a wrong one, `401`. A programmatic client does not need it: it
    sends `Authorization: Bearer <token>` on each request.

Shared shapes: a uniform **error envelope** `{ error, message, code?, field?, hint? }`, a
**list pagination** envelope `{ items, nextCursor }`, and a live-polling **cursor envelope**
`{ cursor, status, items?, state? }`, where `status` is `delta`, `unchanged`, or `reset` (treat an
unknown value as `reset`), and the cursor advances when the route's list changes. A **stream envelope**
`{ cursor, status, items?, dropped?, state? }` has the same statuses for a list that only grows at its
end: its `delta` carries only the entries after the cursor. In both, `state` is an optional object
that a tab adds with its own state, such as the Network tab's `paused` and `throttleDelayMs`, also
to an `unchanged` response. Each tab's OpenAPI document gives its shape. A response names each query
parameter that the route does not declare in the tab's OpenAPI document in a `Lustro-Warning` header,
such as `Lustro-Warning: unknown query parameter "uniqueName"`, because the route ignores it; the
status and the body do not change. A method that a route does not take gets a `405` with an `Allow`
header that lists the methods of the path, when the tab's OpenAPI document declares the path; a path
that the document does not declare is a `404`. The schemas and the SemVer
policy live in [`wire-protocol/v1/`](https://github.com/Twinsen81/Lustro/tree/main/wire-protocol/v1); the Network tab's contract is
[`lustro/src/main/assets/lustro/network.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro/src/main/assets/lustro/network.openapi.json),
the Logs tab's is
[`lustro-logs/src/main/assets/lustro/logs.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-logs/src/main/assets/lustro/logs.openapi.json),
the Preferences tab's is
[`lustro-prefs/openapi/prefs.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-prefs/openapi/prefs.openapi.json),
the SQLite tab's is
[`lustro-sqlite/src/main/assets/lustro/sqlite.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-sqlite/src/main/assets/lustro/sqlite.openapi.json),
the WorkManager tab's is
[`lustro-work/src/main/assets/lustro/work.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-work/src/main/assets/lustro/work.openapi.json),
and the Screen tab's is
[`lustro-screen/src/main/assets/lustro/screen.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-screen/src/main/assets/lustro/screen.openapi.json),
each with a version of its own.

For driving Lustro from agents, scripts, or the `lustro` CLI, see
[Agents and automation](AGENTS.md).
