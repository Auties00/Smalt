#!/usr/bin/env python3
"""Writes the WebP, JPEG XL and JPEG 2000 TIFF vectors of smalt-image's TiffCodecCompressionTest.

Each vector is a small TIFF whose strips or tiles are coded by libwebp, libjxl or OpenJPEG through
tifffile and imagecodecs (both BSD-licensed, used as black-box writers), next to a reference: the
same samples as tifffile and imagecodecs decode them, Deflate-compressed (uncompressed for 12-bit
samples), equal to the source pattern for the lossless vectors and different from it for the lossy
ones, which the script checks. The tests decode both with Smalt and compare the samples.

Made with Python 3.14, numpy 2, tifffile 2026.10.15 and imagecodecs 2026.10.10 (libjxl 0.11,
OpenJPEG 2.5, libwebp 1.6); other versions may code the lossy vectors differently, which is why
the files are kept with the tests and pinned in codecs.sha256, which the script writes next to the
vector directory.

    python -I tools/testdata/tiff-codecs/make-vectors.py <image/src/test/resources/com/github/auties00/smalt/image/tiff/codecs>
"""
import hashlib
import os
import sys

import imagecodecs
import numpy as np
import tifffile

WIDTH = 72
HEIGHT = 56


def pattern(channels, dtype, seed):
    """A smooth gradient with texture, in the range of an integer dtype or 0..1 for floats."""
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:HEIGHT, 0:WIDTH].astype(np.float64)
    planes = []
    for c in range(channels):
        base = 0.5 + 0.35 * np.sin(x / (7.0 + 3 * c) + c) * np.cos(y / (5.0 + 2 * c))
        base += rng.normal(0, 0.015, base.shape)
        planes.append(np.clip(base, 0, 1))
    data = np.stack(planes, axis=-1)
    if channels == 1:
        data = data[..., 0]
    if np.issubdtype(dtype, np.floating):
        return data.astype(dtype)
    info = np.iinfo(dtype)
    if info.min < 0:
        return np.round(data * 2 * info.max - info.max).astype(dtype)
    return np.round(data * info.max).astype(dtype)


# name, channels, dtype, photometric, compression, compression arguments, layout, lossless, byte order,
# and the BitsPerSample of samples narrower than their dtype (or None)
VECTORS = [
    ('jxl-rgb8-tiles', 3, np.uint8, 'rgb', 'jpegxl', None, 'tiles', True, '<'),
    ('jxl-rgba8-strips', 4, np.uint8, 'rgb', 'jpegxl', None, 'strips', True, '<'),
    ('jxl-gray16-tiles-be', 1, np.uint16, 'minisblack', 'jpegxl', None, 'tiles', True, '>'),
    ('jxl-rgb16-planar', 3, np.uint16, 'rgb', 'jpegxl', None, 'planar', True, '<'),
    ('jxl-gray-float32', 1, np.float32, 'minisblack', 'jpegxl', None, 'strips', True, '<'),
    ('jxl-rgb-float16-tiles-be', 3, np.float16, 'rgb', 'jpegxl', None, 'tiles', True, '>'),
    ('jxl-rgb8-lossy', 3, np.uint8, 'rgb', 'jpegxl', {'distance': 1.0}, 'tiles', False, '<'),
    ('jxl-dng-rgb8-tiles', 3, np.uint8, 'rgb', 52546, None, 'tiles', True, '<'),
    ('j2k-rgb8-tiles', 3, np.uint8, 'rgb', 'jpeg2000', None, 'tiles', True, '<'),
    ('j2k-rgba8-strips', 4, np.uint8, 'rgb', 'jpeg2000', None, 'strips', True, '<'),
    ('j2k-gray16-strips', 1, np.uint16, 'minisblack', 'jpeg2000', None, 'strips', True, '<'),
    ('j2k-gray-int16-tiles', 1, np.int16, 'minisblack', 'jpeg2000', None, 'tiles', True, '<'),
    ('j2k-rgb8-planar', 3, np.uint8, 'rgb', 'jpeg2000', None, 'planar', True, '<'),
    ('j2k-rgb8-lossy', 3, np.uint8, 'rgb', 'jpeg2000', {'level': 30}, 'tiles', False, '<'),
    ('aperio-33005-rgb8-lossy', 3, np.uint8, 'rgb', 33005, {'level': 30}, 'tiles', False, '<'),
    ('webp-rgb8-tiles', 3, np.uint8, 'rgb', 'webp', {'lossless': True}, 'tiles', True, '<'),
    ('webp-rgba8-strips', 4, np.uint8, 'rgb', 'webp', {'lossless': True}, 'strips', True, '<'),
    ('webp-rgb8-lossy', 3, np.uint8, 'rgb', 'webp', {'level': 80, 'lossless': False}, 'tiles', False, '<'),
    ('webp-rgba8-lossy-strips', 4, np.uint8, 'rgb', 'webp', {'level': 75, 'lossless': False}, 'strips', False, '<'),
    ('j2k-gray12-tiles', 1, np.uint16, 'minisblack', 'jpeg2000', None, 'tiles', True, '<', 12),
    ('jxl-gray12-strips', 1, np.uint16, 'minisblack', 'jpegxl', {'bitspersample': 12}, 'strips', True, '<', 12),
]


def write(directory, name, channels, dtype, photometric, compression, arguments, layout, lossless, order, bits=None):
    data = pattern(channels, dtype, len(name))
    if bits:
        data >>= np.iinfo(dtype).bits - bits
    options = {'photometric': photometric, 'compression': compression, 'byteorder': order}
    if bits:
        options['bitspersample'] = bits
    if arguments:
        options['compressionargs'] = arguments
    if channels in (2, 4):
        options['extrasamples'] = ['unassalpha']
    if layout == 'tiles':
        options['tile'] = (32, 32)
    elif layout == 'planar':
        options['planarconfig'] = 'separate'
        options['rowsperstrip'] = 24
        data = np.moveaxis(data, -1, 0).copy()
    else:
        options['rowsperstrip'] = 24
    path = os.path.join(directory, name + '.tif')
    tifffile.imwrite(path, data, **options)
    decoded = tifffile.imread(path)
    if lossless and not np.array_equal(decoded, data):
        raise SystemExit(name + ': the lossless vector does not decode to its source')
    if not lossless and np.array_equal(decoded, data):
        raise SystemExit(name + ': the lossy vector decodes to its source exactly')
    reference = {'photometric': photometric, 'byteorder': order, 'compression': 'zlib'}
    if bits:
        reference['bitspersample'] = bits
        reference['compression'] = None
    if channels in (2, 4):
        reference['extrasamples'] = ['unassalpha']
    if layout == 'planar':
        reference['planarconfig'] = 'separate'
    reference_path = os.path.join(directory, name + '.ref.tif')
    tifffile.imwrite(reference_path, decoded, **reference)
    # tifffile 2026.10.15 packs big-endian 12-bit samples wrongly, which this catches
    if not np.array_equal(tifffile.imread(reference_path), decoded):
        raise SystemExit(name + ': the reference does not hold the decoded samples')


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    directory = sys.argv[1]
    os.makedirs(directory, exist_ok=True)
    print('tifffile', tifffile.__version__, 'imagecodecs', imagecodecs.__version__)
    for vector in VECTORS:
        write(directory, *vector)
    lines = []
    for name in sorted(os.listdir(directory)):
        if name.endswith('.tif'):
            with open(os.path.join(directory, name), 'rb') as file:
                lines.append(hashlib.sha256(file.read()).hexdigest() + '  ' + name)
    manifest = os.path.join(os.path.dirname(os.path.abspath(directory)), 'codecs.sha256')
    with open(manifest, 'w', newline='\n') as out:
        out.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
