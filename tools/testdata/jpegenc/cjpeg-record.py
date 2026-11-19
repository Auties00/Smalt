"""Records the MD5 of cjpeg's output for each case of JpegEncoderModesTest's cjpeg-cases.tsv.

Usage: python -I cjpeg-record.py <cjpeg> <libjpeg-turbo testimages directory> <cjpeg-cases.tsv>

Rewrites the case file in place with the MD5 as its fourth column. A case's input is a test image, cropped to
its top-left WxH pixels after "@" (PPM or PGM, 8 or 16 bits); a file argument names a file of the case file's
directory or of the test images directory.
"""
import hashlib
import os
import subprocess
import sys


def read_pnm(data):
    tokens = []
    position = 0
    while len(tokens) < 4:
        while data[position:position + 1].isspace():
            position += 1
        if data[position:position + 1] == b'#':
            while data[position:position + 1] != b'\n':
                position += 1
            continue
        start = position
        while not data[position:position + 1].isspace():
            position += 1
        tokens.append(data[start:position].decode('ascii'))
    position += 1
    magic, width, height, maxval = tokens[0], int(tokens[1]), int(tokens[2]), int(tokens[3])
    channels = 3 if magic == 'P6' else 1
    size = 2 if maxval > 255 else 1
    return magic, width, height, maxval, channels, size, data[position:position + width * height * channels * size]


def crop(data, width, height):
    magic, w, h, maxval, channels, size, pixels = read_pnm(data)
    row = w * channels * size
    out = b''.join(pixels[y * row:y * row + width * channels * size] for y in range(height))
    return ('%s\n%d %d\n%d\n' % (magic, width, height, maxval)).encode('ascii') + out


def main():
    cjpeg, images, cases = sys.argv[1], sys.argv[2], sys.argv[3]
    here = os.path.dirname(os.path.abspath(cases))
    lines = open(cases, encoding='utf-8').read().splitlines()
    out = []
    for line in lines:
        if not line or line.startswith('#'):
            out.append(line)
            continue
        fields = line.split('\t')
        name, source, args = fields[0], fields[1], fields[2]
        file, _, size = source.partition('@')
        data = open(os.path.join(images, file), 'rb').read()
        if size:
            w, h = (int(v) for v in size.split('x'))
            data = crop(data, w, h)
        argv = [cjpeg, '-dct', 'int']
        tokens = args.split()
        for index, token in enumerate(tokens):
            previous = tokens[index - 1] if index > 0 else ''
            if previous in ('-qtables', '-scans', '-icc'):
                local = os.path.join(here, token)
                token = local if os.path.exists(local) else os.path.join(images, token)
            argv.append(token)
        result = subprocess.run(argv, input=data, capture_output=True, check=True)
        out.append('\t'.join([name, source, args, hashlib.md5(result.stdout).hexdigest()]))
    with open(cases, 'w', encoding='utf-8', newline='\n') as f:
        f.write('\n'.join(out) + '\n')


main()
