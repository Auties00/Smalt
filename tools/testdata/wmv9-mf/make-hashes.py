#!/usr/bin/env python3
"""Writes the Media Foundation reference hashes of smalt-mpeg's Vc1ConformanceTest.

The main profile Windows Media Video 9 files written by Microsoft's encoder (vectors.tsv rows
vc1-samples-wmv9-bloodyback.wmv and vc1-samples-wmv9-psychonauts.wmv) are decoded by Microsoft's own decoder,
the Windows Media Video 9 decoder of Media Foundation, through mf-decode.cpp (a Source Reader that outputs I420,
with the format converters disabled). The SHA-256 of every I and P frame of its output (8-bit planar 4:2:0, in
display order) goes into wmv9-mf.tsv:

    file    frame    picture type    decoded sha256

B frames are left out: Microsoft's decoder pulls back B picture motion vectors at the picture edges differently
from SMPTE ST 421 section 8.4.6.1 (it also differs from the conformance stream SMM0005 there, which Smalt and ffmpeg
decode identically), so the test compares B frames with ffmpeg instead. The picture types come from ffprobe, which
drops the frame after frame 280 of psychonauts (it rejects that packet as invalid data), so that file is hashed up
to frame 280 only.

Made on Windows 11 (wmvdecod.dll 10.0.22621) with mf-decode.cpp built by MSYS2 UCRT64 g++ 15:

    g++ -O1 -municode -o mf-decode.exe mf-decode.cpp -lmfplat -lmfreadwrite -lmfuuid -lole32
    python -I tools/testdata/wmv9-mf/make-hashes.py <mf-decode.exe> <output tsv> <file>:<frames>...

    make-hashes.py mf-decode.exe wmv9-mf.tsv vc1-samples-wmv9-bloodyback.wmv:300 vc1-samples-wmv9-psychonauts.wmv:280

where each file is named as its vectors.tsv row and is decoded up to <frames> frames.
"""
import hashlib
import os
import subprocess
import sys
import tempfile

WIDTH_HEIGHT_ENTRIES = 'stream=width,height'


def picture_types(path):
    out = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', 'frame=pict_type',
                          '-of', 'csv=p=0', path], check=True, capture_output=True, text=True).stdout
    return [line.strip() for line in out.splitlines() if line.strip()]


def frame_size(path):
    out = subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_entries', WIDTH_HEIGHT_ENTRIES,
                          '-of', 'csv=p=0', path], check=True, capture_output=True, text=True).stdout
    width, height = (int(value) for value in out.strip().split(','))
    return width * height + 2 * ((width + 1) // 2) * ((height + 1) // 2)


def main():
    decoder, output, files = sys.argv[1], sys.argv[2], sys.argv[3:]
    rows = []
    with tempfile.TemporaryDirectory(dir=os.path.dirname(os.path.abspath(output))) as scratch:
        for argument in files:
            path, frames = argument.rsplit(':', 1)
            frames = int(frames)
            raw = os.path.join(scratch, 'decoded.yuv')
            subprocess.run([decoder, path, raw, str(frames)], check=True)
            with open(raw, 'rb') as stream:
                data = stream.read()
            size = frame_size(path)
            types = picture_types(path)
            count = len(data) // size
            if count != frames:
                sys.exit(f'{path}: decoded {count} frames, expected {frames}')
            for index in range(count):
                if types[index] == 'B':
                    continue
                digest = hashlib.sha256(data[index * size:(index + 1) * size]).hexdigest()
                rows.append(f'{os.path.basename(path)}\t{index}\t{types[index]}\t{digest}\n')
    with open(output, 'w', newline='\n') as stream:
        stream.write('# file\tframe\tpicture type\tdecoded sha256 (Media Foundation WMV9 decoder, I420)\n')
        stream.writelines(rows)


if __name__ == '__main__':
    main()
