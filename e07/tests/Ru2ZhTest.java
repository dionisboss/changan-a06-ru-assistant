package com.stand.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

/**
 * Offline unit tests for {@link Ru2Zh} — the very file that ships in classes7.dex, no stubs, no car.
 *   args[0] tests.tsv   — "фраза<TAB>ожидаемый_ZH[<TAB>UNSAFE|DIR=n]"; NULL = must NOT be a command (chat).
 *                         UNSAFE = double check: blocked with ALLOW_UNSAFE=false, expected ZH with true.
 *                         DIR=n  = the seat that spoke (1 driver, 2 passenger, 3 rear-left, 4 rear-right, 5 rear-mid).
 *   args[1] chatter.txt — small talk / questions; none of these lines may become a command.
 * Speaker seat defaults to the driver. Run via run_tests.sh.
 */
public final class Ru2ZhTest {
    public static void main(String[] args) throws Exception {
        int pass = 0, fail = 0;
        java.io.PrintStream out = new java.io.PrintStream(System.out, true, "UTF-8");
        for (String line : Files.readAllLines(Paths.get(args[0]))) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\\t");
            if (p.length < 2) continue;
            String ru = p[0].trim();
            String exp = p[1].trim().equals("NULL") ? null : p[1].trim();
            String flag = p.length > 2 ? p[2].trim() : "";
            boolean unsafeCase = flag.equals("UNSAFE");
            int dir = flag.startsWith("DIR=") ? Integer.parseInt(flag.substring(4)) : 1;
            if (unsafeCase) {
                Ru2Zh.ALLOW_UNSAFE = false;
                String blocked = Ru2Zh.ru2zh(ru, dir);
                Ru2Zh.ALLOW_UNSAFE = true;
                String got = Ru2Zh.ru2zh(ru, dir);
                if (blocked == null && Objects.equals(got, exp)) pass++;
                else { fail++; out.println("FAIL[unsafe] '" + ru + "' -> blocked=" + blocked + " open=" + got + "  (exp " + exp + ")"); }
            } else {
                Ru2Zh.ALLOW_UNSAFE = false;   // like the release with actuation off: safe commands must still map
                String got = Ru2Zh.ru2zh(ru, dir);
                if (Objects.equals(got, exp)) pass++;
                else { fail++; out.println("FAIL '" + ru + "' -> " + got + "  (exp " + exp + ")"); }
            }
        }
        Path ch = Paths.get(args.length > 1 ? args[1] : "chatter.txt");
        if (Files.exists(ch)) {
            Ru2Zh.ALLOW_UNSAFE = true;   // show what WOULD have been injected
            for (String line : Files.readAllLines(ch)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String got = Ru2Zh.ru2zh(line.trim());
                if (got == null) pass++;
                else { fail++; out.println("FAIL[chatter] '" + line.trim() + "' -> " + got + "  (должно быть чат)"); }
            }
        }
        out.println("PASS=" + pass + " FAIL=" + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
