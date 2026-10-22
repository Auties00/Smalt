"""Writes a JPEG-compressed (compression 7) little-endian TIFF from a 12-bit PNM, every strip or tile coded by
libjpeg-turbo's cjpeg with the given switches.

Usage: python tiff12.py <in.ppm|in.pgm> <out.tif> (--strip ROWS | --tile SIZE) [--tables] -- <cjpeg switches>
       python tiff12.py raw-md5 <in.tif>    MD5 of the strips of an uncompressed little-endian TIFF, in order

The photometric interpretation follows the switches: -grayscale gives BlackIsZero, -rgb gives RGB, and
anything else YCbCr with the YCbCrSubsampling of the luma factors of -sample (2x2 by default). With --tables
the quantization tables move to the JPEGTables tag and the strips keep only their Huffman tables (cjpeg
optimizes the Huffman tables of every 12-bit strip separately). Tiles at the right and bottom edges are
coded from the image extended by edge replication, as TIFF requires full tiles.
"""
import os
import struct
import subprocess
import sys
import tempfile


def read(path):
    data = open(path, 'rb').read()
    fields = []
    i = 0
    while len(fields) < 4:
        while data[i:i + 1].isspace():
            i += 1
        j = i
        while not data[j:j + 1].isspace():
            j += 1
        fields.append(data[i:j])
        i = j
    i += 1
    channels = 3 if fields[0] == b'P6' else 1
    width, height = int(fields[1]), int(fields[2])
    count = width * height * channels
    return width, height, channels, list(struct.unpack('>%dH' % count, data[i:i + 2 * count]))


def region(width, height, channels, samples, x0, y0, w, h):
    out = []
    for y in range(y0, y0 + h):
        sy = min(y, height - 1)
        for x in range(x0, x0 + w):
            sx = min(x, width - 1)
            base = (sy * width + sx) * channels
            out.extend(samples[base:base + channels])
    return out


def cjpeg(switches, w, h, channels, samples, work):
    source = os.path.join(work, 'in.pnm')
    header = ('%s\n%d %d\n4095\n' % ('P6' if channels == 3 else 'P5', w, h)).encode('ascii')
    open(source, 'wb').write(header + struct.pack('>%dH' % len(samples), *samples))
    return subprocess.run(['cjpeg', '-precision', '12'] + switches + [source], check=True,
                          capture_output=True).stdout


def segments(jpeg):
    """Splits a JPEG stream into (marker, bytes) segments, the entropy-coded data kept with SOS."""
    out = []
    i = 2
    while i < len(jpeg):
        marker = jpeg[i + 1]
        if marker == 0xD9:
            break
        length = struct.unpack('>H', jpeg[i + 2:i + 4])[0]
        if marker == 0xDA:
            end = len(jpeg) - 2
            out.append((marker, jpeg[i:end]))
            break
        out.append((marker, jpeg[i:i + 2 + length]))
        i += 2 + length
    return out


def main():
    args = sys.argv[1:]
    split = args.index('--')
    options, switches = args[:split], args[split + 1:]
    source, target = options[0], options[1]
    tile = None
    rows = None
    tables = '--tables' in options
    if '--strip' in options:
        rows = int(options[options.index('--strip') + 1])
    else:
        tile = int(options[options.index('--tile') + 1])
    width, height, channels, samples = read(source)
    if '-grayscale' in switches:
        photometric, count = 1, 1
    elif '-rgb' in switches:
        photometric, count = 2, 3
    else:
        photometric, count = 6, 3
    sampling = (2, 2)
    if '-sample' in switches:
        luma = switches[switches.index('-sample') + 1].split(',')[0].split('x')
        sampling = (int(luma[0]), int(luma[1]))
    pieces = []
    with tempfile.TemporaryDirectory() as work:
        if tile:
            for ty in range(0, height, tile):
                for tx in range(0, width, tile):
                    pieces.append(cjpeg(switches, tile, tile, channels,
                                        region(width, height, channels, samples, tx, ty, tile, tile), work))
        else:
            for y in range(0, height, rows):
                h = min(rows, height - y)
                pieces.append(cjpeg(switches, width, h, channels,
                                    region(width, height, channels, samples, 0, y, width, h), work))
    jpeg_tables = None
    if tables:
        quant = b''.join(seg for marker, seg in segments(pieces[0]) if marker == 0xDB)
        jpeg_tables = b'\xff\xd8' + quant + b'\xff\xd9'
        stripped = []
        for piece in pieces:
            kept = b''.join(seg for marker, seg in segments(piece) if marker != 0xDB and not 0xE0 <= marker <= 0xEF)
            stripped.append(b'\xff\xd8' + kept + b'\xff\xd9')
        pieces = stripped
    data = bytearray(b'II*\x00\x00\x00\x00\x00')
    offsets = []
    for piece in pieces:
        offsets.append(len(data))
        data += piece
        if len(data) % 2:
            data.append(0)

    def blob(payload):
        if len(data) % 2:
            data.append(0)
        at = len(data)
        data.extend(payload)
        return at

    entries = [(256, 4, 1, width), (257, 4, 1, height)]
    if count == 1:
        entries.append((258, 3, 1, 12))
    else:
        entries.append((258, 3, count, blob(struct.pack('<3H', 12, 12, 12))))
    entries.append((259, 3, 1, 7))
    entries.append((262, 3, 1, photometric))
    if tile:
        entries.append((277, 3, 1, count))
        entries.append((284, 3, 1, 1))
        entries.append((322, 4, 1, tile))
        entries.append((323, 4, 1, tile))
        entries.append((324, 4, len(offsets), blob(struct.pack('<%dI' % len(offsets), *offsets))
                        if len(offsets) > 1 else offsets[0]))
        counts = [len(p) for p in pieces]
        entries.append((325, 4, len(counts), blob(struct.pack('<%dI' % len(counts), *counts))
                        if len(counts) > 1 else counts[0]))
    else:
        entries.append((273, 4, len(offsets), blob(struct.pack('<%dI' % len(offsets), *offsets))
                        if len(offsets) > 1 else offsets[0]))
        entries.append((277, 3, 1, count))
        entries.append((278, 4, 1, rows))
        counts = [len(p) for p in pieces]
        entries.append((279, 4, len(counts), blob(struct.pack('<%dI' % len(counts), *counts))
                        if len(counts) > 1 else counts[0]))
        entries.append((284, 3, 1, 1))
    if jpeg_tables:
        entries.append((347, 7, len(jpeg_tables), blob(jpeg_tables)))
    if photometric == 6:
        entries.append((530, 3, 2, sampling[0] | (sampling[1] << 16)))
    entries.sort()
    if len(data) % 2:
        data.append(0)
    ifd = len(data)
    struct.pack_into('<I', data, 4, ifd)
    data += struct.pack('<H', len(entries))
    for tag, kind, n, value in entries:
        if kind == 3 and n == 1:
            data += struct.pack('<HHIHH', tag, kind, n, value, 0)
        else:
            data += struct.pack('<HHII', tag, kind, n, value)
    data += struct.pack('<I', 0)
    open(target, 'wb').write(bytes(data))


def raw_md5(path):
    import hashlib
    data = open(path, 'rb').read()
    assert data[:4] == b'II*' + bytes(1)
    ifd = struct.unpack_from('<I', data, 4)[0]
    tags = {}
    for i in range(struct.unpack_from('<H', data, ifd)[0]):
        tag, kind, n, value = struct.unpack_from('<HHII', data, ifd + 2 + 12 * i)
        if kind == 3 and n == 1:
            value &= 0xFFFF
        elif n > 1:
            value = list(struct.unpack_from('<%d%s' % (n, 'H' if kind == 3 else 'I'), data, value))
        tags[tag] = value if isinstance(value, list) else [value]
    assert tags[259] == [1]
    digest = hashlib.md5()
    for offset, length in zip(tags[273], tags[279]):
        digest.update(data[offset:offset + length])
    print(digest.hexdigest())


if sys.argv[1] == 'raw-md5':
    raw_md5(sys.argv[2])
else:
    main()
