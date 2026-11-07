#!/usr/bin/env python3
"""Writes the per-picture MD5s of the 3D-HEVC texture conformance streams that Hevc3dConformanceTest reads.

The MD5s are those of the output of the 3D-HEVC reference decoder HTM-16.3 (BSD 3-clause, built with
HEVC_EXT=2), which writes one 8-bit 4:2:0 file per layer, rec_<nuh_layer_id>.yuv, the depth layers with
mid-gray chroma. The whole-file MD5 of every layer equals the MD5 the conformance archive ships; this script
checks that before it writes anything.

Usage: python frame-md5.py <out> <key>=<directory with rec_*.yuv>,<archive .md5 file>,<width>x<height> ...
  python frame-md5.py frame-md5.txt A=out/A,3DHC_T_A.md5,1024x768 E=out/E,3DHC_T_E.md5,1920x1088

Each output line is "<key> <layer> <picture index in output order> <md5>".
"""
import hashlib
import os
import re
import sys


def main():
    out = sys.argv[1]
    lines = []
    for spec in sys.argv[2:]:
        key, rest = spec.split('=', 1)
        directory, md5_file, size = rest.split(',')
        width, height = (int(v) for v in size.split('x'))
        frame_size = width * height * 3 // 2
        archive = {}
        with open(md5_file, encoding='ascii') as f:
            for line in f:
                match = re.match(r'^([0-9a-fA-F]{32})\s+\*?\S*_(\d+)\.yuv\s*$', line.strip())
                if match:
                    archive[int(match.group(2))] = match.group(1).lower()
        for layer in sorted(archive):
            path = os.path.join(directory, 'rec_%d.yuv' % layer)
            with open(path, 'rb') as f:
                data = f.read()
            whole = hashlib.md5(data).hexdigest()
            if whole != archive[layer]:
                sys.exit('%s layer %d: %s does not match the archive MD5 %s' % (key, layer, whole, archive[layer]))
            for i in range(len(data) // frame_size):
                digest = hashlib.md5(data[i * frame_size:(i + 1) * frame_size]).hexdigest()
                lines.append('%s %d %d %s' % (key, layer, i, digest))
    with open(out, 'w', encoding='ascii', newline='\n') as f:
        f.write('\n'.join(lines) + '\n')
    print('wrote %d pictures to %s' % (len(lines), out))


if __name__ == '__main__':
    main()
