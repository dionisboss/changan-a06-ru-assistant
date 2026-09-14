package com.incall.voicecore.tts;

/* JADX INFO: loaded from: classes5.dex */
public interface ICaTtsCallback {
    int onAudioData(byte[] bArr, int i);

    void onMessage(int i, String str);

    void onProgress(int i, int i2, String str);
}
