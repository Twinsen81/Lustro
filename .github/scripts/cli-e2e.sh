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

# The rule serves the sample's "GET /get" request, so the step needs no internet.
pattern="https://httpbingo.org/get"
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

step "The CLI end to end passed"
