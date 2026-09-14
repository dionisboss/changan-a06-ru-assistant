package com.stand.bridge;

import com.stand.tts.TeraTts;
import com.stand.tts.tera.TeraTTS;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;

/** Host pre-renderer: every fixed Russian reply sentence → <out>/<key>.pcm (24 kHz mono PCM16), same engine,
 *  same accent lexicon as the car. Composed replies (help topics, trunk acknowledgements, seat greetings)
 *  are generated here because they never appear as one string literal. */
public final class Precache {
    public static void main(String[] args) throws Exception {
        Path deps = Paths.get(args[0]), phrases = Paths.get(args[1]), out = Paths.get(args[2]);
        String accents = TeraTts.accentVersion(Files.size(deps.resolve("assets/tera/ruaccent.bin")));
        System.out.println("accent version " + accents);
        Files.createDirectories(out);
        LinkedHashSet<String> texts = new LinkedHashSet<>(Files.readAllLines(phrases));
        Ru2Zh.ALLOW_TRUNK = true; Ru2Zh.ALLOW_UNSAFE = false;
        for (int i = 0; i <= CommandHelp.TOPICS.length; i++) texts.add(CommandHelp.spoken(i));
        for (String action : new String[]{"открыть", "закрыть", "остановить"})
            for (String label : new String[]{"нижний борт", "крышу багажника", "заднее стекло", "стекло перегородки",
                    "весь задний отсек", "нижний борт и крышу багажника"})
                texts.add("Передала команду: " + action + " " + label);
        for (String seat : new String[]{"Пассажир", "Слева сзади", "Справа сзади", "По центру", "Сзади"})
            texts.add(seat + ", чем могу помочь");
        TreeMap<String, String> index = new TreeMap<>();
        int rendered = 0;
        try (TeraTTS engine = new TeraTTS(deps.resolve("assets/tera"), TeraTts.VOICE)) {
            for (String text : texts) {
                if (text.isBlank()) continue;
                for (String sentence : TeraTts.sentences(TeraTts.ttsNormalize(text))) {
                    String key = TeraTts.cacheKey(sentence, 24000, accents);
                    index.put(key, sentence);
                    Path file = out.resolve(key + ".pcm");
                    if (Files.exists(file)) continue;
                    byte[] pcm = TeraTts.pcm16(engine.synthesize(sentence, TeraTts.SPEED), TeraTts.NATIVE_RATE, 24000);
                    if (pcm.length < 2400) throw new IllegalStateException("suspiciously short render: " + sentence);
                    Files.write(file, pcm);
                    rendered++;
                }
            }
        }
        long stale = 0, bytes = 0;
        try (var files = Files.list(out)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String name = f.getFileName().toString();
                if (!name.endsWith(".pcm")) continue;
                if (!index.containsKey(name.substring(0, name.length() - 4))) { Files.delete(f); stale++; }
                else bytes += Files.size(f);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : index.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        Files.writeString(out.resolveSibling("index.tsv"), sb.toString());
        System.out.println("PRECACHE texts=" + texts.size() + " sentences=" + index.size() + " rendered_now=" + rendered
                + " stale_removed=" + stale + " bytes=" + bytes);
    }
}
