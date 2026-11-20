# Wire protocol v1 — schema index

| Schema file | Describes | Status |
| --- | --- | --- |
| `error-envelope.schema.json` | `{ error, message, code, field?, hint? }` | present |
| `cursor-envelope.schema.json` | `{ cursor, status, items? }` | present |
| `stream-envelope.schema.json` | `{ cursor, status, items?, dropped? }` | present |
| `pagination.schema.json` | `{ items, nextCursor }` | present |
| `meta.schema.json` | `GET /api/v1/_meta` response | present |
| `_schema.json` (in the `lustro` AAR assets) | `GET /api/v1/_schema` response: the shared envelopes, in one document | present |
| `network.openapi.json` (generated from the `lustro` route declarations of the Network tab; served at `GET /api/v1/network/_schema`) | Network tab routes; its version is the protocol version | present |
| `logs.openapi.json` (generated from the `lustro-logs` route declarations; served at `GET /api/v1/logs/_schema`) | Logs tab routes, version `1.1` | present |
| `prefs.openapi.json` (generated from the `lustro-prefs` route declarations; served at `GET /api/v1/prefs/_schema`) | Preferences tab routes, version `1.2` | present |
| `sqlite.openapi.json` (generated from the `lustro-sqlite` route declarations; served at `GET /api/v1/sqlite/_schema`) | SQLite tab routes, version `1.2` | present |
| `work.openapi.json` (generated from the `lustro-work` route declarations; served at `GET /api/v1/work/_schema`) | WorkManager tab routes, version `1.3` | present |
| `screen.openapi.json` (generated from the `lustro-screen` route declarations; served at `GET /api/v1/screen/_schema`) | Screen tab routes, version `1.0` | present |
| `golden/` fixtures | recorded responses for CLI contract tests | present |
