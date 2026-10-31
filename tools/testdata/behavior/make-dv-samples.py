"""Writes the DV samples of the I/O behavior harness: a raw DV stream of two 525/60 frames with 48 kHz stereo audio,
made by ffmpeg's dvvideo encoder and dv muxer from its synthetic lavfi sources, and the same frames wrapped in a type-1
DV AVI file (one interleaved iavs stream of DIF frames), which no oracle writes.

    python -I tools/testdata/behavior/make-dv-samples.py [samples directory]
"""
import os
import struct
import subprocess
import sys

FRAME = 120000


def chunk(fourcc, body):
    return fourcc.encode("ascii") + struct.pack("<I", len(body)) + body + (b"\0" if len(body) & 1 else b"")


def listed(kind, body):
    return chunk("LIST", kind.encode("ascii") + body)


def type_one_avi(dv, scale, rate):
    """Wraps the DIF frames of a raw DV stream in a type-1 DV AVI file, as DvTestFiles.typeOneAvi does."""
    frames = len(dv) // FRAME
    movi = bytearray(b"movi")
    index = bytearray()
    for i in range(frames):
        index += b"00__" + struct.pack("<III", 0x10, len(movi), FRAME)
        movi += chunk("00__", dv[i * FRAME:(i + 1) * FRAME])
    avih = struct.pack("<10I", 1000000 * scale // rate, FRAME * rate // scale, 0, 0x10, frames, 0, 1, FRAME, 720,
                       480) + b"\0" * 16
    strh = b"iavs" + b"dvsd" + struct.pack("<IIIIIIIIiI", 0, 0, 0, scale, rate, 0, frames, FRAME, -1, 0) + b"\0" * 8
    strl = listed("strl", chunk("strh", strh) + chunk("strf", b"\0" * 32))
    hdrl = listed("hdrl", chunk("avih", avih) + strl)
    return chunk("RIFF", b"AVI " + hdrl + chunk("LIST", bytes(movi)) + chunk("idx1", bytes(index)))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "core/src/test/resources/samples"
    os.makedirs(os.path.join(out, "dv"), exist_ok=True)
    os.makedirs(os.path.join(out, "avi"), exist_ok=True)
    raw = os.path.join(out, "dv", "dv25-audio.dv")
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-bitexact",
                    "-f", "lavfi", "-i", "testsrc2=size=720x480:rate=30000/1001",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                    "-frames:v", "2", "-c:v", "dvvideo", "-pix_fmt", "yuv411p", "-c:a", "pcm_s16le", "-ac", "2",
                    "-shortest", "-f", "dv", raw], check=True)
    with open(raw, "rb") as source:
        dv = source.read()
    with open(os.path.join(out, "avi", "dv-type1.avi"), "wb") as target:
        target.write(type_one_avi(dv, 1001, 30000))


if __name__ == "__main__":
    main()
