"""Cuts a crop out of the CFA image of an uncompressed 16-bit DNG and writes it as little-endian 16-bit samples.

Usage: dngcrop.py <dng> <x> <y> <width> <height> <out> [shift]
The samples are shifted left by shift bits (0 by default) and saturated to 16 bits. Prints the size, the CFA pattern
and repeat of the image, and the range of the crop. Strip-organized CFA images only. Run with python -I: the DNG is
a downloaded file.
"""
import struct
import sys

TYPE_SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8, 12: 8, 13: 4}
TYPE_FORMATS = {1: 'B', 3: 'H', 4: 'I', 7: 'B', 13: 'I'}


def main():
    path, x0, y0, w, h, out = sys.argv[1], *map(int, sys.argv[2:6]), sys.argv[6]
    shift = int(sys.argv[7]) if len(sys.argv) > 7 else 0
    data = open(path, 'rb').read()
    order = '<' if data[:2] == b'II' else '>'
    first, = struct.unpack_from(order + 'I', data, 4)

    def ifd(offset):
        count, = struct.unpack_from(order + 'H', data, offset)
        tags = {}
        for i in range(count):
            tag, kind, n, value = struct.unpack_from(order + 'HHII', data, offset + 2 + 12 * i)
            at = offset + 2 + 12 * i + 8 if TYPE_SIZES.get(kind, 1) * n <= 4 else value
            if kind in TYPE_FORMATS:
                tags[tag] = list(struct.unpack_from(order + TYPE_FORMATS[kind] * n, data, at))
        following, = struct.unpack_from(order + 'I', data, offset + 2 + 12 * count)
        return tags, following

    queue = [first]
    images = []
    seen = set()
    while queue:
        offset = queue.pop()
        if offset == 0 or offset in seen:
            continue
        seen.add(offset)
        tags, following = ifd(offset)
        queue.append(following)
        queue.extend(tags.get(330, []))
        images.append(tags)
    cfa = [t for t in images if t.get(262, [0])[0] == 32803]
    if not cfa:
        raise SystemExit('no CFA image')
    t = cfa[0]
    if t.get(259, [1])[0] != 1 or t.get(258, [0])[0] != 16 or 324 in t:
        raise SystemExit('not an uncompressed, strip-organized 16-bit CFA image')
    width, height = t[256][0], t[257][0]
    print('size', width, height, 'pattern', t.get(33422), 'repeat', t.get(33421))
    pixels = bytearray()
    for start, count in zip(t[273], t[279]):
        pixels += data[start:start + count]
    samples = struct.unpack(order + 'H' * (width * height), bytes(pixels[:width * height * 2]))
    crop = []
    for y in range(y0, y0 + h):
        crop.extend(min(65535, v << shift) for v in samples[y * width + x0:y * width + x0 + w])
    open(out, 'wb').write(struct.pack('<' + 'H' * len(crop), *crop))
    print('range', min(crop), max(crop))


main()
