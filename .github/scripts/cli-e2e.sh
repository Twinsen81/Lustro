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
# directory. `lustro open` forwards local port 8080 to the app, or
# CLI_E2E_LOCAL_PORT when it is set; 0 lets adb choose a free port. The other
# commands find that forward, so none of them passes --port. The run replaces the
# sample's mock rules with its own and sets the device to stay awake while it
# charges. The output directory keeps each command's output and, at exit, logcat.
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
# fails, for example when another process holds the local port.
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

step "The CLI end to end passed"
