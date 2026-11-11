#!/usr/bin/env bash
# Checks that the release build of an app finds a DebugTab subclass outside
# src/debug. It puts one in src/release, then in src/main, and each time expects
# the lint that assembleRelease runs (lintVital, which runs only fatal issues) to
# fail with LustroDebugUsageInRelease, once. That lint loads the check only
# because lustro-noop ships it too: the app takes lustro in debug only.
#
# Run it after the Lustro artifacts are in the local Maven repository (see
# README.md). The arguments go to Gradle, such as the -P overrides of a CI job.
set -euo pipefail

cd "$(dirname "$0")"
TASK=":app:lintVitalWithLustroRelease"
PACKAGE_DIR="kotlin/io/github/twinsen81/lustro/consumercheck"
LOG="$(mktemp)"
leak=""

cleanup() {
    if [[ -n "$leak" ]]; then
        rm -f "$leak"
        rmdir -p "$(dirname "$leak")" 2>/dev/null || true
    fi
    rm -f "$LOG"
}
trap cleanup EXIT

for source_set in release main; do
    leak="app/src/$source_set/$PACKAGE_DIR/LeakedTab.kt"
    mkdir -p "$(dirname "$leak")"
    cat > "$leak" <<'EOF'
package io.github.twinsen81.lustro.consumercheck

import io.github.twinsen81.lustro.DebugTab

class LeakedTab : DebugTab() {
    override val id = "leaked"
    override val title = "Leaked"
    override val icon = "x"
}
EOF

    if ./gradlew "$TASK" "$@" --console=plain > "$LOG" 2>&1; then
        cat "$LOG"
        echo "FAIL: $TASK passed with a DebugTab in src/$source_set" >&2
        exit 1
    fi
    # The text around a report differs between AGP versions, and some repeat
    # the first report. The count in lint's summary does not: the app has no
    # other fatal issue, so a second copy of the check would make it 2.
    if ! grep -q 'LeakedTab\.kt:[0-9]*: Error: .*\[LustroDebugUsageInRelease' "$LOG"; then
        cat "$LOG"
        echo "FAIL: $TASK failed, but not on the DebugTab in src/$source_set" >&2
        exit 1
    fi
    if ! grep -Eq 'Lint found 1 errors?[ ,.]' "$LOG"; then
        cat "$LOG"
        echo "FAIL: $TASK did not report exactly one error for the DebugTab in src/$source_set" >&2
        exit 1
    fi
    echo "OK: $TASK reports the DebugTab in src/$source_set"

    rm "$leak"
    rmdir -p "$(dirname "$leak")" 2>/dev/null || true
    leak=""
done
