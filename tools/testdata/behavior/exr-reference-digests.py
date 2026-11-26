"""Records what the reference OpenEXR library (the OpenEXR Python bindings, BSD-3-Clause, version 3.5.2) decodes from
hosted OpenEXR files, for ExrSamplesTest: one line per channel of each part, "file<TAB>part<TAB>channel<TAB>width
<TAB>height<TAB>sha256", the SHA-256 of the channel's samples of the first level (one per sampling period, rows top
first) as little-endian float32, channels in the order OpenEXR sorts them, unsigned integer channels converted to
float, exactly as make-exr-vectors.py writes its .f32 files, but for NaNs, which are hashed as the canonical NaN
0x7FC00000 (Java's Float.floatToIntBits), since a hardware half to float conversion quiets a signaling NaN.

The bindings return the samples of a deep part per pixel; such a part is flattened as ExrDecoder documents and
make-exr-vectors.py computes it: each pixel's samples sorted front to back by depth (a stable sort), composited over
in float32 until the alpha reaches 1, and Z the nearest depth (0 for a pixel without samples). Channels other than
A, B, G, R and Z are not expected in a deep part.

    python -I tools/testdata/behavior/exr-reference-digests.py <file.exr>... > digests.tsv

The script needs numpy and OpenEXR (pip install OpenEXR==3.5.2 numpy).
"""
import hashlib
import os
import sys

if len(sys.argv) < 2 or sys.argv[1].startswith('-'):
    sys.exit(__doc__)

import numpy as np
import OpenEXR as E


def flatten(part):
    channels = part.channels
    names = sorted(channels.keys(), key=lambda n: n.encode("utf-8"))
    assert set(names) <= {"A", "B", "G", "R", "Z"}, names
    raw = {n: channels[n].pixels for n in names}
    height, width = raw[names[0]].shape
    flat = {n: np.zeros((height, width), dtype=np.float32) for n in names}
    for y in range(height):
        for x in range(width):
            z = raw["Z"][y, x]
            if z is None or len(z) == 0:
                continue
            count = len(z)
            samples = []
            for index in range(count):
                samples.append((np.float32(z[index]),
                                [np.float32(raw[n][y, x][index]) if n in raw else np.float32(0)
                                 for n in ("R", "G", "B", "A")]))
            ordered = sorted(samples, key=lambda sample: sample[0])
            acc = [np.float32(0)] * 4
            for _, (r, g, b, a) in ordered:
                weight = np.float32(1) - acc[3]
                acc = [np.float32(acc[0] + weight * r), np.float32(acc[1] + weight * g),
                       np.float32(acc[2] + weight * b), np.float32(acc[3] + weight * a)]
                if acc[3] >= 1:
                    break
            for n, value in zip(("R", "G", "B", "A"), acc):
                if n in flat:
                    flat[n][y, x] = value
            flat["Z"][y, x] = ordered[0][0]
    return [(n, flat[n]) for n in names]


for path in sys.argv[1:]:
    name = os.path.basename(path)
    decoded = E.File(path, separate_channels=True)
    for index, part in enumerate(decoded.parts):
        if part.header.get("type") in (E.Storage.deepscanline, E.Storage.deeptile):
            planes = flatten(part)
        else:
            planes = [(n, np.asarray(part.channels[n].pixels))
                      for n in sorted(part.channels.keys(), key=lambda n: n.encode("utf-8"))]
        for channel, pixels in planes:
            values = pixels.astype("<f4")
            values[np.isnan(values)] = np.uint32(0x7FC00000).view("<f4")
            data = values.tobytes()
            print(f"{name}\t{index}\t{channel}\t{pixels.shape[1]}\t{pixels.shape[0]}\t"
                  f"{hashlib.sha256(data).hexdigest()}")
