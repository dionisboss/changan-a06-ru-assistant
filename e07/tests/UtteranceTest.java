package com.stand.bridge;

public final class UtteranceTest {
    private static int checks;
    private static void check(boolean value, String reason) {
        checks++;
        if (!value) throw new AssertionError(reason);
    }
    private static byte[] pcm(int value, int samples) {
        byte[] bytes = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            bytes[i * 2] = (byte) value;
            bytes[i * 2 + 1] = (byte) (value >> 8);
        }
        return bytes;
    }
    public static void main(String[] args) {
        Utterance silent = new Utterance(100);
        check(!silent.finished(5099), "no-speech deadline early");
        check(silent.finished(5100), "no-speech deadline");
        check(!silent.hasSpeech(), "silence cannot decode");
        byte[] voice = pcm(-1500, 1600), quiet = pcm(30, 1600);
        Utterance spike = new Utterance(0);
        spike.accept(voice, voice.length, 100);
        spike.accept(quiet, quiet.length, 200);
        check(!spike.hasSpeech(), "100ms spike alone is not speech");
        Utterance phrase = new Utterance(0);
        phrase.accept(voice, voice.length, 100);
        phrase.accept(voice, voice.length, 200);
        check(phrase.hasSpeech(), "negative signed PCM onset");
        check(!phrase.finished(899), "silence endpoint early");
        check(phrase.finished(900), "silence endpoint");
        Utterance noise = new Utterance(0);
        for (int t = 100; t <= 15000; t += 100) noise.accept(voice, voice.length, t);
        check(noise.finished(15000), "continuous noise/speech max cap");
        Utterance shortRead = new Utterance(0);
        shortRead.accept(voice, 2, 100);
        check(!shortRead.hasSpeech(), "must respect actual read length");
        shortRead.accept(pcm(-32768, 2000), 4000, 200);
        check(shortRead.hasSpeech(), "full-scale PCM squares must not overflow");
        Utterance tail = new Utterance(900);
        check(!tail.hasSpeech() && !tail.finished(1000), "fresh endpoint after own TTS drops previous speech");
        Utterance.Levels levels = new Utterance.Levels();
        levels.add(pcm(-1500, 1600), 3200);
        check(levels.samples == 1600 && levels.rms() == 1500 && levels.peak == 1500, "negative PCM diagnostics");
        levels.reset();
        levels.add(pcm(-32768, 16000), 32000);
        check(levels.rms() == 32768 && levels.peak == 32768, "diagnostic accumulator cannot overflow at full scale");
        levels.reset();
        levels.add(pcm(3000, 100), 4);
        check(levels.samples == 2 && levels.rms() == 3000, "diagnostics respect actual read size");
        levels.reset();
        check(levels.samples == 0 && levels.rms() == 0 && levels.peak == 0, "empty diagnostic window");
        byte[] cabin = pcm(700, 1600), command = pcm(5000, 1600);
        Utterance calibrated = new Utterance(0, 1200);
        for (int time = 100; time <= 1000; time += 100) calibrated.accept(cabin, cabin.length, time);
        check(!calibrated.hasSpeech(), "measured cabin noise is below calibrated onset");
        calibrated.accept(command, command.length, 1100);
        calibrated.accept(command, command.length, 1200);
        check(calibrated.hasSpeech(), "measured voice starts recognition");
        for (int time = 1300; time <= 1800; time += 100) calibrated.accept(cabin, cabin.length, time);
        check(!calibrated.finished(1899) && calibrated.finished(1900),
                "700ms cabin-noise pause ends command before later conversation");
        Utterance knob = new Utterance(0, 600);
        knob.accept(cabin, cabin.length, 100); knob.accept(cabin, cabin.length, 200);
        check(knob.hasSpeech(), "per-session threshold is applied");
        Utterance invalidKnob = new Utterance(0, 99);
        invalidKnob.accept(cabin, cabin.length, 100); invalidKnob.accept(cabin, cabin.length, 200);
        check(!invalidKnob.hasSpeech(), "out-of-range threshold falls back to 1200");
        System.out.println("Utterance checks PASS " + checks);
    }
}
