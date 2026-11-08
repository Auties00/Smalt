"""Writes the CineForm RAW behavior sample of smalt-video, video/src/test/resources/samples/avi/cfhd-bayer.avi.

The sample is an AVI file of four 128x64 CineForm RAW frames at 25 frames per second: the two samples of the vector
bayer-rggb-filmscan1.cfhd followed by the two of bayer-bggr-low.cfhd (tools/testdata/cfhd/make-vectors.sh, GoPro's
encoder), so the Bayer phase changes inside the stream. No encoder Smalt can run writes CineForm RAW, hence the
samples come from those vectors and this script writes the container.

    python -I tools/testdata/behavior/make-cineform-raw-sample.py
    python -I tools/testdata/behavior/pin-samples.py
"""
import os
import struct

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".."))
VECTORS = os.path.join(ROOT, "video", "src", "test", "resources", "com", "github", "auties00", "smalt", "video",
                       "cfhd", "sdk")
OUT = os.path.join(ROOT, "video", "src", "test", "resources", "samples", "avi", "cfhd-bayer.avi")
WIDTH, HEIGHT = 128, 64


def samples(name):
    """Returns the samples of a CFHDBYR1 vector."""
    data = open(os.path.join(VECTORS, name), "rb").read()
    if data[:8] != b"CFHDBYR1":
        raise SystemExit(name + " is not a CineForm RAW vector")
    position = 8
    found = []
    while position < len(data):
        size, = struct.unpack_from("<I", data, position)
        found.append(data[position + 4:position + 4 + size])
        position += 4 + size
        for _ in range(2):
            size, = struct.unpack_from("<I", data, position)
            position += 4 + size
    return found


def chunk(fourcc, payload):
    return fourcc + struct.pack("<I", len(payload)) + payload + (b"\0" if len(payload) % 2 else b"")


def listing(kind, payload):
    return b"LIST" + struct.pack("<I", len(payload) + 4) + kind + payload


def main():
    frames = samples("bayer-rggb-filmscan1.cfhd") + samples("bayer-bggr-low.cfhd")
    largest = max(len(frame) for frame in frames)
    avih = struct.pack("<10I4I", 40000, largest * 25, 0, 0x10, len(frames), 0, 1, largest, WIDTH, HEIGHT, 0, 0, 0, 0)
    strh = b"vidsCFHD" + struct.pack("<IHHIIIIIIiI4h", 0, 0, 0, 0, 1, 25, 0, len(frames), largest, -1, 0, 0, 0,
                                     WIDTH, HEIGHT)
    strf = struct.pack("<IiiHH4sIiiII", 40, WIDTH, HEIGHT, 1, 24, b"CFHD", WIDTH * HEIGHT * 3, 0, 0, 0, 0)
    header = listing(b"hdrl", chunk(b"avih", avih) + listing(b"strl", chunk(b"strh", strh) + chunk(b"strf", strf)))
    movi = b""
    index = b""
    for frame in frames:
        index += b"00dc" + struct.pack("<III", 0x10, 4 + len(movi), len(frame))
        movi += chunk(b"00dc", frame)
    body = b"AVI " + header + listing(b"movi", movi) + chunk(b"idx1", index)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "wb") as out:
        out.write(b"RIFF" + struct.pack("<I", len(body)) + body)
    print(OUT, os.path.getsize(OUT))


main()
