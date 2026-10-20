#!/usr/bin/env bash
# Runs the watch app's network code (Link, Proto, Prefs) on a desktop JVM against the
# real PC receiver in --simulate mode. Needs JDK 11+ and Python 3. No Android SDK needed:
# the few Android classes involved are replaced by the stand-ins in stubs/.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
APP="$ROOT/watch/src/com/wristmic/app"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

javac -d "$OUT" -encoding UTF-8 \
    $(find "$HERE/stubs" -name '*.java') \
    "$APP/Config.java" "$APP/Proto.java" "$APP/Prefs.java" "$APP/Utterance.java" "$APP/Link.java" \
    "$HERE/src/com/wristmic/app/LinkTest.java"
java -Dwristmic.root="$ROOT" -cp "$OUT" com.wristmic.app.LinkTest
