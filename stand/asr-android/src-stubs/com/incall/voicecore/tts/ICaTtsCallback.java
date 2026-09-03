package com.incall.voicecore.tts;
// COMPILE-ONLY stub of the stock interface (provided by the app's own dex at runtime).
// Must NOT be included in classes7.dex — see build_dex.sh (compiled to build/stubs, off the dex input).
public interface ICaTtsCallback {
    int onAudioData(byte[] pcm, int len);
    void onMessage(int code, String msg);
    void onProgress(int a, int b, String s);
}
