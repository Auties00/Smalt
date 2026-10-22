#!/usr/bin/env python3
"""Writes the Zstandard, LZMA, LERC, JBIG, PixarLog and NeXT TIFF vectors of smalt-image's
TiffCompressionsTest.

Seeded source images of several sample types are written uncompressed by tifffile, then recompressed by
libtiff's tiffcp (zstd, lzma, lerc and jbig, with predictors, tiles, separate planes, big-endian byte order
and the LERC Deflate and Zstandard wrappings) and by tifffile with imagecodecs (zstd, lzma and lerc as
those libraries write them). Neither tiffcp nor tifffile writes PixarLog or NeXT, so this script also codes
them itself, as libtiff's PixarLog encoder and the NeXT format define them: PixarLog strips holding every one
of the 2048 codes, and NeXT strips mixing literal rows, literal spans and runs; further PixarLog pages hold
strips coded by imagecodecs' PixarLog encoder, an independent writer. No writer puts legacy Lerc1 blobs in
TIFF, though libtiff decodes them, so two floating-point LERC pages hold Lerc1 blobs of the LERC decoder
vectors (lerc1-*.lerc, which tools/testdata/lerc/make-vectors.py writes next to this directory). Every vector has a reference next to it, <name>.ref.tif, which is
tiffcp -c zip of the vector: libtiff's decode of it, the black-box oracle the test compares Smalt's
decode with. libtiff decodes one-sample PixarLog rows wrongly (it leaves the first sample out of the
accumulation), so the reference of a gray PixarLog vector is the first channel of libtiff's decode of an
RGB twin holding the same codes in every channel. A copy of a Zstandard vector
with the compression code 34926 checks the alias.

Made with libtiff 4.7.1 (tiffcp, with zstd 1.5.7, liblzma 5.8.1, LERC 4.0.0 and JBIG-KIT 2.1), Python 3.14,
numpy 2, tifffile 2026.10.15 and imagecodecs 2026.10.10 (its PixarLog encoder included); other versions may
code the vectors differently, which is why they are kept with the tests and pinned in compressions.sha256 next to the vector directory.

    python -I tools/testdata/tiff-compressions/make-vectors.py <image/src/test/resources/com/github/auties00/smalt/image/tiff/compressions>
"""
import hashlib
import os
import struct
import subprocess
import sys
import zlib

import imagecodecs
import numpy as np
import tifffile

WIDTH = 64
HEIGHT = 48


def smooth(channels, dtype, seed):
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:HEIGHT, 0:WIDTH].astype(np.float64)
    planes = []
    for c in range(channels):
        base = 0.5 + 0.4 * np.sin(x / (6.0 + 2 * c) + c) * np.cos(y / (5.0 + c))
        planes.append(np.clip(base + rng.normal(0, 0.01, base.shape), 0, 1))
    data = np.stack(planes, axis=-1)
    if channels == 1:
        data = data[..., 0]
    if np.issubdtype(dtype, np.floating):
        return (data * 100 - 20).astype(dtype)
    info = np.iinfo(dtype)
    return np.round(info.min + data * (info.max - info.min)).astype(dtype)


def sources():
    rgba = smooth(4, np.uint8, 4)
    rgba[..., 3] = np.where(rgba[..., 3] > 127, 255, 0)
    gray_nan = smooth(1, np.float32, 6)
    gray_nan[5:12, 8:30] = np.nan
    gray_nan[30, ::3] = np.nan
    bilevel = (smooth(1, np.uint8, 9) > 128)
    return {
        'rgb8': (smooth(3, np.uint8, 1), {'photometric': 'rgb'}),
        'gray8': (smooth(1, np.uint8, 2), {'photometric': 'minisblack'}),
        'gray16': (smooth(1, np.uint16, 3), {'photometric': 'minisblack'}),
        'rgba8': (rgba, {'photometric': 'rgb', 'extrasamples': ['unassalpha']}),
        'rgb16': (smooth(3, np.uint16, 5), {'photometric': 'rgb'}),
        'float32': (smooth(1, np.float32, 7), {'photometric': 'minisblack'}),
        'float32-nan': (gray_nan, {'photometric': 'minisblack'}),
        'rgbfloat32': (smooth(3, np.float32, 8), {'photometric': 'rgb'}),
        'float64': (smooth(1, np.float64, 10), {'photometric': 'minisblack'}),
        'int16': (smooth(1, np.int16, 11), {'photometric': 'minisblack'}),
        'int8': (smooth(1, np.int8, 12), {'photometric': 'minisblack'}),
        'uint32': (smooth(1, np.uint32, 13), {'photometric': 'minisblack'}),
        'int32': (smooth(1, np.int32, 14), {'photometric': 'minisblack'}),
        'bilevel': (bilevel, {'photometric': 'minisblack'}),
    }


# vector name, source, tiffcp arguments
TIFFCP = [
    ('zstd-rgb8', 'rgb8', ['-c', 'zstd']),
    ('zstd-rgb8-predictor', 'rgb8', ['-c', 'zstd:2']),
    ('zstd-gray16-predictor-be', 'gray16', ['-B', '-c', 'zstd:2']),
    ('zstd-float32-fpredictor', 'float32', ['-c', 'zstd:3']),
    ('zstd-rgb8-tiles', 'rgb8', ['-t', '-w', '32', '-l', '16', '-c', 'zstd:p19']),
    ('zstd-rgb16-planar', 'rgb16', ['-r', '20', '-c', 'zstd']),
    ('lzma-rgb8', 'rgb8', ['-c', 'lzma']),
    ('lzma-gray16-predictor-be', 'gray16', ['-B', '-c', 'lzma:2']),
    ('lzma-float32-fpredictor-tiles', 'float32', ['-t', '-w', '32', '-l', '32', '-c', 'lzma:3']),
    ('lzma-rgb8-planar', 'rgb8', ['-c', 'lzma:p9']),
    ('lerc-rgb8', 'rgb8', ['-c', 'lerc']),
    ('lerc-gray16-deflate', 'gray16', ['-c', 'lerc:0.5:s1']),
    ('lerc-gray16-lossy', 'gray16', ['-c', 'lerc:3']),
    ('lerc-gray16-be', 'gray16', ['-B', '-c', 'lerc']),
    ('lerc-float32-nan', 'float32-nan', ['-c', 'lerc']),
    ('lerc-float32-lossy-zstd', 'float32', ['-c', 'lerc:0.01:s2']),
    ('lerc-rgbfloat32', 'rgbfloat32', ['-c', 'lerc']),
    ('lerc-float64-tiles', 'float64', ['-t', '-w', '32', '-l', '32', '-c', 'lerc']),
    ('lerc-int16', 'int16', ['-c', 'lerc:s2']),
    ('lerc-int8', 'int8', ['-c', 'lerc']),
    ('lerc-uint32', 'uint32', ['-c', 'lerc']),
    ('lerc-int32-lossy', 'int32', ['-c', 'lerc:100']),
    ('lerc-rgba8-mask', 'rgba8', ['-c', 'lerc']),
    ('lerc-rgb8-planar', 'rgb8', ['-c', 'lerc']),
    ('jbig-bilevel', 'bilevel', ['-c', 'jbig']),
    ('jbig-bilevel-be', 'bilevel', ['-B', '-c', 'jbig']),
    ('jbig-bilevel-fillorder2', 'bilevel', ['-f', 'lsb2msb', '-c', 'jbig']),
]

# vector name, source, tifffile compression, compression arguments
TIFFFILE = [
    ('tifffile-zstd-gray16', 'gray16', 'zstd', {'level': 10}),
    ('tifffile-lzma-rgb8', 'rgb8', 'lzma', None),
    ('tifffile-lerc-float32', 'float32', 'lerc', None),
    ('tifffile-lerc-zstd-gray16', 'gray16', 'lerc', {'compression': 'zstd'}),
    ('tifffile-lerc-deflate-rgb16', 'rgb16', 'lerc', {'compression': 'deflate'}),
]

# vector name, source, bits per sample, sample format: PixarLog pages whose strips imagecodecs codes
IMAGECODECS_PIXARLOG = [
    ('pixarlog-imagecodecs-rgb8', 'rgb8', 8, 1),
    ('pixarlog-imagecodecs-rgba8', 'rgba8', 8, 1),
    ('pixarlog-imagecodecs-rgb16', 'rgb16', 16, 1),
    ('pixarlog-imagecodecs-rgbfloat32', 'rgbfloat32', 32, 3),
]


def tiff(width, height, bits, samples, compression, photometric, strips, sample_format=1, byteorder='<',
         extra=None):
    """A minimal TIFF with the given coded strips."""
    entries = [(256, 4, [width]), (257, 4, [height]), (258, 3, [bits] * samples), (259, 3, [compression]),
               (262, 3, [photometric]), (273, 4, None), (277, 3, [samples]),
               (278, 4, [-(-height // len(strips))]), (279, 4, [len(s) for s in strips]), (284, 3, [1]),
               (339, 3, [sample_format] * samples)]
    if extra:
        entries += extra
    entries.sort()
    count = len(entries)
    ifd_offset = 8
    data_offset = ifd_offset + 2 + 12 * count + 4
    resolved = list(entries)
    out_of_line = {}
    cursor = data_offset
    for tag, kind, values in resolved:
        if values is None:
            continue
        size = len(values) * (2 if kind == 3 else 4)
        if size > 4:
            out_of_line[tag] = cursor
            cursor += size + (size & 1)
    strip_offsets = []
    for strip in strips:
        strip_offsets.append(cursor)
        cursor += len(strip)
    if len(strips) > 1:
        out_of_line[273] = cursor
    order = byteorder
    header = (b'II' if order == '<' else b'MM') + struct.pack(order + 'HI', 42, ifd_offset)
    ifd = struct.pack(order + 'H', count)
    tail = bytearray()
    for tag, kind, values in resolved:
        if tag == 273:
            values = strip_offsets
        fmt = 'H' if kind == 3 else 'I'
        packed = struct.pack(order + fmt * len(values), *values)
        if len(packed) <= 4:
            ifd += struct.pack(order + 'HHI', tag, kind, len(values)) + packed.ljust(4, b'\0')
        else:
            ifd += struct.pack(order + 'HHII', tag, kind, len(values), out_of_line[tag])
            if tag != 273:
                tail += packed + (b'\0' if len(packed) & 1 else b'')
    ifd += struct.pack(order + 'I', 0)
    body = bytearray(header + ifd + tail)
    assert len(body) == strip_offsets[0], (len(body), strip_offsets[0])
    for strip in strips:
        body += strip
    if len(strips) > 1:
        body += struct.pack(order + 'I' * len(strip_offsets), *strip_offsets)
    return bytes(body)


def pixarlog_codes(stride, seed):
    """Codes for every sample, row by row covering all 2048 codes."""
    rng = np.random.default_rng(seed)
    codes = np.arange(WIDTH * HEIGHT * stride) % 2048
    rng.shuffle(codes)
    return codes.reshape(HEIGHT, WIDTH, stride)


def pixarlog(codes, bits, sample_format, byteorder, seed):
    """PixarLog strips of the given codes, differenced along each row as libtiff's encoder does."""
    rng = np.random.default_rng(seed)
    stride = codes.shape[2]
    rows_per_strip = 16
    strips = []
    for start in range(0, HEIGHT, rows_per_strip):
        words = []
        for pixels in codes[start:start + rows_per_strip]:
            diffs = pixels.copy()
            diffs[1:] = (pixels[1:] - pixels[:-1]) % 65536
            # the decoder masks the accumulated sum to 11 bits, so the high bits of a difference are free
            diffs[1:] = (diffs[1:] + rng.integers(0, 32, diffs[1:].shape) * 2048) % 65536
            words.extend(diffs.reshape(-1).tolist())
        strips.append(zlib.compress(struct.pack(byteorder + 'H' * len(words), *words), 6))
    photometric = 2 if stride >= 3 else 1
    extra = [(338, 3, [2])] if stride == 4 else None
    return tiff(WIDTH, HEIGHT, bits, stride, 32909, photometric, strips, sample_format, byteorder, extra)


def imagecodecs_pixarlog(data, bits, sample_format):
    """A little-endian PixarLog page of strips of 16 rows coded by imagecodecs' encoder."""
    strips = [imagecodecs.pixarlog_encode(np.ascontiguousarray(data[start:start + 16]))
              for start in range(0, HEIGHT, 16)]
    samples = data.shape[2]
    extra = [(338, 3, [2])] if samples == 4 else None
    return tiff(WIDTH, HEIGHT, bits, samples, 32909, 2, strips, sample_format, '<', extra)


def lerc1_tiff(directory, blob_name, width, height):
    """A floating-point LERC page whose one strip is a Lerc1 blob of the LERC decoder vectors."""
    path = os.path.join(directory, '..', '..', 'internal', 'lerc', 'blobs', blob_name)
    with open(path, 'rb') as file:
        blob = file.read()
    return tiff(width, height, 32, 1, 34887, 1, [blob], 3, '<', [(50674, 4, [4, 0])])


def next_tiff(seed):
    rng = np.random.default_rng(seed)
    width = 61
    row_bytes = (width * 2 + 7) // 8
    strips = []
    rows_per_strip = 12
    for start in range(0, HEIGHT, rows_per_strip):
        rows = []
        for y in range(start, min(HEIGHT, start + rows_per_strip)):
            values = [int(v) for v in np.repeat(rng.integers(0, 4, width // 5 + 1), rng.integers(1, 9, width // 5 + 1))[:width]]
            while len(values) < width:
                values.append(3)
            packed = bytearray(row_bytes)
            for x, v in enumerate(values):
                packed[x >> 2] |= v << (6 - 2 * (x & 3))
            rows.append((bytes(packed), values))
        coded = bytearray()
        for packed, values in rows:
            kind = int(rng.integers(0, 3))
            if kind == 0:
                coded += b'\x00' + packed
            elif kind == 1:
                offset = int(rng.integers(0, row_bytes - 1))
                count = int(rng.integers(1, row_bytes - offset + 1))
                coded += b'\x40' + struct.pack('>HH', offset, count) + packed[offset:offset + count]
            else:
                x = 0
                while x < width:
                    grey = values[x]
                    n = 1
                    while x + n < width and values[x + n] == grey and n < 63:
                        n += 1
                    coded.append(grey << 6 | n)
                    x += n
        strips.append(bytes(coded))
    return tiff(width, HEIGHT, 2, 1, 32766, 1, strips)


def tiffcp(arguments, source, target):
    subprocess.run(['tiffcp'] + arguments + [source, target], check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.PIPE)


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    directory = sys.argv[1]
    os.makedirs(directory, exist_ok=True)
    scratch = os.path.join(directory, 'source.tmp.tif')
    made = []
    try:
        all_sources = sources()
        for name, source, arguments in TIFFCP:
            data, options = all_sources[source]
            if name.endswith('-planar'):
                # tiffcp converts only 8-bit samples to separate planes, so the source is written planar
                tifffile.imwrite(scratch, np.moveaxis(data, -1, 0).copy(), planarconfig='separate', **options)
            else:
                tifffile.imwrite(scratch, data, **options)
            target = os.path.join(directory, name + '.tif')
            tiffcp(arguments, scratch, target)
            made.append(name)
        for name, source, compression, arguments in TIFFFILE:
            data, options = all_sources[source]
            target = os.path.join(directory, name + '.tif')
            tifffile.imwrite(target, data, compression=compression, compressionargs=arguments or {},
                             rowsperstrip=16, **options)
            made.append(name)
        with open(os.path.join(directory, 'zstd-rgb8.tif'), 'rb') as file:
            alias = bytearray(file.read())
        page = tifffile.TiffFile(os.path.join(directory, 'zstd-rgb8.tif')).pages[0]
        offset = page.tags['Compression'].offset
        struct.pack_into('<H', alias, offset + 8, 34926)
        with open(os.path.join(directory, 'zstd-alias-34926.tif'), 'wb') as file:
            file.write(alias)
        made.append('zstd-alias-34926')
        gray_codes = {name: pixarlog_codes(1, seed) for name, seed in
                      (('pixarlog-gray8', 20), ('pixarlog-gray16', 21), ('pixarlog-gray16-be', 22),
                       ('pixarlog-float32', 25))}
        handmade = {
            'pixarlog-gray8': pixarlog(gray_codes['pixarlog-gray8'], 8, 1, '<', 20),
            'pixarlog-gray16': pixarlog(gray_codes['pixarlog-gray16'], 16, 1, '<', 21),
            'pixarlog-gray16-be': pixarlog(gray_codes['pixarlog-gray16-be'], 16, 1, '>', 22),
            'pixarlog-rgb8': pixarlog(pixarlog_codes(3, 23), 8, 1, '<', 23),
            'pixarlog-rgba16': pixarlog(pixarlog_codes(4, 24), 16, 1, '<', 24),
            'pixarlog-float32': pixarlog(gray_codes['pixarlog-float32'], 32, 3, '<', 25),
            'next-gray2': next_tiff(26),
        }
        # libtiff accumulates the rows of one-sample PixarLog data wrongly (it leaves out the first sample), so
        # the reference of a gray vector is libtiff's decode of an RGB twin holding the same codes in every channel
        twins = {}
        for name, codes in gray_codes.items():
            bits, sample_format = {'pixarlog-gray8': (8, 1), 'pixarlog-float32': (32, 3)}.get(name, (16, 1))
            twins[name] = pixarlog(np.repeat(codes, 3, axis=2), bits, sample_format, '<', 99)
        handmade['lerc-lerc1-float32'] = lerc1_tiff(directory, 'lerc1-stuffed.lerc', 29, 31)
        handmade['lerc-lerc1-float32-mask'] = lerc1_tiff(directory, 'lerc1-rle-mask.lerc', 33, 27)
        for name, source, bits, sample_format in IMAGECODECS_PIXARLOG:
            handmade[name] = imagecodecs_pixarlog(all_sources[source][0], bits, sample_format)
        for name, body in handmade.items():
            with open(os.path.join(directory, name + '.tif'), 'wb') as file:
                file.write(body)
            made.append(name)
        for name in made:
            vector = os.path.join(directory, name + '.tif')
            reference = os.path.join(directory, name + '.ref.tif')
            if name in twins:
                twin = os.path.join(directory, 'twin.tmp.tif')
                twin_reference = os.path.join(directory, 'twin.ref.tmp.tif')
                try:
                    with open(twin, 'wb') as file:
                        file.write(twins[name])
                    tiffcp(['-c', 'none'], twin, twin_reference)
                    decoded = tifffile.imread(twin_reference)[..., 0]
                finally:
                    for path in (twin, twin_reference):
                        if os.path.exists(path):
                            os.remove(path)
                tifffile.imwrite(reference, decoded, photometric='minisblack', compression='zlib')
                continue
            if name == 'zstd-alias-34926':
                # libtiff does not know the alias; the reference is that of the vector it copies
                with open(os.path.join(directory, 'zstd-rgb8.ref.tif'), 'rb') as file:
                    content = file.read()
                with open(reference, 'wb') as file:
                    file.write(content)
                continue
            tiffcp(['-c', 'zip'], vector, reference)
    finally:
        if os.path.exists(scratch):
            os.remove(scratch)
    pins = []
    for name in sorted(os.listdir(directory)):
        with open(os.path.join(directory, name), 'rb') as file:
            pins.append(hashlib.sha256(file.read()).hexdigest() + '  ' + name)
    with open(os.path.join(os.path.dirname(os.path.abspath(directory)), 'compressions.sha256'), 'w',
              newline='\n') as out:
        out.write('\n'.join(pins) + '\n')


if __name__ == '__main__':
    main()
