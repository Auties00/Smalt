"""Writes the TagFuzzTest seeds of the MP4 tag edits that read and rewrite tracks and fragments.

The seeds are tiny ffmpeg-made files with the boxes the tag editor rewrites:

- valid-timecode-fragmented.mp4: a fragmented file (frag_keyframe+empty_moov) whose QuickTime timecode track has its
  sample in the first movie fragment, whose explicit base data offset locates it.
- valid-track-keyed.mov: a QuickTime movie whose video trak holds a keyed metadata box (handler mdta, QuickTime form)
  and whose audio trak's udta holds one in ISO form.
- valid-compressed-fragments.mp4: the fragmented file with every moof box replaced by a compressed movie fragment
  (!mof, ISO/IEC 14496-12 clause 8.20) whose track fragments state explicit base data offsets.

    python -I tools/fuzz/make-mp4-tag-seeds.py <scratch directory> [seed directory]
"""
import gzip
import os
import struct
import subprocess
import sys


def box(kind, payload):
    return struct.pack(">I", 8 + len(payload)) + kind + payload


def keyed_meta(items, full_box):
    handler = box(b"hdlr", bytes(8) + b"mdta" + bytes(12) + b"\x00")
    keys = box(b"keys", struct.pack(">II", 0, len(items)) + b"".join(
        struct.pack(">I", 8 + len(key)) + b"mdta" + key for key, _ in items))
    ilst = box(b"ilst", b"".join(box(struct.pack(">I", index + 1), box(b"data", struct.pack(">II", kind, 0) + value))
                                 for index, (_, (kind, value)) in enumerate(items)))
    return box(b"meta", (bytes(4) if full_box else b"") + handler + keys + ilst)


def children(data, start, end):
    result = []
    while end - start >= 8:
        size, = struct.unpack(">I", data[start:start + 4])
        result.append((data[start + 4:start + 8], start, size))
        start += size
    return result


def ffmpeg(output, *options):
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=rate=25:duration=0.2:size=32x32",
                    "-f", "lavfi", "-i", "sine=duration=0.2:sample_rate=8000", "-c:v", "mpeg4", "-q:v", "20",
                    "-c:a", "aac", "-b:a", "16k", "-shortest", *options, output], check=True)
    with open(output, "rb") as file:
        return file.read()


def track_keyed(data):
    kind, offset, size = children(data, 0, len(data))[-1]
    if kind != b"moov" or offset + size != len(data):
        raise SystemExit("the movie box is not the last box")
    body = b""
    track = 0
    for child, at, length in children(data, offset + 8, offset + size):
        piece = data[at:at + length]
        if child == b"trak":
            extra = keyed_meta([(b"com.example.camera", (1, b"front")), (b"com.example.exposure", (21, b"\xff\xff\xff\xfd"))],
                               False) if track == 0 else box(b"udta", keyed_meta([(b"com.example.mic", (1, b"left"))], True))
            piece = box(b"trak", piece[8:] + extra)
            track += 1
        body += piece
    return data[:offset] + box(b"moov", body)


def compress_fragments(data):
    out = b""
    for kind, at, size in children(data, 0, len(data)):
        if kind == b"moof":
            out += box(b"!mof", gzip.compress(data[at + 8:at + size], mtime=0))
        else:
            out += data[at:at + size]
    return out


def main():
    scratch = sys.argv[1]
    seeds = sys.argv[2] if len(sys.argv) > 2 else os.path.join("core", "src", "test", "resources", "com", "github",
                                                                 "auties00", "smalt", "fuzz", "TagFuzzTestInputs", "tags")
    os.makedirs(scratch, exist_ok=True)
    fragmented = ffmpeg(os.path.join(scratch, "fragmented.mp4"), "-timecode", "01:00:00:00", "-movflags",
                        "frag_keyframe+empty_moov")
    movie = ffmpeg(os.path.join(scratch, "movie.mov"))
    outputs = {"valid-timecode-fragmented.mp4": fragmented, "valid-track-keyed.mov": track_keyed(movie),
               "valid-compressed-fragments.mp4": compress_fragments(fragmented)}
    for name, data in outputs.items():
        with open(os.path.join(seeds, name), "wb") as file:
            file.write(data)
        print(name, len(data))


main()
