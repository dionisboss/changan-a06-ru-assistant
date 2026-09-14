#!/usr/bin/env python3
"""Run E07TrunkTest --chain on a plain JVM: parse routing, the stop chain and its failure paths.
The park guard needs Android's real org.json, so the full main() still runs on the car (see README)."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-26-openjdk"))
android = Path(os.environ.get("E07_TOOLS", root / "out/tools")) / "android.jar"  # compile-only org.json
with tempfile.TemporaryDirectory(prefix="e07-trunk-chain-") as temporary:
    build = Path(temporary)
    log = build / "android/util/Log.java"
    log.parent.mkdir(parents=True)
    log.write_text("package android.util; public class Log { public static int i(String t, String m) { return 0; } }")
    subprocess.run([str(java_home / "bin/javac"), "-encoding", "UTF-8", "-classpath", str(android), "-d", str(build),
                    str(log), str(root / "src/com/stand/bridge/E07Trunk.java"), str(root / "tests/E07TrunkTest.java")], check=True)
    subprocess.run([str(java_home / "bin/java"), "-cp", f"{build}{os.pathsep}{android}",
                    "com.stand.bridge.E07TrunkTest", "--chain"], check=True)
