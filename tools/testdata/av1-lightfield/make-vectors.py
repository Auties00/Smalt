#!/usr/bin/env python3
"""Writes the AV1 large scale tile vectors of smalt-video's Av1LargeScaleTileTest.

Each vector is a light field (AV1 specification Annex D) coded with libaom's example programs (BSD-2-Clause, built
from the v3.13.1 tag of https://aomedia.googlesource.com/aom with cmake, MSYS2 UCRT64 gcc and nasm, used as black
boxes): ffmpeg 7.1 renders one picture (testsrc2 or mandelbrot) and the camera views are crops of it shifted by a few
samples per camera; lightfield_encoder codes the anchor frames and the camera frames, lightfield_bitstream_parsing
turns the camera frames' tiles named by a tile list description into tile list OBUs after the anchor frames and a
camera frame header, and lightfield_tile_list_decoder decodes them, writing every tile of every tile list one after
the other (its 1D output: the tile's luma rows, then its Cb and Cr rows). The script checks that libaom's
lightfield_decoder, which decodes the same tiles from the camera frames, writes the same bytes, as libaom's
lightfield_test.sh does, and records in lightfield.tsv:

    name    anchor frames    tile lists    stream sha256    SHA-256 of the tile list decoder's output

    python -I tools/testdata/av1-lightfield/make-vectors.py <directory of the libaom examples> \\
        <video/src/test/resources/com/github/auties00/smalt/video/av1/lightfield>
"""
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile

# name: (picture width, height, cameras across, cameras down, block size, ffmpeg source, camera step,
#        tile lists as (camera frame, anchor frame, tile column, tile row) lists)
VECTORS = {
    'lightfield-testsrc2': (256, 128, 4, 4, 2, 'testsrc2', 6, [
        [(0, 0, 0, 0), (1, 0, 1, 0), (2, 0, 2, 1), (3, 0, 3, 1), (4, 1, 0, 1), (9, 2, 2, 0)],
        [(15, 3, 3, 1), (14, 3, 0, 0), (5, 1, 1, 1), (5, 1, 1, 1), (12, 3, 2, 0)],
        [(7, 1, 3, 0)],
    ]),
    'lightfield-mandelbrot': (320, 192, 6, 2, 2, 'mandelbrot', 4, [
        [(0, 0, 0, 0), (1, 0, 4, 2), (6, 1, 2, 1), (9, 2, 1, 0), (11, 2, 3, 2)],
        [(5, 1, 0, 0), (4, 1, 1, 0), (3, 0, 2, 0), (10, 2, 3, 0), (8, 2, 4, 0), (7, 1, 4, 1), (2, 0, 0, 2)],
    ]),
}


def sha256(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    tools = os.path.abspath(sys.argv[1])
    target = os.path.abspath(sys.argv[2])
    os.makedirs(target, exist_ok=True)
    rows = []
    for name, (width, height, across, down, block, pattern, step, lists) in VECTORS.items():
        with tempfile.TemporaryDirectory() as work:
            big = os.path.join(work, 'source.yuv')
            subprocess.check_call(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                                   '%s=size=%dx%d' % (pattern, width + across * step, height + down * step),
                                   '-frames:v', '1', '-pix_fmt', 'yuv420p', '-f', 'rawvideo', big])
            views = os.path.join(work, 'views.yuv')
            with open(views, 'wb') as sink:
                for v in range(down):
                    for u in range(across):
                        view = os.path.join(work, 'view.yuv')
                        subprocess.check_call(['ffmpeg', '-v', 'error', '-y', '-f', 'rawvideo', '-pix_fmt', 'yuv420p',
                                               '-s', '%dx%d' % (width + across * step, height + down * step), '-i',
                                               big, '-vf', 'crop=%d:%d:%d:%d' % (width, height, u * step, v * step),
                                               '-f', 'rawvideo', '-pix_fmt', 'yuv420p', view])
                        sink.write(open(view, 'rb').read())
            anchors = ((across + block - 1) // block) * ((down + block - 1) // block)
            light_field = os.path.join(work, 'light-field.ivf')
            subprocess.check_call([os.path.join(tools, 'lightfield_encoder'), str(width), str(height), views,
                                   light_field, str(across), str(down), str(block)], cwd=work,
                                  stdout=subprocess.DEVNULL)
            description = os.path.join(work, 'tiles.txt')
            with open(description, 'w', newline='\n') as sink:
                for tiles in lists:
                    sink.write('F\n')
                    for frame, anchor, column, row in tiles:
                        sink.write('%d %d %d %d\n' % (frame, anchor, column, row))
            stream = os.path.join(target, name + '.ivf')
            subprocess.check_call([os.path.join(tools, 'lightfield_bitstream_parsing'), light_field, stream,
                                   str(anchors), description], cwd=work, stdout=subprocess.DEVNULL)
            decoded = os.path.join(work, 'tiles.yuv')
            subprocess.check_call([os.path.join(tools, 'lightfield_tile_list_decoder'), stream, decoded, str(anchors),
                                   str(len(lists))], cwd=work, stdout=subprocess.DEVNULL)
            reference = os.path.join(work, 'reference.yuv')
            subprocess.check_call([os.path.join(tools, 'lightfield_decoder'), light_field, reference, str(anchors),
                                   description], cwd=work, stdout=subprocess.DEVNULL)
            if open(decoded, 'rb').read() != open(reference, 'rb').read():
                raise SystemExit(name + ': the tile list decoder and the light field decoder disagree')
            rows.append('\t'.join([name + '.ivf', str(anchors), str(len(lists)), sha256(stream), sha256(decoded)]))
    with open(os.path.join(target, 'lightfield.tsv'), 'w', newline='\n') as sink:
        sink.write('# Written by tools/testdata/av1-lightfield/make-vectors.py: name, anchor frames, tile lists,\n'
                   '# SHA-256 of the stream, SHA-256 of lightfield_tile_list_decoder\'s output (libaom 3.13.1).\n')
        for row in rows:
            sink.write(row + '\n')
    print('wrote %d vectors to %s' % (len(rows), target))


if __name__ == '__main__':
    main()
