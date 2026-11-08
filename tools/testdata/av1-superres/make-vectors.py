#!/usr/bin/env python3
"""Writes the AV1 super-resolution and frame resizing vectors of smalt-video's Av1SuperresTest.

Each vector is a short IVF stream coded by libaom's aomenc (BSD-2-Clause, used as a black-box encoder) from
ffmpeg's testsrc2 pattern, with super-resolution (fixed, random and quantizer-threshold denominators), with frame
resizing that changes the coded size from frame to frame, or both, across bit depths and chroma layouts, with loop
restoration, tiles and film grain on top. Next to each stream the script records the SHA-256 of libaom's aomdec
output (--rawvideo: every frame's planes at its own size, 16-bit little-endian samples above 8 bits), which the test
compares with Smalt's decode, in superres.tsv:

    name    frames    stream sha256    decoded sha256

Made with aomenc and aomdec 3.15.2 (the MSYS2 UCRT64 package mingw-w64-ucrt-x86_64-aom 3.15.2-1) and ffmpeg 7.1;
other aomenc versions code the streams differently, which is why the files are kept with the tests.

    python -I tools/testdata/av1-superres/make-vectors.py <video/src/test/resources/com/github/auties00/smalt/video/av1/superres>
"""
import hashlib
import os
import subprocess
import sys
import tempfile

# name: (width, height, frames, ffmpeg pixel format, aomenc options)
COMMON = ['--cpu-used=6', '--threads=1', '--row-mt=0', '--end-usage=q', '--cq-level=40', '--kf-max-dist=30']
VECTORS = {
    'superres-fixed-8bit': (142, 80, 8, 'yuv420p', ['--superres-mode=1', '--superres-denominator=16',
                                                     '--superres-kf-denominator=11']),
    'superres-random-8bit': (128, 72, 10, 'yuv420p', ['--superres-mode=2']),
    'superres-qthresh-8bit': (160, 96, 8, 'yuv420p', ['--superres-mode=3', '--superres-qthresh=30',
                                                       '--superres-kf-qthresh=30']),
    'superres-restoration-tiles': (264, 72, 6, 'yuv420p', ['--superres-mode=1', '--superres-denominator=13',
                                                            '--superres-kf-denominator=13', '--enable-restoration=1',
                                                            '--tile-columns=1']),
    'superres-10bit': (136, 80, 8, 'yuv420p10le', ['--superres-mode=1', '--superres-denominator=12',
                                                    '--superres-kf-denominator=9', '--bit-depth=10',
                                                    '--input-bit-depth=10']),
    'superres-444': (120, 64, 6, 'yuv444p', ['--profile=1', '--superres-mode=1', '--superres-denominator=14',
                                              '--superres-kf-denominator=14']),
    'superres-422-10bit': (120, 64, 6, 'yuv422p10le', ['--profile=2', '--bit-depth=10', '--input-bit-depth=10',
                                                        '--superres-mode=2']),
    'superres-12bit': (120, 64, 6, 'yuv420p12le', ['--profile=2', '--bit-depth=12', '--input-bit-depth=12',
                                                    '--superres-mode=1', '--superres-denominator=11',
                                                    '--superres-kf-denominator=16']),
    'superres-mono': (128, 64, 6, 'gray', ['--monochrome', '--superres-mode=1', '--superres-denominator=10',
                                           '--superres-kf-denominator=10']),
    'superres-film-grain': (128, 72, 6, 'yuv420p', ['--superres-mode=1', '--superres-denominator=15',
                                                     '--superres-kf-denominator=12', '--denoise-noise-level=20']),
    'resize-random': (144, 96, 10, 'yuv420p', ['--resize-mode=2']),
    'resize-random-superres-random': (144, 96, 10, 'yuv420p', ['--resize-mode=2', '--superres-mode=2']),
}


def sha256(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    target = os.path.abspath(sys.argv[1])
    os.makedirs(target, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory() as work:
        for name, (width, height, frames, pixel_format, options) in VECTORS.items():
            source = os.path.join(work, name + '.y4m')
            # gray has no y4m mapping aomenc reads, so the monochrome source is 4:2:0 with neutral chroma
            ffmpeg_format = 'yuv420p' if pixel_format == 'gray' else pixel_format
            filters = 'format=gray,format=yuv420p' if pixel_format == 'gray' else 'format=' + ffmpeg_format
            subprocess.check_call(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                                   'testsrc2=size=%dx%d:rate=25' % (width, height), '-frames:v', str(frames), '-vf',
                                   filters, '-strict', '-1', '-f', 'yuv4mpegpipe', source])
            stream = os.path.join(target, name + '.ivf')
            subprocess.check_call(['aomenc', '--ivf', '--limit=%d' % frames] + COMMON + options + ['-o', stream,
                                                                                                    source])
            decoded = os.path.join(work, name + '.yuv')
            subprocess.check_call(['aomdec', '--rawvideo', '-o', decoded, stream])
            rows.append('\t'.join([name, str(frames), sha256(stream), sha256(decoded)]))
    with open(os.path.join(target, 'superres.tsv'), 'w', newline='\n') as sink:
        sink.write('# Written by tools/testdata/av1-superres/make-vectors.py: name, frames, SHA-256 of the stream,\n'
                   '# SHA-256 of aomdec --rawvideo output (aomenc and aomdec 3.15.2).\n')
        for row in rows:
            sink.write(row + '\n')
    print('wrote %d vectors to %s' % (len(rows), target))


if __name__ == '__main__':
    main()
