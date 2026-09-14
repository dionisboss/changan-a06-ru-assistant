#!/usr/bin/env python3
"""Compile exact pure-Java production methods without loading Android's stub classes on the JVM."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-26-openjdk"))
with tempfile.TemporaryDirectory(prefix="e07-replies-") as temporary:
    build = Path(temporary)
    bridge = (root / "src/com/stand/bridge/RuBridge.java").read_text()
    bridge_methods = bridge[bridge.index("    static String zh2ru(String zh)"):bridge.index("    /** Subtitle of what's being spoken")]
    tera = (root / "src/com/stand/tts/TeraTts.java").read_text()
    tera_methods = tera[tera.index("    public static String ttsNormalize"):tera.index("    private TeraTts()")]
    (build / "RuBridge.java").write_text("package com.stand.bridge; public final class RuBridge {\n" + bridge_methods + "}\n")
    (build / "TeraTts.java").write_text("package com.stand.tts; public final class TeraTts {\n" + tera_methods + "}\n")
    subprocess.run([str(java_home / "bin/javac"), "-encoding", "UTF-8", "-d", str(build),
                    str(build / "RuBridge.java"), str(build / "TeraTts.java"),
                    str(root / "src/com/stand/tts/tera/TeraAccents.java"),
                    str(root / "src/com/stand/tts/tera/RuAccentDict.java"),
                    str(root / "tests/BatteryReplyTest.java"), str(root / "tests/OemReplyTest.java"),
                    str(root / "tests/TtsChunkTest.java")], check=True)
    subprocess.run([str(java_home / "bin/java"), "-cp", str(build), "com.stand.bridge.BatteryReplyTest"], check=True)
    subprocess.run([str(java_home / "bin/java"), "-cp", str(build), "com.stand.bridge.OemReplyTest"], check=True)
    subprocess.run([str(java_home / "bin/java"), "-cp", str(build), "com.stand.tts.TtsChunkTest"], check=True)
