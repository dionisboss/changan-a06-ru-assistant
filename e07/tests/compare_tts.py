#!/usr/bin/env python3
"""Compare real A06/E07 TeraTTS sources/assets on the same host; never touches a car."""
import argparse
import base64
import hashlib
import html
import json
import os
from pathlib import Path
import platform
import statistics
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PHRASES = ["Чем могу помочь", "Заряд батареи: 55 процентов.", "Запас хода: 247 километров.",
           "Установила температуру 22 градуса.", "Все двери закрыты.",
           "Передала команду: закрыть нижний борт и крышу багажника."]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--a06-repo", type=Path, required=True)
    parser.add_argument("--a06-assets", type=Path, required=True, help="assets/tera extracted from the A06 release")
    parser.add_argument("--dependencies", type=Path, default=ROOT / "out/dependencies")
    parser.add_argument("--out", type=Path, default=ROOT / "out/tts-comparison")
    args = parser.parse_args()
    java = Path(os.environ["JAVA_HOME"]) / "bin"
    ort = args.dependencies / "verification/onnxruntime-1.17.1.jar"
    args.out.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="e07-tts-ab-") as directory:
        work = Path(directory)
        sources = []
        for label, source in (("a06", args.a06_repo / "stand/asr-android/src/com/stand/tts"),
                              ("e07", ROOT / "src/com/stand/tts")):
            for path in (source / "tera").glob("*.java"):
                target = work / label / "tera" / path.name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(path.read_text().replace("com.stand", "comparison." + label))
                sources.append(target)
            text = (source / "TeraTts.java").read_text()
            # Exactly the pure production normalization region used by the host precache check.
            start = text.index("    public static String ttsNormalize") if "    public static String ttsNormalize" in text else text.index("    static String ttsNormalize")
            pure = text[start:text.index("    private TeraTts()")]
            pure = pure.replace("    static String ttsNormalize", "    public static String ttsNormalize")
            target = work / label / "TeraTts.java"
            target.write_text("package comparison." + label + ".tts; public class TeraTts {\n"
                              + pure.replace("com.stand", "comparison." + label) + "}\n")
            sources.append(target)
        subprocess.run([str(java / "javac"), "-encoding", "UTF-8", "-cp", str(ort), "-d", str(work),
                        *map(str, sources), str(ROOT / "tests/CompareTts.java")], check=True)
        phrases = args.out / "phrases.txt"
        phrases.write_text("\n".join(PHRASES) + "\n")
        with (args.out / "benchmark.log").open("w") as log:
            subprocess.run([str(java / "java"), "-XX:ActiveProcessorCount=4", "--enable-native-access=ALL-UNNAMED",
                            "-cp", str(work) + os.pathsep + str(ort), "CompareTts", str(args.a06_assets),
                            str(args.dependencies / "assets/tera"), str(args.out), str(phrases)],
                           stdout=log, stderr=subprocess.STDOUT, check=True)
    rows = []
    for line in (args.out / "benchmark.log").read_text().splitlines():
        if line.startswith("RESULT\t"):
            _, variant, repeat, phrase, ms, samples, rms, peak, text = line.split("\t")
            rows.append(dict(variant=variant, repeat=int(repeat), phrase=int(phrase), ms=float(ms),
                             seconds=int(samples) / 44100, rms=float(rms), peak=float(peak),
                             normalized=base64.b64decode(text).decode()))
    assert len(rows) == len(PHRASES) * 2 * 3
    medians = []
    for i, phrase in enumerate(PHRASES):
        pair = {variant: statistics.median(r["ms"] for r in rows if r["phrase"] == i and r["variant"] == variant)
                for variant in ("a06", "e07")}
        medians.append(dict(phrase=phrase, **pair, ratio=pair["e07"] / pair["a06"]))
    report = dict(host=platform.platform(), threads=2, repeats=3, voice="ru_f2", speed=0.9,
                  method="Alternating order; 2 warmups per engine; native 44100 Hz; no cache or playback; not car timings",
                  a06_source_commit=subprocess.check_output(["git", "-C", str(args.a06_repo), "rev-parse", "HEAD"], text=True).strip(),
                  medians=medians, measurements=rows,
                  wav_sha256={p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(args.out.glob("*.wav"))})
    (args.out / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    table = []
    for i, row in enumerate(medians, 1):
        table.append("<tr><td>" + html.escape(row["phrase"]) + "</td>" + "".join(
            f'<td>{row[v]:.1f} мс<br><audio controls preload="none" src="{i:02d}-{v}.wav"></audio></td>'
            for v in ("a06", "e07")) + "</tr>")
    (args.out / "index.html").write_text('<!doctype html><html lang="ru"><meta charset="utf-8"><title>TTS A06 / E07</title>'
        '<style>body{font:18px system-ui;max-width:1100px;margin:30px auto}td,th{padding:16px;border-bottom:1px solid #ccc;text-align:left}</style>'
        '<h1>TTS: A06 4-step / E07 8-step</h1><p>Один компьютер, 2 потока, медиана 3 запусков. '
        'Это время синтеза без кеша, а не задержка ответа в машине. Голос ru_f2, темп 0.9.</p>'
        '<table><tr><th>Фраза</th><th>A06</th><th>E07</th></tr>' + ''.join(table) + '</table></html>')
    print(json.dumps(medians, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
