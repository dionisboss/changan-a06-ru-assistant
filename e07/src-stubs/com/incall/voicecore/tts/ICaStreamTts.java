package com.incall.voicecore.tts;

/* JADX INFO: loaded from: classes5.dex */
public interface ICaStreamTts extends ICaTts {
    int endSession();

    int sendText(String str);

    int startSession(String str);

    default int sendText(String str, String str2) {
        return sendText(str);
    }
}
