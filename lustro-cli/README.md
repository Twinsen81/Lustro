# lustro CLI

A command-line client for the [Lustro](https://github.com/Twinsen81/Lustro)
Android debug server. It talks **only** to the HTTP wire protocol under
`/api/v1/` — the public, SemVer-governed contract — so it installs with **zero
third-party runtime dependencies** (Python standard library only) and works
without a source checkout.

Target: Python 3.9+.

## Install

Each Lustro release publishes the CLI to PyPI as `lustro-cli`, with the version
of the library. Install it with [uv](https://docs.astral.sh/uv/) or
[pipx](https://pipx.pypa.io/), which keep it apart from other Python packages.
uv also installs a Python when the computer has none.

```bash
uv tool install lustro-cli    # or: pipx install lustro-cli, or: pip install lustro-cli
lustro open
uvx lustro-cli net list       # runs the CLI without an install
```

The package installs one command under two names, `lustro` and `lustro-cli`, so
that `uvx lustro-cli` finds it by the package's name.

> **`lustro` on PyPI is a different project.** It also installs a `lustro`
> command, so `pip install lustro` and `uvx lustro` install that project, not
> this CLI.

Until the first release, the CLI is not on PyPI. Install it from GitHub, or from
a checkout:

```bash
uv tool install "git+https://github.com/Twinsen81/Lustro#subdirectory=lustro-cli"   # pipx takes the same URL
uv tool install ./lustro-cli         # from a checkout; or: pipx install ./lustro-cli
pip install -e 'lustro-cli[test]'    # to work on the CLI: adds pytest and jsonschema for the contract tests
```

The wheel bundles the wire-protocol schemas, the Network OpenAPI document, and
the golden fixtures (`lustro_cli/wire/`).

`lustro --version` prints the version of the CLI and the version of the wire
protocol that it implements, such as `lustro 0.1.0 (protocol 1.16)`. Each
protocol version adds routes or fields, so two builds of the CLI with the same
version can differ in what they do: compare the protocol. When an app's protocol,
`protocolVersion` in `GET /api/v1/_meta`, is newer, `lustro ready` and `lustro
meta` print a warning on stderr: update the CLI.

## Endpoint and token discovery

The CLI resolves the endpoint+token in this order (first hit wins, per field):

1. Explicit `--token` / `--host` / `--port`.
2. The `LUSTRO_TOKEN` environment variable (token only).
3. The `LustroToken` logcat ready line — `adb [-s <serial>] logcat -d -s
   LustroToken` is parsed for the most recent
   `Lustro ready endpoint=http://<host>:<port> token=<token>`. This is the
   first source for host/port/token, **including a fallback port**.
4. (best effort) `adb [-s <serial>] shell run-as <pkg> cat shared_prefs/lustro_debug.xml`
   on debuggable builds: the token, and the host and port of the app's last bind. `<pkg>` is
   `--package <id>`, or else the app in the foreground, because Lustro listens only while its
   app is in the foreground. This finds the app on a device that drops Info logs, such as one
   with `adb shell getprop log.tag` set to `E`, where the ready line never reaches logcat.

If none of these determine the token, the CLI looks for the cause with adb, and
prints it and exits with its code (see [Exit codes](#exit-codes)). It checks, in
this order: that adb finds one device that it can use (`adb get-state`), that the
app of `--package` is installed (`adb shell pm path <pkg>`), that its build
contains the Lustro runtime, that it runs (`pidof`), and that it is in the
foreground. For the runtime, it searches the installed `base.apk` for an entry
under `assets/lustro/` with `grep -c -F` (not `unzip`, which API 28 emulator
images do not have). Every build with the Lustro runtime has such entries. A build
without them is a build with the `-noop` artifacts, which has no server, or a build
from before the app added Lustro: install a debug build that uses the Lustro
artifacts. Without `--package`, the CLI checks only the runtime of the app in the
foreground, which can be another app, such as the launcher when your app is not
running. Pass `--package` in a script, so that the CLI checks your app. A
connection that fails gets the same checks, because a ready line from an earlier
start of the app gives a token and a port that no longer answer, unless `--port`
names the local port: then the server can be on this computer.

A token from the ready line can be stale: when the app was installed again and the device
dropped the new line, the last line in the log is from the earlier install. When the server
answers `401` to a token that discovery found, the CLI reads the app's prefs and sends the
request once more with the token there. `lustro open` compares the two before it prints the
URL, because the browser gets no second try.

The port that discovery finds is the app's port on the device. The CLI reaches
it through an `adb forward`, and the local port of the forward can be a
different number. So when the host is a loopback address and `--port` is not
given, the CLI reads `adb forward --list` and connects to the local port of the
first forward to the app's port on the selected device: `--device`, else
`ANDROID_SERIAL`, else the only device. Without such a forward, it makes one,
as `lustro open` does (see [The local port](#the-local-port)), and exits 1 with
`connection_failed` when adb can't make it. It does not connect to the same
port number on the computer instead: with several devices, that port can be a
forward to the app on another device. Without adb installed, there is no device
to forward to, and a command connects to that port on the computer. A command
with `--port` connects to that port and looks for no forward, for example when
the server runs on the computer. For `lustro open`, `--port` is the app's port,
which it forwards to.

## Global flags

`--host` (default `127.0.0.1`), `--port` (default `8080`), `--token` (else
`$LUSTRO_TOKEN`), `--device <serial>` (adb `-s`), `--package <id>` (run-as
discovery), `--json` (JSON output). They go before or after the command:
`lustro --json net list` and `lustro net list --json` are the same.

## Commands

| Command | Route |
| --- | --- |
| `lustro open [--local-port N]` | discovery + `adb forward` + print/open `…/#lustro_token=<token>`; exits 1 when `adb forward` fails |
| `lustro ready [--timeout SECONDS]` | `GET /api/v1/_meta` until the server answers, then prints the endpoint; at the timeout (30 s by default), prints the cause and exits with its code (see [Wait until the app is ready](#wait-until-the-app-is-ready)) |
| `lustro meta` | `GET /api/v1/_meta` |
| `lustro schema [tabId]` | `GET /api/v1/_schema` or `/api/v1/<tab>/_schema` |
| `lustro net list` | `GET network/transactions` (one poll): the newest 50 transactions, newest first |
| `lustro net state` | the capture state from `GET network/transactions`: pause, overwrite mode, throttle, capture filter, and the number of enabled mock rules |
| `lustro net poll` | cursor loop; prints new transactions, and prints one again when it finishes |
| `lustro net wait [-- COMMAND]` | cursor loop; runs `COMMAND`, prints the first matching request that finishes, and exits |
| `lustro net get <id>` | `GET network/transactions/<id>`, with each body cut at 2 KB |
| `lustro net body <id> [request\|response] [-o FILE]` | `GET network/transactions/<id>/body/<direction>`: saves a body as it is stored, the response by default; stdout without `-o` |
| `lustro net export --har FILE [--ids ID ...]` | `GET network/transactions/_/export`: saves the transactions, or only those in `--ids`, as a HAR file; stdout for `--har -` |
| `lustro net ws list` | `GET network/websockets`: the WebSocket connections, newest first |
| `lustro net ws get <id>` | `GET network/websockets/<id>`: one connection, with the headers of its handshake |
| `lustro net ws events <id> [--follow]` | `GET network/websockets/<id>/events`: the last 50 messages and lifecycle events, oldest first; with `--follow`, each new one as it comes |
| `lustro net ws payload <id> <seq> [-o FILE]` | `GET network/websockets/<id>/events/<seq>/payload`: saves a message's payload as it is stored; stdout without `-o` |
| `lustro net clear` | `POST network/clear` |
| `lustro net pause` | `POST network/pause` |
| `lustro net overwrite` | `POST network/overwrite-mode` |
| `lustro net throttle <ms>` | `POST network/throttle` |
| `lustro mock list` | `GET network/rules` |
| `lustro mock add --url-pattern … [--failure NAME]` | `POST network/rules` |
| `lustro mock delete <id>` | `POST network/rules/delete` |
| `lustro mock toggle <id>` | `POST network/rules/toggle` |
| `lustro mock sync <file.json>` | `POST network/rules/_/sync` (atomic): a JSON array of rules, or the output of `lustro mock list --json` |
| `lustro senders` | `GET network/senders`: the clients that `send` can use, the default first |
| `lustro send --url … [--sender NAME]` | `POST network/send` (synchronous), through the first sender or the one named |
| `lustro screen tree [--unmerged] [--settle MS] [--all]` | `GET screen/tree`: each window of the app, and its nodes on the screen with a text, a tag, a role, an action, or a state, one line each (see [The screen](#the-screen)) |
| `lustro api METHOD PATH [--param K=V] [--body JSON \| --input FILE]` | any route: prints the response body (see [Any route](#any-route)) |
| `lustro mcp` | an MCP server on stdin and stdout, whose tools call the routes above (see [The MCP server](#the-mcp-server)) |

A test that changes the mock rules can save them first and restore them after:

```bash
lustro mock list --json > rules.json   # {"items": [...]}
lustro mock add --url-pattern /v1/orders --status 500
# ... the test ...
lustro mock sync rules.json            # the rules as they were
```

## Wait until the app is ready

A script that starts the app waits for its server with `lustro ready`, before
its first command:

```bash
adb shell am start -W -n com.example.app/.MainActivity
lustro ready --package com.example.app --timeout 30   # prints http://127.0.0.1:8080
```

Each try runs discovery again and sends `GET /api/v1/_meta`, once a second,
because the app logs its ready line only when its server starts, and a line from
an earlier start names a token and a port that may not answer. When the server
answers, `ready` prints the endpoint that the CLI reaches it at and exits 0;
`--json` prints `{"endpoint", "libraryVersion", "protocolVersion"}`, without the
token. When the time is over, it prints the cause and exits with its code: no
device, an app that is not installed, a build without the Lustro runtime, an app
that is not running or not in the foreground, or a port that does not answer. It
makes the checks of the cause once, at the end, because the check of the runtime
reads the whole APK. An error response that a new try can't change, such as a
`401` for a `--token` that is not the app's, ends the wait at once with exit 1;
`ready` tries again after a `5xx` or a `429`.

## Exit codes

Each cause has its own exit code, so that a script can act on it without reading
the message:

| Code | Cause | What to do |
| --- | --- | --- |
| 0 | The command did its work. | |
| 1 | The app answered with an error, such as `404 not_found`, or the command did not get what it waited for: `net wait` at its timeout, and `open` when `adb forward` fails. | Read the error on stderr. |
| 2 | The command can't run as given: a bad flag or input, an id that more than one item starts with, a search that the app rejects, or more than one device with none selected. | Change the command, or pass `--device`. |
| 3 | No device: adb is not installed, or it finds no device, or the device is offline or not authorized. | Connect a device, or start an emulator. |
| 4 | The app of `--package` is not installed. | Install a debug build that uses the Lustro artifacts. |
| 5 | The installed build of `--package` does not contain the Lustro runtime: a build with the `-noop` artifacts, or one from before the app added Lustro. | Install a debug build that uses the Lustro artifacts. This replaces the installed build. |
| 6 | The app can't be reached: it is not running or not in the foreground, it logged no ready line, or its port does not answer. | Start the app, and keep it in the foreground. |

Without `--package`, an app in the foreground without the runtime exits 6, not 5:
that app can be the launcher, or another app than yours. Every command that
finds the app exits with codes 3 to 6, and the error text of an MCP tool names
the same cause.

> **Live use of `lustro open`** requires a running app: the server only listens
> while the app is foregrounded, and discovery reads the `LustroToken` log line
> via `adb`. With no device, discovery falls back to flags / `LUSTRO_TOKEN`.

## The local port

`lustro open` forwards a local port to the app's port on the device, and prints
the console URL with the local port. It keeps an existing forward to the app.
Without one, it forwards the same port number, 8080 by default. When another
process, such as a local development server or a forward to another device,
holds that port on the computer, adb chooses a free local port. To choose the
local port yourself:

```bash
lustro open --local-port 18080   # forward local port 18080 to the app's port
lustro open --local-port 0       # adb chooses a free local port
lustro net list                  # connects through the forward, without --port
```

`open --local-port N` exits 1 when port N is taken. The other commands find
the forward in `adb forward --list`, as
[discovery](#endpoint-and-token-discovery) describes, so they need no `--port`.
This also applies to a forward that you make yourself, such as
`adb forward tcp:18080 tcp:8080`. Without a forward, a command makes one the
way `open` does, so it needs no `open` first. The CLI runs
`adb forward --no-rebind`, so it does not take over a local port that a forward
of another device holds. `--no-forward` skips the forward, and the URL then
uses `--local-port N`, else the app's port.

The commands connect to the forward directly, whatever `http_proxy`,
`HTTPS_PROXY`, or the system's proxy settings say: the server is an adb forward
on this computer, which a proxy cannot reach, and the token must not leave it.

### Several devices

The app on each device or emulator listens on port 8080 of that device, but
the computer has only one local port 8080. Select the device with
`--device SERIAL` or `ANDROID_SERIAL` for each command. The CLI then connects
through a forward to that device, on whatever local port the forward has, and
not through a forward to another device. Several agents or scripts can each
drive the app on their own device at the same time.

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
  request). They combine with each other and with `--search TEXT`, which the
  app runs over the URL, the method, the headers, and the bodies, ignoring
  case. `--regex` makes each word of the text a Java regular expression: all
  words must match, each in any field, and `-word` leaves out. A regex that
  doesn't compile exits 2 with the reason. `--url` is always plain text.
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
- **WebSockets.** `net ws list` prints a row for each connection: a short id,
  the start time, the state (`open`, `closed 1000`, `failed 403`, `canceled`),
  the counts of sent and received messages, and the URL. `net ws events` prints
  the last 50 events of one connection, oldest first; `--last N` and `--all`
  set how many. A message row has `->` for sent or `<-` for received, the type,
  the size, and the start of the payload; a lifecycle row has `--`. A sent
  message means that OkHttp queued it, which does not show that the server
  received it, and `[not sent]` marks one that `send()` refused. A long text
  ends with a marker such as
  `[... 299961 more bytes: lustro net ws payload 3d2a7f10 5]`, and
  `net ws payload` prints a payload as it is stored. `--sent`, `--received`,
  and `--search TEXT` (with `--regex`, a regular expression) select events on
  the server. `--follow` then prints each
  new event as it comes, until Ctrl-C or until the app clears the connection.
  `--json` prints JSON Lines for both commands, and `--fields` keeps only the
  keys you name. A connection id can be its start, as for a transaction.

`net wait` polls until a matching request finishes, prints it, and exits 0. It
exits 1 after `--timeout` seconds (30 by default). It ignores the requests that
finished before it started, and a mocked request finishes in a few
milliseconds, so give the action that sends the request after `--`. `net wait`
runs it after its first poll:

```bash
lustro net wait --url /v1/orders --errors -- adb shell input tap 540 1200
```

## The screen

`lustro screen tree` reads the Screen tab, when the app registers it: each window of the app,
the activity first and each dialog and popup after it, and in each the nodes on the screen that
have a text, a tag, a role, an action, or a state. A line has the node's id, its role or class,
its text, `#tag`, its states and actions, and its visible bounds in uiautomator's format:

```text
window 0  activity  com.example/com.example.MainActivity  no-focus  [0,0][1080,2400]
  c5.15  Button  "Save"  #save  click  [53,1585][985,1667]
window 1  dialog  com.example/com.example.MainActivity  focus  [120,963][960,1510]
  c13.267  Button  "OK"  #dialog-ok  click  [745,1332][897,1437]
```

`--all` prints every node, also the containers and the nodes that are not on the screen.
`--unmerged` reads the unmerged Compose semantics tree, `--settle MS` waits until two reads in a
row are the same, for up to MS milliseconds, and `--json` prints the whole response. Each call
looks up the token and the port again, so a script that reads the screen often sends the GET
itself (see [docs/AGENTS.md](../docs/AGENTS.md#screen-tab-operations)).

## Any route

`lustro api` sends one request to any route, with the token and the port that
the other commands find, and prints the response body: JSON as JSON (compact
when stdout is not a terminal), any other body as it is. Use it for a tab that
has no commands of its own; `lustro schema <tabId>` prints the tab's routes.

```bash
lustro api GET network/transactions --param search=orders   # PATH without / is under /api/v1/
lustro api GET /api/v1/network/transactions/<id>
lustro api POST network/throttle --body '{"delayMs": 2000}'
lustro api POST network/rules/_/sync --input rules.json     # --input - reads stdin
```

`--param KEY=VALUE` adds a query parameter, and can repeat a key. A route ignores a
query parameter that it does not know, so the app names each one that the tab's
OpenAPI document does not declare for the route in a `Lustro-Warning` response
header, and every command prints it on stderr, once, such as
`warning: unknown query parameter "uniqueName"`. An error
response prints the error from its envelope on stderr, such as
`error: 404 not_found: ...`, and exits 1; nothing goes to stdout. A search
that the app rejects (a `400` with `field: "search"`) exits 2, as a bad flag
does. See [Exit codes](#exit-codes) for the others.

## The MCP server

`lustro mcp` is a [Model Context Protocol](https://modelcontextprotocol.io) server on stdin and
stdout, for an agent that can only call MCP tools, such as Claude Desktop. An agent that can run
shell commands should run the commands above: the client puts the server's tools into the
agent's context in every session, and a command costs context only when the agent runs it.

The agent's client starts the server when a session starts, and stops it when the session ends.
Add it to the client's MCP config:

```json
{
  "mcpServers": {
    "lustro": {
      "command": "lustro",
      "args": ["mcp"]
    }
  }
}
```

[With a coding agent](../docs/get-started/coding-agent.md#agents-without-a-shell-the-mcp-server)
has the config for Claude Desktop, Claude Code, Cursor, VS Code, Codex, and Gemini CLI. A desktop
app starts the server with a short `PATH`: give `command` the full path that `which lustro`
prints. When `adb` is not on the `PATH`, the server looks for it in `$ANDROID_HOME/platform-tools`,
then in the default place of the Android SDK.

The tools: `list_requests`, `get_request`, `list_mock_rules`, `add_mock_rule`,
`delete_mock_rule`, `set_throttle`, `read_logs`, `call_api`, which reaches any route as
`lustro api` does, and `get_schema`, which returns the tabs or a tab's OpenAPI document. Each says
in its annotations and its description whether it changes the app.
[docs/AGENTS.md](../docs/AGENTS.md#the-mcp-server-for-agents-without-a-shell) lists the route of
each.

Each tool call finds the app as a command does, so the global flags apply to every call:
`lustro mcp --device SERIAL` selects the device. The server implements JSON-RPC 2.0 with the
standard library, one message per line, and serves the protocol versions from `2024-11-05` to
`2026-07-28`. Only protocol messages go to stdout; its own messages go to stderr. A warning from
the app, such as `warning: unknown query parameter "uniqueName"` for a `call_api` with a parameter
that the route does not declare, is a text block of its own after the output of the tool.

## Contract tests

```bash
cd lustro-cli
pytest
```

The tests validate every golden fixture against its JSON Schema / OpenAPI
component, exercise the cursor-polling state machine, the error-envelope parser,
the `LustroToken` log-line parser, the output of each command, the MCP server's
messages and the route of each tool, and a client smoke test against a local
`http.server`.
