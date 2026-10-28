# Wire protocol v1 — schema index

| Schema file | Describes | Status |
| --- | --- | --- |
| `error-envelope.schema.json` | `{ error, message, code, field?, hint? }` | present |
| `cursor-envelope.schema.json` | `{ cursor, status, items? }` | present |
| `stream-envelope.schema.json` | `{ cursor, status, items?, dropped? }` | present |
| `pagination.schema.json` | `{ items, nextCursor }` | present |
| `meta.schema.json` | `GET /api/v1/_meta` response | present |
| `network.openapi.json` (in AAR assets) | Network tab routes | present |
| `logs.openapi.json` (in the `lustro-logs` AAR assets) | Logs tab routes, version `1.0` | present |
| `prefs.openapi.json` (in the `lustro-prefs` AAR assets) | Preferences tab routes, version `1.0` | present |
| `sqlite.openapi.json` (in the `lustro-sqlite` AAR assets) | SQLite tab routes, version `1.0` | present |
| `work.openapi.json` (in the `lustro-work` AAR assets) | WorkManager tab routes, version `1.0` | present |
| `golden/` fixtures | recorded responses for CLI contract tests | present |
