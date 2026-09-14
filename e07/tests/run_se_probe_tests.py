#!/usr/bin/env python3
"""Run the real bounded SE recorder with Android control/clock stubs; no device or JNI."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-26-openjdk"))
files = {
    "android/content/Context.java": """
package android.content;
import java.io.File;
public class Context {
    public final File directory;
    public boolean fail;
    public volatile java.util.concurrent.CountDownLatch gate;
    public Context(File directory) { this.directory = directory; }
    public Object getContentResolver() { return this; }
    public File getExternalFilesDir(String type) {
        if (!Thread.currentThread().getName().equals("e07-se-probe"))
            throw new AssertionError("file access on callback/control thread");
        if (fail) throw new IllegalStateException("injected file failure");
        try { if (gate != null) gate.await(); }
        catch (InterruptedException e) { throw new RuntimeException(e); }
        return directory;
    }
}
""",
    "android/os/Environment.java": """
package android.os;
import java.io.File;
public class Environment {
    public static boolean emulated = true;
    public static boolean isExternalStorageEmulated() { return emulated; }
    public static boolean isExternalStorageEmulated(File directory) { return emulated; }
}
""",
    "android/provider/Settings.java": """
package android.provider;
public class Settings {
    public static class Global {
        public static int seconds;
        public static boolean deny;
        public static int getInt(Object resolver, String name, int fallback) {
            if (!name.equals("e07_se_probe_seconds")) throw new AssertionError(name);
            return seconds;
        }
        public static boolean putInt(Object resolver, String name, int value) {
            if (!name.equals("e07_se_probe_seconds") || value != 0) throw new AssertionError(name);
            if (deny) return false;
            seconds = value;
            return true;
        }
    }
}
""",
    "android/os/SystemClock.java": """
package android.os;
public class SystemClock {
    public static volatile Thread failingThread;
    public static long elapsedRealtime() {
        if (Thread.currentThread() == failingThread) throw new AssertionError("injected clock failure");
        return System.nanoTime() / 1000000L;
    }
}
""",
    "android/os/Looper.java": """
package android.os;
public class Looper { public static Looper getMainLooper() { return new Looper(); } }
""",
    "android/os/Handler.java": """
package android.os;
public class Handler {
    public static Runnable poll;
    public static int posts;
    public Handler(Looper looper) {}
    public boolean post(Runnable value) { posts++; poll = value; return true; }
    public boolean postDelayed(Runnable value, long delay) {
        if (delay != 500) throw new AssertionError("poll interval");
        poll = value; return true;
    }
}
""",
    "android/util/Log.java": """
package android.util;
public class Log {
    public static final StringBuffer messages = new StringBuffer();
    public static int i(String tag, String text) {
        messages.append(text).append('\\n'); System.out.println(text); return 0;
    }
}
""",
    "com/stand/bridge/SeProbeTest.java": """
package com.stand.bridge;
import android.content.Context;
import android.provider.Settings.Global;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
public class SeProbeTest {
    private static final Thread MAIN = Thread.currentThread();
    private static final Field ACTIVE;
    static {
        try { ACTIVE = SeProbe.class.getDeclaredField("active"); ACTIVE.setAccessible(true); }
        catch (Exception e) { throw new RuntimeException(e); }
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static void put(byte[] data, int at, int value) {
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value);
    }
    private static byte[] frame() {
        byte[] data = new byte[7168];
        System.arraycopy("IFLYAUTOISS".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, data, 0, 11);
        put(data, 16, 2); put(data, 20, data.length); put(data, 24, 2);
        put(data, 64, 2); put(data, 68, 4096); put(data, 72, 512); put(data, 76, 1);
        put(data, 80, 4); put(data, 84, 512); put(data, 88, 512); put(data, 92, 2);
        for (int i = 512; i < data.length; i++) data[i] = (byte) i;
        return data;
    }
    private static Object active() throws Exception { return ACTIVE.get(null); }
    private static void awaitDone() throws Exception {
        long until = System.nanoTime() + 3000000000L;
        while (active() != null && System.nanoTime() < until) Thread.sleep(10);
        check(active() == null, "capture did not stop without new callbacks");
    }
    private static File[] recordings(Context ctx) {
        return ctx.directory.listFiles((dir, name) -> name.endsWith(".bin"));
    }
    private static int countRecords(File file, byte[] expected) throws Exception {
        int count = 0;
        try (DataInputStream in = new DataInputStream(new FileInputStream(file))) {
            byte[] header = new byte[8]; in.readFully(header);
            check(Arrays.equals(header, "E07SE01\\n".getBytes("US-ASCII")), "file magic");
            while (in.available() > 0) {
                check(in.readLong() > 0 && in.readInt() == 3584, "metadata");
                check(in.readInt() == 7168, "stale callback length truncated the native container");
                byte[] bytes = new byte[7168]; in.readFully(bytes);
                if (expected != null) check(Arrays.equals(bytes, expected), "input ownership/copy changed");
                count++;
            }
        }
        return count;
    }
    public static void main(String[] args) throws Exception {
        Context ctx = new Context(new File(args[0]));
        SeProbe.feed(null, -1); SeProbe.start(null);
        for (int seconds : new int[]{0, -1, 21, Integer.MAX_VALUE}) {
            Global.seconds = seconds; SeProbe.start(ctx); check(active() == null, "invalid duration accepted");
        }
        Global.seconds = 1; Global.deny = true; SeProbe.start(ctx);
        check(active() == null, "capture started without consuming control"); Global.deny = false;
        SeProbe.init(ctx); SeProbe.init(ctx);
        check(android.os.Handler.posts == 1, "init registered overlapping pollers");
        android.os.Handler.poll.run();
        Object first = active();
        check(first != null && Global.seconds == 0, "control not consumed before capture");
        Global.seconds = 1; SeProbe.start(ctx);
        check(active() == first, "overlapping capture replaced active worker"); Global.seconds = 0;
        byte[] data = frame(), original = data.clone();
        SeProbe.feed(data, 3584);
        check(Arrays.equals(data, original), "feed modified native input");
        Arrays.fill(data, (byte) 0); // Simulate immediate native buffer reuse.
        SeProbe.feed(null, 3584); SeProbe.feed(new byte[511], 3584);
        data = frame(); data[0] = 'X'; SeProbe.feed(data, 3584);
        data = frame(); put(data, 20, 7169); SeProbe.feed(data, 3584);
        data = Arrays.copyOf(frame(), 65537); put(data, 20, 65537); SeProbe.feed(data, 3584);
        data = frame(); put(data, 24, 29); SeProbe.feed(data, 3584);
        data = frame(); put(data, 68, -1); SeProbe.feed(data, 3584);
        data = frame(); put(data, 72, -1); SeProbe.feed(data, 3584);
        SeProbe.feed(frame(), 0); SeProbe.feed(frame(), 7169);
        android.os.SystemClock.failingThread = MAIN;
        SeProbe.feed(frame(), 3584); // Throwable must never escape onto the OEM callback.
        android.os.SystemClock.failingThread = null;
        awaitDone();
        check(recordings(ctx).length == 1 && countRecords(recordings(ctx)[0], original) == 1,
                "bad input accepted or complete frame lost");
        String logs = android.util.Log.messages.toString();
        check(logs.contains("rejected=10") && logs.contains("errors=1") && logs.contains("status=error"),
                "malformed frames or callback failure not reported: " + logs);
        android.os.Handler.poll.run(); check(active() == null, "consumed flag restarted capture");

        Global.seconds = 1; SeProbe.start(ctx); awaitDone();
        check(recordings(ctx).length == 2 && android.util.Log.messages.toString().contains("status=no_frames"),
                "empty capture did not terminate/report no frames");
        check(ctx.directory.listFiles((dir, name) -> name.endsWith(".partial")).length == 0,
                "success left unfinished files");

        ctx.gate = new CountDownLatch(1); Global.seconds = 1; SeProbe.start(ctx);
        long before = System.nanoTime();
        for (int i = 0; i < 129; i++) SeProbe.feed(frame(), 3584);
        check(System.nanoTime() - before < 500000000L, "callback waited for disk worker");
        ctx.gate.countDown(); awaitDone(); ctx.gate = null;
        check(recordings(ctx).length == 3 && android.util.Log.messages.toString().contains("frames=128 dropped=1"),
                "bounded queue overflow not reported");
        check(android.util.Log.messages.toString().contains("status=incomplete"), "loss reported as success");

        ctx.fail = true; Global.seconds = 1; SeProbe.start(ctx); awaitDone();
        check(Global.seconds == 0 && recordings(ctx).length == 3
                && android.util.Log.messages.toString().contains("injected file failure"), "I/O failure hidden");
        SeProbe.start(ctx); check(active() == null, "failed worker retried consumed request");
        ctx.fail = false; android.os.Environment.emulated = false;
        Global.seconds = 1; SeProbe.start(ctx); awaitDone();
        check(recordings(ctx).length == 3 && android.util.Log.messages.toString().contains("primary storage is not emulated"),
                "removable primary storage was accepted");
        System.out.println("SE probe: complete native frame, ownership, validation, failure isolation and bounded one-shot control passed");
    }
}
""",
}
with tempfile.TemporaryDirectory(prefix="e07-se-probe-tests-") as temporary:
    build = Path(temporary)
    sources = [root / "src/com/stand/bridge/SeProbe.java"]
    for name, content in files.items():
        path = build / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(path)
    recordings = build / "recordings"
    recordings.mkdir()
    subprocess.run([str(java_home / "bin/javac"), "-encoding", "UTF-8", "-d", str(build),
                    *map(str, sources)], check=True)
    subprocess.run([str(java_home / "bin/java"), "-cp", str(build), "com.stand.bridge.SeProbeTest", str(recordings)],
                   check=True, timeout=15)
