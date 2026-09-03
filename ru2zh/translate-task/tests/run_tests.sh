#!/bin/sh
# Local unit tests for ru2zh_extended.java (no car needed).
# Стенд = harness_head.java.part (заглушки Log/showOnScreen + reference-хелперы gradeOf/isOn/isOff/numIn/…)
#       + ../ru2zh_extended.java (ALLOW_UNSAFE делается изменяемым, private static -> static)
#       + harness_tail.java.part (раннер).
# Проверяет: tests.tsv ("фраза<TAB>ожидаемый_ZH"; NULL = чат; 3-я колонка UNSAFE = двойная проверка флага)
#            chatter.txt (болтовня — ни одна строка не должна стать командой).
# Коррекции распознавания (fuzzy/N-best) больше нет: GigaAM даёт чистый текст. Старое — в legacy_fuzzy/.
set -e
cd "$(dirname "$0")"
# Resolve a JDK with javac (any JDK 8+ works for the harness). Override with JAVA_HOME.
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  JH="$JAVA_HOME"
else
  JH=""
  for c in "$(/usr/libexec/java_home 2>/dev/null || true)" \
           /opt/homebrew/opt/openjdk@17 /opt/homebrew/opt/openjdk /usr/local/opt/openjdk@17 \
           /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17; do
    if [ -n "$c" ] && [ -x "$c/bin/javac" ]; then JH="$c"; break; fi
  done
fi
if [ -n "$JH" ]; then JAVAC="$JH/bin/javac"; JAVA="$JH/bin/java"; else JAVAC=javac; JAVA=java; fi

python3 - <<'EOF'
import re
s = open('../ru2zh_extended.java', encoding='utf-8').read()
s = re.sub(r'private static final boolean ALLOW_UNSAFE = \w+;', 'static boolean ALLOW_UNSAFE = false; // test harness: mutable', s)
s = s.replace('private static', 'static')
head = open('harness_head.java.part', encoding='utf-8').read()
tail = open('harness_tail.java.part', encoding='utf-8').read()
open('Ru2Zh.java', 'w', encoding='utf-8').write(head + s + '\n' + tail)
EOF

"$JAVAC" -encoding UTF-8 Ru2Zh.java
"$JAVA" Ru2Zh tests.tsv chatter.txt
