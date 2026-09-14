package com.stand.bridge;

/** Bounded one-shot endpointing for corrected 16 kHz mono PCM16. Threshold needs cabin validation. */
final class Utterance {
    static final long MAX_MS = 15000;
    static final long NO_SPEECH_MS = 5000;
    static final long SILENCE_MS = 700;
    static final int DEFAULT_RMS_THRESHOLD = 1200;
    private static final int ONSET_SAMPLES = 16000 * 120 / 1000;
    private final long started;
    private final int rmsThreshold;
    private long lastVoice;
    private int voicedSamples;
    private boolean speech;

    /** Aggregate diagnostics only: no audio is retained by this counter. */
    static final class Levels {
        long samples;
        long squares;
        int peak;
        void add(byte[] pcm, int length) {
            int count = Math.min(length, pcm.length) / 2;
            for (int i = 0; i < count; i++) {
                int value = (short) ((pcm[i * 2] & 255) | (pcm[i * 2 + 1] << 8));
                squares += (long) value * value;
                peak = Math.max(peak, Math.abs(value));
            }
            samples += count;
        }
        int rms() { return samples == 0 ? 0 : (int) Math.sqrt(squares / samples); }
        void reset() { samples = 0; squares = 0; peak = 0; }
    }

    Utterance(long started) { this(started, DEFAULT_RMS_THRESHOLD); }
    Utterance(long started, int rmsThreshold) {
        this.started = started;
        this.rmsThreshold = rmsThreshold >= 100 && rmsThreshold <= 10000 ? rmsThreshold : DEFAULT_RMS_THRESHOLD;
    }

    void accept(byte[] pcm, int length, long now) {
        int samples = Math.min(length, pcm.length) / 2;
        if (samples <= 0) return;
        long squares = 0;
        for (int i = 0; i < samples; i++) {
            int value = (short) ((pcm[i * 2] & 255) | (pcm[i * 2 + 1] << 8));
            squares += (long) value * value;
        }
        if (squares / samples >= (long) rmsThreshold * rmsThreshold) {
            voicedSamples += samples;
            if (voicedSamples >= ONSET_SAMPLES) speech = true;
            lastVoice = now;
        } else {
            voicedSamples = 0;
        }
    }

    boolean hasSpeech() { return speech; }
    boolean finished(long now) {
        return now - started >= MAX_MS
                || (!speech && now - started >= NO_SPEECH_MS)
                || (speech && now - lastVoice >= SILENCE_MS);
    }

}
