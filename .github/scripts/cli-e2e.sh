#!/usr/bin/env bash
# Runs the lustro CLI against the debug sample on a device or emulator, and checks
# each command's output against the wire schemas the CLI ships. CI runs it after
# connectedCheck, in the same emulator session.
#
# No command passes --token: the CLI finds the endpoint and the token in the
# LustroToken logcat line, as it does for a user, so this also tests discovery.
#
# Needs adb, one connected device (set ANDROID_SERIAL when there are more), and
# Python 3.9 or later. The CLI goes into a virtual environment in the output
# directory. `lustro open` forwards local port 8080 to the app, or a free port
# when 8080 is taken, or CLI_E2E_LOCAL_PORT when it is set; 0 lets adb choose a
# free port. The other commands find that forward, so none of them passes
# --port. The run replaces the sample's mock rules with its own and sets the
# device to stay awake while it charges. The output directory keeps each
# command's output and, at exit, logcat.
set -euo pipefail

# A token in the environment would win over the one in the logcat line.
unset LUSTRO_TOKEN

cd "$(dirname "$0")/../.."
OUT="${CLI_E2E_OUT:-build/cli-e2e}"
PYTHON="${PYTHON:-python3}"
PACKAGE="io.github.twinsen81.lustro.sample"
CHECK=".github/scripts/cli_e2e_check.py"

rm -rf "$OUT"
mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
RULES="$OUT/rules-to-sync.json"
trap 'adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1 || true' EXIT

step() {
  printf '\n==> %s\n' "$*"
}

# lustro NAME ARGS...: runs the CLI, keeps its stdout in $OUT/NAME and prints it.
lustro_to() {
  local name="$1"
  shift
  step "lustro $*"
  lustro "$@" > "$OUT/$name"
  cat "$OUT/$name"
}

check() {
  "$OUT/venv/bin/python" "$CHECK" "$@"
}

step "Install the debug sample"
./gradlew :sample:assembleDebug --console=plain --quiet
adb install -r sample/build/outputs/apk/debug/sample-debug.apk

# The server listens only while the app is in the foreground, and a locked screen
# stops the activity.
adb shell svc power stayon true || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true

step "Start the sample and wait for its LustroToken line"
adb shell am force-stop "$PACKAGE"
adb shell am start -W -n "$PACKAGE/.MainActivity"
ready=""
for _ in $(seq 60); do
  pid="$(adb shell pidof "$PACKAGE" | tr -d '\r' || true)"
  if [ -n "$pid" ]; then
    ready="$(adb logcat -d --pid="$pid" -s LustroToken:I | grep 'Lustro ready' || true)"
    [ -n "$ready" ] && break
  fi
  sleep 1
done
if [ -z "$ready" ]; then
  echo "error: no 'Lustro ready' line from $PACKAGE within 60 s" >&2
  exit 1
fi

step "Install the CLI from the checkout"
"$PYTHON" -m venv "$OUT/venv"
"$OUT/venv/bin/python" -m pip install --quiet --disable-pip-version-check "./lustro-cli[test]"
export PATH="$OUT/venv/bin:$PATH"

# Also runs `adb forward` to the discovered port, and fails when the forward
# fails, for example when another process holds CLI_E2E_LOCAL_PORT.
lustro_to open.txt open --print-only ${CLI_E2E_LOCAL_PORT:+--local-port "$CLI_E2E_LOCAL_PORT"}
check open < "$OUT/open.txt"

lustro_to meta.json --json meta
check meta < "$OUT/meta.json"

step "lustro --json schema network"
lustro --json schema network > "$OUT/schema-network.json"
check schema-network < "$OUT/schema-network.json"
echo "matches the CLI's copy ($(wc -c < "$OUT/schema-network.json") bytes)"

# The first rule serves the sample's "GET /get" request, and the second one
# answers the handshake of its "upgrade refused" WebSocket, so the steps need no
# internet.
pattern="https://httpbingo.org/get"
ws_pattern="https://httpbingo.org/status/403"
cat > "$RULES" <<'EOF'
[
  {
    "id": "cli-e2e-get",
    "name": "CLI end to end",
    "urlPattern": "https://httpbingo.org/get",
    "method": "GET",
    "statusCode": 200,
    "responseHeaders": {"Content-Type": "application/json"},
    "responseBody": "{\"mocked\":true}"
  },
  {
    "id": "cli-e2e-websocket",
    "name": "CLI end to end, WebSocket handshake",
    "urlPattern": "https://httpbingo.org/status/403",
    "method": "GET",
    "statusCode": 403,
    "responseBody": ""
  }
]
EOF
lustro_to mock-sync.json --json mock sync "$RULES"
check sync "$RULES" < "$OUT/mock-sync.json"

lustro_to mock-list.json --json mock list
check rules "$RULES" < "$OUT/mock-list.json"

lustro_to net-list.jsonl --json net list
check list < "$OUT/net-list.jsonl"

lustro_to net-state.json --json net state
check state < "$OUT/net-state.json"

# net wait reads the list before it runs the command, so the mocked request
# can't finish before wait starts to look for it.
step "lustro --json net wait: fire the sample's GET /get and wait for it"
lustro --json net wait --url "$pattern" --timeout 30 -- \
  adb shell am start -W --activity-single-top -n "$PACKAGE/.MainActivity" --es request "'GET /get'" \
  > "$OUT/net-wait.jsonl"
cat "$OUT/net-wait.jsonl"
check list < "$OUT/net-wait.jsonl"
id="$(check find "$RULES" < "$OUT/net-wait.jsonl")"

lustro_to net-list-after.jsonl --json net list --url "$pattern" --last 1
check list < "$OUT/net-list-after.jsonl"
listed="$(check find "$RULES" < "$OUT/net-list-after.jsonl")"
if [ "$listed" != "$id" ]; then
  echo "error: net wait printed $id, but net list shows $listed as the newest match" >&2
  exit 1
fi

lustro_to net-get.json --json net get "$id"
check transaction "$RULES" < "$OUT/net-get.json"

# A table row starts with a short id, and net get takes it.
lustro_to net-list-row.txt net list --url "$pattern" --last 1
short="$(cut -d ' ' -f 1 "$OUT/net-list-row.txt")"
case "$id" in
  "$short"?*) ;;
  *)
    echo "error: the row starts with $short, which is not a short form of $id" >&2
    exit 1
    ;;
esac
lustro_to net-get-short.json --json net get "$short"
check transaction "$RULES" < "$OUT/net-get-short.json"

# The generic verb reaches any route with the same discovery, so a tab without
# commands of its own can be scripted: here the Network routes, a GET with a
# query parameter and a POST with a body, and an error.
lustro_to api-transactions.json api GET network/transactions --param "search=$pattern"
check api-transactions "$RULES" < "$OUT/api-transactions.json"

lustro_to api-throttle.json api POST /api/v1/network/throttle --body '{"delayMs": 0}'
check api-throttle < "$OUT/api-throttle.json"

step "lustro api: a missing transaction exits 1, with the error on stderr"
status=0
lustro api GET "network/transactions/$id-missing" > "$OUT/api-missing.txt" 2> "$OUT/api-missing.err" || status=$?
cat "$OUT/api-missing.err"
if [ "$status" != 1 ] || ! grep -q '^error: 404 not_found' "$OUT/api-missing.err"; then
  echo "error: lustro api exited $status for a missing transaction, expected 1 and a 404 not_found" >&2
  exit 1
fi

# The sample's default sender has the fixture host as its base URL, and its client
# carries the interceptor: the rule answers the relative /get, and the request is
# captured.
lustro_to senders.json --json senders
check senders < "$OUT/senders.json"
lustro_to send.json --json send --url /get
sent="$(check send "$RULES" < "$OUT/send.json")"
lustro_to send-get.json --json net get "$sent"
check transaction "$RULES" < "$OUT/send-get.json"

# net export takes the short id too, and writes the same entry that a full export has.
step "lustro net export --har: the mocked request, then every transaction"
lustro net export --har "$OUT/export-one.har" --ids "$short"
check har "$RULES" < "$OUT/export-one.har"
entries="$("$OUT/venv/bin/python" -c 'import json,sys; print(len(json.load(sys.stdin)["log"]["entries"]))' < "$OUT/export-one.har")"
if [ "$entries" != "1" ]; then
  echo "error: --ids $short exported $entries entries" >&2
  exit 1
fi
lustro net export --har "$OUT/export-all.har"
check har "$RULES" < "$OUT/export-all.har"

# The sample creates its sockets with the factory from Lustro.webSocketFactory.
# The mocked 403 fails this one's handshake, so it is listed as failed, with the
# handshake's transaction.
step "lustro --json net wait: connect the sample's refused WebSocket and wait for its handshake"
lustro --json net wait --url "$ws_pattern" --timeout 30 -- \
  adb shell am start -W --activity-single-top -n "$PACKAGE/.MainActivity" \
  --es request "'WS connect, upgrade refused (/status/403)'" \
  > "$OUT/ws-wait.jsonl"
cat "$OUT/ws-wait.jsonl"
check list < "$OUT/ws-wait.jsonl"

# The capture thread stores the failure a moment after the handshake.
ws_id=""
for _ in $(seq 20); do
  lustro --json net ws list > "$OUT/ws-list.jsonl"
  ws_id="$(check ws-find "$RULES" < "$OUT/ws-list.jsonl" 2> /dev/null || true)"
  [ -n "$ws_id" ] && break
  sleep 0.5
done
cat "$OUT/ws-list.jsonl"
check ws-list < "$OUT/ws-list.jsonl"
if [ -z "$ws_id" ]; then
  echo "error: net ws list shows no failed connection to $ws_pattern" >&2
  exit 1
fi

# A table row starts with a short id, and net ws get takes it.
lustro_to ws-list-row.txt net ws list
ws_short="$(grep "/status/403" "$OUT/ws-list-row.txt" | head -n 1 | cut -d ' ' -f 1)"
lustro_to ws-get.json --json net ws get "$ws_short"
check ws-connection "$RULES" < "$OUT/ws-get.json"

lustro_to ws-events.jsonl --json net ws events "$ws_id"
check ws-events < "$OUT/ws-events.jsonl"

step "lustro net export --har: the handshake carries the WebSocket"
lustro net export --har "$OUT/export-ws.har"
check ws-har "$RULES" < "$OUT/export-ws.har"

# The Logs tab reads the sample's logcat from the start of its process, so the
# first poll has the line the sample writes before Lustro.start(). After a clear,
# the cursor that clear returns gets the lines since, with no dropped count.
step "lustro --json schema logs"
lustro --json schema logs > "$OUT/schema-logs.json"
check schema-logs < "$OUT/schema-logs.json"
echo "matches the CLI's copy ($(wc -c < "$OUT/schema-logs.json") bytes)"

lustro_to logs-startup.json api GET logs/entries --param 'tag=^LustroSample$'
check logs-startup < "$OUT/logs-startup.json"

step "lustro api POST logs/clear, log a repeated warning and an exception, and poll from the clear"
lustro api POST logs/clear > "$OUT/logs-clear.json"
cat "$OUT/logs-clear.json"
cursor="$("$OUT/venv/bin/python" -c 'import json,sys; print(json.load(sys.stdin)["cursor"])' < "$OUT/logs-clear.json")"
adb shell am start -W --activity-single-top -n "$PACKAGE/.MainActivity" --es request "'Log the same line 20 times'"
adb shell am start -W --activity-single-top -n "$PACKAGE/.MainActivity" --es request "'Log an exception'"
sleep 2
lustro_to logs-delta.json api GET logs/entries --param 'tag=^SampleSync$' --param "cursor=$cursor"
check logs-delta < "$OUT/logs-delta.json"

lustro_to logs-errors.txt api GET logs/text --param level=E --param "cursor=$cursor"
check logs-text < "$OUT/logs-errors.txt"

# The Preferences tab lists the sample's files: two that it registers, and the
# library-like one that the tab finds on disk. An edit through the API is in the
# app's file, an app change moves the file's cursor, and a read-only file refuses
# an edit.
step "lustro --json schema prefs"
lustro --json schema prefs > "$OUT/schema-prefs.json"
check schema-prefs < "$OUT/schema-prefs.json"
echo "matches the CLI's copy ($(wc -c < "$OUT/schema-prefs.json") bytes)"

adb shell am start -W --activity-single-top -n "$PACKAGE/.MainActivity" --es request "'Reset the demo preferences'"
sleep 1
lustro_to prefs-files.json api GET prefs/files
check prefs-files < "$OUT/prefs-files.json"

lustro_to prefs-reset.json api GET prefs/entries --param file=sample_settings
check prefs-reset < "$OUT/prefs-reset.json"

step "lustro api POST prefs/edit, then count a launch in the app, and poll with the edit's cursor"
lustro_to prefs-edit.json api POST prefs/edit --body '{"file":"sample_settings","key":"username","type":"string","value":"e2e"}'
check prefs-edit < "$OUT/prefs-edit.json"
adb shell run-as "$PACKAGE" cat shared_prefs/sample_settings.xml | grep -q '<string name="username">e2e</string>'
echo "the app's file holds the new value"
lustro_to prefs-after-edit.json api GET prefs/entries --param file=sample_settings
cursor="$("$OUT/venv/bin/python" -c 'import json,sys; print(json.load(sys.stdin)["cursor"])' < "$OUT/prefs-after-edit.json")"
adb shell am start -W --activity-single-top -n "$PACKAGE/.MainActivity" --es request "'Count a launch'"
sleep 1
lustro_to prefs-delta.json api GET prefs/entries --param file=sample_settings --param "cursor=$cursor"
check prefs-delta "$OUT/prefs-reset.json" < "$OUT/prefs-delta.json"

step "lustro api POST prefs/edit to the read-only session file"
if lustro api POST prefs/edit --body '{"file":"sample_session","key":"session_id","type":"string","value":"e2e"}' 2> "$OUT/prefs-read-only.txt"; then
  echo "error: the read-only file took the edit" >&2
  exit 1
fi
cat "$OUT/prefs-read-only.txt"
grep -q "^error: 403 forbidden" "$OUT/prefs-read-only.txt"

# The SQLite tab lists the sample's databases: the notes database through the
# app's open handle, the catalog file, and the library-like file that discovery
# finds. Pages follow the cursor, and a page reads each cell by its own type: a
# long text is cut, a 3 MB blob is only its size, and a blob in a TEXT column
# is a blob. A sort that is not a column is refused.
step "lustro --json schema sqlite"
lustro --json schema sqlite > "$OUT/schema-sqlite.json"
check schema-sqlite < "$OUT/schema-sqlite.json"
echo "matches the CLI's copy ($(wc -c < "$OUT/schema-sqlite.json") bytes)"

adb shell am start -W --activity-single-top -n "$PACKAGE/.MainActivity" --es request "'Reset the demo databases'"
sleep 3
lustro_to sqlite-databases.json api GET sqlite/databases
check sqlite-databases < "$OUT/sqlite-databases.json"

lustro_to sqlite-tables.json api GET sqlite/tables --param db=notes
check sqlite-tables < "$OUT/sqlite-tables.json"

step "lustro api GET sqlite/rows: the first three pages of 500 notes"
lustro --json api GET sqlite/rows --param db=notes --param table=notes --param limit=500 > "$OUT/sqlite-rows-1.json"
next() { "$OUT/venv/bin/python" -c 'import json,sys; print(json.load(sys.stdin)["nextCursor"])' < "$1"; }
lustro --json api GET sqlite/rows --param db=notes --param table=notes --param limit=500 --param "cursor=$(next "$OUT/sqlite-rows-1.json")" > "$OUT/sqlite-rows-2.json"
lustro --json api GET sqlite/rows --param db=notes --param table=notes --param limit=500 --param "cursor=$(next "$OUT/sqlite-rows-2.json")" > "$OUT/sqlite-rows-3.json"
check sqlite-rows-1 < "$OUT/sqlite-rows-1.json"
check sqlite-rows-3 < "$OUT/sqlite-rows-3.json"

lustro_to sqlite-imported.json --json api GET sqlite/rows --param db=notes --param table=notes --param "filter=Imported note"
check sqlite-imported < "$OUT/sqlite-imported.json"

step "lustro api GET sqlite/rows with a sort that is not a column"
if lustro api GET sqlite/rows --param db=notes --param table=notes --param "sort=score; DROP TABLE notes" 2> "$OUT/sqlite-bad-sort.txt"; then
  echo "error: the tab took the sort" >&2
  exit 1
fi
cat "$OUT/sqlite-bad-sort.txt"
grep -q "^error: 400 bad_request" "$OUT/sqlite-bad-sort.txt"

# POST query runs a client's statement read-only: SQLite refuses a DELETE there.
# POST exec writes to the writable notes database only, and both routes refuse a
# BEGIN, which would leave a transaction on a worker thread of the server.
lustro_to sqlite-query.json --json api POST sqlite/query --body '{"db":"notes","sql":"SELECT count(*) AS notes, max(length(body)) AS longest FROM notes"}'
check sqlite-query < "$OUT/sqlite-query.json"

step "lustro api POST sqlite/query with a DELETE"
if lustro api POST sqlite/query --body '{"db":"notes","sql":"WITH x AS (SELECT 1) DELETE FROM notes"}' 2> "$OUT/sqlite-query-delete.txt"; then
  echo "error: query took the DELETE" >&2
  exit 1
fi
cat "$OUT/sqlite-query-delete.txt"
grep -q "^error: 403 forbidden" "$OUT/sqlite-query-delete.txt"

step "lustro api POST sqlite/exec with a BEGIN, and to the read-only catalog"
if lustro api POST sqlite/exec --body '{"db":"notes","sql":"/* x */ BEGIN"}' 2> "$OUT/sqlite-exec-begin.txt"; then
  echo "error: exec took the BEGIN" >&2
  exit 1
fi
cat "$OUT/sqlite-exec-begin.txt"
grep -q "^error: 400 bad_request" "$OUT/sqlite-exec-begin.txt"
if lustro api POST sqlite/exec --body '{"db":"catalog","sql":"DELETE FROM products"}' 2> "$OUT/sqlite-exec-catalog.txt"; then
  echo "error: exec wrote to the read-only catalog" >&2
  exit 1
fi
cat "$OUT/sqlite-exec-catalog.txt"
grep -q "^error: 403 forbidden" "$OUT/sqlite-exec-catalog.txt"

lustro_to sqlite-exec.json --json api POST sqlite/exec --body '{"db":"notes","sql":"UPDATE notes SET title = '"'"'Renamed by exec'"'"' WHERE id = 1"}'
check sqlite-exec < "$OUT/sqlite-exec.json"

# The WorkManager tab lists the sample's demo work, labelled with its unique
# names, and the events of each run: a success, a failed chain step and the
# step after it, a retry, and what a worker threw. Cancel finds the delayed
# worker by its unique name before it runs, and a name without work is a 404.
step "lustro --json schema work"
lustro --json schema work > "$OUT/schema-work.json"
check schema-work < "$OUT/schema-work.json"
echo "matches the CLI's copy ($(wc -c < "$OUT/schema-work.json") bytes)"

adb shell am start -W --activity-single-top -n "$PACKAGE/.MainActivity" --es request "'Enqueue the demo work'"
step "lustro api GET work/items, until the demo chains have finished"
for _ in $(seq 30); do
  lustro --json api GET work/items > "$OUT/work-items.json"
  check work-items < "$OUT/work-items.json" 2> /dev/null && break
  sleep 1
done
cat "$OUT/work-items.json"
check work-items < "$OUT/work-items.json"

lustro_to work-events.json --json api GET work/events --param type=finished,exception,prerequisiteFailed
check work-events < "$OUT/work-events.json"

adb shell am start -W --activity-single-top -n "$PACKAGE/.MainActivity" --es request "'Enqueue a delayed worker'"
lustro_to work-cancel.json --json api POST work/cancel --body '{"name":"demo-delayed"}'
check work-cancel < "$OUT/work-cancel.json"

step "lustro api POST work/cancel with a unique name that has no work"
if lustro api POST work/cancel --body '{"name":"no-such-work"}' 2> "$OUT/work-cancel-none.txt"; then
  echo "error: the tab cancelled work of a name that has none" >&2
  exit 1
fi
cat "$OUT/work-cancel-none.txt"
grep -q "^error: 404 not_found" "$OUT/work-cancel-none.txt"

step "The CLI end to end passed"
