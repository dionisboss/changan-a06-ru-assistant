#!/bin/sh
set -eu
base=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
build=$(mktemp -d /tmp/e07-runtime-tests.XXXXXX)
trap 'rm -rf "$build"' EXIT HUP INT TERM
javac_bin=${JAVA_HOME:+$JAVA_HOME/bin/}javac
java_bin=${JAVA_HOME:+$JAVA_HOME/bin/}java
"$javac_bin" -d "$build" "$base/src/com/stand/bridge/Utterance.java" "$base/tests/UtteranceTest.java"
"$java_bin" -cp "$build" com.stand.bridge.UtteranceTest
"$javac_bin" -encoding UTF-8 -d "$build" "$base/src/com/stand/bridge/CommandHelp.java" "$base/src/com/stand/bridge/Ru2Zh.java" "$base/tests/CommandHelpTest.java"
"$java_bin" -cp "$build" com.stand.bridge.CommandHelpTest
"$javac_bin" -encoding UTF-8 -d "$build" "$base/src/com/stand/tts/tera/TeraAccents.java" "$base/src/com/stand/tts/tera/RuAccentDict.java" "$base/tests/AccentTest.java"
"$java_bin" -cp "$build" com.stand.tts.tera.AccentTest
python3 "$base/tests/run_reply_tests.py"
python3 "$base/tests/run_tts_playback_tests.py"
python3 "$base/tests/run_trunk_chain_tests.py"
