"""Writes the ISO/IEC 23001-17 uncompressed video samples of the I/O behavior harness.

    python -I tools/testdata/behavior/make-uncv-samples.py [output directory]

The files are written independently of Smalt's muxer and encoder, from ISO/IEC 14496-12 and the uncv sample entry of
ISO/IEC 23001-17 (a visual sample entry holding the cmpd and uncC boxes, and the cmpC box of Amendment 2 for frames
compressed whole), so that the demuxer and the decoder are checked against files they did not write. The frames are
synthetic: component c of the pixel or chroma sample at (x, y) of frame f holds
(37 x + 101 y + 53 f + 71 c) mod 2^depth, which core's Mp4UncompressedVideoTest recomputes.

- uncv-yuv420.mp4: 32x16, four frames, 8-bit Y'CbCr 4:2:0, component interleaving.
- uncv-rgba-zlib.mp4: 24x8, three frames, 8-bit RGBA, pixel interleaving, each frame a zlib stream (cmpC zlib).
- uncv-yuv422-10.mp4: 20x6, three frames, 10-bit Y'CbCr 4:2:2, multi-Y interleaving (Y Cb Y Cr), every value in two
  little-endian bytes, rows padded to 16 bytes, an nclx colr box (BT.709, limited range).
"""
import os
import struct
import sys
import zlib

RATE = 25


def box(kind, *parts):
    payload = b"".join(parts)
    return struct.pack(">I", 8 + len(payload)) + kind + payload


def full(kind, version, flags, *parts):
    return box(kind, struct.pack(">I", version << 24 | flags), *parts)


def value(c, x, y, f, depth):
    return (37 * x + 101 * y + 53 * f + 71 * c) % (1 << depth)


def cmpd(types):
    return box(b"cmpd", struct.pack(">I", len(types)), b"".join(struct.pack(">H", t) for t in types))


def unc_c(components, depth, align, sampling, interleave, little_endian, row_align):
    body = struct.pack(">II", 0, len(components))
    for index in components:
        body += struct.pack(">HBBB", index, depth - 1, 0, align)
    body += bytes([sampling, interleave, 0, 0x80 if little_endian else 0])
    body += struct.pack(">IIIII", 0, row_align, 0, 0, 0)
    return full(b"uncC", 0, 0, body)


def yuv420(width, height, f):
    out = bytearray()
    for c, (w, h) in enumerate([(width, height), (width // 2, height // 2), (width // 2, height // 2)]):
        for y in range(h):
            for x in range(w):
                out.append(value(c, x, y, f, 8))
    return bytes(out)


def rgba(width, height, f):
    out = bytearray()
    for y in range(height):
        for x in range(width):
            for c in range(4):
                out.append(value(c, x, y, f, 8))
    return zlib.compress(bytes(out), 9)


def yuv422_10(width, height, f):
    out = bytearray()
    for y in range(height):
        row = bytearray()
        for unit in range(width // 2):
            for c, x in ((0, 2 * unit), (1, unit), (0, 2 * unit + 1), (2, unit)):
                row += struct.pack("<H", value(c, x, y, f, 10))
        row += bytes(-len(row) % 16)
        out += row
    return bytes(out)


def movie(width, height, entry_boxes, samples):
    file_type = box(b"ftyp", b"isom", struct.pack(">I", 0x200), b"isom", b"iso6", b"mp41")
    media = box(b"mdat", *samples)
    data_offset = len(file_type) + 8
    matrix = struct.pack(">9I", 0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)
    frames = len(samples)
    duration_ms = frames * 1000 // RATE
    movie_header = full(b"mvhd", 0, 0, struct.pack(">IIII", 0, 0, 1000, duration_ms),
                        struct.pack(">IH", 0x00010000, 0x0100), bytes(10), matrix, bytes(24), struct.pack(">I", 2))
    track_header = full(b"tkhd", 0, 3, struct.pack(">IIIII", 0, 0, 1, 0, duration_ms), bytes(8),
                        struct.pack(">hhhH", 0, 0, 0, 0), matrix, struct.pack(">II", width << 16, height << 16))
    media_header = full(b"mdhd", 0, 0, struct.pack(">IIIIHH", 0, 0, RATE, frames, 0x55C4, 0))
    handler = full(b"hdlr", 0, 0, struct.pack(">I", 0), b"vide", bytes(12), b"VideoHandler\0")
    video_header = full(b"vmhd", 0, 1, struct.pack(">HHHH", 0, 0, 0, 0))
    data_information = box(b"dinf", full(b"dref", 0, 0, struct.pack(">I", 1), full(b"url ", 0, 1)))
    entry = box(b"uncv", bytes(6), struct.pack(">H", 1), bytes(16), struct.pack(">HH", width, height),
                struct.pack(">III", 0x00480000, 0x00480000, 0), struct.pack(">H", 1), bytes(32),
                struct.pack(">Hh", 0x18, -1), *entry_boxes)
    sample_table = box(b"stbl",
                       full(b"stsd", 0, 0, struct.pack(">I", 1), entry),
                       full(b"stts", 0, 0, struct.pack(">III", 1, frames, 1)),
                       full(b"stsc", 0, 0, struct.pack(">IIII", 1, 1, frames, 1)),
                       full(b"stsz", 0, 0, struct.pack(">II", 0, frames),
                            b"".join(struct.pack(">I", len(sample)) for sample in samples)),
                       full(b"stco", 0, 0, struct.pack(">II", 1, data_offset)))
    track = box(b"trak", track_header, box(b"mdia", media_header, handler,
                                           box(b"minf", video_header, data_information, sample_table)))
    return file_type + media + box(b"moov", movie_header, track)


def main():
    if len(sys.argv) > 1 and sys.argv[1].startswith('-'):
        sys.exit(__doc__ or 'usage: ' + sys.argv[0] + ' [path]')
    directory = sys.argv[1] if len(sys.argv) > 1 else "core/src/test/resources/samples/mp4"
    files = {
        "uncv-yuv420.mp4": movie(32, 16, [cmpd([1, 2, 3]), unc_c([0, 1, 2], 8, 0, 2, 0, False, 0)],
                                 [yuv420(32, 16, f) for f in range(4)]),
        "uncv-rgba-zlib.mp4": movie(24, 8, [cmpd([4, 5, 6, 7]), unc_c([0, 1, 2, 3], 8, 0, 0, 1, False, 0),
                                            full(b"cmpC", 0, 0, b"zlib", bytes([0]))],
                                    [rgba(24, 8, f) for f in range(3)]),
        "uncv-yuv422-10.mp4": movie(20, 6, [cmpd([1, 2, 3]), unc_c([0, 1, 0, 2], 10, 2, 1, 5, True, 16),
                                            box(b"colr", b"nclx", struct.pack(">HHHB", 1, 1, 1, 0))],
                                    [yuv422_10(20, 6, f) for f in range(3)]),
    }
    for name, data in files.items():
        with open(os.path.join(directory, name), "wb") as handle:
            handle.write(data)


if __name__ == "__main__":
    main()
