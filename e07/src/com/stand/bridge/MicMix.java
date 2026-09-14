package com.stand.bridge;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Экспериментальная подмена MIC потоком слушателя 2 движка SE (штатный wake).
 * Формат потока и работоспособность звонка с этим трактом на машине ещё не проверены.
 * Собственный физический вход не открывается; MODE_NORMAL — дополнительная защита,
 * но не доказательство исправления звонков.
 *
 * Выключено по умолчанию: {@code settings put global e07_mic_se 1} включает этот тракт,
 * только при {@code e07_mic_fix=0}. Перед включением дождаться освобождения ресурсов
 * AcsCenter: опрос флагов двумя процессами не обеспечивает атомарного переключения.
 */
public final class MicMix {
    private static final String TAG = "RuMicMix";
    private static volatile AudioTrack track;
    private static volatile AudioTrack failedTrack;
    private static AudioPolicy policy;
    private static boolean initialized;
    private static int activeRate;
    private static long nextLog;
    private static boolean logged;
    private static volatile boolean probePending = true;

    private MicMix() {}

    /** Контроль работает и тогда, когда SE перестал присылать буферы (например, на звонке). */
    public static synchronized void init(final Context ctx) {
        if (initialized || ctx == null) return;
        final AudioManager manager = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (manager == null) return;
        final Handler handler = new Handler(Looper.getMainLooper());
        initialized = handler.post(new Runnable() {
            public void run() {
                try {
                    sync(ctx, manager);
                } catch (Throwable error) {
                    stop(manager);
                    Log.e(TAG, "mic mix control failed", error);
                } finally {
                    handler.postDelayed(this, 500);
                }
            }
        });
    }

    /** Вызывается из пропатченного {@code SeSession$1.appendData([BII)V} — насквозь.
     *
     *  Буфер здесь нельзя забирать: в отличие от слушателя 3, этот кормит слово
     *  пробуждения, и если не пустить его дальше, помощник перестанет просыпаться.
     *  На входе контейнер IFLYAUTOISS; перед AudioTrack требуется штатное извлечение
     *  ASR/OMNI. Третий аргумент не описывает формат извлечённого PCM. */
    public static void feed(byte[] buffers, int length, int tag) {
        // One real-frame check after process startup, also when mixing is off. It never
        // opens an input/policy or sends speech to ASR; stock wake keeps the original frame.
        if (probePending && buffers != null && length > 0) {
            probePending = false;
            try {
                byte[] pcm = SePcm.extract(buffers, length);
                Log.i(TAG, "SE preflight OK: callbackBytes=" + length + " arrayBytes=" + buffers.length
                        + " pcmBytes=" + pcm.length + " tag=" + tag);
            } catch (Throwable error) {
                Log.e(TAG, "SE preflight failed; stock wake continues", error);
            }
        }
        AudioTrack sink = track;
        if (sink == null || sink == failedTrack || buffers == null || length <= 0) return;
        // Никаких settings/register/unregister на штатном wake callback. Ошибка или
        // гонка с release() не должна мешать передаче исходного буфера движку wake.
        try {
            byte[] pcm = SePcm.extract(buffers, length);
            int n = pcm.length;
            long now = SystemClock.elapsedRealtime();
            if (!logged) {
                logged = true;
                nextLog = now + 10000;
                Log.i(TAG, "SE wake tap: bytes=" + n + " tag=" + tag + " rate=" + activeRate
                        + " peak=" + peak(pcm, n));
            } else if (now >= nextLog) {
                nextLog = now + 10000;
                Log.i(TAG, "mic mix alive bytes=" + n + " peak=" + peak(pcm, n)
                        + " underruns=" + sink.getUnderrunCount());
            }
            // ponytail: короткий write теряет хвост; очередь нужна только после замеров,
            // если это слышно. Штатный wake callback нельзя задерживать ожиданием выхода.
            int written = sink.write(pcm, 0, n, AudioTrack.WRITE_NON_BLOCKING);
            if (written < 0) throw new IllegalStateException("ошибка передачи: " + written);
        } catch (Throwable error) {
            failedTrack = sink;
            Log.e(TAG, "mic mix feed failed; stock wake continues", error);
        }
    }

    private static void sync(Context ctx, AudioManager manager) {
        boolean wanted = enabled(ctx) && manager.getMode() == AudioManager.MODE_NORMAL;
        int sampleRate = rate(ctx);
        if (track != null && (!wanted || activeRate != sampleRate || track == failedTrack)) stop(manager);
        if (wanted && track == null) start(ctx, manager, sampleRate);
    }

    private static void start(Context ctx, AudioManager manager, int sampleRate) {
        AudioPolicy built = null;
        AudioTrack sink = null;
        boolean registered = false;
        try {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setCapturePreset(MediaRecorder.AudioSource.MIC).build();
            // Отбор получателей по UID здесь невозможен: RULE_MATCH_UID у Android 11 —
            // правило проигрывания, рядом с capture preset даёт «Incompatible rule for
            // mix» (проверено на машине 13.09.2026). Правило затронет любого клиента MIC,
            // включая штатного, если он использует MIC; UID здесь не ограничивается.
            AudioMixingRule rule = new AudioMixingRule.Builder()
                    .addMixRule(AudioMixingRule.RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET, attributes).build();
            AudioFormat format = new AudioFormat.Builder().setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
            AudioMix mix = new AudioMix.Builder(rule).setFormat(format)
                    .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK).build();
            built = new AudioPolicy.Builder(ctx).addMix(mix).build();
            int result = manager.registerAudioPolicy(built);
            if (result != 0) {
                Log.e(TAG, "не удалось включить маршрутизацию: " + result);
                return;
            }
            registered = true;
            sink = built.createAudioTrackSource(mix);
            if (sink == null || sink.getState() != AudioTrack.STATE_INITIALIZED) {
                throw new IllegalStateException("не удалось открыть выход микрофона");
            }
            sink.play();
            policy = built;
            activeRate = sampleRate;
            logged = false;
            track = sink;
            Log.i(TAG, "mic mix on: SE listener 2 -> MIC " + sampleRate);
        } catch (Throwable error) {
            Log.e(TAG, "mic mix start failed", error);
        } finally {
            if (track != sink || sink == null) {
                try { if (sink != null) sink.release(); } catch (Throwable ignored) {}
                try { if (registered) manager.unregisterAudioPolicy(built); } catch (Throwable ignored) {}
            }
        }
    }

    private static void stop(AudioManager manager) {
        AudioTrack sink = track;
        AudioPolicy built = policy;
        track = null;
        failedTrack = null;
        policy = null;
        try {
            if (sink != null) sink.release();
        } catch (Throwable ignored) {
        } finally {
            try { if (built != null) manager.unregisterAudioPolicy(built); } catch (Throwable ignored) {}
            Log.i(TAG, "mic mix off");
        }
    }

    /** При конфликте переключателей оставляем старый тракт; флаги сами не меняем. */
    private static boolean enabled(Context ctx) {
        try {
            return android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), "e07_mic_se", 0) != 0
                    && android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), "e07_mic_fix", 1) == 0;
        } catch (Throwable ignored) { return false; }
    }

    /** Частота — проверяемое предположение, лог показывает настройку, а не измерение PCM.
     *  Смена e07_mic_rate пересоздаёт политику и track на следующем опросе. */
    private static int rate(Context ctx) {
        try {
            int value = android.provider.Settings.Global.getInt(
                    ctx.getContentResolver(), "e07_mic_rate", 16000);
            return value >= 8000 && value <= 48000 ? value : 16000;
        } catch (Throwable ignored) { return 16000; }
    }

    private static int peak(byte[] data, int n) {
        int max = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int sample = Math.abs((short) ((data[i] & 0xff) | (data[i + 1] << 8)));
            if (sample > max) max = sample;
        }
        return max;
    }
}
