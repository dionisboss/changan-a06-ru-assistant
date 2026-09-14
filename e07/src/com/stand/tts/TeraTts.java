/*
 * Russian Voice Assistant modification for Changan A06 (C390).
 * Copyright (c) 2026 Tecrow.
 * Licensed under the PolyForm Noncommercial License 1.0.0 — noncommercial use only. See LICENSE.
 * Independent modification — not affiliated with or endorsed by Changan Automobile.
 */
package com.stand.tts;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import com.stand.tts.tera.TeraTTS;

/**
 * TeraTTS (ru_f2) backend for the native TTS engine (PiperCaTts). Pure-Java inference on the STOCK
 * onnxruntime 1.17.1 (we bundle only libonnxruntime4j_jni.so; libonnxruntime.so is the stock one).
 * Output is mono float @44100 → resampled to the player rate (24000) and packed to 16-bit PCM.
 * synthesize() is NOT thread-safe → guarded by LOCK. Model assets (~370 MB) unpack to filesDir once.
 */
public final class TeraTts {
    private static final String TAG = "TeraTts";
    private static final String ASSET_DIR = "tera";     // apk assets/tera: models/, unicode_indexer.json, styles/
    private static final String CACHE_ASSETS = "tera-cache";   // apk assets/tera-cache: host pre-rendered sentences
    private static volatile TeraTTS engine;
    private static volatile boolean validated;
    private static Context appCtx;
    private static final Object LOCK = new Object();

    // Per-sentence PCM cache tiers: this map → filesDir/tera/models/cache → assets/tera-cache (rendered on
    // the build host by tests/precache.py with the same engine and accent lexicon) → live synthesis.
    // Bounded to keep :tts RSS in check.
    private static final int CACHE_MAX = 48;
    // Disk budget for rendered speech. ~4 minutes; the assets tier already holds every fixed reply,
    // so this only ever fills with composed ones (zones, levels, numbers).
    // ponytail: whole-directory scan on overflow, fine at a few hundred files; index it if that changes.
    private static final long DISK_CACHE_BYTES = 32L << 20;
    private static final java.util.Map<String, byte[]> CACHE =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, byte[]>(64, 0.75f, true) {
                protected boolean removeEldestEntry(java.util.Map.Entry<String, byte[]> e) { return size() > CACHE_MAX; }
            });
    private static String procName() {
        try {
            byte[] b = new byte[128];
            java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline");
            int n = in.read(b); in.close();
            if (n <= 0) return "";
            int end = 0; while (end < n && b[end] != 0) end++;
            return new String(b, 0, end).trim();
        } catch (Throwable t) { return ""; }
    }
    private static boolean isTtsProcess() {
        return appCtx != null && procName().equals(appCtx.getPackageName() + ":tts");
    }
    /** Allowed in main (for --es say) and :tts (real engine). */
    public static boolean canSpeak() {
        if (ctx() == null) return false;
        String pn = procName(), pkg = appCtx.getPackageName();
        return pn.equals(pkg) || pn.equals(pkg + ":tts");
    }
    private static Context ctx() {
        if (appCtx != null) return appCtx;
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof Context) appCtx = ((Context) app).getApplicationContext();
        } catch (Throwable ignored) {}
        return appCtx;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void init(final Context c) {
        appCtx = c.getApplicationContext();
        // init() is called once per PiperCaTts instance (config maps several engines to it); guard so
        // engine load + greeting prewarm run exactly ONCE, not 3× (which stormed the CPU at startup).
        if (canSpeak() && STARTED.compareAndSet(false, true)) new Thread(new Runnable() { public void run() {
            // One real synthesis proves the engine works; fixed replies otherwise arrive pre-rendered from assets.
            if (ensure()) {
                try { synchronized (LOCK) { validated = engine.synthesize("Да", SPEED).length > 0; } }
                catch (Throwable t) { Log.e(TAG, "probe synthesis", t); }
            }
            Log.i(TAG, "engine validated=" + validated);
        } }).start();
    }
    private static final int RATE = 24000;   // native TTS player rate (TtsAudioManager.SAMPLE_RATE_DEFAULT)

    private static synchronized boolean ensure() {
        if (engine != null) return true;
        try {
            File dir = new File(appCtx.getFilesDir(), ASSET_DIR);
            // pm install -r KEEPS filesDir, so a plain existence check would keep loading STALE models
            // after a model swap (fp16<->fp32, sampler step change) → broken/silent audio until pm clear.
            // Guard with a signature (name+size of every bundled model): on mismatch, wipe models/ and
            // re-unpack so a new APK's models always win without needing pm clear.
            if (needsUnpack(dir)) {
                Log.i(TAG, "unpacking Tera assets to " + dir + " (~370 MB; model set changed or missing)");
                deleteRec(new File(dir, "models"));          // drop stale models (incl. renamed sampler)
                unpackAssets(ASSET_DIR, dir);
                writeText(new File(dir, "models/.sig"), modelsSig());
            }
            // Runtime toggle for HW acceleration: `adb shell settings put global tera_nnapi 1` (then restart).
            try {
                com.stand.tts.tera.TeraTTS.USE_NNAPI = android.provider.Settings.Global.getInt(
                        appCtx.getContentResolver(), "tera_nnapi", 0) == 1;
            } catch (Throwable ignored) {}
            long t0 = System.currentTimeMillis();
            engine = new TeraTTS(dir.toPath(), VOICE);
            Log.i(TAG, "engine ready in " + (System.currentTimeMillis() - t0) + "ms, provider="
                    + com.stand.tts.tera.TeraTTS.PROVIDER_STATUS);
            return true;
        } catch (Throwable t) { Log.e(TAG, "ensure", t); return false; }
    }

    /** Synthesize Russian text to mono 16-bit LE PCM at targetRate (resampled from 44100). Empty on failure. */
    public static byte[] synthPcm16(String text, int targetRate) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (String sentence : sentences(ttsNormalize(text))) {
            byte[] pcm = synthSentence(sentence, targetRate);
            if (pcm.length == 0) return new byte[0];
            out.write(pcm, 0, pcm.length);
        }
        return out.toByteArray();
    }

    /** One normalized sentence: memory cache → disk cache → pre-rendered asset → engine (then cached). */
    static byte[] synthSentence(String t, int targetRate) {
        try {
            if (t.isEmpty() || ctx() == null) return new byte[0];
            String key = cacheKey(t, targetRate, accents());
            byte[] hit = CACHE.get(key);
            if (hit == null) hit = readCached(key);
            if (hit != null) { remember(key, hit); return hit; }
            if (!ensure()) { Log.e(TAG, "synthSentence: engine not ready"); return new byte[0]; }
            long t0 = System.currentTimeMillis();
            float[] s;
            synchronized (LOCK) { s = engine.synthesize(t, SPEED); }
            byte[] pcm = pcm16(s, NATIVE_RATE, targetRate);
            Log.i(TAG, "synth " + pcm.length / 2 + " @" + targetRate + " in " + (System.currentTimeMillis() - t0) + "ms: " + t);
            remember(key, pcm);
            writeCached(key, pcm);
            return pcm;
        } catch (Throwable e) { Log.e(TAG, "synthSentence", e); return new byte[0]; }
    }
    private static volatile String accents;
    /** Read once: the bundled dictionary cannot change while the process runs. */
    private static String accents() {
        String a = accents;
        if (a == null) accents = a = accentVersion(assetLen(ASSET_DIR + "/ruaccent.bin"));
        return a;
    }
    private static void remember(String key, byte[] pcm) { if (pcm.length <= 400000) CACHE.put(key, pcm); }
    /** Under models/ so a model swap wipes it together with the stale models (see ensure). */
    private static File cacheFile(String key) {
        File dir = new File(appCtx.getFilesDir(), ASSET_DIR + "/models/cache");
        dir.mkdirs();
        return new File(dir, key + ".pcm");
    }
    /** Bytes of the bundled frequency dictionary; -1 when the asset listing fails (still a stable key). */
    /** Stress data is part of the key, so a lexicon change simply stops matching; those renders are
     *  unreachable and age out of the budget instead of needing a wipe. */
    private static void writeCached(String key, byte[] pcm) {
        try {
            File f = cacheFile(key);
            // Сначала во временный файл, потом атомарное переименование. Одно и то же предложение
            // могут писать основной процесс и :tts одновременно, а читатель — открыть файл в этот
            // момент и получить обрезанный звук. Имя временного файла содержит pid, чтобы два
            // процесса не писали в один и тот же. Замечание voronoff2803 в PR #1.
            File part = new File(f.getParentFile(), key + "." + android.os.Process.myPid() + ".part");
            java.nio.file.Files.write(part.toPath(), pcm);
            java.nio.file.Files.move(part.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            trimCache(f.getParentFile(), DISK_CACHE_BYTES);
        } catch (Throwable e) { Log.e(TAG, "cache write", e); }
    }
    private static byte[] readCached(String key) {
        try {
            File f = cacheFile(key);
            if (f.isFile()) {
                f.setLastModified(System.currentTimeMillis());   // a used render survives the next trim
                Log.i(TAG, "cache disk: " + key);
                return java.nio.file.Files.readAllBytes(f.toPath());
            }
        } catch (Throwable ignored) {}
        try {
            InputStream in = appCtx.getAssets().open(CACHE_ASSETS + "/" + key + ".pcm");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[1 << 16]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            Log.i(TAG, "cache asset: " + key);
            return bo.toByteArray();
        } catch (Throwable ignored) { return null; }
    }

    /** Re-unpack if a key non-model file is missing OR the bundled model set (name+size of every file
     *  under assets/tera/models) differs from what was unpacked. Catches fp16<->fp32 and sampler-step
     *  swaps that reuse or change asset filenames — the plain existence check missed these. */
    private static boolean needsUnpack(File dir) {
        if (!new File(dir, "unicode_indexer.json").exists()
                || !new File(dir, "ruaccent.bin").exists()
                || !new File(dir, "styles/" + VOICE + "/style_ttl.bin").exists()) return true;
        String want = modelsSig();
        if (want.isEmpty()) return !new File(dir, "models/vocoder.onnx").exists(); // asset listing failed → fallback
        String have = readText(new File(dir, "models/.sig"));
        if (want.equals(have)) return false;
        Log.i(TAG, "models signature changed:\n  apk =" + want + "\n  disk=" + have);
        return true;
    }
    /** "name:size|..." (sorted) over assets/tera/models — cheap (openFd length, no full read; assets are zip-stored). */
    private static String modelsSig() {
        try {
            String base = ASSET_DIR + "/models";
            String[] list = appCtx.getAssets().list(base);
            if (list == null || list.length == 0) return "";
            java.util.Arrays.sort(list);
            StringBuilder sb = new StringBuilder();
            for (String n : list) { if (n.equals(".sig")) continue; sb.append(n).append(':').append(assetLen(base + "/" + n)).append('|'); }
            return sb.toString();
        } catch (Throwable t) { return ""; }
    }
    private static long assetLen(String path) {
        try { android.content.res.AssetFileDescriptor fd = appCtx.getAssets().openFd(path);
              long n = fd.getLength(); fd.close(); return n; }
        catch (Throwable t) { return -1; }
    }
    private static void deleteRec(File f) {
        try { if (f.isDirectory()) { File[] ch = f.listFiles(); if (ch != null) for (File c : ch) deleteRec(c); }
              f.delete(); } catch (Throwable ignored) {}
    }
    private static String readText(File f) {
        try { byte[] b = new byte[(int) f.length()]; java.io.FileInputStream in = new java.io.FileInputStream(f);
              int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; in.close();
              return new String(b, 0, off, "UTF-8"); }
        catch (Throwable t) { return ""; }
    }
    private static void writeText(File f, String s) {
        try { f.getParentFile().mkdirs(); FileOutputStream o = new FileOutputStream(f);
              o.write(s.getBytes("UTF-8")); o.close(); }
        catch (Throwable t) { Log.e(TAG, "writeText " + f, t); }
    }

    private static void unpackAssets(String assetDir, File outDir) throws Exception {
        AssetManager am = appCtx.getAssets();
        String[] list = am.list(assetDir);
        if (list == null || list.length == 0) {   // file
            outDir.getParentFile().mkdirs();
            InputStream in = am.open(assetDir);
            OutputStream out = new FileOutputStream(outDir);
            byte[] b = new byte[1 << 16]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            in.close(); out.close();
            return;
        }
        outDir.mkdirs();
        for (String name : list) unpackAssets(assetDir + "/" + name, new File(outDir, name));
    }

    // ---- Direct playback path (replaces the former PiperTts.speak): used to voice our own RU
    //      responses/tips (RuBridge.onTtsText / onTipText / StandNluReceiver). Synthesizes via
    //      TeraTTS (synthPcm16, cached) and streams the PCM through AudioTrack. -------------------
    private static final Object PLAY_LOCK = new Object();
    private static volatile Playback playback;
    private static final android.os.Handler PULSE_HANDLER = new android.os.Handler(android.os.Looper.getMainLooper());

    public interface PlaybackListener {
        void begin();
        /** Keep the OEM dialog timer alive during synthesis and playback. */
        default void progress() {}
        /** 0 = completed, 1 = interrupted, -1 = failure. Exactly once for accepted speech. */
        void end(int status);
    }
    private static final class Playback implements Runnable {
        final Object owner;
        final PlaybackListener listener;
        final int usage;
        final boolean stockFocus;
        final long acceptedAt = android.os.SystemClock.elapsedRealtime();
        final java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        volatile boolean cancelled;
        volatile AudioTrack track;
        volatile long totalFrames, playedFrames;
        volatile String reason = "error";
        Playback(Object owner, int usage, boolean stockFocus, PlaybackListener listener) {
            this.owner = owner; this.listener = listener; this.usage = usage <= 0 ? 18 : usage; this.stockFocus = stockFocus;
        }
        synchronized void begin() {
            if (!cancelled && !ended.get() && listener != null) {
                try { listener.begin(); } catch (Throwable t) { Log.e(TAG, "begin callback", t); }
            }
        }
        synchronized void end(int status) {
            if (ended.compareAndSet(false, true)) {
                PULSE_HANDLER.removeCallbacks(this);
                Log.i(TAG, "playback end id=" + Integer.toHexString(System.identityHashCode(this))
                        + " status=" + status + " reason=" + reason + " frames=" + playedFrames + "/" + totalFrames
                        + " elapsed=" + (android.os.SystemClock.elapsedRealtime() - acceptedAt));
                if (listener != null) {
                    try { listener.end(status); } catch (Throwable t) { Log.e(TAG, "end callback", t); }
                }
            }
        }
        public void run() {
            boolean timeout;
            synchronized (this) {
                if (cancelled || ended.get() || playback != this) return;
                timeout = android.os.SystemClock.elapsedRealtime() - acceptedAt >= 60000;
                if (!timeout) {
                    if (listener != null) {
                        try { listener.progress(); } catch (Throwable t) { Log.e(TAG, "progress callback", t); }
                    }
                    if (!cancelled && !ended.get() && playback == this) PULSE_HANDLER.postDelayed(this, 1000);
                }
            }
            // Match the exact request: an expired pulse must not cancel a replacement using the same player.
            if (timeout) stopPlayback(this, "timeout");
        }
    }

    public static boolean ready() { return engine != null && validated; }
    public static boolean isSpeaking() { return playback != null; }
    public static boolean isSpeaking(Object owner) { Playback p = playback; return p != null && p.owner == owner; }

    public static void speak(String text) { speak(text, null); }
    public static void speak(String text, final Runnable onDone) {
        if (!speakOwned(text, TeraTts.class, new PlaybackListener() {
            public void begin() {}
            public void end(int status) { if (onDone != null) onDone.run(); }
        }) && onDone != null) onDone.run();
    }

    /** Accept only a loaded engine; the caller retains stock playback on false. */
    public static boolean speakOwned(final String text, Object owner, PlaybackListener listener) {
        return speakOwned(text, owner, 18, false, listener);
    }
    public static boolean speakOwned(final String text, Object owner, int usage, boolean stockFocus, PlaybackListener listener) {
        if (!ready() || !canSpeak() || text == null || text.trim().isEmpty()) return false;
        final Playback next = new Playback(owner, usage, stockFocus, listener);
        Playback old;
        synchronized (PLAY_LOCK) {
            old = playback;
            if (old != null) cancelLocked(old, "replaced");
            playback = next;
        }
        if (old != null) old.end(1);
        try {
            new Thread(new Runnable() { public void run() { play(next, text); } }, "RuTts").start();
            PULSE_HANDLER.post(next);
            return true;
        } catch (Throwable t) {
            synchronized (PLAY_LOCK) { if (playback == next) playback = null; }
            Log.e(TAG, "start playback worker", t);
            // No callback: the request was not accepted and the hook will use stock TTS.
            return false;
        }
    }

    public static boolean stop(Object owner) {
        Playback old;
        synchronized (PLAY_LOCK) {
            old = playback;
            if (old == null || old.owner != owner) return false;
            cancelLocked(old, "stop"); playback = null;
        }
        old.end(1);
        return true;
    }
    public static void stop() {
        Playback old;
        synchronized (PLAY_LOCK) {
            old = playback;
            if (old == null) return;
            cancelLocked(old, "stop"); playback = null;
        }
        old.end(1);
    }
    private static boolean stopPlayback(Playback p, String reason) {
        synchronized (PLAY_LOCK) {
            if (playback != p) return false;
            cancelLocked(p, reason); playback = null;
        }
        p.end(1);
        return true;
    }
    private static void recordPlayed(Playback p, AudioTrack at) {
        if (at != null) {
            try { p.playedFrames = Math.max(p.playedFrames, at.getPlaybackHeadPosition() & 0xffffffffL); }
            catch (Throwable ignored) {}
        }
    }
    private static void cancelLocked(Playback p, String reason) {
        if (!p.cancelled) p.reason = reason;
        p.cancelled = true;
        AudioTrack at = p.track;
        recordPlayed(p, at);
        if (at != null) { try { at.pause(); at.flush(); } catch (Throwable ignored) {} }
    }

    private static void play(final Playback p, String text) {
        AudioTrack at = null;
        AudioManager am = null;
        android.media.AudioFocusRequest focus = null;
        int status = -1;
        AudioManager.OnAudioFocusChangeListener focusListener = new AudioManager.OnAudioFocusChangeListener() {
            public void onAudioFocusChange(int change) {
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    stopPlayback(p, "focus-loss");
                }
            }
        };
        try {
            if (p.cancelled) { status = 1; return; }
            String[] parts = sentences(ttsNormalize(text));
            if (parts.length == 0) throw new IllegalStateException("empty TeraTTS input");
            byte[] pcm = synthSentence(parts[0], RATE);
            if (p.cancelled) { status = 1; return; }
            if (pcm.length == 0) throw new IllegalStateException("empty TeraTTS output");
            android.media.AudioAttributes attributes = new android.media.AudioAttributes.Builder()
                    .setContentType(p.usage == 12 ? 1 : 4).setUsage(p.usage).build();
            am = (AudioManager) appCtx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && !p.stockFocus) {
                android.media.AudioFocusRequest request = new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener).build();
                if (am.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                    throw new IllegalStateException("audio focus denied");
                focus = request;
            }
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IllegalStateException("AudioTrack minimum buffer=" + min);
            android.media.AudioFormat format = new android.media.AudioFormat.Builder().setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            // Буфер под первое предложение, как в прежней рабочей версии. 20-секундный буфер на этой
            // машине приводил к треку, который не начинал играть: getPlaybackHeadPosition оставался 0
            // и весь ответ падал по drain timeout (проверено на ГУ 13.09.2026). Следующие предложения
            // пишутся в тот же трек; write блокируется, пока не освободится место, и это ровно то
            // перекрытие, которое нужно: предложение N+1 синтезируется до своей записи.
            at = new AudioTrack(attributes, format, Math.max(min, pcm.length), AudioTrack.MODE_STREAM, 0);
            if (at.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("AudioTrack not initialized");
            synchronized (PLAY_LOCK) {
                if (p.cancelled || playback != p) { status = 1; return; }
                p.track = at;
                at.play();
            }
            p.begin(); // Never invoke OEM code while holding PLAY_LOCK; serializes with end per utterance.
            for (int i = 0; i < parts.length && !p.cancelled; i++) {
                if (i > 0) pcm = synthSentence(parts[i], RATE);
                if (pcm.length == 0) throw new IllegalStateException("empty TeraTTS output");
                p.totalFrames += pcm.length / 2;
                for (int off = 0; off < pcm.length && !p.cancelled;) {
                    int n = at.write(pcm, off, Math.min(4800, pcm.length - off));
                    if (n <= 0) throw new IllegalStateException("AudioTrack write=" + n);
                    off += n;
                    recordPlayed(p, at);
                }
            }
            long deadline = android.os.SystemClock.elapsedRealtime()
                    + (p.totalFrames - (at.getPlaybackHeadPosition() & 0xffffffffL)) * 1000L / RATE + 2000;
            while (!p.cancelled && (at.getPlaybackHeadPosition() & 0xffffffffL) < p.totalFrames) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IllegalStateException("playback drain timeout");
                Thread.sleep(10);
            }
            if (!p.cancelled) p.reason = "drained";
            status = p.cancelled ? 1 : 0;
        } catch (Throwable t) {
            status = p.cancelled ? 1 : -1;
            if (!p.cancelled) { p.reason = "error:" + t.getClass().getSimpleName(); Log.e(TAG, "playback", t); }
        } finally {
            recordPlayed(p, at);
            synchronized (PLAY_LOCK) { p.track = null; if (playback == p) playback = null; }
            if (at != null) { try { at.stop(); } catch (Throwable ignored) {} try { at.release(); } catch (Throwable ignored) {} }
            if (am != null && focus != null) { try { am.abandonAudioFocusRequest(focus); } catch (Throwable ignored) {} }
            p.end(status);
        }
    }

    // ---- Text normalization before neural synthesis (moved here from the former PiperTts):
    //   - drop the degree sign ("26 °C" -> "26"); expand units with RU number agreement
    //     ("30%" -> "30 процентов", "5 км" -> "5 километров"); rephrase masculine-past
    //     confirmations as first-person present ("Установил" -> "Ставлю"). ---------------------
    public static String ttsNormalize(String t) {
        if (t == null) return "";
        String s = t.replaceAll("[°℃]\\s?[CcСс]?", "");          // degree unit
        s = expandUnit(s, "%",  "процент", "процента", "процентов");
        s = expandUnit(s, "км", "километр", "километра", "километров");
        s = femPresent(s);
        return s.replaceAll("\\s{2,}", " ").trim();
    }

    /** Replace "<number><unit>" with the number + the RU-agreeing word (1 процент / 2 процента / 5 процентов). */
    private static String expandUnit(String s, String unit, String one, String few, String many) {
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d+)\\s*" + java.util.regex.Pattern.quote(unit) + "(?![А-Яа-яA-Za-z])").matcher(s);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                String form;
                try { form = plural(Integer.parseInt(m.group(1)), one, few, many); }
                catch (Throwable e) { form = many; }
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(m.group(1) + " " + form));
            }
            m.appendTail(sb);
            return sb.toString();
        } catch (Throwable e) { return s; }
    }

    /** Russian quantity agreement: 1 -> one, 2..4 -> few, else many (with the 11..14 exception). */
    private static String plural(int n, String one, String few, String many) {
        int m100 = ((n % 100) + 100) % 100, m10 = m100 % 10;
        if (m100 >= 11 && m100 <= 14) return many;
        if (m10 == 1) return one;
        if (m10 >= 2 && m10 <= 4) return few;
        return many;
    }

    /** Rephrase a first-person past confirmation as first-person present (gender-neutral):
     *  «Установил»/«Установила» -> «Ставлю». Whole words only, and only the masculine/feminine singular —
     *  the plural «вы открыли мне новое» is someone else's action and must keep its own wording. */
    private static String femPresent(String s) {
        for (String[] p : PRESENT) {
            s = s.replaceAll("(?<![А-Яа-яЁё])" + p[0] + "а?(?![А-Яа-яЁё])", p[1]);
            s = s.replaceAll("(?<![А-Яа-яЁё])" + deCap(p[0]) + "а?(?![А-Яа-яЁё])", deCap(p[1]));
        }
        return s;
    }
    /** Masculine singular stem (the feminine adds «а») -> first-person present. */
    private static final String[][] PRESENT = {
        {"Установил","Ставлю"},{"Открыл","Открываю"},{"Закрыл","Закрываю"},
        {"Включил","Включаю"},{"Выключил","Выключаю"},{"Отключил","Отключаю"},
        {"Поднял","Поднимаю"},{"Опустил","Опускаю"},{"Увеличил","Увеличиваю"},
        {"Уменьшил","Уменьшаю"},{"Переключил","Переключаю"},{"Настроил","Настраиваю"},
        {"Запустил","Запускаю"},{"Сделал","Делаю"},{"Готов","Готова"}
    };

    private static String deCap(String w) {
        return w.isEmpty() ? w : Character.toLowerCase(w.charAt(0)) + w.substring(1);
    }

    // ---- Pure helpers shared with the build host (tests/precache.py compiles this region alone). ----------
    public static final String VOICE = "ru_f2";
    public static final int NATIVE_RATE = 44100;       // TeraTTS output rate
    public static final float SPEED = 0.9f;            // durationScale <1 = faster speech (user wanted quicker)

    /** Sentence chunks of normalized text: each is rendered and cached on its own, so a long reply starts
     *  playing after its first sentence and fixed sentences hit the cache inside variable replies. */
    public static String[] sentences(String normalized) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (normalized != null) for (String part : normalized.split("(?<=[.!?…])\\s+")) if (!part.trim().isEmpty()) out.add(part.trim());
        return out.toArray(new String[0]);
    }

    /** Everything besides the text that changes how a sentence sounds: the built-in stress lexicon and the
     *  frequency dictionary. Both the car and tests/precache.py must report the same string. */
    public static String accentVersion(long accentDictBytes) {
        return com.stand.tts.tera.TeraAccents.VERSION + "." + accentDictBytes;
    }

    /** Stable id of one rendered sentence (text, rate, speed, voice, stress data); the host pre-renderer
     *  computes it with the same code, so a stress change re-renders instead of serving stale audio. */
    public static String cacheKey(String sentence, int rate, String accentVersion) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1")
                    .digest((sentence + "@" + rate + "@" + SPEED + "@" + VOICE + "@" + accentVersion).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(40);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    /** Float mono @from → 16-bit LE PCM @to (linear resample, clipped). */
    public static byte[] pcm16(float[] s, int from, int to) {
        float[] rs = (from == to) ? s : resample(s, from, to);
        byte[] pcm = new byte[rs.length * 2];
        for (int i = 0; i < rs.length; i++) {
            int v = Math.round(rs[i] * 32767f);
            if (v > 32767) v = 32767; else if (v < -32768) v = -32768;
            pcm[i * 2] = (byte) (v & 0xff);
            pcm[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        return pcm;
    }

    /** Drop the least recently used renders until the directory fits the budget.
     *  Fully qualified so this method compiles as part of the pure slice the host tests extract. */
    public static int trimCache(java.io.File dir, long budget) {
        java.io.File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return 0;
        long total = 0;
        for (java.io.File f : files) total += f.length();
        if (total <= budget) return 0;
        java.util.Arrays.sort(files, new java.util.Comparator<java.io.File>() {
            public int compare(java.io.File a, java.io.File b) { return Long.compare(a.lastModified(), b.lastModified()); }
        });
        int removed = 0;
        for (java.io.File f : files) {
            if (total <= budget) break;
            long size = f.length();
            if (f.delete()) { total -= size; removed++; }
        }
        return removed;
    }

    private static float[] resample(float[] in, int from, int to) {
        if (from == to || in.length == 0) return in;
        int outLen = (int) ((long) in.length * to / from);
        float[] out = new float[outLen];
        double step = (double) from / to;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step; int j = (int) pos; double frac = pos - j;
            float a = in[j], b = (j + 1 < in.length) ? in[j + 1] : a;
            out[i] = (float) (a + (b - a) * frac);
        }
        return out;
    }

    private TeraTts() {}
}
