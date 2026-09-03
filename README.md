# Russian Voice Assistant for Changan A06 (C390)

An offline-first Russian voice assistant modification for the **Changan A06 / C390** head unit
(MediaTek MT6897, Android 14 Automotive). It replaces the recognizer and voice of the stock
assistant (`com.incall.apps.speechassistant`) with Russian, while riding the stock NLU / actuation
pipeline so real car commands keep working.

**Author:** Voronov Aleksei Sergeevich · **Version:** 1.0.2 · Distributed free of charge.
Independent modification — **not affiliated with, endorsed by, or produced by Changan Automobile.**

## What it does

- **Offline Russian ASR** — [GigaAM‑v3](https://github.com/salute-developers/GigaAM) CTC (sherpa‑onnx),
  full‑utterance decode. Open‑vocabulary + a Levenshtein fuzzy‑correction pass for command words.
- **Russian TTS** — [TeraTTS](https://github.com/Tera2Space/TeraTTS) (`ru_f2`, ONNX Runtime),
  registered as a native TTS engine so the whole assistant speaks Russian.
- **Command routing (`ru2zh`)** — a rule‑based Russian→Chinese mapper turns recognized phrases into
  the stock NLU's intents and injects them, so climate / seats / windows / media / lights / etc.
  actuate through the factory pipeline (and future OTAs).
- **Online conversational path (optional)** — free‑form questions (weather, chit‑chat) are translated
  RU→ZH via [MyMemory](https://mymemory.translated.net/), sent to the real Changan Dubhe cloud, and the
  answer is translated ZH→RU and spoken. The device signs its own request; no external server needed.
- **One‑step self‑install** — the app runs as `uid=system` on Permissive SELinux, so on first launch it
  registers its TTS engine and sets the default wake word itself; no manual `adb` config editing.

## How it works (short)

The stock `SpeechAssistant.apk` is disassembled (baksmali), a handful of methods are patched to tap the
ASR audio, feed our recognized text into the NLU, and route TTS through our engine; our own code ships
as an extra `classes7.dex` plus the model assets and JNI libs. The result is re‑zipped, zip‑aligned and
signed with the **public AOSP test‑keys** — the C390 firmware is itself signed with those keys, so the
patched app installs as a normal system‑app update **without root**.

## Requirements

- A Changan A06 / C390 head unit whose firmware is signed with AOSP test‑keys (stock for C390), reachable
  over `adb` (USB or network). **No root needed.**
- Build host: **JDK 17**, **Android SDK** with `platforms;android-34` and `build-tools;34.0.0`, `adb`.
- The **stock `SpeechAssistant.apk`** from *your* device (proprietary — not included here, see below).

## Build

1. Pull the stock APK from your car (proprietary Changan component — you must supply your own copy):

   ```sh
   SER=$(adb devices | awk '/device$/{print $1; exit}')
   P=$(adb -s "$SER" shell pm path com.incall.apps.speechassistant | head -1 | sed 's/package://' | tr -d '\r')
   adb -s "$SER" pull "$P" ./SpeechAssistant.orig.apk
   ```

   > Two model files (>100 MB) are not tracked by git — see [MODELS.md](MODELS.md) and place them at the
   > listed paths before building.

2. Build (auto‑detects `JAVA_HOME` / `ANDROID_HOME`; export them if needed):

   ```sh
   ./build.sh ./SpeechAssistant.orig.apk out/speechassistant-ru2zh.apk
   ```

## Install

```sh
adb push out/speechassistant-ru2zh.apk /data/local/tmp/sa.apk
adb shell pm install -r -d -g -t /data/local/tmp/sa.apk
adb shell am force-stop com.incall.apps.speechassistant
```

Wake with **«нихао» (你好)** and speak, e.g. «включи климат», «какая погода в шанхае».

## Tests

The `ru2zh` command mapper has offline unit tests (no car, no models, just a JDK) — 640+ phrase→intent
cases plus a set of chatter that must *not* trigger a command:

```sh
sh ru2zh/translate-task/tests/run_tests.sh   # -> PASS=684 FAIL=0
```

## Uninstall / revert to factory

```sh
adb shell pm uninstall com.incall.apps.speechassistant   # removes the update -> factory /system app
```

On first run the mod backs up the TTS config it edits to `tts_config.txt.bak` next to it, so the factory
voice can be restored from that backup. A factory reset of the head unit always restores the original as
well (the mod lives in `/data`, the factory app in `/system`).

## Layout

```
build.sh                     one-shot: compile classes7 + patch/repack/sign
stand/build_sa.sh            baksmali -> patch smali -> add classes7/assets/libs -> zipalign -> sign
stand/env.sh                 toolchain + platform-key paths
stand/asr-android/           our code + models
  src/com/stand/**           VoskBridge (ASR tap, ru2zh, cloud), GigaAsr, TeraTts, Translate, ...
  build_dex.sh               javac + d8 -> classes7.dex
  gigaam/                    GigaAM-v3 int8 ONNX model + tokens
  piper/jni/arm64-v8a/       sherpa-onnx + onnxruntime JNI libs
  libs/ src-stubs/ sherpa-src/  compile-time deps
tools/                       baksmali/smali/uber-apk-signer jars, AOSP test-keys, TeraTTS assets
ru2zh/translate-task/ru2zh_extended.java   the ru2zh command table (spliced into VoskBridge.java)
```

## License

Source‑available under the **PolyForm Noncommercial License 1.0.0** — **noncommercial use only**
(see [LICENSE](LICENSE)). The modification is distributed free of charge. Third‑party components
(models, JNI libs, tooling) keep their own licenses — see [THIRD_PARTY.md](THIRD_PARTY.md).

**No warranty.** Voice control of a vehicle can fail or misrecognize commands; use at your own risk.
