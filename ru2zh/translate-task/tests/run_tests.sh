#!/bin/sh
# Offline unit tests for the ru2zh command mapper (no car, no models — just a JDK).
# Compiles the PRODUCTION source stand/asr-android/src/com/stand/bridge/Ru2Zh.java (the file that ships
# in classes7.dex) together with Ru2ZhTest.java, then checks:
#   tests.tsv   — "фраза<TAB>ожидаемый_ZH[<TAB>UNSAFE]"; NULL = must not be a command (chat)
#   chatter.txt — small talk / questions; none may become a command
set -e
cd "$(dirname "$0")"
SRC=../../../stand/asr-android/src/com/stand/bridge/Ru2Zh.java
# Resolve a JDK with javac (any JDK 11+ works). Override with JAVA_HOME.
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JH="$JAVA_HOME"
else
  JH=""
  for c in "$(/usr/libexec/java_home 2>/dev/null || true)" \
           /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk /usr/local/opt/openjdk@17 \
           /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17; do
    if [ -n "$c" ] && [ -x "$c/bin/javac" ]; then JH="$c"; break; fi
  done
fi
if [ -n "$JH" ]; then JAVAC="$JH/bin/javac"; JAVA="$JH/bin/java"; else JAVAC=javac; JAVA=java; fi

rm -rf build && mkdir -p build
"$JAVAC" -encoding UTF-8 -d build "$SRC" Ru2ZhTest.java
"$JAVA" -cp build com.stand.bridge.Ru2ZhTest tests.tsv chatter.txt
