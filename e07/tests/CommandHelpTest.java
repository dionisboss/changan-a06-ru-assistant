package com.stand.bridge;

public final class CommandHelpTest {
    private static int checks;
    private static void check(boolean value, String name) {
        if (!value) throw new AssertionError(name);
        checks++;
    }
    public static void main(String[] args) {
        for (String query : new String[]{"список команд", "Что ты умеешь?", "покажи полный список команд",
                "расскажи список голосовых команд", "помощь", "какие команды ты знаешь", "покажи справку пожалуйста"})
            check(CommandHelp.topic(query) == 0, "general help: " + query);
        String[] queries = {"команды климата", "помощь по окнам", "справка по сиденьям", "команды музыки",
                "что ты умеешь со светом", "подсказки по состоянию машины", "список команд для дверей", "команды багажника", "команды зеркал", "команды экрана", "команды связи", "команды камеры", "команды вождения", "команды телефона",
                "команды крыши багажника", "команды нижнего борта", "команды заднего стекла", "команды стекла перегородки"};
        for (int i = 0; i < queries.length; i++) check(CommandHelp.topic(queries[i]) == i + 1, queries[i]);
        for (String query : new String[]{null, "", "климат", "включи климат", "помоги открыть багажник", "закрой окно",
                "не показывай список команд", "список команд и открой окно", "какая погода", "проложи маршрут домой",
                "включи музыку список команд", "помощь по рецептам"})
            check(CommandHelp.topic(query) == -1, "ordinary input not intercepted: " + query);
        check(!Ru2Zh.ALLOW_UNSAFE && Ru2Zh.ALLOW_TRUNK, "default trunk-only profile");
        boolean previous = Ru2Zh.ALLOW_UNSAFE;
        try {
          for (boolean broad : new boolean[]{false, true}) {
            Ru2Zh.ALLOW_UNSAFE = broad; // Local text checks only; no SDK or vehicle calls.
            for (int i = 0; i <= CommandHelp.TOPICS.length; i++) {
                check(CommandHelp.spoken(i).split("\\s+").length <= 20, "short spoken help " + i);
                check(CommandHelp.screen(i).length() <= 180, "compact stock text " + i);
                if (i == 0) continue;
                String[] row = CommandHelp.TOPICS[i - 1];
                if (CommandHelp.unavailable(i) != null) {
                    check(CommandHelp.spoken(i).contains("отключены"), "disabled spoken topic " + i);
                    for (int j = 2; j < row.length; j++) {
                        check(!CommandHelp.screen(i).contains(row[j]), "disabled example not advertised: " + row[j]);
                        check(Ru2Zh.ru2zh(row[j], 1) == null, "disabled example blocked: " + row[j]);
                    }
                    continue;
                }
                for (int j = 2; j < row.length; j++) {
                    String mapped = Ru2Zh.ru2zh(row[j], 1);
                    check(mapped != null && !mapped.startsWith("@"), "example maps to Chinese: " + row[j] + " => " + mapped);
                    check(CommandHelp.topic(row[j]) == -1, "example remains a command: " + row[j]);
                }
            }
          }
        } finally { Ru2Zh.ALLOW_UNSAFE = previous; }
        System.out.println("Command help checks PASS " + checks);
    }
}
