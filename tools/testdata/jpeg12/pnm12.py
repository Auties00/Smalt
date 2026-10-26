"""Crops 12-bit PNM files and writes the synthetic 12-bit source of the 12-bit JPEG test vectors.

Usage:
    python pnm12.py crop <in.ppm> <out.ppm> <width> <height>    top-left crop
    python pnm12.py synth <out.ppm> <width> <height>            deterministic 12-bit test pattern
"""
import struct
import sys


def read(path):
    data = open(path, 'rb').read()
    fields = []
    i = 0
    while len(fields) < 4:
        while data[i:i + 1].isspace():
            i += 1
        j = i
        while not data[j:j + 1].isspace():
            j += 1
        fields.append(data[i:j])
        i = j
    i += 1
    channels = 3 if fields[0] == b'P6' else 1
    width, height, maxval = int(fields[1]), int(fields[2]), int(fields[3])
    count = width * height * channels
    samples = struct.unpack('>%dH' % count, data[i:i + 2 * count])
    return width, height, channels, maxval, samples


def write(path, width, height, channels, samples):
    header = ('%s\n%d %d\n4095\n' % ('P6' if channels == 3 else 'P5', width, height)).encode('ascii')
    open(path, 'wb').write(header + struct.pack('>%dH' % len(samples), *samples))


def crop(source, target, width, height):
    w, h, channels, maxval, samples = read(source)
    if maxval != 4095:
        raise SystemExit('the source must have maxval 4095')
    out = []
    for y in range(height):
        start = y * w * channels
        out.extend(samples[start:start + width * channels])
    write(target, width, height, channels, out)


def synth(target, width, height):
    # Gradients, a hard-edged checkerboard and full-amplitude noise from a fixed LCG, so that q100
    # coefficients reach the largest magnitude categories of 12-bit JPEG
    state = 12345
    out = []
    for y in range(height):
        for x in range(width):
            for c in range(3):
                state = (state * 1103515245 + 12345) & 0x7FFFFFFF
                noise = (state >> 8) % 4096
                if (x // 8 + y // 8) % 2 == 0:
                    value = 4095 if (x + y + c) % 2 == 0 else 0
                elif x < width // 2:
                    value = (x * 4095 // max(width - 1, 1) + c * 1365) % 4096
                else:
                    value = noise
                out.append(value)
    write(target, width, height, 3, out)


if sys.argv[1] == 'crop':
    crop(sys.argv[2], sys.argv[3], int(sys.argv[4]), int(sys.argv[5]))
elif sys.argv[1] == 'synth':
    synth(sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
else:
    raise SystemExit(__doc__)
