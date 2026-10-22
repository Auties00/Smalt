#!/usr/bin/env python3
"""Writes the LERC blobs of smalt-image's LercDecoderTest.

Each blob is written by Esri's LERC library through imagecodecs (both Apache/BSD-licensed, used as a
black-box writer) from seeded data of every data type, at lossless and lossy levels, Lerc2 versions 2
to 6, with masks, several values per pixel and several bands; the manifest line holds the shape and
the SHA-256 of the decoded values as LERC decodes them (as little-endian doubles, pixels outside the
mask zero) and of the masks (one byte per pixel and band). The script parses each blob and fails
unless the set covers every coding path of the decoder: constant bands, uncompressed sweeps, bit-stuffed
and lookup-table blocks, difference-coded depths, both 8-bit Huffman modes and the lossless
floating-point mode.

LERC writes only Lerc2, so the legacy Lerc1 blobs (lerc1-*) are written by this script as the format
defines them, covering every count and value tile coding, run-length masks, the clamp to the largest
value and several bands; LERC decodes each one (the oracle), and the script fails unless that decode is
the one the script meant to code.

Made with Python 3.14, numpy 2 and imagecodecs 2026.10.10 (LERC 4.2.0); other versions may code the
blobs differently, which is why they are kept with the tests and pinned in lerc.sha256 next to the
vector directory.

    python -I tools/testdata/lerc/make-vectors.py <image/src/test/resources/com/github/auties00/smalt/image/internal/lerc/blobs>
"""
import hashlib
import os
import struct
import sys

import imagecodecs
import numpy as np

TYPES = {'int8': 0, 'uint8': 1, 'int16': 2, 'uint16': 3, 'int32': 4, 'uint32': 5, 'float32': 6, 'float64': 7}


def field(rows, cols, depth, dtype, seed, smooth=True):
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:rows, 0:cols].astype(np.float64)
    planes = []
    for d in range(depth):
        base = np.sin(x / (6.0 + d)) * np.cos(y / (4.0 + 2 * d)) if smooth else rng.random((rows, cols))
        planes.append(base + rng.normal(0, 0.02, base.shape))
    data = np.stack(planes, axis=-1)
    if depth == 1:
        data = data[..., 0]
    if np.issubdtype(dtype, np.floating):
        return (data * 1000.0).astype(dtype)
    info = np.iinfo(dtype)
    scale = min(info.max, 30000) if info.bits > 8 else info.max
    values = data * scale * 0.45 + (0 if info.min < 0 else scale * 0.5)
    return np.clip(np.round(values), info.min, info.max).astype(dtype)


def mask(rows, cols, seed):
    rng = np.random.default_rng(seed)
    valid = rng.random((rows, cols)) > 0.1
    valid[rows // 3: rows // 2, cols // 4: cols // 2] = False
    return valid


def modes(blob):
    """Returns the coding of each band: (version, one sweep, mode or None, block flags)."""
    found = []
    pos = 0
    while pos < len(blob) and blob[pos:pos + 6] == b'Lerc2 ':
        version = struct.unpack_from('<i', blob, pos + 6)[0]
        p = pos + 10 + (4 if version >= 3 else 0)
        nints = 6 + (version >= 4) + (version >= 6)
        ints = struct.unpack_from('<%di' % nints, blob, p)
        p += 4 * nints + (4 if version >= 6 else 0)
        ndbl = 3 + (2 if version >= 6 else 0)
        dbls = struct.unpack_from('<%dd' % ndbl, blob, p)
        p += 8 * ndbl
        rows, cols = ints[0], ints[1]
        depth = ints[2] if version >= 4 else 1
        i = 3 if version >= 4 else 2
        valid, block, size, dt = ints[i], ints[i + 1], ints[i + 2], ints[i + 3]
        more = ints[i + 4] if version >= 6 else 0
        max_error, zmin, zmax = dbls[0], dbls[1], dbls[2]
        mask_bytes = struct.unpack_from('<i', blob, p)[0]
        p += 4 + mask_bytes
        kind = None
        if valid == 0:
            kind = 'empty'
        elif zmin == zmax:
            kind = 'const'
        else:
            if version >= 4:
                width = {0: 1, 1: 1, 2: 2, 3: 2, 4: 4, 5: 4, 6: 4, 7: 8}[dt]
                mins = blob[p:p + width * depth]
                maxs = blob[p + width * depth:p + 2 * width * depth]
                p += 2 * width * depth
                if mins == maxs:
                    kind = 'const-ranges'
            if kind is None:
                if blob[p]:
                    kind = 'sweep'
                else:
                    p += 1
                    huffman_int = version >= 2 and dt in (0, 1) and max_error == 0.5
                    huffman_flt = version >= 6 and dt in (6, 7) and max_error == 0
                    flag = blob[p] if (huffman_int or huffman_flt) else 0
                    if flag == 1:
                        kind = 'huffman-delta'
                    elif flag == 2:
                        kind = 'huffman'
                    elif flag == 3:
                        kind = 'float-lossless'
                    else:
                        kind = 'tiles'
        found.append((version, kind))
        pos += size
        if not (version <= 5 or more > 0):
            break
    return found


CASES = []
for name in TYPES:
    dtype = name
    CASES.append((name + '-lossless', dtype, 37, 29, 1, 1, 0, None, False, True))
    CASES.append((name + '-masked', dtype, 37, 29, 1, 1, 0, None, True, True))
    CASES.append((name + '-lossy', dtype, 41, 23, 1, 1, 3 if 'int' in name else 0.05, None, False, True))
    CASES.append((name + '-depth3', dtype, 19, 17, 3, 1, 0, None, False, True))
    CASES.append((name + '-noise', dtype, 33, 31, 1, 1, 0, None, False, False))
for version in (2, 3, 4, 5):
    CASES.append(('uint16-v%d' % version, 'uint16', 45, 21, 1, 1, 0, version, True, True))
    CASES.append(('uint8-v%d' % version, 'uint8', 45, 21, 1, 1, 0, version, False, True))
    CASES.append(('float32-v%d' % version, 'float32', 45, 21, 1, 1, 0.01, version, True, True))
CASES.append(('uint8-depth4-v4', 'uint8', 23, 19, 4, 1, 0, 4, False, True))
CASES.append(('float32-large', 'float32', 160, 120, 1, 1, 0, None, False, True))
CASES.append(('float64-large', 'float64', 120, 90, 1, 1, 0, None, True, True))
CASES.append(('float32-depth2-large', 'float32', 90, 80, 2, 1, 0, None, False, True))
CASES.append(('uint8-large', 'uint8', 200, 150, 1, 1, 0, None, False, True))
CASES.append(('int8-large-masked', 'int8', 200, 150, 1, 1, 0, None, True, True))
CASES.append(('int16-bands3', 'int16', 31, 27, 1, 3, 0, None, True, True))
CASES.append(('float32-bands2-depth2', 'float32', 25, 23, 2, 2, 0.001, None, True, True))
CASES.append(('float32-large-v6', 'float32', 160, 120, 1, 1, 0, 6, False, True))
CASES.append(('float64-large-v6', 'float64', 120, 90, 1, 1, 0, 6, True, True))
CASES.append(('float32-depth3-large-v6', 'float32', 90, 80, 3, 1, 0, 6, False, True))
CASES.append(('float64-depth2-v6', 'float64', 70, 50, 2, 1, 0, 6, True, True))
CASES.append(('float32-noise-v6', 'float32', 64, 64, 1, 1, 0, 6, False, False))
CASES.append(('float32-lossy-v6', 'float32', 64, 48, 1, 1, 0.25, 6, True, True))
CASES.append(('int16-bands3-v6', 'int16', 31, 27, 1, 3, 0, 6, True, True))
CASES.append(('uint8-depth3-v6', 'uint8', 50, 40, 3, 1, 0, 6, False, True))
CASES.append(('int16-depth3-v5', 'int16', 40, 30, 3, 1, 0, 5, True, True))
CASES.append(('uint16-depth4-lossy-v6', 'uint16', 40, 30, 4, 1, 4, 6, False, True))
CASES.append(('int32-depth2-v6', 'int32', 33, 21, 2, 1, 0, 6, False, True))
CASES.append(('uint8-skewed', 'uint8', 100, 100, 1, 1, 0, None, False, 'skewed'))
CASES.append(('int8-skewed-v6', 'int8', 100, 100, 1, 1, 0, 6, True, 'skewed'))
CASES.append(('uint8-constant', 'uint8', 16, 16, 1, 1, 0, None, False, None))
CASES.append(('float32-constant-depth3', 'float32', 9, 7, 3, 1, 0, None, False, None))
CASES.append(('uint16-single-pixel', 'uint16', 1, 1, 1, 1, 0, None, False, True))
CASES.append(('uint32-wide-range', 'uint32', 64, 3, 1, 1, 0, None, False, False))


def build(name, dtype, cols, rows, depth, bands, level, version, masked, smooth, seed):
    dt = np.dtype(dtype)
    if smooth is None:
        data = np.full((rows, cols) if depth == 1 else (rows, cols, depth), 7, dtype=dt)
    elif smooth != 'skewed':
        data = field(rows, cols, depth, dt, seed, smooth)
    if smooth == 'skewed':
        rng = np.random.default_rng(seed)
        data = np.minimum(np.iinfo(dt).max, rng.exponential(3, (rows, cols))).astype(dt)
    if name == 'uint32-wide-range':
        data = (np.random.default_rng(seed).random((rows, cols)) * 4e9).astype(np.uint32)
    masks = mask(rows, cols, seed + 1) if masked else None
    if bands > 1:
        data = np.stack([data + np.asarray(b, dtype=dt) for b in range(bands)])
        if masked:
            masks = np.stack([mask(rows, cols, seed + 10 + b) for b in range(bands)])
    arguments = {'level': level}
    if version is not None:
        arguments['version'] = version
    if masks is not None:
        arguments['masks'] = masks
    if bands > 1:
        arguments['planar'] = True
    return imagecodecs.lerc_encode(data, **arguments), dt


def lerc1_stuff(values, bits67=None):
    """Lerc1 bit stuffing: the element count in 1, 2 or 4 bytes, then the values packed from the most
    significant bit of little-endian 32-bit words, the last word cut to the bytes it needs."""
    values = [int(v) for v in values]
    count = len(values)
    bits = (max(values) if values else 0).bit_length()
    if bits67 is None:
        bits67 = 2 if count < 256 else 1 if count < 65536 else 0
    out = bytearray([bits | bits67 << 6]) + count.to_bytes({0: 4, 1: 2, 2: 1}[bits67], 'little')
    if bits == 0 or count == 0:
        return bytes(out)
    total = count * bits
    words = [0] * ((total + 31) // 32)
    position = 0
    for value in values:
        for bit in range(bits - 1, -1, -1):
            if value >> bit & 1:
                words[position // 32] |= 1 << (31 - position % 32)
            position += 1
    tail = ((total & 31) + 7) >> 3
    for index, word in enumerate(words):
        if index == len(words) - 1 and tail:
            out += (word >> (8 * (4 - tail))).to_bytes(tail, 'little')
        else:
            out += word.to_bytes(4, 'little')
    return bytes(out)


def lerc1_number(value, bits67):
    """A tile offset of 4 (bits67 0, float), 2 (1, signed 16-bit) or 1 (2, signed 8-bit) bytes."""
    if bits67 == 0:
        return struct.pack('<f', value)
    return struct.pack('<h' if bits67 == 1 else '<b', int(value))


def lerc1_rle(data):
    """LERC's run-length coding: counts of literal bytes or negated run lengths, then the end marker."""
    out = bytearray()
    literal = bytearray()

    def flush():
        while literal:
            chunk = bytes(literal[:32767])
            del literal[:32767]
            out.extend(struct.pack('<h', len(chunk)) + chunk)

    i = 0
    while i < len(data):
        j = i
        while j < len(data) and data[j] == data[i] and j - i < 32767:
            j += 1
        if j - i >= 3:
            flush()
            out.extend(struct.pack('<h', -(j - i)) + bytes([data[i]]))
            i = j
        else:
            literal.append(data[i])
            i += 1
    flush()
    return bytes(out + struct.pack('<h', -32768))


def lerc1_tiles(height, width, vertical, horizontal):
    """The tiles of a Lerc1 part: equal tiles and the remainders, row by row."""
    for i_tile in range(vertical + 1):
        tile_h = height // vertical if i_tile < vertical else height % vertical
        if tile_h:
            i0 = i_tile * (height // vertical)
            for j_tile in range(horizontal + 1):
                tile_w = width // horizontal if j_tile < horizontal else width % horizontal
                if tile_w:
                    j0 = j_tile * (width // horizontal)
                    yield i0, i0 + tile_h, j0, j0 + tile_w


def lerc1_count_tile(cnt, i0, i1, j0, j1, kind):
    """One tile of the count part: 'zero', 'minus', 'one', 'raw' or ('stuffed', bits67, offset)."""
    region = cnt[i0:i1, j0:j1].reshape(-1)
    if kind in ('zero', 'minus', 'one'):
        cnt[i0:i1, j0:j1] = {'zero': 0, 'minus': -1, 'one': 1}[kind]
        return bytes([{'zero': 2, 'minus': 3, 'one': 4}[kind]])
    if kind == 'raw':
        return bytes([0]) + struct.pack('<%df' % region.size, *region.tolist())
    _, bits67, offset = kind
    values = np.maximum(0, np.round(region - offset)).astype(np.int64)
    cnt[i0:i1, j0:j1] = (offset + values).reshape(i1 - i0, j1 - j0)
    return bytes([1 | bits67 << 6]) + lerc1_number(offset, bits67) + lerc1_stuff(values)


def lerc1_value_tile(z, cnt, box, kind, max_error, all_pixels, maximum, decoded):
    """One tile of the value part: 'zero', 'raw', ('const', bits67, offset) or ('stuffed', bits67, offset);
    decoded receives the values the format gives back."""
    i0, i1, j0, j1 = box
    valid = cnt[i0:i1, j0:j1] > 0
    region = z[i0:i1, j0:j1]
    out = decoded[i0:i1, j0:j1]
    if kind == 'zero':
        out[valid] = 0
        return bytes([2])
    if kind == 'raw':
        out[valid] = region[valid]
        return bytes([0]) + struct.pack('<%df' % int(valid.sum()), *region[valid].tolist())
    name, bits67, offset = kind
    head = bytes([(1 if name == 'stuffed' else 3) | bits67 << 6]) + lerc1_number(offset, bits67)
    if name == 'const':
        out[valid] = np.float32(int(offset) if bits67 else offset)
        return head
    used = np.ones_like(valid) if all_pixels else valid
    scale = 2 * max_error
    quantized = np.maximum(0, np.round((region[used].astype(np.float64) - offset) / scale)).astype(np.int64)
    target = out.copy()
    target[used] = np.minimum((offset + quantized * scale).astype(np.float32), np.float32(maximum))
    out[valid] = target[valid]
    return head + lerc1_stuff(quantized)


def lerc1_blob(rows, cols, max_error, counts, value_parts, seed):
    """A Lerc1 blob, with the values its format gives back and their validity, one band per value part.

    counts is ('const', count), ('rle', valid) or ('tiles', tiling, kinds, initial counts); each value part
    is (tiling, kinds, how far below the largest value the clamp of the stuffed tiles lies)."""
    cnt = np.zeros((rows, cols), np.float32)
    if counts[0] == 'const':
        cnt[:] = counts[1]
        count_part = struct.pack('<iiif', 0, 0, 0, counts[1])
        all_pixels = counts[1] > 0
    elif counts[0] == 'rle':
        cnt[:] = counts[1]
        coded = lerc1_rle(np.packbits(counts[1].reshape(-1).astype(np.uint8)).tobytes())
        count_part = struct.pack('<iiif', 0, 0, len(coded), 1.0) + coded
        all_pixels = False
    else:
        _, tiling, kinds, initial = counts
        cnt[:] = initial
        body = b''.join(lerc1_count_tile(cnt, *box, kinds[index % len(kinds)])
                        for index, box in enumerate(lerc1_tiles(rows, cols, *tiling)))
        count_part = struct.pack('<iiif', tiling[0], tiling[1], len(body), float(cnt.max())) + body
        all_pixels = False
    blob = bytearray()
    decoded = []
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:rows, 0:cols].astype(np.float64)
    for band, (tiling, kinds, below) in enumerate(value_parts):
        z = (50.0 * np.sin(x / 5.0) * np.cos(y / 4.0) + rng.normal(0, 0.3, (rows, cols)) + 10 * band).astype(np.float32)
        maximum = float(z.max()) - below
        out = np.zeros((rows, cols), np.float32)
        body = b''.join(lerc1_value_tile(z, cnt, box, kinds[index % len(kinds)], max_error,
                                         all_pixels and band == 0, maximum, out)
                        for index, box in enumerate(lerc1_tiles(rows, cols, *tiling)))
        blob += b'CntZImage ' + struct.pack('<iiiid', 11, 8, rows, cols, max_error)
        if band == 0:
            blob += count_part
        blob += struct.pack('<iiif', tiling[0], tiling[1], len(body), maximum) + body
        decoded.append(out)
    valid = np.broadcast_to(cnt > 0, (len(value_parts), rows, cols))
    return bytes(blob), np.stack(decoded), valid


LERC1_STUFFED = [('stuffed', 0, -60.5), ('stuffed', 1, -61), ('stuffed', 2, -100)]
LERC1_CONST = [('const', 0, 3.25), ('const', 1, -300), ('const', 2, 7)]


def lerc1_cases():
    """Lerc1 blobs covering every count and value tile coding, run-length masks, the clamp and several bands."""
    rng = np.random.default_rng(5)
    mask = rng.random((27, 33)) > 0.15
    mask[5:12, 4:20] = False
    initial = np.where(np.random.default_rng(9).random((25, 26)) > 0.3,
                       np.random.default_rng(10).integers(-1, 4, (25, 26)), 0)
    return [
        ('lerc1-raw', 17, 23, 0.0, ('const', 1.0), [((2, 3), ['raw'], 0)]),
        ('lerc1-stuffed', 31, 29, 0.25, ('const', 1.0), [((3, 4), LERC1_STUFFED + LERC1_CONST + ['zero', 'raw'], 0.5)]),
        ('lerc1-rle-mask', 27, 33, 0.5, ('rle', mask), [((4, 3), LERC1_STUFFED + ['raw', 'zero'] + LERC1_CONST, 0)]),
        ('lerc1-count-tiles', 25, 26, 0.1,
         ('tiles', (3, 2), ['zero', 'minus', 'one', 'raw', ('stuffed', 0, -1.5), ('stuffed', 1, -2), ('stuffed', 2, -1)],
          initial), [((2, 2), LERC1_STUFFED + ['raw'], 0)]),
        ('lerc1-bands3', 27, 33, 0.05, ('rle', mask),
         [((2, 3), LERC1_STUFFED, 0), ((1, 1), ['raw'], 0), ((3, 3), LERC1_CONST + LERC1_STUFFED, 1.0)]),
        ('lerc1-empty', 9, 11, 0.5, ('const', 0.0), [((1, 1), ['zero'], 0)]),
        ('lerc1-single-pixel', 1, 1, 0.0, ('const', 1.0), [((1, 1), ['raw'], 0)]),
        # one tile of more than 65535 values, whose element count takes four bytes
        ('lerc1-large', 257, 256, 0.5, ('const', 1.0), [((1, 1), [('stuffed', 0, -60)], 0)]),
    ]


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    directory = sys.argv[1]
    os.makedirs(directory, exist_ok=True)
    print('imagecodecs', imagecodecs.__version__, imagecodecs.lerc_version())
    lines = []
    seen = set()
    for index, (name, dtype, cols, rows, depth, bands, level, version, masked, smooth) in enumerate(CASES):
        blob, dt = build(name, dtype, cols, rows, depth, bands, level, version, masked, smooth, index)
        values, valid = imagecodecs.lerc_decode(blob, masks=True)
        values = np.asarray(values)
        valid = np.asarray(valid if valid is not None else True, dtype=bool)
        if valid.size == 1:
            valid = np.full((bands, rows, cols), bool(valid.reshape(-1)[0]))
        if bands > 1:
            values = values.reshape(bands, rows, cols, depth)
            valid = np.broadcast_to(valid.reshape(-1, rows, cols), (bands, rows, cols))
        else:
            values = values.reshape(1, rows, cols, depth)
            valid = valid.reshape(1, rows, cols)
        canonical = np.where(valid[..., None], values.astype(np.float64), 0.0)
        found = modes(blob)
        seen.update(kind for _, kind in found)
        with open(os.path.join(directory, name + '.lerc'), 'wb') as file:
            file.write(blob)
        lines.append('\t'.join([name + '.lerc', str(TYPES[dtype]), str(depth), str(cols), str(rows), str(bands),
                                hashlib.sha256(canonical.astype('<f8').tobytes()).hexdigest(),
                                hashlib.sha256(valid.astype(np.uint8).tobytes()).hexdigest(),
                                ','.join('v%d:%s' % item for item in found)]))
    for name, rows, cols, max_error, counts, value_parts in lerc1_cases():
        blob, expected, valid = lerc1_blob(rows, cols, max_error, counts, value_parts, len(lines))
        bands = len(value_parts)
        values, masks = imagecodecs.lerc_decode(blob, masks=True)
        values = np.asarray(values).reshape(bands, rows, cols)
        masks = np.ones((bands, rows, cols), bool) if masks is None else np.broadcast_to(
            np.asarray(masks, dtype=bool).reshape(-1, rows, cols), (bands, rows, cols))
        canonical = np.where(valid, values.astype(np.float64), 0.0)
        if not np.array_equal(masks, valid) or not np.array_equal(canonical, np.where(valid, expected, 0.0)):
            raise SystemExit(name + ': LERC decodes another image than the one coded')
        seen.add('lerc1')
        with open(os.path.join(directory, name + '.lerc'), 'wb') as file:
            file.write(blob)
        lines.append('\t'.join([name + '.lerc', '6', '1', str(cols), str(rows), str(bands),
                                hashlib.sha256(canonical.astype('<f8').tobytes()).hexdigest(),
                                hashlib.sha256(valid.astype(np.uint8).tobytes()).hexdigest(), 'lerc1']))
    print('coding paths:', sorted(seen))
    for required in ('const', 'sweep', 'tiles', 'huffman', 'huffman-delta', 'float-lossless', 'lerc1'):
        if required not in seen:
            raise SystemExit('no vector covers ' + required)
    with open(os.path.join(directory, 'blobs.tsv'), 'w', newline='\n') as file:
        file.write('# blob\tdata type\tdepth\tcolumns\trows\tbands\tvalues sha256\tmasks sha256\tcoding\n'
                   + '\n'.join(sorted(lines)) + '\n')
    pins = []
    for name in sorted(os.listdir(directory)):
        with open(os.path.join(directory, name), 'rb') as file:
            pins.append(hashlib.sha256(file.read()).hexdigest() + '  ' + name)
    with open(os.path.join(os.path.dirname(os.path.abspath(directory)), 'lerc.sha256'), 'w', newline='\n') as out:
        out.write('\n'.join(pins) + '\n')


if __name__ == '__main__':
    main()
