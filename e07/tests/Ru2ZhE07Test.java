package com.stand.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Release-profile mapper cases; Chinese output only, no Android/car calls. */
public final class Ru2ZhE07Test {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("--legacy")) {
            Ru2Zh.ALLOW_TRUNK = false;
            Ru2ZhTest.main(new String[]{args[1], args[2]});
            return;
        }
        if (Ru2Zh.ALLOW_UNSAFE || !Ru2Zh.ALLOW_TRUNK) throw new AssertionError("Expected trunk-only release profile");
        int count = 0;
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] fields = line.split("\t");
            String expected = fields[1].equals("NULL") ? null : fields[1];
            int direction = fields.length > 2 ? Integer.parseInt(fields[2]) : 1;
            boolean unsafe = fields.length > 3 && fields[3].equals("UNSAFE");
            if (unsafe && Ru2Zh.ru2zh(fields[0], direction) != null)
                throw new AssertionError("Broader group enabled in release: " + fields[0]);
            Ru2Zh.ALLOW_UNSAFE = unsafe;
            String actual = Ru2Zh.ru2zh(fields[0], direction);
            if (!Objects.equals(actual, expected))
                throw new AssertionError(fields[0] + " -> " + actual + "; expected " + expected);
            if (Ru2Zh.ru2zh("не " + fields[0], direction) != null)
                throw new AssertionError("Negation bypass: " + fields[0]);
            count += 2;
            Ru2Zh.ALLOW_UNSAFE = false;
        }
        System.out.println("E07_COMPAT_PASS=" + count + " FAIL=0");
    }
}
