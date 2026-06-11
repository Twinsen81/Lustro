# Wire protocol v1 — schema index

| Schema file | Describes | Status |
| --- | --- | --- |
| `error-envelope.schema.json` | `{ error, message, code, field?, hint? }` | present |
| `cursor-envelope.schema.json` | `{ cursor, status, items? }` | present |
| `pagination.schema.json` | `{ items, nextCursor }` | present |
| `meta.schema.json` | `GET /api/v1/_meta` response | present |
| `network.openapi.json` (in AAR assets) | Network tab routes | present |
| `golden/` fixtures | recorded responses for CLI contract tests | present |
