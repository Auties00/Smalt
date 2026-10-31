#!/usr/bin/env python3
"""Writes the MPEG-1 and MPEG-2 video streams of the smalt-aot training workloads (MpegWorkloads.mpegVideoDecode)
with the ISO/IEC 13818-5 reference encoder that build.py builds.

Each stream codes six frames of a synthetic scene (two drifting sine gratings and a bar whose fields move apart, the
pattern of the mpeg module's ReferenceEncoder) at 128x96, choosing the coding tools that select different paths of
Smalt's decoder: frame pictures with B-pictures and field and frame prediction; field pictures with dual-prime
prediction; 4:2:2 field pictures with B-pictures, the alternate scan and the intra VLC table; and MPEG-1 with
B-pictures.

Usage: python -I make-training.py <smalt-mpeg2enc> <output directory> <work directory>
"""
import math
import os
import subprocess
import sys

WIDTH = 128
HEIGHT = 96
FRAMES = 6

STREAMS = [
    # name, mpeg1, field pictures, distance, chroma, profile, frame_pred_frame_dct, q_scale_type, intra_vlc,
    # alternate_scan
    ('frame-bframes', False, False, 3, 1, 4, (0, 0, 0), (1, 1, 1), (1, 0, 0), (0, 0, 0)),
    ('field-dualprime', False, True, 1, 1, 4, (0, 0, 0), (0, 0, 0), (0, 0, 0), (0, 0, 0)),
    ('field-422-bframes', False, True, 3, 2, 1, (0, 0, 0), (1, 1, 1), (1, 1, 1), (1, 1, 1)),
    ('mpeg1-bframes', True, False, 3, 1, 4, (1, 1, 1), (0, 0, 0), (0, 0, 0), (0, 0, 0)),
]


def plane(width, height, frame, component, step_x, step_y):
    out = bytearray(width * height)
    for y in range(height):
        full_y = y * step_y
        time = frame * 2 + (0 if full_y % 2 == 0 else 1)
        for x in range(width):
            full_x = x * step_x
            if component == 0:
                value = (128 + 50 * math.sin((full_x + time * 2) / 6.0) * math.cos(full_y / 9.0)
                         + 30 * math.sin((full_x - full_y + time) / 13.0))
                bar = (time * 3 // 2) % height
                if abs(full_y - bar) < 6 and width * step_x // 4 < full_x < width * step_x * 3 // 4:
                    value = 230
            else:
                phase = 0.0 if component == 1 else 1.7
                value = 128 + 40 * math.sin((full_x + time) / 17.0 + phase) + 20 * math.cos((full_y - time) / 11.0)
            out[y * width + x] = max(0, min(255, int(math.floor(value + 0.5))))
    return bytes(out)


def parameters(name, mpeg1, fields, distance, chroma, profile, fpfd, qst, ivf, scan, source, stat):
    lines = ['Smalt training sequence ' + name, source, '-', '-', '-', stat, '0', str(FRAMES), '0', '00:00:00:00',
             '6', str(distance), '1' if mpeg1 else '0', '1' if fields else '0', str(WIDTH), str(HEIGHT), '2', '3',
             '300000.0', '20', '0', '0', str(profile), '8', '1' if mpeg1 else '0', str(chroma), '2', '5', '5', '4',
             str(WIDTH), str(HEIGHT), '0', '1', ' '.join(map(str, fpfd)), '0 0 0', ' '.join(map(str, qst)),
             ' '.join(map(str, ivf)), ' '.join(map(str, scan)), '0', '1' if mpeg1 else '0', '0']
    lines += ['0'] * 8
    lines.append('2 2 11 11')
    for _ in range(1, distance):
        lines += ['1 1 7 7', '1 1 7 7']
    return '\n'.join(lines) + '\n'


def main():
    encoder, output, work = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(output, exist_ok=True)
    for name, mpeg1, fields, distance, chroma, profile, fpfd, qst, ivf, scan in STREAMS:
        directory = os.path.join(work, name)
        os.makedirs(directory, exist_ok=True)
        chroma_width = WIDTH // 2
        chroma_height = HEIGHT // 2 if chroma == 1 else HEIGHT
        for frame in range(FRAMES):
            base = os.path.join(directory, 'f%d' % frame)
            with open(base + '.Y', 'wb') as f:
                f.write(plane(WIDTH, HEIGHT, frame, 0, 1, 1))
            for component, suffix in ((1, '.U'), (2, '.V')):
                with open(base + suffix, 'wb') as f:
                    f.write(plane(chroma_width, chroma_height, frame, component, WIDTH // chroma_width,
                                  HEIGHT // chroma_height))
        par = os.path.join(directory, 'encode.par')
        with open(par, 'w', newline='\n') as f:
            f.write(parameters(name, mpeg1, fields, distance, chroma, profile, fpfd, qst, ivf, scan,
                               os.path.join(directory, 'f%d').replace('\\', '/'),
                               os.path.join(directory, 'stat.out').replace('\\', '/')))
        target = os.path.join(output, name + ('.m1v' if mpeg1 else '.m2v'))
        subprocess.run([encoder, par, target], check=True, stdout=subprocess.DEVNULL)
        print(name, os.path.getsize(target))


if __name__ == '__main__':
    main()
