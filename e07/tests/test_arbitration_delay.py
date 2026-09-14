#!/usr/bin/env python3
"""Check the exact patch against an unmodified E07 ArbitrationManager.smali."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from patch import arbitration_delay

original = Path(sys.argv[1]).read_text()
patched = arbitration_delay(original)
guard = '''    if-eqz p1, :e07_arbitration_delay
    const-string v1, "standzh-"
    invoke-virtual {p1, v1}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z
    move-result v1
    if-eqz v1, :e07_arbitration_delay
    const-string v1, "localConfidence"
    invoke-virtual {v1, p2}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
    move-result v1
    if-eqz v1, :e07_arbitration_delay
    const/4 v0, 0x0
    :e07_arbitration_delay
'''
assert patched.count(guard) == 1
assert patched.replace(guard, "", 1) == original  # All other methods, callbacks and cloud branch unchanged.
assert "    :goto_44\n" + guard + "    new-instance v1, Ljava/lang/StringBuilder;" in patched
assert "v2" not in guard and "v3" not in guard  # AtomicBoolean preserved; no register expansion.
try:
    arbitration_delay(patched)
except AssertionError:
    pass
else:
    raise AssertionError("patch must reject an already-patched method")
print("PASS: exact null/prefix/source guards, original class preserved outside insertion, repeat rejected")
