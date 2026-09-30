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
# directory. adb forwards host port 8080 to the device, so run one device at a
# time. The run replaces the sample's mock rules with its own. The output
# directory keeps each command's output and, at exit, logcat.
set -euo pipefail

cd "$(dirname "$0")/../.."
OUT="${CLI_E2E_OUT:-build/cli-e2e}"
PYTHON="${PYTHON:-python3}"
PACKAGE="io.github.twinsen81.lustro.sample"
CHECK=".github/scripts/cli_e2e_check.py"
RULES="$OUT/rules-to-sync.json"

rm -rf "$OUT"
mkdir -p "$OUT"
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
export PATH="$PWD/$OUT/venv/bin:$PATH"

# Also runs `adb forward` for the discovered port, but reports a failed forward
# only with --device, so the check reads the forward list.
lustro_to open.txt open --print-only
check open < "$OUT/open.txt"
port="$(printf '%s\n' "$ready" | sed -n 's|.*endpoint=http://[^ ]*:\([0-9][0-9]*\) .*|\1|p' | tail -n 1)"
if ! adb forward --list | grep -q "^$(adb get-serialno) tcp:$port tcp:$port\$"; then
  echo "error: lustro open did not forward port $port; is another process listening on it?" >&2
  exit 1
fi

lustro_to meta.json --json meta
check meta < "$OUT/meta.json"

step "lustro --json schema network"
lustro --json schema network > "$OUT/schema-network.json"
check schema-network < "$OUT/schema-network.json"
echo "matches the CLI's copy ($(wc -c < "$OUT/schema-network.json") bytes)"

# The rule serves the sample's "GET /get" request, so the step needs no internet.
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

lustro_to net-list.json --json net list
check list < "$OUT/net-list.json"

step "Fire the sample's GET /get and wait for it in net list"
adb shell am start -W --activity-single-top -n "$PACKAGE/.MainActivity" --es request "'GET /get'"
id=""
for _ in $(seq 30); do
  lustro --json net list > "$OUT/net-list-after.json"
  check list < "$OUT/net-list-after.json"
  id="$(check find "$RULES" < "$OUT/net-list-after.json" 2> /dev/null || true)"
  [ -n "$id" ] && break
  sleep 1
done
if [ -z "$id" ]; then
  cat "$OUT/net-list-after.json"
  echo "error: the fired request did not reach net list within 30 s" >&2
  exit 1
fi

lustro_to net-get.json --json net get "$id"
check transaction "$RULES" < "$OUT/net-get.json"

step "The CLI end to end passed"
