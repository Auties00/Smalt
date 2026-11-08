"""Writes core/src/test/resources/samples/mp4/motion.mj2, the Motion JPEG 2000 sample of the I/O behavior harness.

The frames are JP2 files ffmpeg 7.1's own JPEG 2000 encoder makes from its synthetic testsrc2 source; this script
writes the file around them independently of Smalt's muxer, from ISO/IEC 14496-12 and the Motion JPEG 2000 layout of
ISO/IEC 15444-3 (the signature box, the mjp2 brand, each sample the frame's Contiguous Codestream box, and an mjp2
sample entry holding the JP2 Header box of the first frame and the fiel, jsub and orfo boxes), so that the demuxer
is checked against a file it did not write.

    python -I tools/testdata/behavior/make-mj2-sample.py [output file]
"""
import os
import struct
import subprocess
import sys
import tempfile

WIDTH, HEIGHT, RATE, FRAMES = 64, 48, 15, 6


def box(kind, *parts):
    payload = b"".join(parts)
    return struct.pack(">I", 8 + len(payload)) + kind + payload


def full(kind, version, flags, *parts):
    return box(kind, struct.pack(">I", version << 24 | flags), *parts)


def children(data, start, end):
    at = start
    while at + 8 <= end:
        size, kind = struct.unpack(">I4s", data[at:at + 8])
        if size < 8 or at + size > end:
            raise SystemExit("bad box in a frame")
        yield kind, data[at:at + size], data[at + 8:at + size]
        at += size


def frames():
    with tempfile.TemporaryDirectory() as directory:
        pattern = os.path.join(directory, "frame%02d.jp2")
        subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-bitexact", "-f", "lavfi", "-i",
                        "testsrc2=size=%dx%d:rate=%d" % (WIDTH, HEIGHT, RATE), "-frames:v", str(FRAMES), "-c:v",
                        "jpeg2000", "-pix_fmt", "yuv420p", "-f", "image2", pattern], check=True)
        result = []
        for index in range(1, FRAMES + 1):
            with open(pattern % index, "rb") as handle:
                result.append(handle.read())
        return result


def main():
    output = sys.argv[1] if len(sys.argv) > 1 else "core/src/test/resources/samples/mp4/motion.mj2"
    files = frames()
    header = None
    samples = []
    for data in files:
        boxes = {kind: (whole, content) for kind, whole, content in children(data, 12, len(data))}
        if header is None:
            header = boxes[b"jp2h"][0]
        samples.append(box(b"jp2c", boxes[b"jp2c"][1]))
    signature = box(b"jP  ", struct.pack(">I", 0x0D0A870A))
    file_type = box(b"ftyp", b"mjp2", struct.pack(">I", 0), b"mjp2", b"mj2s")
    media = box(b"mdat", *samples)
    data_offset = len(signature) + len(file_type) + 8
    matrix = struct.pack(">9I", 0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)
    duration_ms = FRAMES * 1000 // RATE
    movie_header = full(b"mvhd", 0, 0, struct.pack(">IIII", 0, 0, 1000, duration_ms),
                        struct.pack(">IH", 0x00010000, 0x0100), bytes(10), matrix, bytes(24), struct.pack(">I", 2))
    track_header = full(b"tkhd", 0, 7, struct.pack(">IIIII", 0, 0, 1, 0, duration_ms), bytes(8),
                        struct.pack(">hhhH", 0, 0, 0, 0), matrix, struct.pack(">II", WIDTH << 16, HEIGHT << 16))
    media_header = full(b"mdhd", 0, 0, struct.pack(">IIIIHH", 0, 0, RATE, FRAMES, 0x55C4, 0))
    handler = full(b"hdlr", 0, 0, struct.pack(">I", 0), b"vide", bytes(12), b"VideoHandler\0")
    video_header = full(b"vmhd", 0, 1, struct.pack(">HHHH", 0, 0, 0, 0))
    data_information = box(b"dinf", full(b"dref", 0, 0, struct.pack(">I", 1), full(b"url ", 0, 1)))
    name = b"Motion JPEG2000"
    entry = box(b"mjp2", bytes(6), struct.pack(">H", 1), bytes(16), struct.pack(">HH", WIDTH, HEIGHT),
                struct.pack(">III", 0x00480000, 0x00480000, 0), struct.pack(">H", 1),
                bytes([len(name)]) + name + bytes(31 - len(name)), struct.pack(">Hh", 0x18, -1), header,
                box(b"fiel", bytes([1, 0])), box(b"jsub", bytes([2, 2, 0, 0])), box(b"orfo", bytes([1, 0])))
    sample_table = box(b"stbl",
                       full(b"stsd", 0, 0, struct.pack(">I", 1), entry),
                       full(b"stts", 0, 0, struct.pack(">III", 1, FRAMES, 1)),
                       full(b"stsc", 0, 0, struct.pack(">IIII", 1, 1, FRAMES, 1)),
                       full(b"stsz", 0, 0, struct.pack(">II", 0, FRAMES),
                            b"".join(struct.pack(">I", len(sample)) for sample in samples)),
                       full(b"stco", 0, 0, struct.pack(">II", 1, data_offset)))
    track = box(b"trak", track_header, box(b"mdia", media_header, handler,
                                           box(b"minf", video_header, data_information, sample_table)))
    movie = box(b"moov", movie_header, track)
    with open(output, "wb") as handle:
        handle.write(signature + file_type + media + movie)


if __name__ == "__main__":
    main()
