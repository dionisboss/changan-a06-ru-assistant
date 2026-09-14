#!/usr/bin/env python3
"""Exercise the production MicMix on a host JVM; no car, ADB or Android processes."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-26-openjdk"))
stubs = {
    "com/stand/bridge/SePcm.java": """package com.stand.bridge;
        // Extractor itself is checked using real OEM PcmConvertor by run_se_pcm_tests.py.
        public class SePcm {
            public static boolean fail;
            public static int calls;
            public static byte[] extract(byte[] frame, int length) throws java.io.IOException {
                calls++;
                if (fail || length < 2 || length > frame.length || length % 2 != 0)
                    throw new java.io.IOException("invalid frame");
                return java.util.Arrays.copyOf(frame, length);
            }
        }""",
    "android/content/Context.java": """package android.content;
        public class Context {
            public static final String AUDIO_SERVICE = "audio";
            public final android.media.AudioManager manager = new android.media.AudioManager();
            public Object getSystemService(String name) { return manager; }
            public Object getContentResolver() { return this; }
        }""",
    "android/provider/Settings.java": """package android.provider;
        public class Settings { public static class Global {
            public static final java.util.Map<String, Integer> values = new java.util.HashMap<>();
            public static boolean fail; public static int reads;
            public static int getInt(Object resolver, String name, int fallback) {
                reads++;
                if (fail) throw new SecurityException("settings unavailable");
                return values.getOrDefault(name, fallback);
            }
        }}""",
    "android/os/Looper.java": "package android.os; public class Looper { public static Looper getMainLooper() { return null; } }",
    "android/os/SystemClock.java": "package android.os; public class SystemClock { public static long now; public static long elapsedRealtime() { return now; } }",
    "android/os/Handler.java": """package android.os;
        public class Handler {
            public static final java.util.Queue<Runnable> queue = new java.util.ArrayDeque<>();
            public Handler(Looper looper) {}
            public boolean post(Runnable r) { queue.add(r); return true; }
            public boolean postDelayed(Runnable r, long ms) { return post(r); }
            public static void tick() { queue.remove().run(); }
        }""",
    "android/util/Log.java": """package android.util; public class Log {
        public static int i(String t, String m) { return 0; }
        public static int e(String t, String m) { return 0; }
        public static int e(String t, String m, Throwable e) { return 0; }
    }""",
    "android/media/AudioManager.java": """package android.media;
        public class AudioManager {
            public static final int MODE_NORMAL = 0;
            public int mode, result, registered, released, active; public boolean failMode;
            public int getMode() { if (failMode) throw new IllegalStateException("mode"); return mode; }
            public int registerAudioPolicy(android.media.audiopolicy.AudioPolicy p) {
                registered++; if (result == 0) active++; return result;
            }
            public void unregisterAudioPolicy(android.media.audiopolicy.AudioPolicy p) { released++; active--; }
        }""",
    "android/media/AudioTrack.java": """package android.media;
        public class AudioTrack {
            public static final int STATE_INITIALIZED = 1, WRITE_NON_BLOCKING = 1;
            public static AudioTrack latest;
            public static Runnable beforeWrite;
            public static boolean failPlay, invalid;
            public boolean released, failWrite, failUnderruns, failRelease;
            public int written, writes, result;
            public AudioTrack() { latest = this; }
            public int getState() { return invalid ? 0 : STATE_INITIALIZED; }
            public void play() { if (failPlay) throw new IllegalStateException("play"); }
            public void release() { released = true; if (failRelease) throw new IllegalStateException("release"); }
            public int getUnderrunCount() { if (failUnderruns) throw new IllegalStateException("underruns"); return 0; }
            public int write(byte[] bytes, int offset, int n, int mode) {
                if (beforeWrite != null) { Runnable r = beforeWrite; beforeWrite = null; r.run(); }
                if (failWrite || released) throw new IllegalStateException("write");
                if (offset != 0 || mode != WRITE_NON_BLOCKING || n > bytes.length || n % 2 != 0)
                    throw new AssertionError("invalid PCM write");
                writes++; written = n; return result < 0 ? result : n;
            }
        }""",
    "android/media/AudioAttributes.java": """package android.media; public class AudioAttributes {
        public static class Builder { public Builder setCapturePreset(int n) { return this; }
        public AudioAttributes build() { return new AudioAttributes(); } }
    }""",
    "android/media/AudioFormat.java": """package android.media; public class AudioFormat {
        public static final int ENCODING_PCM_16BIT = 2, CHANNEL_OUT_MONO = 4;
        public static int lastRate;
        public static class Builder {
            public Builder setSampleRate(int n) { lastRate = n; return this; }
            public Builder setEncoding(int n) { return this; }
            public Builder setChannelMask(int n) { return this; }
            public AudioFormat build() { return new AudioFormat(); }
        }
    }""",
    "android/media/MediaRecorder.java": "package android.media; public class MediaRecorder { public static class AudioSource { public static final int MIC = 1; } }",
    "android/media/audiopolicy/AudioMixingRule.java": """package android.media.audiopolicy; public class AudioMixingRule {
        public static final int RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET = 2;
        public static class Builder { public Builder addMixRule(int n, Object v) { return this; }
        public AudioMixingRule build() { return new AudioMixingRule(); } }
    }""",
    "android/media/audiopolicy/AudioMix.java": """package android.media.audiopolicy; public class AudioMix {
        public static final int ROUTE_FLAG_LOOP_BACK = 2;
        public static class Builder {
            public Builder(AudioMixingRule rule) {}
            public Builder setFormat(android.media.AudioFormat format) { return this; }
            public Builder setRouteFlags(int flags) { return this; }
            public AudioMix build() { return new AudioMix(); }
        }
    }""",
    "android/media/audiopolicy/AudioPolicy.java": """package android.media.audiopolicy; public class AudioPolicy {
        public android.media.AudioTrack createAudioTrackSource(AudioMix mix) { return new android.media.AudioTrack(); }
        public static class Builder {
            public Builder(android.content.Context ctx) {}
            public Builder addMix(AudioMix mix) { return this; }
            public AudioPolicy build() { return new AudioPolicy(); }
        }
    }""",
    "com/stand/bridge/MicMixTest.java": """package com.stand.bridge;
        import android.media.*;
        import android.os.*;
        import android.provider.Settings.Global;
        public class MicMixTest {
            static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
            static void flag(String name, int value) { Global.values.put(name, value); }
            public static void main(String[] args) {
                android.content.Context ctx = new android.content.Context();
                AudioManager manager = ctx.manager;
                byte[] pcm = {0, 1, 2, 3, 4};
                MicMix.init(ctx); MicMix.init(ctx);
                check(Handler.queue.size() == 1, "one independent controller");
                Handler.tick(); MicMix.feed(pcm, 4, 2); MicMix.feed(pcm, 4, 2);
                check(manager.registered == 0, "SE mix defaults off");
                check(SePcm.calls == 1 && AudioTrack.latest == null,
                    "one passive preflight with mixing off; no track or policy");
                flag("e07_mic_se", 1); Handler.tick();
                check(manager.registered == 0, "missing old flag means old mix enabled");
                flag("e07_mic_fix", 0); manager.mode = 3; Handler.tick();
                check(manager.registered == 0, "non-normal audio mode blocks start");
                manager.mode = 0; Handler.tick();
                AudioTrack first = AudioTrack.latest;
                check(manager.active == 1 && AudioFormat.lastRate == 16000, "start after explicit switch");
                int reads = Global.reads;
                MicMix.feed(null, 10, 2); MicMix.feed(pcm, -1, 2);
                check(first.writes == 0, "ignore absent input");
                MicMix.feed(pcm, 4, 2);
                check(first.written == 4 && pcm[0] == 0 && pcm[4] == 4, "extracted PCM only, original unchanged");
                check(Global.reads == reads, "wake callback never reads settings");
                flag("e07_mic_rate", 48000); Handler.tick();
                check(first.released && manager.active == 1 && AudioFormat.lastRate == 48000, "rate replaces track");
                AudioTrack current = AudioTrack.latest;
                manager.mode = 2; Handler.tick();
                check(current.released && manager.active == 0, "call guard stops without PCM");
                manager.mode = 0; Handler.tick(); current = AudioTrack.latest;
                flag("e07_mic_fix", 1); Handler.tick();
                check(current.released && manager.active == 0, "old mix flag stops SE without PCM");
                flag("e07_mic_fix", 0); Handler.tick(); current = AudioTrack.latest;
                flag("e07_mic_se", 0); Handler.tick();
                check(current.released && manager.active == 0, "disable works without PCM");
                flag("e07_mic_se", 1); flag("e07_mic_rate", 7999); Handler.tick();
                check(AudioFormat.lastRate == 16000, "invalid rate falls back");
                current = AudioTrack.latest; current.failWrite = true;
                MicMix.feed(pcm, 4, 2); Handler.tick();
                check(current.released && manager.active == 1, "write exception contained and track replaced");
                current = AudioTrack.latest; current.result = -6;
                MicMix.feed(pcm, 4, 2); Handler.tick();
                check(current.released && manager.active == 1, "negative write status cleaned up");
                current = AudioTrack.latest;
                AudioTrack.beforeWrite = () -> { flag("e07_mic_rate", 44100); Handler.tick(); };
                MicMix.feed(pcm, 4, 2);
                check(current.released && manager.active == 1, "release between snapshot and write is contained");
                AudioTrack replacement = AudioTrack.latest; Handler.tick();
                check(AudioTrack.latest == replacement && manager.active == 1, "old write failure preserves replacement");
                SePcm.fail = true; current = AudioTrack.latest;
                MicMix.feed(pcm, 4, 2); Handler.tick();
                check(current.writes == 0 && current.released, "malformed container never reaches AudioTrack");
                SePcm.fail = false;
                current = AudioTrack.latest; MicMix.feed(pcm, 4, 2);
                current.failUnderruns = true; SystemClock.now = 20000;
                MicMix.feed(pcm, 4, 2); Handler.tick();
                check(current.released && manager.active == 1, "diagnostic failure cannot escape wake callback");
                current = AudioTrack.latest; current.failRelease = true; Global.fail = true; Handler.tick();
                check(current.released && manager.active == 0, "settings failure closes policy despite release exception");
                Global.fail = false; AudioTrack.failPlay = true; Handler.tick();
                check(AudioTrack.latest.released && manager.active == 0, "play failure releases new sink/policy");
                AudioTrack.failPlay = false; AudioTrack.invalid = true; Handler.tick();
                check(AudioTrack.latest.released && manager.active == 0, "uninitialized sink released");
                AudioTrack.invalid = false; manager.result = -1; int released = manager.released; Handler.tick();
                check(manager.active == 0 && manager.released == released, "failed registration never unregistered");
                manager.result = 0; Handler.tick(); current = AudioTrack.latest;
                manager.failMode = true; Handler.tick();
                check(current.released && manager.active == 0 && Handler.queue.size() == 1,
                    "control exception releases policy and keeps monitor alive");
                System.out.println("MicMix host checks passed");
            }
        }""",
}
with tempfile.TemporaryDirectory(prefix="e07-mic-mix-") as temporary:
    build = Path(temporary)
    sources = [str(root / "src/com/stand/bridge/MicMix.java")]
    for name, content in stubs.items():
        path = build / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(str(path))
    subprocess.run([str(java_home / "bin/javac"), "-encoding", "UTF-8", "-d", str(build), *sources], check=True)
    subprocess.run([str(java_home / "bin/java"), "-cp", str(build), "com.stand.bridge.MicMixTest"], check=True)
