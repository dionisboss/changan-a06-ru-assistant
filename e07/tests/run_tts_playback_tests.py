#!/usr/bin/env python3
"""Run the exact production Playback/stop logic with minimal deterministic Android substitutes."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-26-openjdk"))
source = (root / "src/com/stand/tts/TeraTts.java").read_text()
playback = source[source.index("    private static final Object PLAY_LOCK"):source.index("    public static boolean ready()")]
stop = source[source.index("    private static boolean stopPlayback("):source.index("    private static void play(")]
stubs = {
    "android/os/Looper.java": "package android.os; public class Looper { public static Looper getMainLooper() { return null; } }",
    "android/os/SystemClock.java": "package android.os; public class SystemClock { public static long now; public static long elapsedRealtime() { return now; } }",
    "android/os/Handler.java": """package android.os;
        public class Handler {
            private static final java.util.Set<Runnable> queued = new java.util.HashSet<>();
            public Handler(Looper l) {}
            public boolean postDelayed(Runnable r, long ms) { queued.add(r); return true; }
            public void removeCallbacks(Runnable r) { queued.remove(r); }
            public static boolean pending(Object r) { return queued.contains(r); }
        }""",
    "android/media/AudioTrack.java": "package android.media; public class AudioTrack { public int getPlaybackHeadPosition() { return 0; } public void pause() {} public void flush() {} }",
    "android/util/Log.java": "package android.util; public class Log { public static int i(String tag, String msg) { return 0; } public static int e(String tag, String msg, Throwable t) { throw new AssertionError(msg, t); } }",
    "com/stand/tts/TeraTts.java": "package com.stand.tts; import android.media.AudioTrack; import android.util.Log; public class TeraTts { private static final String TAG = \"test\";\n" + playback + stop + "}\n",
}
with tempfile.TemporaryDirectory(prefix="e07-tts-playback-") as temporary:
    build = Path(temporary)
    sources = []
    for name, content in stubs.items():
        path = build / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(str(path))
    subprocess.run([str(java_home / "bin/javac"), "-encoding", "UTF-8", "-d", str(build),
                    *sources, str(root / "tests/TtsPlaybackTest.java")], check=True)
    subprocess.run([str(java_home / "bin/java"), "-cp", str(build), "com.stand.tts.TtsPlaybackTest"], check=True)
