# OpenAPI and wire protocol

Every tab is a JSON API under `/api/v1/`. Framework routes:

- `GET /api/v1/_meta` — library/protocol versions and the schema-exposing tabs.
- `GET /api/v1/_schema` — JSON Schema for the shared envelopes.
- `GET /api/v1/<id>/_schema` — a tab's OpenAPI document.

Shared shapes: a uniform **error envelope** `{ error, message, code?, field?, hint? }`, a
**list pagination** envelope `{ items, nextCursor }`, and a live-polling **cursor envelope**
`{ cursor, status, items? }` where `status` is `delta` / `unchanged` / `reset` (unknown values →
`reset`) and the cursor advances when the route's list changes. A **stream envelope**
`{ cursor, status, items?, dropped? }` has the same statuses for a list that only grows at its
end: its `delta` carries only the entries after the cursor. The schemas and the SemVer
policy live in [`wire-protocol/v1/`](https://github.com/Twinsen81/Lustro/tree/main/wire-protocol/v1); the Network tab's contract is
[`lustro/src/main/assets/lustro/network.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro/src/main/assets/lustro/network.openapi.json),
the Logs tab's is
[`lustro-logs/src/main/assets/lustro/logs.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-logs/src/main/assets/lustro/logs.openapi.json),
the Preferences tab's is
[`lustro-prefs/src/main/assets/lustro/prefs.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-prefs/src/main/assets/lustro/prefs.openapi.json),
the SQLite tab's is
[`lustro-sqlite/src/main/assets/lustro/sqlite.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-sqlite/src/main/assets/lustro/sqlite.openapi.json),
the WorkManager tab's is
[`lustro-work/src/main/assets/lustro/work.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-work/src/main/assets/lustro/work.openapi.json),
and the Screen tab's is
[`lustro-screen/src/main/assets/lustro/screen.openapi.json`](https://github.com/Twinsen81/Lustro/blob/main/lustro-screen/src/main/assets/lustro/screen.openapi.json),
each with a version of its own.

For driving Lustro from agents, scripts, or the `lustro` CLI, see
[Agents and automation](AGENTS.md).
