package com.stand.bridge;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Optional, bounded copy of already received OEM containers; never opens an audio input. */
public final class SeProbe {
    private static final String TAG = "SeProbe", SETTING = "e07_se_probe_seconds";
    private static final byte[] MAGIC = {'I', 'F', 'L', 'Y', 'A', 'U', 'T', 'O', 'I', 'S', 'S', 0, 0, 0, 0, 0};
    private static volatile Capture active;
    private static boolean initialized;

    private SeProbe() {}

    /** The control setting is polled away from the OEM callback and consumed once. */
    public static synchronized void init(final Context ctx) {
        if (initialized || ctx == null) return;
        try {
            final Handler handler = new Handler(Looper.getMainLooper());
            initialized = handler.post(new Runnable() {
                public void run() {
                    start(ctx);
                    try { handler.postDelayed(this, 500); }
                    catch (Throwable error) { log("control error: " + error); }
                }
            });
        } catch (Throwable error) { log("init error: " + error); }
    }

    public static synchronized void start(Context ctx) {
        if (ctx == null || active != null) return;
        try {
            int seconds = Settings.Global.getInt(ctx.getContentResolver(), SETTING, 0);
            if (seconds < 1 || seconds > 20) return;
            // Consume before thread creation, including failed starts: never restart indefinitely.
            if (!Settings.Global.putInt(ctx.getContentResolver(), SETTING, 0)) {
                log("start error: cannot consume " + SETTING);
                return;
            }
            Capture capture = new Capture(ctx, seconds);
            active = capture;
            try {
                Thread worker = new Thread(capture, "e07-se-probe");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable error) {
                if (active == capture) active = null;
                log("start error: " + error);
            }
        } catch (Throwable error) { log("start error: " + error); }
    }

    /** No disk/native/settings calls here. The OEM retains the untouched input buffer. */
    public static void feed(byte[] frame, int callbackLength) {
        Capture capture = active;
        try {
            if (capture == null || SystemClock.elapsedRealtime() >= capture.deadline) return;
            // The worker takes this lock only to close the queue, never around file I/O.
            synchronized (capture) {
                if (!capture.accepting) return;
                int length = frame == null || frame.length < 512 ? 0 : (int) u32(frame, 20);
                if (length < 512 || length > 65536 || length > frame.length
                        || callbackLength <= 0 || callbackLength > frame.length) {
                    capture.rejected.incrementAndGet();
                    return;
                }
                for (int i = 0; i < MAGIC.length; i++) {
                    if (frame[i] != MAGIC[i]) { capture.rejected.incrementAndGet(); return; }
                }
                long count = u32(frame, 24);
                if (count < 1 || count > (512 - 64) / 16) {
                    capture.rejected.incrementAndGet();
                    return;
                }
                for (int i = 0; i < count; i++) {
                    int at = 64 + i * 16;
                    long offset = u32(frame, at + 4), bytes = u32(frame, at + 8);
                    long channels = u32(frame, at + 12);
                    if (offset > length - 512 || (bytes != 0 && channels > (length - 512 - offset) / bytes)) {
                        capture.rejected.incrementAndGet();
                        return;
                    }
                }
                Record record = new Record(SystemClock.elapsedRealtime(), callbackLength, Arrays.copyOf(frame, length));
                if (record.time < capture.deadline && !capture.queue.offer(record)) {
                    capture.dropped.incrementAndGet();
                }
            }
        } catch (Throwable error) {
            try {
                if (capture != null) {
                    capture.errors.incrementAndGet();
                    capture.lastError = error.toString();
                }
            } catch (Throwable ignored) {}
        }
    }

    private static long u32(byte[] data, int at) {
        return (data[at] & 255L) | ((data[at + 1] & 255L) << 8)
                | ((data[at + 2] & 255L) << 16) | ((data[at + 3] & 255L) << 24);
    }

    private static void log(String message) {
        try { Log.i(TAG, message); } catch (Throwable ignored) {}
    }

    private static final class Record {
        final long time;
        final int callbackLength;
        final byte[] frame;
        Record(long time, int callbackLength, byte[] frame) {
            this.time = time; this.callbackLength = callbackLength; this.frame = frame;
        }
    }

    private static final class Capture implements Runnable {
        final Context ctx;
        final long started = SystemClock.elapsedRealtime(), deadline;
        // ponytail: overflow drops diagnostic frames; raise capacity only after measured need.
        final ArrayBlockingQueue<Record> queue = new ArrayBlockingQueue<Record>(128);
        final AtomicInteger dropped = new AtomicInteger(), rejected = new AtomicInteger(), errors = new AtomicInteger();
        volatile String lastError = "none";
        boolean accepting = true;

        Capture(Context ctx, int seconds) {
            this.ctx = ctx;
            deadline = started + seconds * 1000L;
        }

        public void run() {
            int frames = 0;
            String path = "unopened";
            boolean complete = false;
            try {
                if (!Environment.isExternalStorageEmulated()) throw new IOException("primary storage is not emulated");
                File directory = ctx.getExternalFilesDir(null);
                if (directory == null || !Environment.isExternalStorageEmulated(directory)) {
                    throw new IOException("no emulated app storage; removable storage forbidden");
                }
                File file = new File(directory, "se-probe-" + System.currentTimeMillis() + "-" + started + ".bin");
                File partial = new File(file.getPath() + ".partial");
                path = partial.getAbsolutePath();
                log("start path=" + file.getAbsolutePath() + " seconds=" + ((deadline - started) / 1000));
                try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(partial)))) {
                    out.writeBytes("E07SE01\n");
                    while (true) {
                        long remaining = deadline - SystemClock.elapsedRealtime();
                        if (remaining <= 0) break;
                        Record record = queue.poll(remaining, TimeUnit.MILLISECONDS);
                        if (record == null) break;
                        out.writeLong(record.time);
                        out.writeInt(record.callbackLength);
                        out.writeInt(record.frame.length);
                        out.write(record.frame);
                        frames++;
                    }
                }
                if (!partial.renameTo(file)) throw new IOException("cannot finalize " + partial);
                path = file.getAbsolutePath();
                complete = true;
            } catch (Throwable error) {
                errors.incrementAndGet();
                lastError = error.toString();
            } finally {
                synchronized (this) {
                    accepting = false;
                    dropped.addAndGet(queue.size());
                    queue.clear();
                }
                String status = !complete || errors.get() != 0 ? "error"
                        : dropped.get() != 0 || rejected.get() != 0 ? "incomplete"
                        : frames == 0 ? "no_frames" : "complete";
                log("completion status=" + status + " path=" + path + " frames=" + frames
                        + " dropped=" + dropped.get() + " rejected=" + rejected.get()
                        + " errors=" + errors.get() + " lastError=" + lastError);
                synchronized (SeProbe.class) { if (active == this) active = null; }
            }
        }
    }
}
