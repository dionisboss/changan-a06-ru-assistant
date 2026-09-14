package com.stand.tts;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Exercises the production per-request heartbeat with a fake clock and queued Handler. */
public final class TtsPlaybackTest {
    private static int checks;
    private static final Class<?> TYPE;
    private static final Constructor<?> CTOR;
    private static final Field CURRENT;
    private static final Method END, STOP;
    static {
        try {
            TYPE = Class.forName("com.stand.tts.TeraTts$Playback");
            CTOR = TYPE.getDeclaredConstructor(Object.class, int.class, boolean.class, TeraTts.PlaybackListener.class);
            CURRENT = TeraTts.class.getDeclaredField("playback");
            END = TYPE.getDeclaredMethod("end", int.class);
            STOP = TeraTts.class.getDeclaredMethod("stopPlayback", TYPE, String.class);
            CTOR.setAccessible(true); CURRENT.setAccessible(true); END.setAccessible(true); STOP.setAccessible(true);
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    private static final class Listener implements TeraTts.PlaybackListener {
        int progress, ends, status;
        Runnable duringProgress;
        public void begin() {}
        public void progress() { progress++; if (duringProgress != null) duringProgress.run(); }
        public void end(int status) { ends++; this.status = status; }
    }
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    private static Object create(Object owner, Listener listener) throws Exception {
        Object p = CTOR.newInstance(owner, 18, true, listener);
        CURRENT.set(null, p);
        return p;
    }
    public static void main(String[] args) throws Exception {
        Object owner = new Object();
        Listener first = new Listener();
        Object p = create(owner, first);
        ((Runnable) p).run();
        check(first.progress == 1 && android.os.Handler.pending(p), "heartbeat starts during synthesis");
        END.invoke(p, 0);
        END.invoke(p, 1);
        ((Runnable) p).run();
        check(first.ends == 1 && first.status == 0, "completion callback exactly once");
        check(!android.os.Handler.pending(p) && first.progress == 1, "end removes pulse and prevents reschedule");

        Listener expired = new Listener();
        p = create(owner, expired);
        android.os.SystemClock.now = 59999;
        ((Runnable) p).run();
        check(expired.progress == 1 && expired.ends == 0, "active before deadline");
        android.os.SystemClock.now = 60000;
        ((Runnable) p).run();
        check(expired.ends == 1 && expired.status == 1 && CURRENT.get(null) == null, "deadline cancels exact active request");
        check(!android.os.Handler.pending(p), "deadline removes pulse");

        Listener old = new Listener(), next = new Listener();
        Object stale = create(owner, old);
        Object replacement = create(owner, next);
        android.os.SystemClock.now = 120000;
        ((Runnable) stale).run();
        check(CURRENT.get(null) == replacement && next.ends == 0, "stale deadline cannot stop same-owner replacement");
        check(!(Boolean) STOP.invoke(null, stale, "timeout") && CURRENT.get(null) == replacement,
                "replacement race checked again inside stop");
        check((Boolean) STOP.invoke(null, replacement, "stop") && next.ends == 1 && next.status == 1,
                "manual stop ends immediately");
        ((Runnable) replacement).run();
        check(next.progress == 0 && !android.os.Handler.pending(replacement), "cancel never renews timer");

        Listener reentrant = new Listener();
        final Object same = create(owner, reentrant);
        reentrant.duringProgress = () -> {
            try { STOP.invoke(null, same, "stop"); } catch (Exception e) { throw new RuntimeException(e); }
        };
        ((Runnable) same).run();
        check(reentrant.ends == 1 && !android.os.Handler.pending(same), "callback cancellation cannot reschedule pulse");
        System.out.println("TTS playback checks PASS " + checks);
    }
}
