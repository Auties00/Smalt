"""Regenerates the BW64 samples whose Audio Definition Model metadata declares an ambisonic layout.

Each sample is a short AmbiX (ACN) PCM file, every channel a sine of its own frequency, which the EBU ADM Renderer
(EAR, https://github.com/ebu/ebu_adm_renderer, BSD-3-Clause-Clear) wraps with its "ear-utils ambix_to_bwf" command:

- wav/adm-hoa-order1-sn3d.wav: first order, SN3D, with an axml chunk defining the HOA channel, stream and track
  formats and a chna chunk referencing them;
- wav/adm-hoa-order1-fuma.wav: first order, Furse-Malham normalization (--norm FuMa), axml and chna;
- wav/adm-hoa-order2-n3d-chna.wav: second order, N3D, a chna chunk referencing the ITU-R BS.2094 common
  definitions only (--chna-only), without axml;
- wav/adm-hoa-order3-sn3d-chna.wav: third order, SN3D, chna only.

EAR 2.1.0 was used (pip install ear "setuptools<81", Python 3.12; its common definitions need pkg_resources). The
committed files are the reference their golden dumps are tied to.

    python -I tools/testdata/behavior/make-adm-samples.py [output directory] [ear-utils executable]
"""
import math
import os
import struct
import subprocess
import sys
import tempfile

RATE = 48000
FRAMES = 2400

SAMPLES = [
    ("adm-hoa-order1-sn3d.wav", 1, ["--norm", "SN3D"]),
    ("adm-hoa-order1-fuma.wav", 1, ["--norm", "FuMa"]),
    ("adm-hoa-order2-n3d-chna.wav", 2, ["--norm", "N3D", "--chna-only"]),
    ("adm-hoa-order3-sn3d-chna.wav", 3, ["--norm", "SN3D", "--chna-only"]),
]


def ambix(channels):
    """A 16-bit PCM WAVE file whose channel k holds a sine of 250 * (k + 1) Hz."""
    data = bytearray()
    for i in range(FRAMES):
        for channel in range(channels):
            value = round(6000 * math.sin(2 * math.pi * 250 * (channel + 1) * i / RATE))
            data += struct.pack("<h", value)
    fmt = struct.pack("<HHIIHH", 1, channels, RATE, RATE * 2 * channels, 2 * channels, 16)
    body = b"WAVE" + b"fmt " + struct.pack("<I", len(fmt)) + fmt + b"data" + struct.pack("<I", len(data)) + data
    return b"RIFF" + struct.pack("<I", len(body)) + body


def main():
    if len(sys.argv) > 1 and sys.argv[1].startswith('-'):
        sys.exit(__doc__ or 'usage: ' + sys.argv[0] + ' [path]')
    out = sys.argv[1] if len(sys.argv) > 1 else "core/src/test/resources/samples"
    ear = sys.argv[2] if len(sys.argv) > 2 else "ear-utils"
    os.makedirs(os.path.join(out, "wav"), exist_ok=True)
    with tempfile.TemporaryDirectory() as scratch:
        for name, order, options in SAMPLES:
            source = os.path.join(scratch, "ambix-%d.wav" % order)
            with open(source, "wb") as handle:
                handle.write(ambix((order + 1) * (order + 1)))
            subprocess.run([ear, "ambix_to_bwf", *options, source, os.path.join(out, "wav", name)], check=True)


main()
