#!/usr/bin/env python3
"""A model of SMPTE RDD 5-2006 film grain synthesis, written from the RDD 5 text, for checking smalt-mpeg's renderer.

RDD 5 (free at https://pub.smpte.org/latest/rdd5/) specifies the bit-exact film grain of H.264 film grain
characteristics SEI messages for 8-bit 4:2:0 frames. The model reads the Gaussian and seed tables from
mpeg/src/main/java/com/github/auties00/smalt/internal/sei/FilmGrainTables.java (transcribed from RDD 5 2.2) and builds
the 64x64 transformation matrix of RDD 5 2.2.3 from its cosine definition. It settles the points RDD 5 leaves open as
ffmpeg's renderer (the black-box oracle) does, which the model matches bit for bit on ffmpeg 7.1 decodes of 8-bit
4:2:0 H.264 and HEVC streams carrying the messages:

- x(0, e) is the seed itself: the first 16x16 area, and the first coefficient row of a pattern, use the seed, and the
  generator steps after each one;
- the generator shifts in the complement of bit 30 exclusive-or bit 2;
- a block the picture cuts short averages the samples it has as if the missing ones were 0, and the vertical edge
  before it is smoothed when the block has a second column.

    python -I tools/testdata/film-grain/rdd5_model.py golden     # prints the digest FilmGrainSynthesizerTest checks
"""
import hashlib
import math
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
TABLES = os.path.join(ROOT, "mpeg", "src", "main", "java", "com", "github", "auties00", "smalt", "internal", "sei",
                      "FilmGrainTables.java")
DEBLOCK = [64, 71, 77, 84, 90, 96, 103, 109, 116, 122, 128, 128, 128]


def load_tables():
    source = open(TABLES, encoding="utf-8").read()

    def table(name):
        start = source.index(name + " = {")
        return [int(v) for v in re.findall(r"-?\d+", source[start + len(name):source.index("};", start)])]

    return table("GAUSSIAN"), table("SEEDS")


GAUSS, SEEDS = load_tables()
R = [[32 if k == 0 else round(32 * math.sqrt(2) * math.cos(math.pi * (2 * n + 1) * k / 128)) for n in range(64)]
     for k in range(64)]


def prng(x):
    bit = 1 ^ ((x >> 30) & 1) ^ ((x >> 2) & 1)
    return ((x << 1) | bit) & 0xFFFFFFFF


_patterns = {}


def pattern(h, v):
    """Returns database[h][v] of RDD 5 2.1 as rows indexed [k][l], k horizontal."""
    if (h, v) in _patterns:
        return _patterns[(h, v)]
    b = [[0] * 64 for _ in range(64)]
    x = SEEDS[h + v * 13]
    for l in range((((v + 3) << 2) - 1) + 1):
        for k in range(0, (((h + 3) << 2) - 1) + 1, 4):
            for j in range(4):
                b[k + j][l] = GAUSS[(x + j) % 2048]
            x = prng(x)
    b[0][0] = 0
    t = [[(sum(R[k][i] * b[k][j] for k in range(64)) + 128) >> 8 for j in range(64)] for i in range(64)]
    db = [[max(-127, min(127, (sum(t[i][k] * R[k][j] for k in range(64)) + 128) >> 8)) for j in range(64)]
          for i in range(64)]
    for l in range(0, 64, 8):
        for k in range(64):
            db[k][l] = (db[k][l] * DEBLOCK[v]) >> 7
            db[k][l + 7] = (db[k][l + 7] * DEBLOCK[v]) >> 7
    _patterns[(h, v)] = db
    return db


def apply(plane, width, height, comp, log2scale, intervals, pic_offset, chroma420=True):
    """Adds RDD 5 grain to one 8-bit component given as rows; intervals are (lower, upper, [v0, v1, v2])."""
    model = []
    for lower, upper, values in intervals:
        v0, v1, v2 = values[0], values[1] if len(values) > 1 else 8, values[2] if len(values) > 2 else None
        v2 = v1 if v2 is None else v2
        if comp > 0 and chroma420:
            v0 >>= 1
            v1 = max(2, min(14, v1 << 1))
            v2 = max(2, min(14, v2 << 1))
        model.append((lower, upper, v0, v1, v2))
    grain = [[0] * width for _ in range(height)]
    x = SEEDS[(pic_offset + [0, 85, 170][comp]) % 256]
    for by in range(0, height, 16):
        for bx in range(0, width, 16):
            for m, n in ((bx, by), (bx + 8, by), (bx, by + 8), (bx + 8, by + 8)):
                if m >= width or n >= height:
                    continue
                bw, bh = min(8, width - m), min(8, height - n)
                avg = sum(plane[n + l][m + k] for l in range(bh) for k in range(bw)) >> 6
                chosen = next((i for i in model if i[0] <= avg <= i[1]), None)
                if chosen is None:
                    continue
                _, _, v0, v1, v2 = chosen
                k_off = (((x >> 16) % 52) & 0xFFFC) + (m & 8)
                l_off = (((x & 0xFFFF) % 56) & 0xFFF8) + (n & 8)
                scale = v0 if x & 1 == 0 else -v0
                db = pattern(v1 - 2, v2 - 2)
                for k in range(bw):
                    for l in range(bh):
                        grain[n + l][m + k] = (scale * db[k + k_off][l + l_off]) >> (log2scale + 6)
            x = prng(x)
    for y in range(height):
        m = 8
        while m + 1 < width:
            l1, l0, r0, r1 = grain[y][m - 2], grain[y][m - 1], grain[y][m], grain[y][m + 1]
            grain[y][m] = (l0 + (r0 << 1) + r1) >> 2
            grain[y][m - 1] = (l1 + (l0 << 1) + r0) >> 2
            m += 8
    return [[max(0, min(255, plane[y][x] + grain[y][x])) for x in range(width)] for y in range(height)]


def golden():
    """The case of FilmGrainSynthesizerTest.golden: a 168x136 4:2:0 picture with partial chroma blocks."""
    components = [
        [(0, 120, [60, 8, 6]), (121, 255, [100, 4, 10])],
        [(0, 127, [40, 6, 6]), (128, 255, [90, 12, 3])],
        [(0, 127, [80, 3, 12]), (128, 255, [30, 10, 10])],
    ]
    digest = hashlib.sha256()
    for comp in range(3):
        width, height = (168, 136) if comp == 0 else (84, 68)
        plane = [[(x * 7 + y * 13 + comp * 50 + (x * y) % 23) & 0xFF for x in range(width)] for y in range(height)]
        out = apply(plane, width, height, comp, 3, components[comp], 37)
        digest.update(bytes(v for row in out for v in row))
    return digest.hexdigest()


if __name__ == "__main__":
    if sys.argv[1:] == ["golden"]:
        print(golden())
    else:
        print(__doc__)
        sys.exit(2)
