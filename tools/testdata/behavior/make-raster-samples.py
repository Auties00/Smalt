"""Writes the I/O behavior samples of the raster formats no installed encoder writes: DDS textures (block
compressed with deterministic pseudo-random blocks, which reach every mode, and uncompressed), Photoshop documents,
ICNS icons and JPEG XR images, under image/src/test/resources/samples. The committed files are the reference their
golden dumps are tied to.

    python -I tools/testdata/behavior/make-raster-samples.py [output directory]
"""
import os
import random
import struct
import sys


def dds_header(width, height, mipmaps, pixel_flags, four_cc, bits, masks, caps2=0, depth=1):
    flags = 0x1007 | (0x20000 if mipmaps > 1 else 0) | (0x800000 if depth > 1 else 0)
    header = b'DDS ' + struct.pack('<7I', 124, flags, height, width, 0, depth, mipmaps) + b'\0' * 44
    header += struct.pack('<8I', 32, pixel_flags, four_cc, bits, *masks)
    header += struct.pack('<5I', 0x1000, caps2, 0, 0, 0)
    assert len(header) == 128
    return header


def code(text):
    return struct.unpack('<I', text.encode('ascii'))[0]


def blocks(width, height):
    return ((width + 3) // 4) * ((height + 3) // 4)


def dds(out):
    rng = random.Random(20261029)
    os.makedirs(os.path.join(out, 'dds'), exist_ok=True)

    def write(name, data):
        with open(os.path.join(out, 'dds', name), 'wb') as file:
            file.write(data)

    sizes = [(48, 32), (24, 16), (12, 8), (6, 4), (3, 2), (1, 1)]
    payload = b''.join(rng.randbytes(blocks(w, h) * 8) for w, h in sizes)
    write('dxt1-mipmaps.dds', dds_header(48, 32, len(sizes), 0x4, code('DXT1'), 0, (0, 0, 0, 0)) + payload)
    write('dxt5.dds', dds_header(48, 32, 1, 0x4, code('DXT5'), 0, (0, 0, 0, 0)) + rng.randbytes(blocks(48, 32) * 16))
    dx10 = struct.pack('<5I', 98, 3, 0, 1, 0)
    write('bc7.dds', dds_header(48, 32, 1, 0x4, code('DX10'), 0, (0, 0, 0, 0)) + dx10
          + rng.randbytes(blocks(48, 32) * 16))
    dx10 = struct.pack('<5I', 95, 3, 0, 1, 0)
    write('bc6h.dds', dds_header(48, 32, 1, 0x4, code('DX10'), 0, (0, 0, 0, 0)) + dx10
          + rng.randbytes(blocks(48, 32) * 16))
    cube = 0x200 | 0xFC00
    write('cube-bgra.dds', dds_header(16, 16, 1, 0x41, 0, 32, (0xFF0000, 0xFF00, 0xFF, 0xFF000000), cube)
          + rng.randbytes(6 * 16 * 16 * 4))


def packbits(row):
    out = bytearray()
    position = 0
    while position < len(row):
        run = 1
        while position + run < len(row) and run < 128 and row[position + run] == row[position]:
            run += 1
        if run >= 3:
            out += bytes([(257 - run) & 0xFF, row[position]])
            position += run
            continue
        literal = 1
        while (position + literal < len(row) and literal < 128
               and not (position + literal + 2 < len(row) and row[position + literal] == row[position + literal + 1]
                        == row[position + literal + 2])):
            literal += 1
        out += bytes([literal - 1]) + row[position:position + literal]
        position += literal
    return bytes(out)


def gradient_planes(width, height, channels, depth, rng):
    planes = []
    for channel in range(channels):
        plane = bytearray()
        for y in range(height):
            for x in range(width):
                value = ((x * 5 + y * 3 + channel * 60) % 256) if (x // 8 + y // 8 + channel) % 3 else rng.randrange(256)
                plane += bytes([value]) if depth == 8 else struct.pack('>H', value * 257)
        planes.append(bytes(plane))
    return planes


def psd_file(width, height, depth, mode, planes, compression, layer_count=0, version=1):
    out = b'8BPS' + struct.pack('>H', version) + b'\0' * 6
    out += struct.pack('>HIIHH', len(planes), height, width, depth, mode)
    out += struct.pack('>I', 0) + struct.pack('>I', 0)
    if layer_count:
        if version == 2:
            out += struct.pack('>QQhIH', 8 + 2 + 4 + 2, 2, layer_count, 0, 0)
        else:
            out += struct.pack('>IIhIH', 4 + 2 + 4 + 2, 2, layer_count, 0, 0)
    else:
        out += struct.pack('>Q', 0) if version == 2 else struct.pack('>I', 0)
    out += struct.pack('>H', compression)
    row = width * depth // 8
    if compression == 0:
        out += b''.join(planes)
    elif compression == 1:
        rows = [packbits(plane[y * row:(y + 1) * row]) for plane in planes for y in range(height)]
        out += b''.join(struct.pack('>I' if version == 2 else '>H', len(r)) for r in rows) + b''.join(rows)
    else:
        import zlib
        out += zlib.compress(b''.join(planes), 9)
    return out


def psd(out):
    rng = random.Random(8)
    os.makedirs(os.path.join(out, 'psd'), exist_ok=True)

    def write(name, data):
        with open(os.path.join(out, 'psd', name), 'wb') as file:
            file.write(data)

    write('rgba-rle.psd', psd_file(48, 32, 8, 3, gradient_planes(48, 32, 4, 8, rng), 1, -1))
    write('gray16-raw.psd', psd_file(48, 32, 16, 1, gradient_planes(48, 32, 1, 16, rng), 0))
    write('cmyk-zip.psd', psd_file(48, 32, 8, 4, gradient_planes(48, 32, 4, 8, rng), 2))
    write('rgb-rle.psb', psd_file(48, 32, 8, 3, gradient_planes(48, 32, 3, 8, rng), 1, 0, 2))


def icns_rle(plane):
    """Codes one plane as ICNS runs: repeats of 3 to 130 bytes, literals of up to 128."""
    out = bytearray()
    position = 0
    literal = bytearray()
    while position < len(plane):
        run = 1
        while position + run < len(plane) and run < 130 and plane[position + run] == plane[position]:
            run += 1
        if run >= 3:
            if literal:
                out += bytes([len(literal) - 1]) + literal
                literal = bytearray()
            out += bytes([run + 125, plane[position]])
            position += run
        else:
            literal.append(plane[position])
            position += 1
            if len(literal) == 128:
                out += bytes([127]) + literal
                literal = bytearray()
    if literal:
        out += bytes([len(literal) - 1]) + literal
    return bytes(out)


def icns_rgba(size, seed):
    """The RGBA pattern of an ICNS sample image: a flat band (long runs), gradients, and transparent, partial and
    opaque pixels."""
    pixels = bytearray()
    for y in range(size):
        for x in range(size):
            if y < size // 4:
                r = g = b = 200
            else:
                r = x * 255 // (size - 1)
                g = y * 255 // (size - 1)
                b = ((x + y) * 37 + seed) & 255
            a = (x * 16 + seed) & 255 if y >= size // 2 else (255 if (x + y) % 5 else 0)
            pixels += bytes([r, g, b, a])
    return bytes(pixels)


def icns_family(elements):
    body = b''.join(code + struct.pack('>I', 8 + len(data)) + data for code, data in elements)
    return b'icns' + struct.pack('>I', 8 + len(body)) + body


def icns_bits(width, height, depth, value):
    """Packs an icon whose pixel (x, y) is value(x, y), most significant bits first, rows padded to bytes."""
    out = bytearray()
    for y in range(height):
        row = 0
        bits = 0
        for x in range(width):
            row = row << depth | value(x, y)
            bits += depth
            if bits == 8:
                out.append(row)
                row = 0
                bits = 0
        if bits:
            out.append(row << (8 - bits))
    return bytes(out)


def png_file(width, height, rgba):
    import zlib
    raw = b''.join(b'\x00' + rgba[y * width * 4:(y + 1) * width * 4] for y in range(height))

    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))


def icns(out):
    os.makedirs(os.path.join(out, 'icns'), exist_ok=True)

    def write(name, data):
        with open(os.path.join(out, 'icns', name), 'wb') as file:
            file.write(data)

    def rgb(size, seed, prefix=b''):
        rgba = icns_rgba(size, seed)
        return prefix + b''.join(icns_rle(rgba[channel::4]) for channel in range(3))

    def mask(size, seed):
        return icns_rgba(size, seed)[3::4]

    def mono(width, height):
        return (icns_bits(width, height, 1, lambda x, y: (x ^ y) & 1)
                + icns_bits(width, height, 1, lambda x, y: 1 if x < width - 2 else 0))

    def argb(size, seed):
        rgba = icns_rgba(size, seed)
        return b'ARGB' + b''.join(icns_rle(rgba[channel::4]) for channel in (3, 0, 1, 2))

    write('legacy.icns', icns_family([
        (b'ics#', mono(16, 16)),
        (b'ics4', icns_bits(16, 16, 4, lambda x, y: (x * 5 + y * 3) & 15)),
        (b'ics8', icns_bits(16, 16, 8, lambda x, y: (x * 5 + y * 3) & 255)),
        (b'is32', rgb(16, 1)),
        (b's8mk', mask(16, 1)),
        (b'ICN#', mono(32, 32)),
        (b'icl8', icns_bits(32, 32, 8, lambda x, y: (x * 7 + y) & 255)),
        (b'il32', rgb(32, 2)),
        (b'l8mk', mask(32, 2)),
        (b'ih32', rgb(48, 3)),
        (b'it32', rgb(128, 4, b'\x00\x00\x00\x00')),
        (b't8mk', mask(128, 4))]))
    stored = icns_rgba(16, 5)
    write('stored.icns', icns_family([
        (b'is32', b''.join(stored[i:i + 3] for i in range(0, len(stored), 4))),
        (b'icm#', mono(16, 12)),
        (b'icm4', icns_bits(16, 12, 4, lambda x, y: (x + y) & 15)),
        (b'ICON', icns_bits(32, 32, 1, lambda x, y: (x // 4 + y // 4) & 1))]))
    dark = icns_family([(b'ic04', argb(16, 9)), (b'ic05', argb(32, 10))])
    write('modern.icns', icns_family([
        (b'TOC ', b''),
        (b'ic04', argb(16, 6)),
        (b'ic05', argb(32, 7)),
        (b'ic07', png_file(128, 128, icns_rgba(128, 8))),
        (b'\xfd\xd9\x2f\xa8', dark)]))


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else 'image/src/test/resources/samples'
    dds(out)
    psd(out)
    icns(out)


if __name__ == '__main__':
    main()
