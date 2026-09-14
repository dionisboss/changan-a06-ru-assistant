#!/usr/bin/env python3
"""Offline regression: smali exit 0, stale DEX, and the OEM audio handoff boundary."""
import argparse
from pathlib import Path
import re
import sys
import tempfile
from unittest.mock import patch as mock_patch
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import build
from patch import APP, BRIDGE, patch


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stock", type=Path, help="verified factory SpeechAssistant APK")
    parser.add_argument("--upstream", type=Path, default=Path(__file__).resolve().parents[1] / "out/upstream-a06")
    args = parser.parse_args()
    assert build.sha(args.stock) == build.STOCK_SHA
    with tempfile.TemporaryDirectory(prefix="e07-smali-regression-") as directory:
        work = Path(directory)
        source = work / "fixture"
        name = "sr/SrSession.smali"
        fixture = source / APP[1:] / name
        fixture.parent.mkdir(parents=True)
        text = f""".class public {APP}sr/SrSession;
.super Ljava/lang/Object;
.method public onSrMsg(ILjava/lang/String;)V
    .registers 19
    invoke-static/range {{p1 .. p2}}, {BRIDGE}->onSrPhase(ILjava/lang/String;)V
    return-void
.end method
"""
        fixture.write_text(text)
        output = work / "fixture.dex"
        build.assemble_patched(args.upstream, source, output, [name])
        stale = output.read_bytes()
        fixture.write_text(text.replace("invoke-static/range {p1 .. p2}", "invoke-static {p1, p2}"))
        try:
            build.assemble_patched(args.upstream, source, output, [name])
        except RuntimeError as error:
            assert "produced no patched DEX" in str(error)
        else:
            raise AssertionError("smali exit 0 must not accept a stale DEX")
        assert not output.exists()

        # A valid DEX containing RuBridge alone does not prove the current hooks are present.
        fixture.write_text(text.replace("    return-void", f"    invoke-static {{}}, {BRIDGE}->isRuSessionActive()Z\n    return-void"))
        original_run = build.run

        def stale_assembler(*command, **kwargs):
            if Path(command[2]).name == "smali.jar":
                output.write_bytes(stale)
                return
            return original_run(*command, **kwargs)

        with mock_patch.object(build, "run", side_effect=stale_assembler):
            try:
                build.assemble_patched(args.upstream, source, output, [name])
            except RuntimeError as error:
                assert "method calls differ" in str(error)
            else:
                raise AssertionError("DEX missing a current hook must be rejected")

        with zipfile.ZipFile(args.stock) as archive:
            stock_dex = work / "stock.dex"
            stock_dex.write_bytes(archive.read("classes5.dex"))
        source = work / "stock-smali"
        build.run(build.JAVA, "-jar", args.upstream / "tools/baksmali.jar", "d", "--api", 30,
                  stock_dex, "-o", source)
        base = source / APP[1:]
        wake = (base / "se/SeSession$1.smali").read_text()
        listener = (base / "se/SeSession$2.smali").read_text()
        changed = patch(source)
        assert len(changed) == 7
        assert (base / "se/SeSession$1.smali").read_text() == wake, "native wake must stay unchanged"
        patched_listener = (base / "se/SeSession$2.smali").read_text()
        assert f"invoke-static {{p1, p2}}, {BRIDGE}->acceptStockFrame([BI)Z" in patched_listener
        # Removing ONLY the new consumer boundary must recover the entire OEM class,
        # including outside/VPR/audio-start checks and the SR/SDK exporters.
        boundary = (r"    invoke-static \{p1, p2\}, Lcom/stand/bridge/SeProbe;->feed\(\[BI\)V\n"
                    r".*?    if-nez v0, :e07_sr_feed_done\n"
                    r"(    invoke-virtual \{p3, p1, p2\}, [^\n]+)\n    :e07_sr_feed_done")
        restored, count = re.subn(boundary, r"\1", patched_listener, flags=re.S)
        assert count == 1 and restored == listener, "changed OEM code outside SR consumer boundary"
        build.assemble_patched(args.upstream, source, work / "patched.dex", changed)
    print("PASS: stale/missing DEX rejected; native audio boundary preserved; all 7 E07 classes round-trip")


if __name__ == "__main__":
    main()
