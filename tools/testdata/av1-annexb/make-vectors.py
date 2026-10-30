#!/usr/bin/env python3
"""Writes the AV1 Annex B vectors of smalt-video's Av1AnnexBTest.

Each vector is a short stream coded by libaom's aomenc (BSD-2-Clause, used as a black box) from ffmpeg's testsrc2
pattern in the length-delimited bitstream format of the AV1 specification's Annex B (--annexb=1): IVF files, whose
frames each hold one temporal unit with its temporal_unit_size, and one raw .obu stream of consecutive temporal units.
The streams hide frames (alternate reference frames), so that temporal units carry several frame units, and cover
tiles, 10-bit 4:4:4 and monochrome. Next to each stream the script records the SHA-256 of libaom's aomdec --annexb
--rawvideo output (every frame's planes, 16-bit little-endian samples above 8 bits, mid-gray chroma for monochrome),
which the test compares with Smalt's decode, in annexb.tsv:

    name    frames    stream sha256    decoded sha256

Made with aomenc and aomdec 3.13.1 built from the v3.13.1 tag of https://aomedia.googlesource.com/aom (cmake, MSYS2
UCRT64 gcc, nasm) and ffmpeg 7.1; other aomenc versions code the streams differently, which is why the files are kept
with the tests.

    python -I tools/testdata/av1-annexb/make-vectors.py <directory of aomenc and aomdec> \\
        <video/src/test/resources/com/github/auties00/smalt/video/av1/annexb>
"""
import hashlib
import os
import subprocess
import sys
import tempfile

# name: (container, width, height, frames, ffmpeg pixel format, aomenc options)
COMMON = ['--cpu-used=6', '--threads=1', '--row-mt=0', '--end-usage=q', '--cq-level=44', '--kf-max-dist=12',
          '--auto-alt-ref=1', '--lag-in-frames=8']
VECTORS = {
    'annexb-8bit-tiles': ('ivf', 160, 96, 10, 'yuv420p', ['--tile-columns=1', '--tile-rows=1']),
    'annexb-10bit-444': ('ivf', 96, 64, 8, 'yuv444p10le', ['--profile=1', '--bit-depth=10', '--input-bit-depth=10']),
    'annexb-monochrome': ('ivf', 96, 64, 6, 'gray', ['--monochrome']),
    'annexb-raw': ('obu', 128, 80, 9, 'yuv420p', []),
}


def sha256(path):
    return hashlib.sha256(open(path, 'rb').read()).hexdigest()


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    tools = sys.argv[1]
    aomenc = os.path.join(tools, 'aomenc')
    aomdec = os.path.join(tools, 'aomdec')
    target = os.path.abspath(sys.argv[2])
    os.makedirs(target, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory() as work:
        for name, (container, width, height, frames, pixel_format, options) in VECTORS.items():
            source = os.path.join(work, name + '.y4m')
            # gray has no y4m mapping aomenc reads, so the monochrome source is 4:2:0 with neutral chroma
            ffmpeg_format = 'yuv420p' if pixel_format == 'gray' else pixel_format
            filters = 'format=gray,format=yuv420p' if pixel_format == 'gray' else 'format=' + ffmpeg_format
            subprocess.check_call(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                                   'testsrc2=size=%dx%d:rate=25' % (width, height), '-frames:v', str(frames), '-vf',
                                   filters, '-strict', '-1', '-f', 'yuv4mpegpipe', source])
            stream = os.path.join(target, name + '.' + container)
            subprocess.check_call([aomenc, '--' + container, '--annexb=1', '--limit=%d' % frames] + COMMON + options
                                  + ['-o', stream, source], stderr=subprocess.DEVNULL)
            decoded = os.path.join(work, name + '.yuv')
            subprocess.check_call([aomdec, '--annexb', '--rawvideo', '-o', decoded, stream])
            rows.append('\t'.join([name + '.' + container, str(frames), sha256(stream), sha256(decoded)]))
    with open(os.path.join(target, 'annexb.tsv'), 'w', newline='\n') as sink:
        sink.write('# Written by tools/testdata/av1-annexb/make-vectors.py: name, frames, SHA-256 of the stream,\n'
                   '# SHA-256 of aomdec --annexb --rawvideo output (aomenc and aomdec 3.13.1).\n')
        for row in rows:
            sink.write(row + '\n')
    print('wrote %d vectors to %s' % (len(rows), target))


if __name__ == '__main__':
    main()
