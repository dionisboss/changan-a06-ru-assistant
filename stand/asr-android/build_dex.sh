#!/usr/bin/env bash
# Compile com/stand/** (RuBridge + Ru2Zh + GigaAsr + TeraTts) + sherpa-onnx java-api into
# build/dex7/classes.dex (=> classes7.dex inside the patched SpeechAssistant.apk).
set -euo pipefail
D="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AJAR="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/platforms/android-34/android.jar"
BT="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}/build-tools/34.0.0"
[ -x "${JAVA_HOME:-}/bin/javac" ] || export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH="$JAVA_HOME/bin:$PATH"
rm -rf "$D/build/dex7" "$D/build/stubs"; mkdir -p "$D/build/dex7" "$D/build/stubs"
# sherpa-onnx java sources (GigaAM ASR runs on sherpa-onnx); include if present.
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
javac -source 17 -target 17 -encoding UTF-8 -d "$D/build/dex7" \
  -classpath "$AJAR:$ORT_JAR$STUB_CP" \
  $(find "$D/src/com/stand" -name '*.java') $SHERPA_SRC
"$BT/d8" --min-api 29 --lib "$AJAR" --output "$D/build/dex7" \
  $(find "$D/build/dex7" -name '*.class') ${ORT_JAR:+"$ORT_JAR"}
echo "classes7.dex: $(ls -la "$D/build/dex7/classes.dex" | awk '{print $5}') bytes"
