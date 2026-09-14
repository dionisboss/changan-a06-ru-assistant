import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Same host, alternating model order, fixed inputs/seed, no PCM cache. */
public final class CompareTts {
    public static void main(String[] args) throws Exception {
        String[] labels = {"a06", "e07"};
        Object[] engines = new Object[2];
        Method[] synth = new Method[2], normalize = new Method[2];
        Path out = Path.of(args[2]);
        Files.createDirectories(out);
        String[] phrases = Files.readAllLines(Path.of(args[3])).toArray(new String[0]);
        try {
            for (int i = 0; i < 2; i++) {
                Class<?> type = Class.forName("comparison." + labels[i] + ".tts.tera.TeraTTS");
                long start = System.nanoTime();
                engines[i] = type.getConstructor(Path.class, String.class).newInstance(Path.of(args[i]), "ru_f2");
                synth[i] = type.getMethod("synthesize", String.class, float.class);
                normalize[i] = Class.forName("comparison." + labels[i] + ".tts.TeraTts")
                        .getMethod("ttsNormalize", String.class);
                System.out.println("LOAD\t" + labels[i] + "\t" + (System.nanoTime() - start) / 1e6);
                for (int warmup = 0; warmup < 2; warmup++) synth[i].invoke(engines[i], "Чем могу помочь", 0.9f);
            }
            for (int round = 0; round < 3; round++) {
                for (int phrase = 0; phrase < phrases.length; phrase++) {
                    for (int order = 0; order < 2; order++) {
                        int i = (order + round) % 2;
                        String text = (String) normalize[i].invoke(null, phrases[phrase]);
                        long start = System.nanoTime();
                        float[] samples = (float[]) synth[i].invoke(engines[i], text, 0.9f);
                        double ms = (System.nanoTime() - start) / 1e6;
                        double sum = 0, peak = 0;
                        for (float sample : samples) {
                            if (!Float.isFinite(sample)) throw new AssertionError("Non-finite TTS output");
                            sum += sample * sample;
                            peak = Math.max(peak, Math.abs(sample));
                        }
                        if (samples.length == 0 || sum == 0) throw new AssertionError("Empty/silent TTS output");
                        if (round == 0) {
                            // Common PCM packing for listening; inference stays at native 44.1 kHz.
                            byte[] pcm = comparison.e07.tts.TeraTts.pcm16(samples, 44100, 44100);
                            try (AudioInputStream audio = new AudioInputStream(new ByteArrayInputStream(pcm),
                                    new AudioFormat(44100, 16, 1, true, false), samples.length)) {
                                AudioSystem.write(audio, AudioFileFormat.Type.WAVE,
                                        out.resolve(String.format("%02d-%s.wav", phrase + 1, labels[i])).toFile());
                            }
                        }
                        String encoded = Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
                        System.out.println("RESULT\t" + labels[i] + "\t" + round + "\t" + phrase + "\t" + ms
                                + "\t" + samples.length + "\t" + Math.sqrt(sum / samples.length) + "\t" + peak + "\t" + encoded);
                    }
                }
            }
        } finally {
            for (Object engine : engines) if (engine != null) ((AutoCloseable) engine).close();
        }
    }
}
