package com.incall.voicecore;
import android.content.Context;
// COMPILE-ONLY stub (real one ships in the app's dex). Base of ICaTts.
// setResDir is abstract; setContext/setVin are default methods in the real interface.
public interface ICaBase {
    default void setContext(Context ctx) {}
    void setResDir(String dir);
    default void setVin(String vin) {}
}
