package com.stand.tts;

/** Pure sentence/cache-key/PCM helpers shared by the car and the host pre-renderer. */
public final class TtsChunkTest {
    private static int checks;
    private static void eq(Object actual, Object expected, String name) {
        if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(name + ": " + actual + " != " + expected);
        checks++;
    }
    /** Least-recently-used eviction down to a byte budget, on a real temporary directory. */
    private static void trimCacheChecks() throws Exception {
        java.io.File dir = java.nio.file.Files.createTempDirectory("e07-trim-").toFile();
        java.io.File[] made = new java.io.File[5];
        for (int i = 0; i < made.length; i++) {                 // 100 bytes each, oldest first
            made[i] = new java.io.File(dir, "f" + i + ".pcm");
            java.nio.file.Files.write(made[i].toPath(), new byte[100]);
            made[i].setLastModified(1000L + i * 1000L);
        }
        eq(TeraTts.trimCache(dir, 1000), 0, "under budget removes nothing");
        eq(dir.list().length, 5, "all files kept");
        made[0].setLastModified(9000L);                          // a read touched the oldest file
        eq(TeraTts.trimCache(dir, 250), 3, "evicts down to the budget");
        eq(made[0].exists(), true, "the touched file survives");
        eq(made[4].exists(), true, "the newest file survives");
        eq(made[1].exists(), false, "the least recently used goes first");
        eq(TeraTts.trimCache(new java.io.File(dir, "missing"), 10), 0, "missing directory is not an error");
        eq(TeraTts.trimCache(null, 10), 0, "null directory is not an error");
        for (java.io.File f : dir.listFiles()) f.delete();
        dir.delete();
    }

    public static void main(String[] args) throws Exception {
        eq(String.join("|", TeraTts.sentences(TeraTts.ttsNormalize("Заряд батареи: 68%. Запас хода: 304 км"))),
           "Заряд батареи: 68 процентов.|Запас хода: 304 километра", "battery reply splits after the first sentence");
        eq(TeraTts.sentences("Да").length, 1, "single word");
        eq(TeraTts.sentences("Примеры: Температура 22 градуса. Обдув на три. Выключи климат.").length, 3, "help examples");
        eq(TeraTts.sentences("Температура 22.5 градуса").length, 1, "decimal point is not a sentence end");
        eq(TeraTts.sentences("  ").length, 0, "blank");
        String v = TeraTts.accentVersion(2097138);
        eq(TeraTts.cacheKey("Да", 24000, v), TeraTts.cacheKey("Да", 24000, v), "stable key");
        eq(TeraTts.cacheKey("Да", 24000, v).equals(TeraTts.cacheKey("Да", 16000, v)), false, "rate is part of the key");
        eq(TeraTts.cacheKey("Да", 24000, v).equals(TeraTts.cacheKey("Да", 24000, v + "x")), false,
           "stress data is part of the key");
        eq(TeraTts.accentVersion(1).equals(TeraTts.accentVersion(2)), false, "dictionary size is part of the version");
        eq(v.startsWith(com.stand.tts.tera.TeraAccents.VERSION + "."), true, "version carries the lexicon fingerprint");
        eq(com.stand.tts.tera.TeraAccents.VERSION.length(), 16, "lexicon fingerprint is 16 hex chars");
        eq(TeraTts.cacheKey("Да", 24000, v).length(), 40, "sha1 hex");
        byte[] pcm = TeraTts.pcm16(new float[]{0f, 2f, -2f, 0.5f}, 44100, 44100);
        eq(pcm.length, 8, "two bytes per sample");
        eq((short) ((pcm[3] << 8) | (pcm[2] & 255)), (short) 32767, "clip high");
        eq((short) ((pcm[5] << 8) | (pcm[4] & 255)), (short) -32768, "clip low");
        eq(TeraTts.pcm16(new float[441], 44100, 24000).length, 240 * 2, "resampled length");
        // femPresent: feminine and masculine singular both become first person; other forms are left alone.
        for (String[] c : new String[][]{{"Установила", "Ставлю"}, {"Установил", "Ставлю"}, {"Включила", "Включаю"},
                {"Выключила", "Выключаю"}, {"Открыла", "Открываю"}, {"Закрыла", "Закрываю"}, {"Отключила", "Отключаю"},
                {"Подняла", "Поднимаю"}, {"Опустила", "Опускаю"}, {"Готов", "Готова"}, {"Готова", "Готова"},
                {"Спасибо, вы открыли мне новое", "Спасибо, вы открыли мне новое"},
                {"Окна заблокированы", "Окна заблокированы"}, {"Готовность к движению", "Готовность к движению"},
                {"включила подогрев", "включаю подогрев"}, {"Установила 22 градуса", "Ставлю 22 градуса"}})
            eq(TeraTts.ttsNormalize(c[0]), c[1], "femPresent " + c[0]);
        trimCacheChecks();
        System.out.println("PASS tts chunks: " + checks + " checks");
    }
}
