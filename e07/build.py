#!/usr/bin/env python3
"""Build a signed E07 update from the verified factory APK; never installs it."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import tempfile
import zipfile

from patch import APP, patch

HERE = Path(__file__).resolve().parent
TOOLS = Path(os.environ.get("E07_TOOLS", HERE / "out/tools"))
JAVA_HOME = Path(os.environ.get("JAVA_HOME", "/usr/lib/jvm/java-26-openjdk"))
JAVA, JAVAC = JAVA_HOME / "bin/java", JAVA_HOME / "bin/javac"
STOCK_SHA = "4919522ba2ca57ea8eab7390dc95db146e125dc903d2a5beb83adb628cf37b57"
CERT_SHA = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8"
UPSTREAM_REV = "274e7edd426a547720fb9be1301baa16583e5944"


def sha(path):
    with open(path, "rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def run(*args, capture=False):
    result = subprocess.run([str(arg) for arg in args], check=True, text=True,
                            stdout=subprocess.PIPE if capture else None,
                            env={**os.environ, "LD_LIBRARY_PATH": str(TOOLS / "lib64"), "JAVA_HOME": str(JAVA_HOME)})
    return result.stdout


def is_signature(name):
    return name.startswith("META-INF/") and (name.endswith((".SF", ".RSA", ".DSA", ".EC")) or name == "META-INF/MANIFEST.MF")


def dex_classes(data):
    """Read class descriptors from DEX tables to detect accidentally packaged compile stubs."""
    assert data[:4] == b"dex\n"
    string_count, string_offset, type_count, type_offset = struct.unpack_from("<IIII", data, 56)
    strings = []
    for index in range(string_count):
        offset = struct.unpack_from("<I", data, string_offset + 4 * index)[0]
        while data[offset] & 128:  # skip the UTF-16 length ULEB128
            offset += 1
        offset += 1
        strings.append(data[offset:data.index(0, offset)])
    types = [strings[struct.unpack_from("<I", data, type_offset + 4 * i)[0]] for i in range(type_count)]
    count, offset = struct.unpack_from("<II", data, 96)
    return {types[struct.unpack_from("<I", data, offset + 32 * i)[0]] for i in range(count)}


def assemble_patched(upstream, source, output, changed_classes):
    # smali can report an error but exit 0. Never accept an earlier output file.
    output.unlink(missing_ok=True)
    run(JAVA, "-jar", upstream / "tools/smali.jar", "a", "--api", 30, source, "-o", output)
    if not output.is_file():
        raise RuntimeError("smali produced no patched DEX; see assembler output above")

    def calls(path):
        return {signature: re.findall(r"^\s*(invoke-\S+[^\n]*)", body, re.M)
                for signature, body in re.findall(r"^\.method [^\n]* (\S+)\n(.*?)^\.end method",
                                                   path.read_text(), re.M | re.S)}

    # Check actual bytecode, including invoke-static/range and argument registers.
    classes = ",".join(APP + name.removesuffix(".smali") + ";" for name in changed_classes)
    with tempfile.TemporaryDirectory(prefix="e07-smali-check-") as directory:
        decoded = Path(directory)
        run(JAVA, "-jar", upstream / "tools/baksmali.jar", "d", "--api", 30,
            "--classes", classes, output, "-o", decoded)
        for name in changed_classes:
            relative = Path(APP[1:]) / name
            expected = calls(source / relative)
            if not expected or expected != calls(decoded / relative):
                raise RuntimeError(f"patched DEX method calls differ from source: {name}")


def main():
    if not __debug__:
        raise RuntimeError("build verification requires Python assertions; remove -O/PYTHONOPTIMIZE")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", type=Path, default=HERE / "out/upstream-a06")
    parser.add_argument("--stock", type=Path, required=True)
    parser.add_argument("--build-dir", type=Path, default=HERE / "out/build")
    parser.add_argument("--base-update", type=Path, help="reuse a previously built ZIP layout for a small transfer delta")
    args = parser.parse_args()
    assert sha(args.stock) == STOCK_SHA, "unexpected factory APK: re-audit hooks/signature before using another build"
    assert run("git", "-C", args.upstream, "rev-parse", "HEAD", capture=True).strip() == UPSTREAM_REV
    deps = Path(os.environ.get("E07_DEPENDENCIES", HERE / "out/dependencies"))
    for item in json.loads((deps / "inventory.json").read_text()):
        assert sha(deps / item["path"]) == item["sha256"], item["path"]
    source_sha256 = {path.relative_to(HERE).as_posix(): sha(path)
                     for path in [HERE / "build.py", HERE / "patch.py", *sorted((HERE / "src").rglob("*.java"))]}
    build = args.build_dir.resolve()
    build.mkdir(parents=True, exist_ok=True)
    (build / "verification.json").unlink(missing_ok=True)
    for name in ("smali5", "classes", "stubs", "dex7"):
        path = build / name
        if path.exists():
            shutil.rmtree(path)
        path.mkdir()
    with zipfile.ZipFile(args.stock) as stock:
        (build / "classes5.dex").write_bytes(stock.read("classes5.dex"))
    run(JAVA, "-jar", args.upstream / "tools/baksmali.jar", "d", "--api", 30,
        build / "classes5.dex", "-o", build / "smali5")
    changed_classes = patch(build / "smali5")
    patched = build / "classes5-patched.dex"
    assemble_patched(args.upstream, build / "smali5", patched, changed_classes)
    android = TOOLS / "android.jar"
    compiler = [JAVAC, "-source", 17, "-target", 17, "-parameters", "-encoding", "UTF-8"]
    run(*compiler, "-classpath", android, "-d", build / "stubs", *sorted((HERE / "src-stubs").rglob("*.java")))
    with zipfile.ZipFile(build / "stubs.jar", "w") as archive:
        for path in sorted((build / "stubs").rglob("*.class")):
            archive.write(path, path.relative_to(build / "stubs").as_posix())
    ort = deps / "libs/ort-android-classes.jar"
    # AudioPolicy/AudioMix — системный API, в android.jar его нет. Берём тот же jar,
    # которым собирается AcsCenter; идёт первым, у его AudioManager есть registerAudioPolicy.
    system_jar = TOOLS / "android-system.jar"
    classpath = os.pathsep.join(map(str, [system_jar, android, ort, build / "stubs"]))
    run(*compiler, "-classpath", classpath, "-d", build / "classes",
        *sorted((HERE / "src").rglob("*.java")), *sorted((deps / "sherpa-src").rglob("*.java")))
    run("bash", HERE / "tests/run_runtime_tests.sh")
    run("python3", HERE / "tests/run_capture_tests.py")
    run("python3", HERE / "tests/run_micmix_tests.py")
    run("python3", HERE / "tests/run_se_pcm_tests.py")
    run("python3", HERE / "tests/run_se_probe_tests.py")
    run("python3", HERE / "tests/precache.py")   # fixed replies → assets/tera-cache, no on-car synthesis
    run(JAVA, "-cp", TOOLS / "d8.jar", "com.android.tools.r8.D8", "--min-api", 30,
        "--lib", android, "--classpath", build / "stubs.jar", "--classpath", system_jar, "--release", "--output", build / "dex7",
        *sorted((build / "classes").rglob("*.class")), ort)
    assert [p.name for p in (build / "dex7").glob("*.dex")] == ["classes.dex"]

    unsigned = build / "unsigned.apk"
    shutil.copyfile(args.base_update or args.stock, unsigned)
    # Reuse untouched local ZIP records; replace only their central-directory references.
    with zipfile.ZipFile(unsigned, "a", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as output:
        output.filelist = [info for info in output.filelist if info.filename not in
                           ("classes5.dex", "classes7.dex", "assets/e07-build.json") and not is_signature(info.filename)
                           and not info.filename.startswith("assets/tera-cache/")]
        output.NameToInfo = {info.filename: info for info in output.filelist}
        for path in sorted((deps / "assets").rglob("*")):
            if path.is_file():
                name = path.relative_to(deps).as_posix()
                if name in output.NameToInfo:
                    assert hashlib.sha256(output.read(name)).hexdigest() == sha(path), name
                else:
                    output.write(path, name)
        for path in sorted((deps / "jni/arm64-v8a").glob("*.so")):
            name = "lib/arm64-v8a/" + path.name
            if name in output.NameToInfo:
                assert hashlib.sha256(output.read(name)).hexdigest() == sha(path), name
            else:
                output.write(path, name, compress_type=zipfile.ZIP_STORED)
        for path in sorted((deps / "licenses").rglob("*")):
            if path.is_file():
                name = "assets/e07-notices/" + path.relative_to(deps / "licenses").as_posix()
                if name in output.NameToInfo:
                    assert output.read(name) == path.read_bytes(), name
                else:
                    output.write(path, name)
        for path in sorted((HERE / "out/tts-cache/tera-cache").glob("*.pcm")):
            output.write(path, "assets/tera-cache/" + path.name)
        # dex пишутся ПОСЛЕДНИМИ намеренно: правка кода тогда сдвигает только хвост,
        # и дельта между сборками измеряется мегабайтами, а не сотнями мегабайт.
        # Раньше они шли до ассетов, и каждое изменение сдвигало 640 МБ моделей.
        output.write(build / "classes5-patched.dex", "classes5.dex")
        output.write(build / "dex7/classes.dex", "classes7.dex")
        output.writestr("assets/e07-build.json", json.dumps({"base_sha256": STOCK_SHA, "a06_revision": UPSTREAM_REV,
                        "variant": "E07 native SE handoff to Russian ASR; no added AudioRecord; speech validation pending", "changed_classes": changed_classes,
                        "source_sha256": source_sha256}, ensure_ascii=False))
    aligned = build / "aligned.apk"
    run(TOOLS / "zipalign", "-p", "-f", 4, unsigned, aligned)
    key = build / "platform.pk8"
    key.write_bytes(base64.b64decode((TOOLS / "aosp-platform.pk8.b64").read_bytes()))
    key.chmod(0o600)
    apk = build / "SpeechAssistant-E07-RU.apk"
    run(JAVA, "-jar", TOOLS / "apksigner.jar", "sign", "--key", key, "--cert", TOOLS / "aosp-platform.x509.pem",
        "--min-sdk-version", 30, "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
        "--v3-signing-enabled", "true", "--out", apk, aligned)
    key.unlink()
    signature = run(JAVA, "-jar", TOOLS / "apksigner.jar", "verify", "--verbose", "--print-certs", apk, capture=True)
    assert CERT_SHA in signature
    (build / "signature.txt").write_text(signature)
    run(TOOLS / "zipalign", "-c", "-p", 4, apk)
    with zipfile.ZipFile(args.stock) as original, zipfile.ZipFile(apk) as candidate:
        names = candidate.namelist()
        assert len(names) == len(set(names)), "duplicate ZIP names"
        changed = [info.filename for info in original.infolist() if not is_signature(info.filename)
                   and original.read(info) != candidate.read(info.filename)]
        assert changed == ["classes5.dex"], changed
        added = sorted(set(names) - set(original.namelist()))
        assert candidate.testzip() is None
        seen = set()
        class_counts = {}
        for name in sorted(names):
            if name.startswith("classes") and name.endswith(".dex"):
                classes = dex_classes(candidate.read(name))
                assert not seen.intersection(classes), "duplicate DEX class definitions: " + name
                if name in original.namelist():
                    assert classes == dex_classes(original.read(name)), "changed OEM class definitions: " + name
                seen.update(classes)
                class_counts[name] = len(classes)
    report = {"apk": str(apk), "bytes": apk.stat().st_size, "sha256": sha(apk),
              "stock_sha256": STOCK_SHA, "certificate_sha256": CERT_SHA,
              "source_sha256": source_sha256,
              "changed_stock_payloads": changed, "changed_stock_classes": changed_classes, "added": added,
              "verified_dex_method_calls": changed_classes,
              "manifest_and_version_unchanged": True, "native_page_alignment": True,
              "classes_per_dex": class_counts, "duplicate_dex_classes": 0}
    (build / "verification.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({key: report[key] for key in ("apk", "bytes", "sha256", "changed_stock_payloads")}, indent=2))


if __name__ == "__main__":
    main()
