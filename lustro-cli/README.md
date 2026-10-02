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

The port that discovery finds is the app's port on the device. The CLI reaches
it through an `adb forward`, and the local port of the forward can be a
different number. So when the host is a loopback address and `--port` is not
given, the CLI reads `adb forward --list` and connects to the local port of the
first forward to the app's port on the selected device: `--device`, else
`ANDROID_SERIAL`, else the only device. Without such a forward, it connects to
the same port number on the computer. A command with `--port` connects to that
port and looks for no forward, for example when the server runs on the
computer. For `lustro open`, `--port` is the app's port, which it forwards to.

## Global flags

`--host` (default `127.0.0.1`), `--port` (default `8080`), `--token` (else
`$LUSTRO_TOKEN`), `--device <serial>` (adb `-s`), `--package <id>` (run-as
discovery), `--json` (JSON output). They go before or after the command:
`lustro --json net list` and `lustro net list --json` are the same.

## Commands

| Command | Route |
| --- | --- |
| `lustro open [--local-port N]` | discovery + `adb forward` + print/open `…/#lustro_token=<token>`; exits 1 when `adb forward` fails |
| `lustro meta` | `GET /api/v1/_meta` |
| `lustro schema [tabId]` | `GET /api/v1/_schema` or `/api/v1/<tab>/_schema` |
| `lustro net list` | `GET network/transactions` (one poll): the newest 50 transactions, newest first |
| `lustro net state` | the capture state from `GET network/transactions`: pause, overwrite mode, throttle, capture filter |
| `lustro net poll` | cursor loop; prints new transactions, and prints one again when it finishes |
| `lustro net wait [-- COMMAND]` | cursor loop; runs `COMMAND`, prints the first matching request that finishes, and exits |
| `lustro net get <id>` | `GET network/transactions/<id>`, with each body cut at 2 KB |
| `lustro net body <id> [request\|response] [-o FILE]` | `GET network/transactions/<id>/body/<direction>`: saves a body as it is stored, the response by default; stdout without `-o` |
| `lustro net export --har FILE [--ids ID ...]` | `GET network/transactions/_/export`: saves the transactions, or only those in `--ids`, as a HAR file; stdout for `--har -` |
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

## The local port

`lustro open` forwards a local port to the app's port on the device, and prints
the console URL with the local port. It keeps an existing forward to the app.
Without one, it forwards the same port number, 8080 by default, and exits 1
when another process, such as a local development server, holds that port on
the computer. Then choose the local port:

```bash
lustro open --local-port 18080   # forward local port 18080 to the app's port
lustro open --local-port 0       # adb chooses a free local port
lustro net list                  # connects through the forward, without --port
```

The other commands find the forward in `adb forward --list`, as
[discovery](#endpoint-and-token-discovery) describes, so they need no `--port`.
This also applies to a forward that you make yourself, such as
`adb forward tcp:18080 tcp:8080`. `open` runs `adb forward --no-rebind`, so it
does not take over a local port that a forward of another device holds.
`--no-forward` skips the forward, and the URL then uses `--local-port N`, else
the app's port.

## Output

The output is small by default, because an agent reads all of it.

- **Rows.** Each `net list`, `net poll`, and `net wait` row starts with a short
  transaction id, then the start time, the method, the status (`...` in flight,
  `ERR` for a failed request), the duration, and the URL. The short id is the
  first 8 characters of the id, or more characters when two listed ids start
  with the same 8. JSON output has the full id.
- **Ids.** `net get`, `net body`, and `net export --ids` take a full id, or the
  start of one, such as the short id of a row. They find the id that starts with it in the transaction
  list, which is one more request. When more than one id starts with it, they
  print the matches and exit 2. When no id starts with it, they exit 1. The wire
  routes take only the full id.
- **The newest 50.** `net list` prints the newest 50 transactions, and says on
  stderr how many it left out. `--last N` prints the newest N, and `--all`
  prints every one.
- **Filters.** `net list`, `net poll`, and `net wait` take `--url TEXT` (the URL
  contains it, ignoring case), `--method`, `--status` (a code such as `404`, or a
  class such as `5xx`), and `--errors` (status 400 or higher, or a failed
  request). They combine with each other and with `--search`, which the server
  runs over the URL, the method, and the bodies.
- **JSON Lines.** With `--json`, these commands print one compact JSON object
  per transaction, so `head` and `jq` can cut the output. `--fields
  id,statusCode,url` keeps only those keys, and implies `--json`. Other
  commands print one JSON document: compact when stdout is not a terminal, and
  indented when it is.
- **Bodies.** `net get` cuts each body at 2 KB and ends it with a marker such as
  `[... 18251 more bytes: lustro net body <id> response]`. `--max-body BYTES`
  sets the cut, `--no-body` leaves the bodies out, and `--full` prints them
  whole. `net body` prints one body as it is stored.
- **Updates.** `net poll` prints a transaction again, marked `[update]`, when it
  was in flight and finishes or fails. With `--json`, the later line for an id
  replaces the earlier one.

`net wait` polls until a matching request finishes, prints it, and exits 0. It
exits 1 after `--timeout` seconds (30 by default). It ignores the requests that
finished before it started, and a mocked request finishes in a few
milliseconds, so give the action that sends the request after `--`. `net wait`
runs it after its first poll:

```bash
lustro net wait --url /v1/orders --errors -- adb shell input tap 540 1200
```

## Contract tests

```bash
cd lustro-cli
pytest
```

The tests validate every golden fixture against its JSON Schema / OpenAPI
component, exercise the cursor-polling state machine, the error-envelope parser,
the `LustroToken` log-line parser, the output of each command, and a client
smoke test against a local `http.server`.
