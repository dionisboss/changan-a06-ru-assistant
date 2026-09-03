# Shared environment for the local test stand. `source` this.
# Toolchain
# JDK 17 + Android SDK. Override by exporting JAVA_HOME / ANDROID_HOME before running.
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-}/bin/java" ]; then
  JAVA_HOME=""
  for c in "$(/usr/libexec/java_home -v 17 2>/dev/null || true)" \
           /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 \
           /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17 /usr/lib/jvm/temurin-17-jdk; do
    if [ -n "$c" ] && [ -x "$c/bin/javac" ]; then JAVA_HOME="$c"; break; fi
  done
fi
export JAVA_HOME
if [ -z "${ANDROID_HOME:-}" ]; then
  for c in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk" /opt/homebrew/share/android-commandlinetools /usr/local/share/android-commandlinetools; do
    if [ -d "$c" ]; then ANDROID_HOME="$c"; break; fi
  done
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# Build-tools (pick highest installed)
BT_DIR="$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)"
export APKSIGNER="$BT_DIR/apksigner"
export ZIPALIGN="$BT_DIR/zipalign"

# Project paths
STAND_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export STAND_DIR
export PROJ_DIR="$(cd "$STAND_DIR/.." && pwd)"
export KEY_DIR="$PROJ_DIR/tools/platform-key"
export PLATFORM_PK8="$KEY_DIR/platform.pk8"
export PLATFORM_CERT="$KEY_DIR/platform.x509.pem"
export UBER_SIGNER="$PROJ_DIR/tools/uber-apk-signer.jar"

# Emulator / AVD
export AVD_NAME="${AVD_NAME:-changan_test}"
export SYS_IMAGE="${SYS_IMAGE:-system-images;android-34;default;arm64-v8a}"
# SAFETY: pin every adb command in the stand to the emulator, never the real car.
# Override with `ANDROID_SERIAL=<real-serial> ./stand.sh ...` only when you mean it.
export ANDROID_SERIAL="${ANDROID_SERIAL:-emulator-5554}"

# Default app targets (override on CLI)
export SA_APK="$PROJ_DIR/aiassist/SpeechAssistant.apk"
export AIA_APK="$PROJ_DIR/aiassist/IncallAIassist.apk"
