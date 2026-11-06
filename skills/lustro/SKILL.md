---
name: lustro
description: "Debug an Android app that has Lustro, an in-app debug server, through the lustro CLI: the app's HTTP requests and WebSocket messages, mocked responses and slow connections, its logcat, SharedPreferences and DataStores, SQLite and Room databases, and WorkManager work. Also adds Lustro to an Android app and checks the integration. Use in an Android project whose Gradle files name io.github.twinsen81:lustro when you need to see what the running app sends, receives, logs, or stores; when the user mentions Lustro; or when asked to add Lustro to an app."
---

# Lustro

Lustro runs a small server in the debug build of an Android app. Each of its tabs (Network, Logs,
Preferences, SQLite, WorkManager) is a JSON API under `/api/v1/` on the device, and a desktop
browser shows the same tabs as a console. Use the `lustro` CLI to read and change them: it finds
the token and the port itself, and its output is small. Do not read the console's HTML.

To add Lustro to an app, follow [references/integrate.md](references/integrate.md). The rest of
this file is for an app that has Lustro.

## Get the CLI

```bash
lustro-cli --version || uv tool install lustro-cli    # or: pipx install lustro-cli
```

The package installs one command under two names, `lustro` and `lustro-cli`; this file uses
`lustro`. `uvx lustro-cli <command>` runs it without an install. The PyPI package `lustro` is a
different project with a `lustro` command of its own: never install it, and never run
`uvx lustro`.

## Connect

1. Install the debug build and start the app. The server listens only while the app is in the
   foreground: a locked screen or another app in front stops it.
2. `lustro meta` lists the tabs that the app registers. Each command finds the token in logcat, or
   through `run-as` in the app's prefs, and makes the `adb forward` itself.
3. With more than one device, `export ANDROID_SERIAL=<serial>`: the CLI and `adb` both use it.
   `--device <serial>` selects the device for one `lustro` command.

When a command cannot connect, the app is in the background, or the installed build is not a
debug build with Lustro. `adb logcat -d -s LustroToken` shows the line of each start:
`Lustro ready endpoint=http://127.0.0.1:8080 token=<token>`.

## Network

```bash
lustro net list --errors --last 20       # failed requests, and status 400 or higher
lustro net list --url /v1/orders --last 5
lustro net get <id> --no-body            # the headers, the timing, and the status of one request
lustro net body <id> | head -c 4000      # the response body as stored; add `request` for the request's
lustro net wait --url /v1/orders -- adb shell input tap 540 1200   # run the action, print its request
lustro net export --har out.har --ids <id> <id>
lustro net ws list                       # WebSockets; then `net ws events <id> --last 20`
```

The `net get`, `net body`, `net export`, and `net ws` commands take a row's short id. To change
what the app gets, and to undo it:

```bash
lustro mock add --url-pattern /v1/orders --status 500 --body '{"error":"down"}'
lustro mock list                         # the rules, each with its whole id
lustro mock delete <whole id>            # a short id deletes nothing and still prints ok
lustro net throttle 3000                 # each request waits 3 s; `lustro net throttle 0` ends it
lustro senders                           # the app's clients that `lustro send --url <url>` can use
```

After an undo, run `mock list` or `net state` again to see that the change is gone.

## Logs, preferences, databases, and work

These tabs have no commands of their own. `lustro api METHOD PATH` reaches each route, with
`--param KEY=VALUE` and `--body JSON`. A PATH without a leading `/` is under `/api/v1/`.
`lustro schema <tab>` prints the tab's whole OpenAPI document, which is large:
`lustro schema prefs | jq -r '.paths | keys[]'` lists the routes.

```bash
lustro api GET logs/text --param level=W --param limit=200     # warnings and errors, as logcat prints them
lustro api GET logs/text --param q=timeout --param limit=100   # lines whose message contains the text
lustro api GET prefs/files                                      # SharedPreferences files and DataStores
lustro api GET prefs/entries --param file=<name>
lustro api GET prefs/entry --param file=<name> --param key=<key>  # read the value before you change it
lustro api POST prefs/edit --body '{"file":"<name>","key":"<key>","type":"boolean","value":true}'
lustro api POST prefs/delete --body '{"file":"<name>","key":"<key>"}'   # undoes an edit that added a key
lustro api GET sqlite/databases
lustro api GET sqlite/tables --param db=<name>
lustro api POST sqlite/query --body '{"db":"<name>","sql":"SELECT * FROM orders LIMIT 20"}'
lustro api GET work/items --param state=FAILED,CANCELLED
lustro api GET work/items | jq -c .state                        # `listeners: installed`, or why there are no events
lustro api GET work/events --param type=finished,exception     # the result or exception of each run
```

## Rules

- Lustro's runtime artifacts belong in the debug dependencies only, and a `DebugTab` subclass in
  `src/debug` only.
- The token gives access to the app's data: do not write it into files, commits, or reports.
- A mock rule, a throttle, an edit, an SQL `exec`, and a work `cancel` change what the app does.
  Tell the user what you changed, and undo it when you are done. An app can read a preference
  only when it starts or connects, so restart it after an edit and after the undo.
- Keep the output small: `--last N`, `--no-body`, `--fields id,statusCode,url`, `--param limit=N`,
  and `head -c`. Bodies and log text can be large.

## More

- `lustro <command> --help`.
- [docs/AGENTS.md](https://github.com/Twinsen81/Lustro/blob/main/docs/AGENTS.md): every route, the
  common workflows, and the failure modes.
