"""Writes the deterministic golden inputs as interleaved s16le PCM.

Usage: python -I tools/testdata/opus-oracle/gen.py <vector dir> <name> <out.pcm>

Mirrors OpusEncoderGoldenTest.build and OpusMultistreamGoldenTest.build exactly (integer
operations only).
"""
import array
import math
import os
import sys

vec_dir, name, out_path = sys.argv[1], sys.argv[2], sys.argv[3]


def vector(n):
    a = array.array("h")
    with open(os.path.join(vec_dir, "testvector%02d.dec" % n), "rb") as f:
        a.frombytes(f.read())
    if sys.byteorder != "little":
        a.byteswap()
    return a


def take(a, div, channels, seconds):
    frames = len(a) // 2
    n = min(frames // div, seconds * 48000 // div)
    out = array.array("h", bytes(2 * n * channels))
    for i in range(n):
        j = i * div
        if channels == 1:
            out[i] = a[2 * j]
        else:
            out[2 * i] = a[2 * j]
            out[2 * i + 1] = a[2 * j + 1]
    return out


# Sources of the surround channels in Vorbis order: (vector, side, offset in frames).
SOURCES = [(7, 0, 0), (11, 0, 24000), (7, 1, 0), (11, 1, 72000), (3, 0, 0), (7, 0, 120000), (11, 0, 168000), (3, 1, 96000)]
LFE_INDEX = {6: 5, 7: 6, 8: 7}


def surround(channels, seconds, lfe=True):
    frames = seconds * 48000
    out = array.array("h", bytes(2 * frames * channels))
    cache = {}
    for c in range(channels):
        if lfe and LFE_INDEX.get(channels) == c:
            src = cache.setdefault(7, vector(7))
            n = len(src) // 2
            acc = 0
            window = []
            for i in range(frames):
                v = src[2 * (i % n)]
                window.append(v)
                acc += v
                if len(window) > 64:
                    acc -= window.pop(0)
                out[i * channels + c] = acc // 64
            continue
        v, side, offset = SOURCES[c]
        src = cache.setdefault(v, vector(v))
        n = len(src) // 2
        for i in range(frames):
            out[i * channels + c] = src[2 * ((i + offset) % n) + side]
    return out


def build(name):
    if name == "music48k":
        return take(vector(7), 1, 2, 10)
    if name == "speech16k":
        return take(vector(3), 3, 1, 10)
    if name == "speech8k":
        return take(vector(3), 6, 1, 10)
    if name == "speech12k":
        return take(vector(3), 4, 1, 10)
    if name == "speech24k":
        return take(vector(3), 2, 1, 10)
    if name == "speech48k":
        return take(vector(3), 1, 1, 10)
    if name == "speech16k_stereo":
        return take(vector(3), 3, 2, 10)
    if name == "music48k_mono":
        return take(vector(7), 1, 1, 10)
    if name.startswith("quiet"):
        # quiet<channels>_<seconds>: the surround input 48 dB down (floor division by 256)
        parts = name[len("quiet"):].split("_")
        loud = surround(int(parts[0]), int(parts[1]))
        return array.array("h", [v // 256 for v in loud])
    if name.startswith("surround"):
        # surround<channels>_<seconds>[_nolfe]
        parts = name[len("surround"):].split("_")
        return surround(int(parts[0]), int(parts[1]), len(parts) < 3)
    if name.startswith("amb"):
        # amb<channels>_<seconds>: channel c is vector 07, 11 or 03 (c % 3), side (c // 3) % 2, offset
        # 24000 * c frames, divided by 1 + isqrt(c) (floor division)
        parts = name[len("amb"):].split("_")
        return ambisonic(int(parts[0]), int(parts[1]))
    raise SystemExit("unknown input " + name)


def ambisonic(channels, seconds):
    frames = seconds * 48000
    out = array.array("h", bytes(2 * frames * channels))
    cache = {}
    for c in range(channels):
        src = cache.setdefault((7, 11, 3)[c % 3], vector((7, 11, 3)[c % 3]))
        n = len(src) // 2
        side = (c // 3) % 2
        div = 1 + math.isqrt(c)
        for i in range(frames):
            out[i * channels + c] = src[2 * ((i + 24000 * c) % n) + side] // div
    return out


data = build(name)
if sys.byteorder != "little":
    data.byteswap()
with open(out_path, "wb") as f:
    f.write(data.tobytes())
