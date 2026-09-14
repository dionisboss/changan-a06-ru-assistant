package com.stand.bridge;

import android.util.Log;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;

/** The SE callback carries an IFLYAUTOISS container; OEM type 4 extracts its speech PCM. */
public final class SePcm {
    private static final byte[] MAGIC = {'I', 'F', 'L', 'Y', 'A', 'U', 'T', 'O', 'I', 'S', 'S', 0, 0, 0, 0, 0};
    private static Object session;
    private static Method convert;
    private static boolean logged;

    private SePcm() {}

    // ponytail: serialize both taps; separate locks only if capture profiling shows contention.
    public static synchronized byte[] extract(byte[] frame, int length) throws Exception {
        int arrayLength = frame == null ? 0 : frame.length;
        long headerLength = arrayLength >= 24 ? u32(frame, 20) : 0;
        String sizes = "callbackBytes=" + length + " arrayBytes=" + arrayLength + " headerBytes=" + headerLength;
        if (length <= 0 || length > arrayLength || headerLength < 512
                || headerLength > 65536 || headerLength > arrayLength) {
            throw new IOException("Invalid SE frame length: " + sizes);
        }
        // ISSSeImpl reports a stale 3584. Native ISSSEProcess/JNI copy the full packet length at +20.
        byte[] copy = Arrays.copyOf(frame, (int) headerLength);
        for (int i = 0; i < MAGIC.length; i++) {
            if (copy[i] != MAGIC[i]) throw new IOException("Missing IFLYAUTOISS header: " + sizes);
        }
        long count = u32(copy, 24);
        if (u32(copy, 20) != headerLength || count == 0 || count > (512 - 64) / 16) {
            throw new IOException("Invalid SE header: " + sizes + " count=" + count);
        }
        HashSet<Long> types = new HashSet<Long>();
        StringBuilder layout = new StringBuilder(sizes).append(" count=").append(count);
        String invalid = null;
        int asr = -1, omni = -1;
        for (int i = 0; i < count; i++) {
            int at = 64 + i * 16;
            long type = u32(copy, at), offset = u32(copy, at + 4);
            long bytes = u32(copy, at + 8), channels = u32(copy, at + 12);
            layout.append(" [type=").append(type).append(" offset=").append(offset)
                    .append(" bytes=").append(bytes).append(" channels=").append(channels).append(']');
            // Division avoids overflow even for malformed unsigned 32-bit fields. Empty entries are allowed.
            if (!types.add(type) || offset > headerLength - 512
                    || (bytes != 0 && channels > (headerLength - 512 - offset) / bytes)) {
                invalid = "Invalid SE descriptor";
            }
            if ((type == 2 || type == 4) && bytes != 0 && channels != 0
                    && (bytes != 512 || channels != 1)) {
                invalid = "Unsupported SE speech descriptor";
            }
            if (bytes == 512 && channels == 1) {
                if (type == 2) asr = 512 + (int) offset;
                if (type == 4) omni = 512 + (int) offset;
            }
        }
        if (invalid != null) throw new IOException(invalid + ": " + layout);
        if (asr < 0 && omni < 0) throw new IOException("No mono 256-sample SE speech stream: " + layout);
        byte[] pcm;
        try {
            if (convert == null) {
                Class<?> type = Class.forName("com.incall.apps.speechassistant.se.SeSession");
                session = type.getMethod("getInstance").invoke(null);
                convert = type.getMethod("processPcmData", int.class, byte[].class, int.class);
            }
            pcm = (byte[]) convert.invoke(session, 4, copy, 2);
        } catch (Exception error) {
            throw new IOException("OEM SE extraction failed: " + layout, error);
        }
        boolean isAsr = matches(pcm, copy, asr), isOmni = matches(pcm, copy, omni);
        if (!isAsr && !isOmni) throw new IOException("OEM output is not mono 256-sample ASR/OMNI: " + layout);
        if (!logged) {
            logged = true;
            Log.i("SePcm", "SE PCM16 mono 16000: matchedType="
                    + (isAsr ? (isOmni ? "2/4" : "2") : "4") + " " + layout);
        }
        return pcm;
    }

    private static boolean matches(byte[] pcm, byte[] frame, int offset) {
        if (pcm == null || pcm.length != 512 || offset < 0) return false;
        for (int i = 0; i < pcm.length; i++) if (pcm[i] != frame[offset + i]) return false;
        return true;
    }

    private static long u32(byte[] data, int at) {
        return (data[at] & 255L) | ((data[at + 1] & 255L) << 8)
                | ((data[at + 2] & 255L) << 16) | ((data[at + 3] & 255L) << 24);
    }
}
