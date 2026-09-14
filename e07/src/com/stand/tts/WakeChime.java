package com.stand.tts;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * The stock wake-up chime (SpeechAssistant res/raw/wakeup.wav — 44.1 kHz stereo 24-bit, ~1 s), decoded
 * once into 16-bit mono PCM at the TTS player rate, so PiperCaTts can push it through the SAME native
 * TTS route instead of voicing the wake greeting. The stock app ships this sound (SoundManager.
 * playWakeupSounds) but never calls it — it voices "我在" via TTS; we swap the audio, not the flow.
 */
public final class WakeChime {
    private static final String TAG = "WakeChime";
    private static final float GAIN = 0.9f;
    private static byte[] cache;
    private static boolean failed;

    private WakeChime() {}

    /** 16-bit LE mono PCM at outRate, or null if the resource is missing/undecodable (caller falls back to speech). */
    public static synchronized byte[] pcm16(Context ctx, int outRate) {
        if (cache != null) return cache;
        if (failed || ctx == null) return null;
        try {
            InputStream in; String src;
            try {                                              // bundled: stock sr.mp3 pre-converted (build_sa WAKE_CHIME)
                in = ctx.getAssets().open("stand/wake_chime.wav"); src = "assets/stand/wake_chime.wav";
            } catch (Throwable noAsset) {                      // fallback: stock R.raw.wakeup
                Resources r = ctx.getResources();
                int id = r.getIdentifier("wakeup", "raw", ctx.getPackageName());
                if (id == 0) id = 0x7f100004;
                in = r.openRawResource(id); src = "res/raw/wakeup.wav";
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            cache = wavToPcm16Mono(bo.toByteArray(), outRate);
            Log.i(TAG, "decoded " + src + " -> " + cache.length + " bytes @" + outRate);
        } catch (Throwable t) {
            failed = true;
            Log.e(TAG, "wakeup.wav decode failed", t);
        }
        return cache;
    }

    /** Sum two 16-bit LE mono PCM buffers sample-by-sample (both start at t=0; result = longer length),
     *  hard-clamped to 16-bit. Used to play the chime IN PARALLEL with the spoken voice-wake greeting. */
    public static byte[] mix(byte[] a, byte[] b) {
        if (a == null || a.length == 0) return b;
        if (b == null || b.length == 0) return a;
        int n = Math.max(a.length, b.length) & ~1;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i += 2) {
            int sa = i + 1 < a.length ? (short) ((a[i] & 0xff) | (a[i + 1] << 8)) : 0;
            int sb = i + 1 < b.length ? (short) ((b[i] & 0xff) | (b[i + 1] << 8)) : 0;
            int s = Math.max(-32768, Math.min(32767, sa + sb));
            out[i] = (byte) (s & 0xff); out[i + 1] = (byte) ((s >> 8) & 0xff);
        }
        return out;
    }

    private static int le16(byte[] b, int p) { return (b[p] & 0xff) | (b[p + 1] & 0xff) << 8; }
    private static int le32(byte[] b, int p) { return le16(b, p) | le16(b, p + 2) << 16; }

    /** Minimal RIFF/WAVE PCM decoder (walks chunks — the stock file has a JUNK chunk before fmt). */
    static byte[] wavToPcm16Mono(byte[] w, int outRate) {
        if (w.length < 12 || w[0] != 'R' || w[1] != 'I' || w[2] != 'F' || w[3] != 'F') throw new IllegalArgumentException("not RIFF");
        int channels = 0, rate = 0, bits = 0, dataOff = -1, dataLen = 0, p = 12;
        while (p + 8 <= w.length) {
            String id = new String(w, p, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int sz = le32(w, p + 4);
            if ("fmt ".equals(id)) { channels = le16(w, p + 10); rate = le32(w, p + 12); bits = le16(w, p + 22); }
            else if ("data".equals(id)) { dataOff = p + 8; dataLen = Math.min(sz, w.length - dataOff); break; }
            p += 8 + sz + (sz & 1);
        }
        if (dataOff < 0 || channels <= 0 || rate <= 0 || (bits != 8 && bits != 16 && bits != 24 && bits != 32))
            throw new IllegalArgumentException("unsupported wav: ch=" + channels + " rate=" + rate + " bits=" + bits);
        int bps = bits / 8, frameBytes = bps * channels, frames = dataLen / frameBytes;
        // mono mix as float [-1,1]
        float[] mono = new float[frames];
        for (int f = 0; f < frames; f++) {
            float acc = 0;
            for (int c = 0; c < channels; c++) {
                int q = dataOff + f * frameBytes + c * bps;
                float s;
                switch (bits) {
                    case 8:  s = ((w[q] & 0xff) - 128) / 128f; break;
                    case 16: s = (short) le16(w, q) / 32768f; break;
                    case 24: s = (((w[q] & 0xff) | (w[q + 1] & 0xff) << 8 | (w[q + 2]) << 16)) / 8388608f; break;
                    default: s = le32(w, q) / 2147483648f; break;
                }
                acc += s;
            }
            mono[f] = acc / channels;
        }
        // linear resample rate -> outRate
        int outFrames = (int) ((long) frames * outRate / rate);
        byte[] out = new byte[outFrames * 2];
        double step = (double) rate / outRate;
        for (int i = 0; i < outFrames; i++) {
            double pos = i * step; int i0 = (int) pos; int i1 = Math.min(i0 + 1, frames - 1);
            float fr = (float) (pos - i0);
            float v = (mono[i0] * (1 - fr) + mono[i1] * fr) * GAIN;
            int s = Math.round(Math.max(-1f, Math.min(1f, v)) * 32767f);
            out[i * 2] = (byte) (s & 0xff); out[i * 2 + 1] = (byte) ((s >> 8) & 0xff);
        }
        return out;
    }
}
