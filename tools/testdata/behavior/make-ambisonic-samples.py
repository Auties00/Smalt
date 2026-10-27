"""Regenerates the ambisonic samples of the I/O behavior harness.

- ogg/opus-ambisonic.opus: Opus mapping family 2 (RFC 8486), first order with a head-locked stereo pair, which
  ffmpeg 7.1 codes with libopus from its synthetic lavfi sources;
- mp4/ambisonic.mov: six channels of 16-bit PCM in a QuickTime file ffmpeg writes, with the spatial audio box
  (SA3D, Google Spatial Audio RFC) the spatial media injector writes for first-order ambisonics with a head-locked
  stereo pair (ambisonic type 0x80, ACN, SN3D, channel map 0 to 5), added by this script since no encoder tool
  writes it;
- wav/ambisonic-bformat.wav: four channels of 16-bit PCM in the Ambisonic B-format WAVE sub-format
  (WAVE_FORMAT_EXTENSIBLE, KSDATAFORMAT_SUBTYPE_AMBISONIC_B_FORMAT_PCM, channel mask 0), written around the
  samples ffmpeg makes.

As for make-samples.sh, the committed files are the reference their golden dumps are tied to.

    python -I tools/testdata/behavior/make-ambisonic-samples.py [output directory]
"""
import os
import struct
import subprocess
import sys
import tempfile

NOISE = "anoisesrc=color=pink:amplitude=0.3:sample_rate=48000:seed=11:duration=0.25"

CONTAINERS = {b"moov", b"trak", b"mdia", b"minf", b"stbl"}


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-bitexact", *args], check=True)


def pan(channels):
    return "pan=%dC|" % channels + "|".join("c%d=%.3f*c0" % (c, 1.0 / (c + 1)) for c in range(channels))


def sa3d_box(type_byte, order, channel_map):
    body = struct.pack(">BBIBBI", 0, type_byte, order, 0, 0, len(channel_map))
    body += b"".join(struct.pack(">I", entry) for entry in channel_map)
    return struct.pack(">I", 8 + len(body)) + b"SA3D" + body


def inject(data, box):
    """Appends a box to the first sample entry of a file whose moov follows its media."""
    data = bytearray(data)
    parents = []

    def find(start, end):
        position = start
        while position + 8 <= end:
            size, kind = struct.unpack(">I4s", data[position:position + 8])
            if kind in CONTAINERS:
                parents.append(position)
                hit = find(position + 8, position + size)
                if hit is not None:
                    return hit
                parents.pop()
            elif kind == b"stsd":
                entry = position + 16
                parents.extend([position, entry])
                return entry + struct.unpack(">I", data[entry:entry + 4])[0]
            position += size
        return None

    insert = find(0, len(data))
    if insert is None:
        raise ValueError("no sample entry")
    for offset in parents:
        size = struct.unpack(">I", data[offset:offset + 4])[0]
        data[offset:offset + 4] = struct.pack(">I", size + len(box))
    data[insert:insert] = box
    return bytes(data)


def b_format(samples, channels, rate):
    guid = struct.pack("<I", 1) + bytes.fromhex("2107d3118644c8c1ca000000")
    block = 2 * channels
    fmt = struct.pack("<HHIIHHHHI", 0xFFFE, channels, rate, rate * block, block, 16, 22, 16, 0) + guid
    body = b"WAVE" + b"fmt " + struct.pack("<I", len(fmt)) + fmt + b"data" + struct.pack("<I", len(samples)) + samples
    return b"RIFF" + struct.pack("<I", len(body)) + body


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else "core/src/test/resources/samples"
    for directory in ("ogg", "mp4", "wav"):
        os.makedirs(os.path.join(out, directory), exist_ok=True)
    noise = ["-f", "lavfi", "-i", NOISE]
    ffmpeg(*noise, "-af", pan(6), "-c:a", "libopus", "-mapping_family", "2", "-b:a", "96k", "-fflags", "+bitexact",
           os.path.join(out, "ogg", "opus-ambisonic.opus"))
    with tempfile.TemporaryDirectory() as scratch:
        mov = os.path.join(scratch, "ambisonic.mov")
        ffmpeg(*noise, "-af", pan(6), "-c:a", "pcm_s16le", "-f", "mov", mov)
        with open(mov, "rb") as handle:
            data = inject(handle.read(), sa3d_box(0x80, 1, list(range(6))))
        with open(os.path.join(out, "mp4", "ambisonic.mov"), "wb") as handle:
            handle.write(data)
        raw = os.path.join(scratch, "ambisonic.raw")
        ffmpeg(*noise, "-af", pan(4), "-f", "s16le", "-c:a", "pcm_s16le", raw)
        with open(raw, "rb") as handle:
            samples = handle.read()
        with open(os.path.join(out, "wav", "ambisonic-bformat.wav"), "wb") as handle:
            handle.write(b_format(samples, 4, 48000))


main()
