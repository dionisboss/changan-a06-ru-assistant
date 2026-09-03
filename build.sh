#!/usr/bin/env bash
# Build the Russian voice-assistant mod for Changan A06 (C390).
#
#   ./build.sh [path/to/SpeechAssistant.orig.apk] [out.apk]
#
# The STOCK SpeechAssistant.apk is proprietary (Changan) and is NOT shipped here — pull it from
# your own head unit:
#   SER=$(adb devices | awk '/device$/{print $1; exit}')
#   P=$(adb -s "$SER" shell pm path com.incall.apps.speechassistant | head -1 | sed 's/package://' | tr -d '\r')
#   adb -s "$SER" pull "$P" ./SpeechAssistant.orig.apk
#
# Requirements: JDK 17, Android SDK (platforms;android-34 + build-tools;34.0.0). Set ANDROID_HOME
# and JAVA_HOME if they aren't auto-detected.
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="${1:-$HERE/SpeechAssistant.orig.apk}"
OUT="${2:-$HERE/out/speechassistant-ru2zh.apk}"

if [ ! -f "$SRC" ]; then
  echo "Stock SpeechAssistant.apk not found: $SRC"
  echo "Pull it from your car first (see the comment block at the top of this script)."
  exit 1
fi
mkdir -p "$(dirname "$OUT")"

echo "[1/2] compiling com/stand/** -> classes7.dex"
bash "$HERE/stand/asr-android/build_dex.sh"

echo "[2/2] patching + repacking + signing (platform test-keys)"
# Flags = the shipped v1.0.2 "online" profile: GigaAM ASR + TeraTTS + ru2zh->stock NLU, real Changan
# cloud reachable (NATIVE_CLOUD) with MyMemory translation, our RU text kept in dialog (CAPTURE).
PROFILE=car HOST=127.0.0.1:8080 RUSSIAN_ASR=1 NO_CN_SR=1 NO_CN_ENGINE=0 \
  VOSK=1 PIPER=1 TERA=1 GIGAAM=1 TTS_HOOK=0 TTS_REWRITE=1 \
  NATIVE_CLOUD=1 CAPTURE=1 \
  bash "$HERE/stand/build_sa.sh" "$SRC" "$OUT"

echo
echo "built: $OUT"
echo "install:  adb push \"$OUT\" /data/local/tmp/sa.apk && adb shell pm install -r -d -g -t /data/local/tmp/sa.apk"
