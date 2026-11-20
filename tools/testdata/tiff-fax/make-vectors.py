#!/usr/bin/env python3
"""Writes the CCITT fax TIFF vectors of smalt-image's TiffEncoderModesTest.

Each vector is a bilevel pattern of a given width (rows mixing short runs with runs longer than the
2560-pixel make-up code) saved by Pillow through libtiff as a black-box writer: uncompressed
(`fax-<width>-none.tif`, the source the test encodes), CCITT modified Huffman (compression 2,
`-rle`), its word-aligned variant (compression 32771, `-rlew`), T.4 one-dimensional coding
(compression 3, `-g3`, without a return-to-control sequence) and T.6 coding (compression 4,
`-g4`), one strip each. Pillow stores mode "1" images min-is-black, so
the coded bitmaps hold 1 for white; the test encodes the inverted pixels, whose white-is-zero
bitmap is the same, and compares the strip bytes. The script checks that every coded file but the
word-aligned one decodes to the source pixels: libtiff 4.7.1 misreads the rows of compression 32771
that end within the last byte its bit reader has loaded, its own output included, so that file is
checked by the test through Smalt's decoder instead.

Pillow cannot set T4Options, so the Group 3 variants with two-dimensional coding and fill bits
come from libtiff's own tiffcp (4.7.1, on PATH), run on the uncompressed file with one strip:
`fax-<width>-g3-2d.tif` (`-c g3:2d`), `-g3-fill` (`-c g3:fill`) and `-g3-2d-fill`
(`-c g3:2d:fill`), at the file's unset resolution, so with the two-dimensional parameter K = 2,
and `-g3-2d-fine` (`-c g3:2d`) from a copy whose YResolution tiffset made 196 dots per inch, so
with K = 4.

Made with Python 3.14, Pillow 12.0.0 and its bundled libtiff 4.7.1, and libtiff 4.7.1's tiffcp and
tiffset; the files are kept with the tests and pinned in fax.sha256, which the script writes next
to the vector directory.

    python tools/testdata/tiff-fax/make-vectors.py <image/src/test/resources/com/github/auties00/smalt/image/tiff/fax>
"""
import hashlib
import os
import random
import shutil
import subprocess
import sys
import tempfile

from PIL import Image, features

WIDTHS = (9, 77, 1731, 3001)
HEIGHT = 23
COMPRESSIONS = (('none', 'raw'), ('rle', 'tiff_ccitt'), ('rlew', 'tiff_raw_16'), ('g3', 'group3'),
                ('g4', 'group4'))
TIFFCP_SCHEMES = (('g3-2d', 'g3:2d', False), ('g3-fill', 'g3:fill', False), ('g3-2d-fill', 'g3:2d:fill', False),
                  ('g3-2d-fine', 'g3:2d', True))


def libtiff_tool(name):
    """The path of a libtiff tool on PATH, checked to be version 4.7.1."""
    path = shutil.which(name)
    if path is None:
        raise SystemExit(name + ' is not on PATH')
    banner = subprocess.run([path], capture_output=True, text=True).stderr
    if 'Version 4.7.1' not in banner:
        raise SystemExit(name + ' is not libtiff 4.7.1: ' + banner.splitlines()[0] if banner else name)
    return path


def tiffcp_variants(directory, width, tiffcp, tiffset):
    """Writes the Group 3 variants tiffcp makes from the uncompressed file of a width."""
    source = os.path.join(directory, 'fax-%d-none.tif' % width)
    with tempfile.TemporaryDirectory() as scratch:
        fine = os.path.join(scratch, 'fine.tif')
        shutil.copyfile(source, fine)
        subprocess.run([tiffset, '-s', '283', '196', fine], check=True)
        for suffix, scheme, high in TIFFCP_SCHEMES:
            path = os.path.join(directory, 'fax-%d-%s.tif' % (width, suffix))
            # one strip, as the Pillow files have, rather than tiffcp's default of about 8 KiB strips
            subprocess.run([tiffcp, '-c', scheme, '-r', str(HEIGHT), fine if high else source, path], check=True)


def pattern(width, seed):
    """Rows of alternating runs; every seventh row from the fourth is all black or all white."""
    rng = random.Random(seed)
    image = Image.new('1', (width, HEIGHT), 1)
    pixels = image.load()
    for y in range(HEIGHT):
        black = rng.random() < 0.5
        x = 0
        while x < width:
            kind = rng.randrange(8)
            run = rng.randrange(3000) if kind == 0 else 64 + rng.randrange(200) if kind == 1 else rng.randrange(12)
            for _ in range(run):
                if x >= width:
                    break
                pixels[x, y] = 0 if black else 1
                x += 1
            black = not black
        if y % 7 == 3:
            for x in range(width):
                pixels[x, y] = 0 if y % 2 == 0 else 1
    return image


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    directory = sys.argv[1]
    os.makedirs(directory, exist_ok=True)
    print('Pillow', Image.__version__, 'libtiff', features.version('libtiff'))
    tiffcp = libtiff_tool('tiffcp')
    tiffset = libtiff_tool('tiffset')
    for width in WIDTHS:
        image = pattern(width, width)
        for suffix, compression in COMPRESSIONS:
            path = os.path.join(directory, 'fax-%d-%s.tif' % (width, suffix))
            image.save(path, compression=compression)
        tiffcp_variants(directory, width, tiffcp, tiffset)
        suffixes = [suffix for suffix, _ in COMPRESSIONS] + [suffix for suffix, _, _ in TIFFCP_SCHEMES]
        for suffix in suffixes:
            if suffix == 'rlew':
                continue
            path = os.path.join(directory, 'fax-%d-%s.tif' % (width, suffix))
            with Image.open(path) as check:
                if list(check.convert('L').getdata()) != list(image.convert('L').getdata()):
                    raise SystemExit(path + ': the file does not hold the pattern')
    lines = []
    for name in sorted(os.listdir(directory)):
        if name.endswith('.tif'):
            with open(os.path.join(directory, name), 'rb') as file:
                lines.append(hashlib.sha256(file.read()).hexdigest() + '  ' + name)
    manifest = os.path.join(os.path.dirname(os.path.abspath(directory)), 'fax.sha256')
    with open(manifest, 'w', newline='\n') as out:
        out.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
