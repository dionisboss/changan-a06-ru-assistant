package com.incall.voicecore.tts;
// COMPILE-ONLY stub (real one ships in the app's dex). Streaming engine variant used by the stock
// StreamTtsEngineProxy (iFlytek IssTtsStreamImpl, Changan ChanganTtsImpl implement this).
public interface ICaStreamTts extends ICaTts {
    int startSession(String sid);
    int sendText(String text);
    default int sendText(String text, String param) { return sendText(text); }
    int endSession();
}
