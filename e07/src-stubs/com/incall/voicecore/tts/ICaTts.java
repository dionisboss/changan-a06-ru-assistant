package com.incall.voicecore.tts;

import com.incall.voicecore.ICaBase;

/* JADX INFO: loaded from: classes5.dex */
public interface ICaTts extends ICaBase {
    int create(ICaTtsCallback iCaTtsCallback);

    int destroy();

    int pause();

    int resume();

    int setParam(String str, String str2);

    int start(String str);

    int stop();

    default int start(String str, String str2) {
        return start(str);
    }

    default int start(String str, String str2, boolean z) {
        return start(str);
    }
}
