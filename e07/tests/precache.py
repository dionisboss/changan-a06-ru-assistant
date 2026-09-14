#!/usr/bin/env python3
"""Pre-render every fixed Russian reply with the real TeraTTS engine on the host → out/tts-cache/tera-cache/.
The APK ships these as assets/tera-cache/<key>.pcm; TeraTts.synthSentence serves them without synthesis."""
import os
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-26-openjdk"))
deps = Path(os.environ.get("E07_DEPENDENCIES", root / "out/dependencies"))
out = root / "out/tts-cache/tera-cache"
ort = deps / "verification/onnxruntime-1.17.1.jar"
# One pass over strings AND comments, so a phrase quoted inside a comment never reaches the renderer.
token = re.compile(r'"(?:\\.|[^"\\\n])*"|//[^\n]*|/\*.*?\*/', re.S)
reply = re.compile(r'^"([А-ЯЁ][^"\\]*)"$')   # reply literals start with a capital; keyword stems are lowercase
phrases = set()
for src in [*sorted((root / "src/com/stand/bridge").glob("*.java")), *sorted((root / "src/com/stand/tts").glob("*.java"))]:
    for match in token.finditer(src.read_text(encoding="utf-8")):
        found = reply.match(match.group())
        # Concatenation prefixes ("Заряд батареи: ", "Температура ") are never spoken on their own: they
        # keep the trailing space or punctuation that joins them to a runtime value.
        if found and found.group(1) == found.group(1).strip() and not found.group(1).endswith((":", ",")):
            phrases.add(found.group(1))
with tempfile.TemporaryDirectory(prefix="e07-precache-") as temporary:
    build = Path(temporary)
    tera = (root / "src/com/stand/tts/TeraTts.java").read_text(encoding="utf-8")
    pure = tera[tera.index("    public static String ttsNormalize"):tera.index("    private TeraTts()")]
    (build / "TeraTts.java").write_text("package com.stand.tts; public final class TeraTts {\n" + pure + "}\n", encoding="utf-8")
    (build / "phrases.txt").write_text("\n".join(sorted(phrases)) + "\n", encoding="utf-8")
    subprocess.run([str(java_home / "bin/javac"), "-encoding", "UTF-8", "-cp", str(ort), "-d", str(build),
                    str(build / "TeraTts.java"), *map(str, sorted((root / "src/com/stand/tts/tera").glob("*.java"))),
                    str(root / "src/com/stand/bridge/CommandHelp.java"), str(root / "src/com/stand/bridge/Ru2Zh.java"),
                    str(root / "tests/Precache.java")], check=True)
    subprocess.run([str(java_home / "bin/java"), "--enable-native-access=ALL-UNNAMED", "-cp", f"{build}{os.pathsep}{ort}",
                    "com.stand.bridge.Precache", str(deps), str(build / "phrases.txt"), str(out)], check=True)
