package com.stand.bridge;

/** Exercises the exact production translation and Tera normalization methods extracted by the runner. */
public final class BatteryReplyTest {
    private static int checks;
    private static void check(String source, String expected) throws Exception {
        String actual = RuBridge.zh2ru(source);
        if (actual != null) {
            java.lang.reflect.Method normalize = com.stand.tts.TeraTts.class.getDeclaredMethod("ttsNormalize", String.class);
            normalize.setAccessible(true);
            actual = (String) normalize.invoke(null, actual);
        }
        checks++;
        if (!java.util.Objects.equals(actual, expected))
            throw new AssertionError(source + " => " + actual + "; expected " + expected);
    }
    public static void main(String[] args) throws Exception {
        check("剩余电量68%,可行驶304公里", "Заряд батареи: 68 процентов. Запас хода: 304 километра");
        check("剩余电量1％，可行驶21公里。", "Заряд батареи: 1 процент. Запас хода: 21 километр");
        check("剩余电量100%, 可行驶1100公里!", "Заряд батареи: 100 процентов. Запас хода: 1100 километров");
        check("[w0]剩余电量0%,可行驶0公里", "Заряд батареи: 0 процентов. Запас хода: 0 километров");
        check("剩余电量101%,可行驶304公里", null);
        check("剩余电量68%", null);
        check("可行驶304公里", null);
        check("我在", "Чем могу помочь");
        System.out.println("Battery reply checks PASS " + checks);
    }
}
