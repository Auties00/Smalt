"""Regenerates the multichannel AMR storage samples of the I/O behavior harness.

RFC 4867 section 5.2 multichannel files store one frame per channel in every frame-block. No common encoder
writes them, so this script interleaves the frames of the committed single-channel samples (amr/nb.amr and
amr/wb.awb, made by make-samples.sh) into:

- amr/nb-stereo.amr: AMR-NB, 2 channels; channel 1 is nb.amr, channel 2 is nb.amr started 25 frames later
  (wrapping around) with every seventh frame replaced by a NO_DATA frame;
- amr/wb-3ch.awb: AMR-WB, 3 channels; wb.awb as channel 1, then wb.awb started 10 and 40 frames later.

As for make-samples.sh, the committed files are the reference their golden dumps are tied to.

    python -I tools/testdata/behavior/make-amr-mc-samples.py [samples directory]
"""
import os
import sys

NB_SIZES = [12, 13, 15, 17, 19, 20, 26, 31, 5, 6, 5, 5, 0, 0, 0, 0]
WB_SIZES = [17, 23, 32, 36, 40, 46, 50, 58, 60, 5, 0, 0, 0, 0, 0, 0]
NO_DATA = bytes([15 << 3 | 0x04])


def frames(data, magic, sizes):
    """Returns the frames of a single-channel storage file, header bytes included."""
    if not data.startswith(magic):
        raise SystemExit("not a single-channel AMR file")
    result = []
    position = len(magic)
    while position < len(data):
        size = 1 + sizes[(data[position] >> 3) & 0x0F]
        if position + size > len(data):
            break
        result.append(data[position:position + size])
        position += size
    return result


def multichannel(magic, channels):
    """Returns a multichannel storage file whose frame-block k holds channels[c][k] for every channel c."""
    out = bytearray(magic)
    out += bytes([0, 0, 0, len(channels)])
    for block in zip(*channels):
        for frame in block:
            out += frame
    return bytes(out)


def rotate(values, start):
    return values[start:] + values[:start]


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repository = os.path.dirname(os.path.dirname(os.path.dirname(here)))
    samples = sys.argv[1] if len(sys.argv) > 1 else os.path.join(repository, "core", "src", "test", "resources",
                                                                 "samples")
    amr = os.path.join(samples, "amr")
    nb = frames(open(os.path.join(amr, "nb.amr"), "rb").read(), b"#!AMR\n", NB_SIZES)
    second = [NO_DATA if i % 7 == 6 else frame for i, frame in enumerate(rotate(nb, 25))]
    open(os.path.join(amr, "nb-stereo.amr"), "wb").write(multichannel(b"#!AMR_MC1.0\n", [nb, second]))
    wb = frames(open(os.path.join(amr, "wb.awb"), "rb").read(), b"#!AMR-WB\n", WB_SIZES)
    open(os.path.join(amr, "wb-3ch.awb"), "wb").write(
        multichannel(b"#!AMR-WB_MC1.0\n", [wb, rotate(wb, 10), rotate(wb, 40)]))


if __name__ == "__main__":
    main()
