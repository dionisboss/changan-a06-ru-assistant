#!/bin/sh
set -eu
TASK_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TEST_DIR="$TASK_ROOT/tests"
RESULT_DIR="$TASK_ROOT/out/commands"
JAVAC_BIN=$(command -v javac || true)
if [ -z "$JAVAC_BIN" ]; then
    for candidate in /usr/lib/jvm/*/bin/javac; do
        if [ -x "$candidate" ]; then JAVAC_BIN=$candidate; break; fi
    done
fi
if [ -z "$JAVAC_BIN" ]; then echo 'JDK 11+ with javac required' >&2; exit 1; fi
JAVA_BIN="$(dirname "$JAVAC_BIN")/java"
mkdir -p "$RESULT_DIR/upstream-regression-classes" "$RESULT_DIR/e07-regression-classes"
if [ "$#" -gt 0 ]; then
    "$JAVAC_BIN" --release 11 -encoding UTF-8 -d "$RESULT_DIR/upstream-regression-classes" "$1" "$TEST_DIR/Ru2ZhTest.java"
    "$JAVA_BIN" -cp "$RESULT_DIR/upstream-regression-classes" com.stand.bridge.Ru2ZhTest "$TEST_DIR/fixtures/tests.tsv" "$TEST_DIR/fixtures/chatter.txt"
fi
# Generate E07 expectations separately; never edit or silently relax the upstream fixtures.
python3 - "$TEST_DIR/fixtures/tests.tsv" "$TEST_DIR/fixtures/e07-policy.tsv" "$RESULT_DIR/tests-e07.tsv" <<'PYCODE'
import sys
from pathlib import Path
original,policy,target=map(Path,sys.argv[1:])
changes={p[0]:(p[1],p[2]) for line in policy.read_text().splitlines() if line and not line.startswith('#') for p in [line.split('\t')]}
seen=set(); result=[]
for line in original.read_text().splitlines():
    p=line.split('\t')
    if p[0] in changes:
        value,reason=changes[p[0]]; seen.add(p[0]); print('E07 POLICY: '+p[0]+' -> '+value+'; '+reason)
        p[1]=value; line='\t'.join(p)
    result.append(line)
assert seen==set(changes), 'Policy override did not match an upstream fixture'
target.write_text('\n'.join(result)+'\n')
PYCODE
"$JAVAC_BIN" --release 11 -encoding UTF-8 -d "$RESULT_DIR/e07-regression-classes" "$TASK_ROOT/src/com/stand/bridge/Ru2Zh.java" "$TEST_DIR/Ru2ZhTest.java" "$TEST_DIR/Ru2ZhSafetyTest.java" "$TEST_DIR/Ru2ZhE07Test.java"
"$JAVA_BIN" -cp "$RESULT_DIR/e07-regression-classes" com.stand.bridge.Ru2ZhE07Test --legacy "$RESULT_DIR/tests-e07.tsv" "$TEST_DIR/fixtures/chatter.txt"
"$JAVA_BIN" -cp "$RESULT_DIR/e07-regression-classes" com.stand.bridge.Ru2ZhSafetyTest "$TEST_DIR/fixtures/tests.tsv"
"$JAVA_BIN" -cp "$RESULT_DIR/e07-regression-classes" com.stand.bridge.Ru2ZhE07Test "$TEST_DIR/fixtures/e07-compatibility.tsv"
