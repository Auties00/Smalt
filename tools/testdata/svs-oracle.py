#!/usr/bin/env python3
"""Writes the oracle files of smalt-image's TiffCodecCompressionTest for the Aperio JPEG 2000 slides.

The test decodes a pyramid level of two public Aperio slides of the OpenSlide test data (compression
33005, RGB codestreams with the irreversible component transform, and 33003, Y, Cb and Cr codestreams
with subsampled chroma) and compares it with two renderings this script writes into
<conformance cache>/svs-oracle (-Dsmalt.test.svsOracle overrides the directory):

  <slide>-page<k>.rgba       OpenSlide's read_region of the whole level, RGBA;
  <slide>-page<k>.openjpeg   for 33005 only, the level assembled from its tiles as OpenJPEG decodes
                             them (through imagecodecs), RGB.

OpenSlide and imagecodecs are used as black boxes: pip install openslide-python openslide-bin
tifffile imagecodecs numpy (made with openslide-python 1.4.6, openslide-bin 4.0.1, tifffile 2026.10.15
and imagecodecs 2026.10.10, which bundles OpenJPEG 2.5.4), on Windows: the OpenJPEG of imagecodecs'
Linux wheel rounds the irreversible component transform of one sample of the 33005 level otherwise. The
slides are fetched first when the cache lacks them.

    python -I tools/testdata/svs-oracle.py
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import testdata  # noqa: E402

import imagecodecs  # noqa: E402
import numpy as np  # noqa: E402
import openslide  # noqa: E402
import tifffile  # noqa: E402

# vector name, TIFF page, OpenSlide level, whether to write the OpenJPEG rendering
SLIDES = [
    ('openslide-aperio-cmu-1-jp2k-33005.svs', 3, 2, True),
    ('openslide-aperio-jp2k-33003-1.svs', 3, 2, False),
]


def vector(name):
    rows = testdata.read_tsv(testdata.VECTORS, testdata.VECTOR_COLUMNS)
    for row in rows:
        if row['name'] == name:
            path = os.path.join(testdata.conformance_dir(), name)
            if not testdata.valid(path, row['sha256']):
                testdata.download(row['url'], path, row['sha256'])
            return path
    raise SystemExit(name + ' is not in vectors.tsv')


def openjpeg(path, page):
    with tifffile.TiffFile(path) as tiff:
        level = tiff.pages[page]
        width, height = level.imagewidth, level.imagelength
        tile_width, tile_height = level.tilewidth, level.tilelength
        across = (width + tile_width - 1) // tile_width
        out = np.zeros((height, width, 3), np.uint8)
        handle = tiff.filehandle
        for index, (offset, count) in enumerate(zip(level.dataoffsets, level.databytecounts)):
            handle.seek(offset)
            tile = imagecodecs.jpeg2k_decode(handle.read(count))
            y0 = (index // across) * tile_height
            x0 = (index % across) * tile_width
            h = min(tile_height, height - y0)
            w = min(tile_width, width - x0)
            out[y0:y0 + h, x0:x0 + w] = tile[:h, :w, :3]
        return out


def main():
    directory = os.path.join(testdata.conformance_dir(), 'svs-oracle')
    os.makedirs(directory, exist_ok=True)
    for name, page, level, with_openjpeg in SLIDES:
        path = vector(name)
        slide = openslide.OpenSlide(path)
        width, height = slide.level_dimensions[level]
        region = slide.read_region((0, 0), level, (width, height)).convert('RGBA')
        with open(os.path.join(directory, '%s-page%d.rgba' % (name, page)), 'wb') as out:
            out.write(region.tobytes())
        if with_openjpeg:
            with open(os.path.join(directory, '%s-page%d.openjpeg' % (name, page)), 'wb') as out:
                out.write(openjpeg(path, page).tobytes())
        print(name, 'page', page, width, 'x', height)


if __name__ == '__main__':
    main()
