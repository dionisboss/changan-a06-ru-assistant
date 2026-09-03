/*
 * Russian Voice Assistant modification for Changan A06 (C390).
 * Copyright (c) 2026 Tecrow. All rights reserved.
 *
 * Required Notice: Copyright (c) 2026 Tecrow. Reverse engineering prohibited.
 * Required Notice: Noncommercial use only. See LICENSE (PolyForm Noncommercial 1.0.0).
 *
 * Licensed under the PolyForm Noncommercial License 1.0.0 — COMMERCIAL USE IS NOT PERMITTED.
 * Reverse engineering, decompilation, and disassembly are NOT permitted under this license,
 * except to the minimum extent applicable mandatory law expressly allows. This source and the
 * compiled result are protected by copyright; unauthorized redistribution is prohibited.
 * Independent mod — NOT affiliated with or endorsed by Changan Automobile. See LICENSE.
 */
package com.stand.asr;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * GigaAM-v3 CTC offline ASR backend (replaces Vosk). Runs on sherpa-onnx (the same native
 * libsherpa-onnx-jni.so we already bundle for Piper — it carries GigaAM `is_giga_am` fbank support).
 *
 * GigaAM is a full-utterance Conformer, NOT streaming: audio (16 kHz mono s16le) arrives as chunks
 * from the stock SR callback with a phase flag (wp==1 start .. wp==3 end). We accumulate the whole
 * utterance between wp==1 and wp==3, then run one greedy-CTC decode. sherpa computes the 64-bin
 * GigaAM log-mel (via the model's `is_giga_am` metadata) and the CTC decode internally, so no
 * hand-rolled feature extraction. Model assets (~224 MB int8) unpack from apk assets to filesDir once.
 */
public final class GigaAsr {
    private static final String TAG = "GigaAsr";
    private static final String ASSET_DIR = "gigaam";   // apk assets/gigaam: model.int8.onnx, tokens.txt
    private static final String MODEL = "model.int8.onnx";
    private static final String TOKENS = "tokens.txt";
    private static final int FEAT_DIM = 64;             // GigaAM v3 = 64 mel bins (NOT 80)
    private static final int RATE = 16000;
    private static final int THREADS = 3;               // big cluster on MT6897 (like TeraTTS)

    private static volatile OfflineRecognizer rec;
    private static Context appCtx;
    private static final ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 20);

    public static void init(final Context c) {
        appCtx = c.getApplicationContext();
        android.util.Log.i("GigaAsr", com.stand.vosk.VoskBridge.NOTICE);  // legal notice (anchored in dex)
        if (rec != null) return;
        new Thread(new Runnable() { public void run() { ensure(); } }).start();
    }

    private static synchronized boolean ensure() {
        if (rec != null) return true;
        try {
            if (appCtx == null) return false;
            File dir = new File(appCtx.getFilesDir(), ASSET_DIR);
            File onnx = new File(dir, MODEL), toks = new File(dir, TOKENS);
            if (!onnx.exists() || !toks.exists()) {
                Log.i(TAG, "unpacking GigaAM assets to " + dir + " (~224 MB, one-time)");
                unpackAssets(ASSET_DIR, dir);
            }
            OfflineNemoEncDecCtcModelConfig nemo =
                    OfflineNemoEncDecCtcModelConfig.builder().setModel(onnx.getAbsolutePath()).build();
            OfflineModelConfig model = OfflineModelConfig.builder()
                    .setNemo(nemo)
                    .setTokens(toks.getAbsolutePath())
                    .setNumThreads(THREADS)
                    .setDebug(false)
                    .setProvider("cpu")
                    .build();
            FeatureConfig feat = FeatureConfig.builder().setSampleRate(RATE).setFeatureDim(FEAT_DIM).build();
            OfflineRecognizerConfig cfg = OfflineRecognizerConfig.builder()
                    .setFeatureConfig(feat)
                    .setOfflineModelConfig(model)
                    .setDecodingMethod("greedy_search")
                    .build();
            long t0 = System.currentTimeMillis();
            rec = new OfflineRecognizer(cfg);
            Log.i(TAG, "recognizer ready in " + (System.currentTimeMillis() - t0) + "ms");
            return true;
        } catch (Throwable t) { Log.e(TAG, "ensure", t); return false; }
    }

    public static boolean ready() { return rec != null; }

    /** Start of a new utterance (wp==1): drop any leftover audio. */
    public static void reset() { synchronized (buf) { buf.reset(); } }

    /** Middle chunks: append raw 16-bit LE PCM. */
    public static void accept(byte[] pcm, int len) {
        if (pcm == null || len <= 0) return;
        synchronized (buf) { buf.write(pcm, 0, Math.min(len, pcm.length)); }
    }

    /** End of utterance (wp==3): decode accumulated PCM to Russian text (lowercase, no punctuation). */
    public static String finish() {
        if (!ensure()) { Log.e(TAG, "finish: recognizer not ready"); return ""; }
        byte[] b;
        synchronized (buf) { b = buf.toByteArray(); buf.reset(); }
        if (b.length < RATE / 25 * 2) return "";   // <40 ms of audio → nothing
        float[] f = new float[b.length / 2];
        for (int i = 0; i < f.length; i++) {
            int lo = b[2 * i] & 0xff, hi = b[2 * i + 1];   // hi keeps sign
            f[i] = ((short) ((hi << 8) | lo)) / 32768f;
        }
        OfflineStream st = null;
        try {
            long t0 = System.currentTimeMillis();
            st = rec.createStream();
            st.acceptWaveform(f, RATE);
            rec.decode(st);
            String text = rec.getResult(st).getText();
            Log.i(TAG, "decoded " + f.length + " samples (" + (f.length * 1000L / RATE) + "ms) in "
                    + (System.currentTimeMillis() - t0) + "ms: " + text);
            return text == null ? "" : text.trim();
        } catch (Throwable t) { Log.e(TAG, "finish", t); return ""; }
        finally { if (st != null) try { st.release(); } catch (Throwable ignored) {} }
    }

    private static void unpackAssets(String assetDir, File outDir) throws Exception {
        AssetManager am = appCtx.getAssets();
        String[] list = am.list(assetDir);
        if (list == null || list.length == 0) {   // it's a file
            outDir.getParentFile().mkdirs();
            InputStream in = am.open(assetDir);
            OutputStream out = new FileOutputStream(outDir);
            byte[] bb = new byte[1 << 16]; int n;
            while ((n = in.read(bb)) > 0) out.write(bb, 0, n);
            in.close(); out.close();
            return;
        }
        outDir.mkdirs();
        for (String name : list) unpackAssets(assetDir + "/" + name, new File(outDir, name));
    }

    private GigaAsr() {}
}