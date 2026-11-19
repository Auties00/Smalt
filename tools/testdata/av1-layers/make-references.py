#!/usr/bin/env python3
"""Writes the per operating point references of smalt-video's Av1LayersTest.

The libaom scalability test vectors (av1-1-b8-22-svc-L1T2, -L2T1-2 and -L2T2-2, pinned in tools/testdata/vectors.tsv
and cached by the tests' Conformance.fetch) declare several operating points. For every operating point the sequence
headers declare, plus one they do not declare, and with and without --all-layers, the script decodes each vector
with libaom's aomdec (BSD-2-Clause, used as a black box) into one raw file per frame and records the size and MD5 of
every frame, in output order, in layers.tsv:

    vector    operating point    all layers (0 or 1)    frame    width    height    MD5 of the frame's planes

Made with aomdec 3.13.1 built from the v3.13.1 tag of https://aomedia.googlesource.com/aom (cmake, MSYS2 UCRT64
gcc, nasm). The output of a conforming decoder does not depend on the aomdec version.

    python -I tools/testdata/av1-layers/make-references.py <aomdec> <conformance cache> \\
        <video/src/test/resources/com/github/auties00/smalt/video/av1/layers>
"""
import glob
import hashlib
import os
import re
import subprocess
import sys
import tempfile

# vector name: the operating point count its sequence header declares
VECTORS = {
    'av1-1-b8-22-svc-L1T2.ivf': 2,
    'av1-1-b8-22-svc-L2T1-2.ivf': 2,
    'av1-1-b8-22-svc-L2T2-2.ivf': 4,
}


def decode(aomdec, stream, point, all_layers, work):
    for name in glob.glob(os.path.join(work, 'frame-*.yuv')):
        os.remove(name)
    command = [aomdec, '--rawvideo', '--oppoint=%d' % point, '-o', os.path.join(work, 'frame-%4-%wx%h.yuv'), stream]
    if all_layers:
        command.insert(2, '--all-layers')
    subprocess.check_call(command, stdout=subprocess.DEVNULL)
    frames = []
    for name in sorted(glob.glob(os.path.join(work, 'frame-*.yuv'))):
        match = re.search(r'frame-(\d+)-(\d+)x(\d+)\.yuv$', name)
        data = open(name, 'rb').read()
        frames.append((int(match.group(1)), int(match.group(2)), int(match.group(3)), hashlib.md5(data).hexdigest()))
    frames.sort()
    return [(width, height, md5) for _, width, height, md5 in frames]


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    aomdec, cache, target = sys.argv[1], sys.argv[2], os.path.abspath(sys.argv[3])
    os.makedirs(target, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory() as work:
        for vector, points in VECTORS.items():
            stream = os.path.join(cache, 'av1-' + vector)
            for point in range(points + 1):
                for all_layers in (0, 1):
                    frames = decode(aomdec, stream, point, all_layers, work)
                    if point == points:
                        # an undeclared operating point selects operating point 0
                        if frames != decode(aomdec, stream, 0, all_layers, work):
                            raise SystemExit('%s: undeclared operating point %d differs from 0' % (vector, point))
                    for index, (width, height, md5) in enumerate(frames):
                        rows.append('\t'.join([vector, str(point), str(all_layers), str(index), str(width),
                                               str(height), md5]))
    with open(os.path.join(target, 'layers.tsv'), 'w', newline='\n') as sink:
        sink.write('# Written by tools/testdata/av1-layers/make-references.py from aomdec 3.13.1 --rawvideo '
                   '--oppoint=<point> [--all-layers]:\n'
                   '# vector, operating point, all layers, frame, width, height, MD5 of the frame.\n')
        for row in rows:
            sink.write(row + '\n')
    print('wrote %d frames to %s' % (len(rows), target))


if __name__ == '__main__':
    main()
