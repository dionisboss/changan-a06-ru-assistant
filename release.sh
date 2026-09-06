#!/usr/bin/env bash
# Build the mod and publish it as a GitHub Release (the APK is NEVER committed to git — it is
# attached to the release as a download).
#
#   ./release.sh <version> [path/to/SpeechAssistant.orig.apk]
#   e.g. ./release.sh 1.0.3 ./SpeechAssistant.orig.apk
#
# Needs: gh (GitHub CLI, logged in), JDK 17, Android SDK, the stock APK and the model files (MODELS.md).
set -euo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VER="${1:?version, e.g. 1.0.3}"; SRC="${2:-$HERE/SpeechAssistant.orig.apk}"
TAG="v$VER"; NAME="speechassistant-ru-$TAG.apk"; OUT="$HERE/out/$NAME"

[ -f "$SRC" ] || { echo "stock SpeechAssistant.apk not found: $SRC (pull it from your car, see README)"; exit 1; }
command -v gh >/dev/null || { echo "gh (GitHub CLI) is required: brew install gh && gh auth login"; exit 1; }

echo "[release] building $NAME"
"$HERE/build.sh" "$SRC" "$OUT"
( cd "$HERE/out" && shasum -a 256 "$NAME" > "$NAME.sha256" )
echo "[release] $(du -h "$OUT" | cut -f1)  sha256: $(cut -d' ' -f1 "$OUT.sha256")"

echo "[release] tests"
sh "$HERE/ru2zh/translate-task/tests/run_tests.sh" | tail -1

if git -C "$HERE" rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
  echo "[release] tag $TAG exists"
else
  git -C "$HERE" tag -a "$TAG" -m "Release $TAG"
  git -C "$HERE" push origin "$TAG"
fi

NOTES="Russian voice assistant mod for Changan A06 (C390) — $TAG.

Install (no root):
\`\`\`
adb push $NAME /data/local/tmp/sa.apk
adb shell pm install -r -d -g -t /data/local/tmp/sa.apk
adb shell am force-stop com.incall.apps.speechassistant
\`\`\`
Wake with «нихао» or the steering-wheel key. Command list: COMMANDS.md. Revert: \`adb shell pm uninstall com.incall.apps.speechassistant\`.
SHA-256: $(cut -d' ' -f1 "$OUT.sha256")"

if gh release view "$TAG" -R "$(git -C "$HERE" remote get-url origin)" >/dev/null 2>&1; then
  gh release upload "$TAG" "$OUT" "$OUT.sha256" --clobber
else
  gh release create "$TAG" "$OUT" "$OUT.sha256" --title "$TAG" --notes "$NOTES"
fi
echo "[release] done: $(gh release view "$TAG" --json url -q .url)"
