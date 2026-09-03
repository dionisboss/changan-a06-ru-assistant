package com.incall.voicecore.tts;
// COMPILE-ONLY stub (real one ships in the app's dex). Signatures match the decompiled interface:
//   abstract: create/destroy/pause/resume/setParam/stop ; default: start(String), start(String,String,boolean,cb)
public interface ICaTts extends com.incall.voicecore.ICaBase {
    int create(ICaTtsCallback cb);
    int destroy();
    int pause();
    int resume();
    int setParam(String key, String value);
    default int start(String text) { return start(text, "", false, null); }
    default int start(String text, String param, boolean flag, ICaTtsCallback cb) { return 0; }
    int stop();
}
