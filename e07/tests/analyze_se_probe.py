#!/usr/bin/env python3
"""Export E07SE01 native SE packets; WAV rate is assumed, callback timing is retained."""
import argparse
import json
import math
import struct
import tempfile
import wave
from pathlib import Path

MAGIC = b"E07SE01\n"
SE_MAGIC = b"IFLYAUTOISS" + bytes(5)
RATE = 16000
TYPES = {0: "VW", 1: "VW_LOCATE", 2: "ASR", 3: "ASR_VAD", 4: "OMNI", 5: "OMNI_VAD", 6: "REF"}


def packets(data):
    if not data.startswith(MAGIC):
        raise ValueError("Missing E07SE01 header")
    offset, previous = len(MAGIC), -1
    result = []
    while offset < len(data):
        if len(data) - offset < 16:
            raise ValueError(f"Truncated record header at byte {offset}")
        timestamp, callback, length = struct.unpack_from(">qii", data, offset)
        offset += 16
        if timestamp < previous or timestamp < 0:
            raise ValueError("Non-monotonic or negative elapsedRealtime")
        if not 512 <= length <= 65536 or not 0 < callback <= length:
            raise ValueError(f"Invalid record sizes: callback={callback}, frame={length}")
        if length > len(data) - offset:
            raise ValueError(f"Truncated SE frame at byte {offset}")
        frame = data[offset:offset + length]
        offset += length
        if frame[:16] != SE_MAGIC:
            raise ValueError("Missing IFLYAUTOISS header")
        size, count = struct.unpack_from("<II", frame, 20)
        if size != length or not 1 <= count <= 28:
            raise ValueError(f"Invalid SE header: length={size}, count={count}")
        planes, types, ranges = {}, set(), []
        for index in range(count):
            kind, start, size, channels = struct.unpack_from("<IIII", frame, 64 + 16 * index)
            start += 512
            end = start + size * channels
            if kind in types or size % 2 or start > length or end > length:
                raise ValueError(f"Invalid SE descriptor type={kind}")
            types.add(kind)
            if not size or not channels:
                continue
            if any(start < other_end and end > other_start for other_start, other_end in ranges):
                raise ValueError(f"Overlapping SE descriptor type={kind}")
            ranges.append((start, end))
            for lane in range(channels):
                at = start + lane * size
                planes[kind, lane] = frame[at:at + size]
        if not planes:
            raise ValueError("SE frame has no PCM planes")
        result.append((timestamp, callback, length, planes))
        previous = timestamp
    if not result:
        raise ValueError("Probe contains no packets")
    return result


def empty_stats():
    return dict(samples=0, total=0, squares=0, peak=0, clipped_samples=0)


def accumulate(stats, pcm):
    for (sample,) in struct.iter_unpack("<h", pcm):
        stats["samples"] += 1
        stats["total"] += sample
        stats["squares"] += sample * sample
        stats["peak"] = max(stats["peak"], abs(sample))
        stats["clipped_samples"] += sample in (-32768, 32767)


def describe(stats):
    count = stats["samples"]
    return dict(samples=count, rms=math.sqrt(stats["squares"] / count) if count else None,
                peak=stats["peak"] if count else None, dc=stats["total"] / count if count else None,
                clipped_samples=stats["clipped_samples"],
                clipping_fraction=stats["clipped_samples"] / count if count else None)


def analyze(data, output):
    records = packets(data)  # Validate the entire capture before creating exports.
    first, last = records[0][0], records[-1][0]
    streams, timeline, gaps = {}, [], []
    previous, previous_duration = first, 0
    for number, (timestamp, callback, length, planes) in enumerate(records):
        delta = timestamp - previous if number else None
        duration = max(len(pcm) // 2 for pcm in planes.values()) * 1000 / RATE
        item = dict(packet=number, elapsed_realtime_ms=timestamp, offset_ms=timestamp - first,
                    callback_length=callback, frame_length=length, delta_ms=delta,
                    packet_duration_assumed_ms=duration)
        if number:
            item["interval_excess_assumed_ms"] = delta - previous_duration
            if delta > 2 * previous_duration:
                gaps.append(dict(after_packet=number - 1, delta_ms=delta,
                                 interval_excess_assumed_ms=delta - previous_duration))
        timeline.append(item)
        second = (timestamp - first) // 1000
        for key, pcm in planes.items():
            stream = streams.setdefault(key, dict(pcm=bytearray(), overall=empty_stats(), seconds={}, packets=0))
            stream["pcm"].extend(pcm)
            stream["packets"] += 1
            accumulate(stream["overall"], pcm)
            bucket = stream["seconds"].setdefault(second, dict(stats=empty_stats(), packets=0))
            bucket["packets"] += 1
            accumulate(bucket["stats"], pcm)
        previous, previous_duration = timestamp, duration
    report = dict(sample_rate_assumed=RATE, pcm_format_assumed="signed PCM16 little-endian",
                  packet_count=len(records), first_elapsed_realtime_ms=first, last_elapsed_realtime_ms=last,
                  callback_span_ms=last - first, timeline_duration_assumed_ms=last - first + previous_duration,
                  wav_timeline="Packets concatenated; gaps are not padded. See packet_timeline.",
                  per_second_basis="Relative callback timestamp; each whole packet belongs to its start second.",
                  clipping_definition="Samples equal to -32768 or 32767; not proof of upstream clipping.",
                  gap_definition="Callback interval exceeds twice the previous packet duration at assumed rate.",
                  time_gaps=gaps, packet_timeline=timeline, streams=[])
    output.mkdir(parents=True, exist_ok=True)
    for (kind, lane), stream in sorted(streams.items()):
        name = TYPES.get(kind, f"TYPE_{kind}")
        filename = f"type{kind}_{name.lower()}_lane{lane}.wav"
        with wave.open(str(output / filename), "wb") as wav:
            wav.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
            wav.writeframes(stream["pcm"])
        seconds = []
        for second in range((last - first) // 1000 + 1):
            bucket = stream["seconds"].get(second, dict(stats=empty_stats(), packets=0))
            seconds.append(dict(second=second, packet_count=bucket["packets"], **describe(bucket["stats"])))
        report["streams"].append(dict(type=kind, name=name, lane=lane, wav=filename,
                                      note="OEM VAD-related stream; not assumed to be speech." if kind in (3, 5) else "",
                                      packet_count=stream["packets"],
                                      audio_duration_assumed_seconds=len(stream["pcm"]) / (2 * RATE),
                                      overall=describe(stream["overall"]), per_second=seconds))
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return report


def self_test():
    frame = bytearray(7168)
    frame[:16] = SE_MAGIC
    struct.pack_into("<II", frame, 20, len(frame), 7)
    offset = 0
    for kind in range(7):
        channels = 4 if kind < 2 else 1
        struct.pack_into("<IIII", frame, 64 + kind * 16, kind, offset, 512, channels)
        for lane in range(channels):
            struct.pack_into("<256h", frame, 512 + offset + lane * 512, *([100 * kind + lane] * 256))
        offset += 512 * channels
    struct.pack_into("<2h", frame, 4608, -32768, 32767)
    data = MAGIC + b"".join(struct.pack(">qii", time, 3584, len(frame)) + frame for time in (1000, 1016, 2096))
    with tempfile.TemporaryDirectory() as directory:
        report = analyze(data, Path(directory))
        assert report["packet_count"] == 3 and len(report["streams"]) == 13
        assert report["time_gaps"][0]["delta_ms"] == 1080
        asr = next(stream for stream in report["streams"] if stream["type"] == 2)
        assert asr["overall"]["peak"] == 32768 and asr["overall"]["clipped_samples"] == 6
        assert asr["per_second"][0]["packet_count"] == 2 and asr["per_second"][1]["packet_count"] == 1
        with wave.open(str(Path(directory) / "type0_vw_lane3.wav")) as wav:
            assert wav.getnframes() == 768 and wav.getframerate() == RATE
            assert struct.unpack("<h", wav.readframes(1))[0] == 3
    malformed = bytearray(data)
    struct.pack_into("<I", malformed, len(MAGIC) + 16 + 68, 65536)
    for invalid in (data[:-1], data[:len(MAGIC) + 5], bytes(malformed), MAGIC):
        try:
            packets(invalid)
        except ValueError:
            pass
        else:
            raise AssertionError("Malformed/truncated capture accepted")
    print("SE probe self-test passed: 7168/3584 format, all planes, timing, stats, WAV, truncation/bounds.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", nargs="?", type=Path)
    parser.add_argument("--out", type=Path, help="Output directory; default INPUT-stem-analysis")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if args.input is None:
        parser.error("input is required unless --self-test is used")
    output = args.out or args.input.parent / (args.input.stem + "-analysis")
    try:
        report = analyze(args.input.read_bytes(), output)
    except (OSError, ValueError) as error:
        parser.exit(1, f"SE probe: {error}\n")
    print(f"{report['packet_count']} packets, {len(report['streams'])} planes, {len(report['time_gaps'])} gaps: {output / 'report.json'}")


if __name__ == "__main__":
    main()
