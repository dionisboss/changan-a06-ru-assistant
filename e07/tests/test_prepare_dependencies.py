#!/usr/bin/env python3
"""A failed download must preserve an existing file and remove the partial output."""
import hashlib
from pathlib import Path
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_dependencies import download

with tempfile.TemporaryDirectory() as directory:
    source = Path(directory) / "source"
    target = Path(directory) / "target.onnx"
    source.write_bytes(b"verified model")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    download(source.as_uri(), target, digest)
    assert target.read_bytes() == source.read_bytes()
    source.unlink()
    download(source.as_uri(), target, digest)  # Correct cached file needs no source/network.
    source.write_bytes(b"corrupt download")
    try:
        download(source.as_uri(), target, "0" * 64)
    except ValueError:
        pass
    else:
        raise AssertionError("corrupt download accepted")
    assert target.read_bytes() == b"verified model"
    assert not target.with_suffix(".onnx.part").exists()
print("PASS: dependency checksums, cache reuse and failed-download preservation")
