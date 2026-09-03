#!/usr/bin/env bash
# Compile com/stand/** (vosk + tts) + sherpa-onnx java-api (+ vosk/jna jars) into
# build/vosk7/classes.dex (=> classes7.dex).
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Portable toolchain resolution (override any of these via env):
#   ANDROID_HOME  — Android SDK root (must have platforms/android-34 + build-tools)
#   JAVA_HOME     — JDK 17
if [ -z "${ANDROID_HOME:-}" ]; then
  ANDROID_HOME="${ANDROID_SDK_ROOT:-}"
  if [ -z "$ANDROID_HOME" ]; then
    for c in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools; do
      if [ -d "$c" ]; then ANDROID_HOME="$c"; break; fi
    done
  fi
fi
export ANDROID_HOME
# Resolve a JDK 17 that actually has javac (macOS java_home can point at a JRE-only Java 8).
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/javac" ]; then
  JAVA_HOME=""
  for c in "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" \
           /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 \
           /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17 /usr/lib/jvm/temurin-17-jdk; do
    if [ -n "$c" ] && [ -x "$c/bin/javac" ]; then JAVA_HOME="$c"; break; fi
  done
fi
[ -n "$JAVA_HOME" ] || { echo "JDK 17 with javac not found — set JAVA_HOME"; exit 1; }
export JAVA_HOME; export PATH="$JAVA_HOME/bin:$PATH"
AJAR="${ANDROID_JAR:-$ANDROID_HOME/platforms/android-34/android.jar}"
BT="${BUILD_TOOLS_DIR:-$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)}"
[ -f "$AJAR" ] || { echo "android.jar not found at $AJAR — set ANDROID_HOME (needs platforms;android-34)"; exit 1; }
[ -x "$BT/d8" ] || { echo "d8 not found in $BT — install Android build-tools (34.0.0+)"; exit 1; }
rm -rf "$D/build/vosk7" "$D/build/stubs"; mkdir -p "$D/build/vosk7" "$D/build/stubs"
# sherpa-onnx java sources are optional (only needed for Piper TTS); include if present.
SHERPA_SRC=""; [ -d "$D/sherpa-src" ] && SHERPA_SRC="$(find "$D/sherpa-src" -name '*.java')"
# COMPILE-ONLY stubs of the app's own interfaces (ICaTts/ICaStreamTts/ICaTtsCallback for PiperCaTts).
# Compiled to build/stubs and put ONLY on the classpath — NOT fed to d8 (would duplicate app classes).
STUB_CP=""
if [ -d "$D/src-stubs" ]; then
  javac -source 17 -target 17 -d "$D/build/stubs" -classpath "$AJAR" $(find "$D/src-stubs" -name '*.java')
  STUB_CP=":$D/build/stubs"
fi
# onnxruntime-android Java API (ai.onnxruntime.*) for TeraTTS — classes only; libonnxruntime.so is the
# stock one, we bundle just libonnxruntime4j_jni.so (build_sa PIPER block).
ORT_JAR="$D/libs/ort-android-classes.jar"; [ -f "$ORT_JAR" ] || ORT_JAR=""
javac -source 17 -target 17 -d "$D/build/vosk7" \
  -classpath "$AJAR:$D/libs/vosk-classes.jar:$D/libs/jna-classes.jar:$ORT_JAR$STUB_CP" \
  $(find "$D/src/com/stand" -name '*.java') $SHERPA_SRC
"$BT/d8" --min-api 29 --lib "$AJAR" --output "$D/build/vosk7" \
  $(find "$D/build/vosk7" -name '*.class') "$D/libs/vosk-classes.jar" "$D/libs/jna-classes.jar" ${ORT_JAR:+"$ORT_JAR"}
echo "classes7.dex: $(ls -la "$D/build/vosk7/classes.dex" | awk '{print $5}') bytes"
