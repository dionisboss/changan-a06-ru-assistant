#!/usr/bin/env python3
"""Prepare the pinned E07 ASR/TTS files; verify every runtime file against DEPENDENCIES.json."""
import argparse
import json
from pathlib import Path
import shutil
import urllib.request
import zipfile

from build import HERE, STOCK_SHA, UPSTREAM_REV, run, sha

DOWNLOADS = {
    "assets/gigaam/model.int8.onnx": "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16/resolve/32a4c7cc81809bd132e2d935ab99e9e6ab47fbec/model.int8.onnx",
    "assets/tera/models/sampler_distilled_cfg3_8step.onnx": "https://huggingface.co/TeraSpace/TeraTTSv2/resolve/f05ea799094571a3553904a555df3834fb0b963b/models/sampler_distilled_cfg3_8step.onnx",
}


def download(url, target, digest):
    if target.exists() and sha(target) == digest:
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(target.suffix + ".part")
    try:
        with urllib.request.urlopen(url, timeout=60) as source, partial.open("wb") as dest:
            shutil.copyfileobj(source, dest)
        if sha(partial) != digest:
            raise ValueError("Downloaded checksum mismatch: " + target.name)
        partial.replace(target)
    finally:
        partial.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, default=HERE / "out/upstream-a06")
    parser.add_argument("--stock", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=HERE / "out/dependencies")
    args = parser.parse_args()
    if sha(args.stock) != STOCK_SHA:
        raise ValueError("Unexpected factory APK")
    if run("git", "-C", args.upstream, "rev-parse", "HEAD", capture=True).strip() != UPSTREAM_REV:
        raise ValueError("Unexpected upstream revision")
    inventory = json.loads((HERE / "DEPENDENCIES.json").read_text())
    with zipfile.ZipFile(args.stock) as stock:
        for item in inventory:
            name = item["path"]
            target = args.out / name
            target.parent.mkdir(parents=True, exist_ok=True)
            if name in DOWNLOADS:
                download(DOWNLOADS[name], target, item["sha256"])
            elif name == "jni/arm64-v8a/libonnxruntime.so":
                target.write_bytes(stock.read("lib/arm64-v8a/libonnxruntime.so"))
            else:
                if name.startswith("assets/tera/"):
                    source = args.upstream / "tools/tera-tts-java/assets" / name.removeprefix("assets/tera/")
                elif name.startswith("assets/gigaam/"):
                    source = args.upstream / "stand/asr-android" / name.removeprefix("assets/")
                elif name.startswith("jni/"):
                    source = args.upstream / "stand/asr-android/piper" / name
                else:
                    source = args.upstream / "stand/asr-android" / name
                shutil.copyfile(source, target)
            if target.stat().st_size != item["bytes"] or sha(target) != item["sha256"]:
                raise ValueError("Runtime checksum mismatch: " + name)
    shutil.copytree(HERE / "licenses", args.out / "licenses", dirs_exist_ok=True)
    download("https://repo.maven.apache.org/maven2/com/microsoft/onnxruntime/onnxruntime/1.17.1/onnxruntime-1.17.1.jar",
             args.out / "verification/onnxruntime-1.17.1.jar",
             "a4e57e18a04b6c1b5b0e2885af1d8557873d4ca04bfcebf79743966c4c8eb20f")
    # Written last: an interrupted preparation must not look like a complete dependency set.
    shutil.copyfile(HERE / "DEPENDENCIES.json", args.out / "inventory.json")
    print(f"Verified {len(inventory)} runtime files in {args.out}")


if __name__ == "__main__":
    main()
