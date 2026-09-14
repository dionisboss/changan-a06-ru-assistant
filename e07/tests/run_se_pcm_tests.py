#!/usr/bin/env python3
"""Check SePcm against the actual decompiled OEM PcmConvertor, without Android or JNI."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-26-openjdk"))
oem = Path(os.environ.get("E07_OEM_CONVERTER", root / "out/oem/sources/com/iflytek/speech/se/PcmConvertor.java"))
files = {
    "android/util/Log.java": """
package android.util;
public class Log {
    public static int count;
    public static int i(String tag, String message) { count++; System.out.println(message); return 0; }
}
""",
    "com/incall/apps/voicebase/consts/SeConst.java": """
package com.incall.apps.voicebase.consts;
public class SeConst {
    public static final String PARAM_WORK_MODE_VALUE_MAB = "mab", PARAM_WORK_MODE_VALUE_MAE = "mae";
}
""",
    "com/iflytek/speech/LibISSSE2.java": """
package com.iflytek.speech;
public class LibISSSE2 {
    public static int extractAudio(byte[] data, int length, int type, byte[] out, int size, int[] params) {
        throw new AssertionError("The Java OEM extractor must not call JNI");
    }
}
""",
    "com/incall/apps/speechassistant/se/SeSession.java": """
package com.incall.apps.speechassistant.se;
import com.iflytek.speech.se.PcmConvertor;
public class SeSession {
    private static final SeSession INSTANCE = new SeSession();
    public static final PcmConvertor CONVERTER = new PcmConvertor();
    public static byte[] input;
    public static boolean corrupt;
    public static SeSession getInstance() { return INSTANCE; }
    public byte[] processPcmData(int type, byte[] frame, int channels) {
        if (type != 4 || channels != 2) throw new AssertionError("Wrong OEM extractor arguments");
        input = frame;
        byte[] pcm = CONVERTER.processPcm(type, frame);
        if (corrupt && pcm != null) pcm[0] ^= 1;
        return pcm;
    }
}
""",
    "com/stand/bridge/SePcmTest.java": """
package com.stand.bridge;
import com.incall.apps.speechassistant.se.SeSession;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
public class SePcmTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void put(byte[] data, int at, int value) {
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putInt(at, value);
    }
    private static byte[] frame() {
        byte[] data = new byte[3584];
        byte[] magic = "IFLYAUTOISS".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, data, 0, magic.length);
        put(data, 16, 2); put(data, 20, data.length); put(data, 24, 4);
        for (int i = 0; i < 3; i++) {
            int at = 64 + i * 16;
            put(data, at, i + 2); put(data, at + 4, i * 512);
            put(data, at + 8, 512); put(data, at + 12, 1);
            for (int j = 0; j < 512; j += 2) {
                int sample = 100 + i * 500 + j / 2;
                data[512 + i * 512 + j] = (byte) sample;
                data[513 + i * 512 + j] = (byte) (sample >> 8);
            }
        }
        put(data, 112, 6); // Unsupported empty descriptor is valid.
        return data;
    }
    private static String reject(byte[] data, int length) throws Exception {
        try { SePcm.extract(data, length); }
        catch (IOException expected) { return expected.getMessage(); }
        throw new AssertionError("Malformed/unsupported SE frame accepted");
    }
    private static void rejectField(int at, int value) throws Exception {
        byte[] data = frame(); put(data, at, value); reject(data, data.length);
    }
    public static void main(String[] args) throws Exception {
        byte[] data = frame();
        byte[] expected = Arrays.copyOfRange(data, 512, 1024);
        byte[] pcm = SePcm.extract(data, data.length);
        check(Arrays.equals(pcm, expected) && pcm.length == 512, "MAB ASR extraction");
        check(SeSession.input != data, "OEM must receive a private frame copy");
        check(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(2) == 22860, "fixture magic peak");
        check(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(4) == 21825, "old lane0 magic peak");
        for (int i = 0; i < pcm.length; i += 2) {
            check(Math.abs((short) ((pcm[i] & 255) | (pcm[i + 1] << 8))) < 22860, "magic leaked into PCM");
        }
        Arrays.fill(data, (byte) 0); Arrays.fill(SeSession.input, (byte) 0);
        check(Arrays.equals(pcm, expected), "caller/OEM buffer reuse changed extracted audio");
        SeSession.CONVERTER.setMode("mae");
        data = frame();
        check(Arrays.equals(SePcm.extract(data, data.length), Arrays.copyOfRange(data, 1536, 2048)), "MAE OMNI extraction");
        rejectField(108, 2); // Selected OMNI has two channels.
        SeSession.CONVERTER.setMode("mab");
        // JNI returned a 7168-byte packet, but ISSSeImpl still reports its old constant 3584.
        data = Arrays.copyOf(frame(), 7168); put(data, 20, data.length);
        System.arraycopy(data, 512, data, 4608, 512); put(data, 68, 4096);
        check(Arrays.equals(SePcm.extract(data, 3584), Arrays.copyOfRange(data, 4608, 5120)), "ASR beyond callback length");
        check(SeSession.input.length == 7168, "full native packet was not copied");
        SeSession.CONVERTER.setMode("mae");
        check(Arrays.equals(SePcm.extract(data, 3584), Arrays.copyOfRange(data, 1536, 2048)), "padded MAE extraction");
        SeSession.CONVERTER.setMode("mab");
        reject(Arrays.copyOf(data, 3584), 3584); // No zero-padding a truncated native packet.
        data = frame();
        check(Arrays.equals(SePcm.extract(data, 3583), Arrays.copyOfRange(data, 512, 1024)), "callback length is advisory");
        reject(null, 512); reject(frame(), 0); reject(frame(), -1); reject(frame(), 3585);
        data = frame(); data[0] = 'X'; reject(data, data.length);
        rejectField(20, 1024); rejectField(24, 29); rejectField(24, -1); rejectField(24, 0);
        rejectField(68, -1); rejectField(68, 3073); rejectField(80, 2); // Bounds and duplicate type.
        data = frame(); put(data, 72, -1); put(data, 76, -1); reject(data, data.length); // Unsigned overflow.
        rejectField(76, 2); rejectField(72, 256); rejectField(64, 1); // Wrong channel/size/missing ASR.
        data = frame(); Arrays.fill(data, 512, data.length, (byte) 0);
        put(data, 72, 256); put(data, 76, 2); // ASR stereo silence must not match valid mono OMNI silence.
        SeSession.input = null;
        String error = reject(data, data.length);
        check(SeSession.input == null, "unsupported speech layout reached OEM conversion");
        check(error.contains("type=6") && error.contains("callbackBytes=3584")
                && error.contains("arrayBytes=3584") && error.contains("headerBytes=3584"), "rejection must show the complete layout");
        data = Arrays.copyOf(frame(), 65537); put(data, 20, data.length); reject(data, data.length);
        data = frame(); put(data, 72, 0); put(data, 104, 0); reject(data, data.length); // No speech stream.
        SeSession.corrupt = true; reject(frame(), 3584); SeSession.corrupt = false;
        check(android.util.Log.count == 1, "successful layout must log only once");
        System.out.println("SE PCM: OEM MAB/MAE extraction, ownership and malformed-frame rejection passed");
    }
}
""",
}
with tempfile.TemporaryDirectory(prefix="e07-se-pcm-tests-") as temporary:
    build = Path(temporary)
    sources = [root / "src/com/stand/bridge/SePcm.java", oem]
    for name, content in files.items():
        path = build / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(path)
    subprocess.run([str(java_home / "bin/javac"), "-encoding", "UTF-8", "-d", str(build),
                    *map(str, sources)], check=True)
    subprocess.run([str(java_home / "bin/java"), "-cp", str(build), "com.stand.bridge.SePcmTest"],
                   check=True, timeout=15)
