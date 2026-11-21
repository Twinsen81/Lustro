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

Until the first Lustro release, `lustro-cli` is not on PyPI. Install it from GitHub; pipx takes
the same URL:

```bash
lustro-cli --version || uv tool install "git+https://github.com/Twinsen81/Lustro#subdirectory=lustro-cli"
```

From the first release, `uv tool install lustro-cli` or `pipx install lustro-cli` installs it from
PyPI, and `uvx lustro-cli <command>` runs it without an install.

The package installs one command under two names, `lustro` and `lustro-cli`; this file uses
`lustro`. The PyPI package `lustro` is a different project with a `lustro` command of its own:
never install it, and never run `uvx lustro`.

`lustro --version` prints the wire protocol that the CLI implements, such as
`lustro 0.1.0 (protocol 1.17)`. When `lustro ready` or `lustro meta` warns that the app's protocol
is newer, install the CLI again with the command above, with `--force`.

An app on an older protocol ignores an option that its protocol does not have, so the CLI refuses
such a command before it changes anything, with exit status 1 and `error: protocol_too_old:
--failure needs wire protocol 1.12, and the app uses 1.11, which ignores it`: `send --sender`
(1.7), `--regex` (1.8), and `mock add --failure` or `mock sync` of a rule with `failure` (1.12).
Then tell the user that the app needs a newer Lustro release, or do the step without the option.
`lustro api` with `--param` to an app older than 1.15 prints a note: that app does not warn about
a parameter that the route does not declare, so check the names in `lustro schema <tab>`.

## Connect

1. With more than one device, `export ANDROID_SERIAL=<serial>`: the CLI and `adb` both use it.
   `--device <serial>` selects the device for one `lustro` command.
2. Install and start the app, then `lustro ready --package <package>`. It waits up to 30 s until
   the server answers, and prints the endpoint. It stops at once with exit 4 or 5 when the app is
   not installed or the build has no Lustro runtime, so do not run it while `adb install` runs.
   The server listens only while the app is in the foreground: a locked screen or another app in
   front stops it.
3. `lustro meta` lists the tabs that the app registers. Each command finds the token and the port,
   and makes the `adb forward` itself, on a local port that adb chooses (only `lustro open` tries
   8080 first). With `--package`, it reads them first through `run-as` in that app's prefs, and
   runs no `logcat` when they have them, which keeps each command fast on a device with a full log;
   it reads the log line of that app's current process only for a build that is not debuggable,
   or before the app writes its prefs. Without `--package`, it takes the last line in the log,
   which can be another app's, then the prefs of the app in the foreground. Pass `--package` in
   each command.

When `ready` or another command cannot reach the app, it prints the cause, and its exit code says
which cause it is:

| Code | Cause | What to do |
| --- | --- | --- |
| 1 | The app answered with an error. | Read the error. |
| 2 | A bad flag or input, or more than one device with none selected. | Change the command, or pass `--device`. |
| 3 | No device, or adb is not installed. | Connect a device, or start an emulator. |
| 4 | The app of `--package` is not installed. | Install a debug build with Lustro. |
| 5 | The installed build has no Lustro runtime: a build with the `-noop` artifacts, or one from before the app added Lustro. | Install a debug build with Lustro. This replaces the installed build: if the user asked you to look at that build, ask first. |
| 6 | The app is not running or not in the foreground, or its port does not answer. | Start the app, and keep it in front. |

Without `--package`, the CLI checks only the app in the foreground, and an app there without the
runtime exits 6: it can be another app. `adb logcat -d -s LustroToken` shows the line of each
start: `Lustro ready endpoint=http://127.0.0.1:8080 token=<token>`.

A crash ends the app's process and the server with it, so Lustro cannot show the crash. Logcat
keeps it: `adb logcat -d -b crash` prints the recent crashes of the device, and the one for the
app has the line `Process: <package>, PID: <pid>`. `adb logcat -d --pid=<pid>` shows what the app
logged before it.

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
lustro mock add --url-pattern /v1/orders --failure timeout   # or unknownHost, connectionRefused, connectionReset, tls
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

`prefs/files` gives each file's `store`: `shared_prefs` for SharedPreferences, or
`preferences_datastore` for a DataStore. Pass it in each route of that file. A route without it
uses `shared_prefs`, so a DataStore's name gets a 404, or the SharedPreferences file of that name.

```bash
lustro api GET logs/text --param level=W --param limit=200     # warnings and errors, as logcat prints them
lustro api GET logs/text --param q=timeout --param limit=100   # lines whose message contains the text
lustro api GET prefs/files                                      # each file's name and store
lustro api GET prefs/entries --param store=<store> --param file=<name>
lustro api GET prefs/entry --param store=<store> --param file=<name> --param key=<key>   # read it before you change it
lustro api POST prefs/edit --body '{"store":"<store>","file":"<name>","key":"<key>","type":"boolean","value":true}'
lustro api POST prefs/delete --body '{"store":"<store>","file":"<name>","key":"<key>"}'   # undoes an edit that added a key
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
  Tell the user what you changed, and undo it when you are done. Many apps read a preference once,
  for example when they start, so restart the app after an edit and after the undo.
- Keep the output small: `--last N`, `--no-body`, `--fields id,statusCode,url`, `--param limit=N`,
  and `head -c`. Bodies and log text can be large.

## More

- `lustro <command> --help`.
- [docs/AGENTS.md](https://github.com/Twinsen81/Lustro/blob/main/docs/AGENTS.md): every route, the
  common workflows, and the failure modes.
