#!/usr/bin/env python3
"""Writes the AV1 YCgCo-Re and YCgCo-Ro vectors of smalt-video's Av1YcgcoRTest.

Each vector is a short IVF stream coded by libaom (BSD-2-Clause, through ffmpeg 7.1's libaom-av1 encoder, both used
as black boxes) from synthetic R'G'B' frames that this script transforms to Y'CgCo with the integer YCgCo-R transform
of ITU-T H.273 (equations 58 to 61); ffmpeg's av1_metadata bitstream filter then sets the sequence header's color
description: BT.709 primaries, sRGB transfer, matrix_coefficients 16 (YCgCo-Re) or 17 (YCgCo-Ro). The R'G'B'
sample of channel c (0 red, 1 green, 2 blue) at column x, row y of frame f is

    (x * (37 + 2c) + y * (23 + 5c) + f * 17 + ((x * y + 13c) % 7) * 29) % 2^rgbDepth

in the lossless vectors and the smooth gradient

    ((x * (16 + 4c) + y * (12 + 3c) + f * 8) * (2^rgbDepth - 1)) // (47 * (16 + 4c) + 39 * (12 + 3c) + 16)

in the lossy ones, with rgbDepth two (YCgCo-Re) or one (YCgCo-Ro) bits below the coded depth; the monochrome vector
uses the green
channel for all three, which the transform maps to luma only. The test computes the same frames. The lossless
vectors restore the transform exactly; the lossy 4:2:0 and 4:2:2 ones average the color differences of each chroma
sample's pixels (rounded half up) before coding. Each lossless 4:4:4 vector also gets a one-frame AVIF copy
(ffmpeg's avif muxer, stream copy) for the AVIF decoder comparisons (libavif's avifdec writes PNG only from full-range
10-bit YCgCo-Re, whose R'G'B' is 8-bit). The script records the SHA-256 of every file
in ycgcor.tsv:

    name    frames    sha256

Made with ffmpeg 7.1 (the gyan.dev essentials build, libaom 3.x inside); other encoder versions code the streams
differently, which is why the files are kept with the tests.

    python -I tools/testdata/av1-ycgco-r/make-vectors.py <video/src/test/resources/com/github/auties00/smalt/video/av1/ycgcor>
"""
import hashlib
import os
import struct
import subprocess
import sys
import tempfile

WIDTH = 48
HEIGHT = 40

# name: (matrix 16 or 17, coded depth, layout '444', '422', '420' or 'mono', full range, frames, lossless)
VECTORS = {
    're10-full-444': (16, 10, '444', True, 3, True),
    're10-limited-444': (16, 10, '444', False, 2, True),
    'ro10-full-444': (17, 10, '444', True, 3, True),
    're12-limited-444': (16, 12, '444', False, 2, True),
    'ro12-full-444': (17, 12, '444', True, 2, True),
    're10-full-mono': (16, 10, 'mono', True, 2, True),
    're10-full-420': (16, 10, '420', True, 3, False),
    'ro10-limited-422': (17, 10, '422', False, 2, False),
}

PIXEL_FORMATS = {
    ('444', 10): 'yuv444p10le', ('444', 12): 'yuv444p12le', ('422', 10): 'yuv422p10le',
    ('420', 10): 'yuv420p10le', ('mono', 10): 'gray10le',
}


def sample(channel, x, y, frame, rgb_depth, lossless):
    if not lossless:
        return ((x * (16 + 4 * channel) + y * (12 + 3 * channel) + frame * 8) * ((1 << rgb_depth) - 1)
                // (47 * (16 + 4 * channel) + 39 * (12 + 3 * channel) + 16))
    return (x * (37 + 2 * channel) + y * (23 + 5 * channel) + frame * 17
            + ((x * y + 13 * channel) % 7) * 29) % (1 << rgb_depth)


def forward(r, g, b, depth):
    half = 1 << (depth - 1)
    co = r - b
    t = b + (co >> 1)
    cg = g - t
    return t + (cg >> 1), cg + half, co + half


def frame_planes(matrix, depth, layout, frame, lossless):
    rgb_depth = depth - 2 if matrix == 16 else depth - 1
    luma, cg, co = [], [], []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            if layout == 'mono':
                g = sample(1, x, y, frame, rgb_depth, lossless)
                r = b = g
            else:
                r, g, b = (sample(c, x, y, frame, rgb_depth, lossless) for c in range(3))
            values = forward(r, g, b, depth)
            luma.append(values[0])
            cg.append(values[1])
            co.append(values[2])
    if layout == 'mono':
        return [luma]
    if layout == '444':
        return [luma, cg, co]
    sy = 2 if layout == '420' else 1
    cw = (WIDTH + 1) // 2
    ch = (HEIGHT + sy - 1) // sy
    planes = [luma]
    for plane in (cg, co):
        sub = []
        for cy in range(ch):
            for cx in range(cw):
                cells = [plane[yy * WIDTH + xx] for yy in range(cy * sy, min(HEIGHT, cy * sy + sy))
                         for xx in range(cx * 2, min(WIDTH, cx * 2 + 2))]
                sub.append((2 * sum(cells) + len(cells)) // (2 * len(cells)))
        planes.append(sub)
    return planes


def sha256(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    target = os.path.abspath(sys.argv[1])
    os.makedirs(target, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory() as work:
        for name, (matrix, depth, layout, full, frames, lossless) in VECTORS.items():
            raw = os.path.join(work, name + '.yuv')
            with open(raw, 'wb') as sink:
                for frame in range(frames):
                    for plane in frame_planes(matrix, depth, layout, frame, lossless):
                        sink.write(struct.pack('<%dH' % len(plane), *plane))
            stream = os.path.join(target, name + '.ivf')
            quality = ['-aom-params', 'lossless=1'] if lossless else ['-crf', '18', '-b:v', '0']
            subprocess.check_call(['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt',
                                   PIXEL_FORMATS[(layout, depth)], '-s', '%dx%d' % (WIDTH, HEIGHT), '-r', '25', '-i',
                                   raw, '-c:v', 'libaom-av1', '-cpu-used', '6', '-threads', '1', '-g', '30'] + quality
                                  + ['-bsf:v', 'av1_metadata=color_primaries=1:transfer_characteristics=13'
                                     ':matrix_coefficients=%d:color_range=%s' % (matrix, 'pc' if full else 'tv'),
                                     '-f', 'ivf', stream])
            rows.append('\t'.join([name + '.ivf', str(frames), sha256(stream)]))
            if lossless and layout == '444':
                still = os.path.join(target, name + '.avif')
                subprocess.check_call(['ffmpeg', '-v', 'error', '-y', '-i', stream, '-frames:v', '1', '-c', 'copy',
                                       '-f', 'avif', still])
                rows.append('\t'.join([name + '.avif', '1', sha256(still)]))
    with open(os.path.join(target, 'ycgcor.tsv'), 'w', newline='\n') as sink:
        sink.write('# Written by tools/testdata/av1-ycgco-r/make-vectors.py: file, frames, SHA-256 of the file.\n')
        sink.write('\n'.join(rows) + '\n')


if __name__ == '__main__':
    main()
