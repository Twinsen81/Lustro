# lustro CLI

A command-line client for the [Lustro](https://github.com/Twinsen81/Lustro)
Android debug server. It talks **only** to the HTTP wire protocol under
`/api/v1/` — the public, SemVer-governed contract — so it installs with **zero
third-party runtime dependencies** (Python standard library only) and works
without a source checkout.

Target: Python 3.9+.

## Install

```bash
pip install lustro-cli
# or, from a checkout:
pip install -e 'lustro-cli[test]'   # includes pytest + jsonschema for contract tests
```

This installs a `lustro` console entry point and bundles the wire-protocol
schemas, the Network OpenAPI document, and the golden fixtures inside the wheel
(`lustro_cli/wire/`).

## Endpoint and token discovery

The CLI resolves the endpoint+token in this order (first hit wins, per field):

1. Explicit `--token` / `--host` / `--port`.
2. The `LUSTRO_TOKEN` environment variable (token only).
3. The `LustroToken` logcat ready line — `adb [-s <serial>] logcat -d -s
   LustroToken` is parsed for the most recent
   `Lustro ready endpoint=http://<host>:<port> token=<token>`. This is the
   single source of truth for host/port/token, **including a fallback port**.
4. (best effort) `adb [-s <serial>] shell run-as <pkg> cat shared_prefs/lustro_debug.xml`
   to recover the token on debuggable builds (`--package <id>`).

If none of these determine the token, the CLI prints a clear, actionable error.

## Global flags

`--host` (default `127.0.0.1`), `--port` (default `8080`), `--token` (else
`$LUSTRO_TOKEN`), `--device <serial>` (adb `-s`), `--package <id>` (run-as
discovery), `--json` (raw JSON output).

## Commands

| Command | Route |
| --- | --- |
| `lustro open` | discovery + `adb forward` + print/open `…/#lustro_token=<token>` |
| `lustro meta` | `GET /api/v1/_meta` |
| `lustro schema [tabId]` | `GET /api/v1/_schema` or `/api/v1/<tab>/_schema` |
| `lustro net list` | `GET network/transactions` (one poll) |
| `lustro net poll` | cursor loop; prints new transactions |
| `lustro net get <id>` | `GET network/transactions/<id>` |
| `lustro net clear` | `POST network/clear` |
| `lustro net pause` | `POST network/pause` |
| `lustro net overwrite` | `POST network/overwrite-mode` |
| `lustro net throttle <ms>` | `POST network/throttle` |
| `lustro mock list` | `GET network/rules` |
| `lustro mock add --url-pattern …` | `POST network/rules` |
| `lustro mock delete <id>` | `POST network/rules/delete` |
| `lustro mock toggle <id>` | `POST network/rules/toggle` |
| `lustro mock sync <file.json>` | `POST network/rules/_/sync` (atomic) |
| `lustro send --url …` | `POST network/send` (synchronous) |

> **Live use of `lustro open`** requires a running app: the server only listens
> while the app is foregrounded, and discovery reads the `LustroToken` log line
> via `adb`. With no device, discovery falls back to flags / `LUSTRO_TOKEN`.

## Contract tests

```bash
cd lustro-cli
pytest
```

The tests validate every golden fixture against its JSON Schema / OpenAPI
component, exercise the cursor-polling state machine, the error-envelope parser,
the `LustroToken` log-line parser, and a client smoke test against a local
`http.server`.
