package com.stand.tts.tera;

/** Deliberate stress overrides: the frequency dictionary gives the wrong form for these in our replies.
 *  Passing a null dictionary keeps the check asset-free — only the built-in lexicon is exercised. */
public final class AccentTest {
    private static int checks;
    private static void eq(String word, String expected) {
        String actual = TeraAccents.accentize(word, null);
        if (!expected.equals(actual)) throw new AssertionError(word + " -> " + actual + "; expected " + expected);
        checks++;
    }
    public static void main(String[] args) {
        eq("хода", "х+ода");            // «запас хОда», не ходА
        eq("борта", "б+орта");          // «нижнего бОрта», не бортА
        eq("двери", "дв+ери");          // «заблокируй двЕри», не дверИ
        eq("голоса", "г+олоса");        // «громкость гОлоса», не голосА
        eq("замок", "зам+ок");          // дверной замОк, не зАмок
        eq("багажника", "баг+ажника");
        eq("Хода", "Х+ода");            // capitalisation is restored from the lexicon form
        eq("х+ода", "х+ода");           // an already marked word is left alone
        eq("Запас хода", "Зап+ас х+ода");
        System.out.println("PASS accents: " + checks + " checks");
    }
}
