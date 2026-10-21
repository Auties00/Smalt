#!/usr/bin/env python3
"""Writes the Zstandard frames of smalt-image's ZstdDecoderTest.

Each input is built here from a seeded generator (text, smooth image rows, their horizontal
differences, incompressible bytes, runs, long-distance repeats and tiny edge cases), compressed by
the zstd command-line tool (used as a black-box writer) at several levels and with several frame
options, and the frame is written next to a manifest line holding the decoded size and the SHA-256
of the decoded content, so the test needs neither the inputs nor the tool. A few frames are
assembled here from others: concatenated frames with a skippable frame between them, and a frame
whose content checksum is corrupted. The script decodes every frame with zstd -d and checks it
against its input.

Made with zstd 1.5.7 and Python 3.12; other versions of zstd code the frames differently, which is
why the frames are kept with the tests and pinned in zstd.sha256 next to the vector directory.

    python -I tools/testdata/zstd/make-vectors.py <image/src/test/resources/com/github/auties00/smalt/image/internal/zstd/frames>
"""
import hashlib
import os
import random
import struct
import subprocess
import sys

WORDS = ('the quick brown fox jumps over a lazy dog while seven zebras quietly graze near an old '
         'river bank and tiff strips carry samples rows tiles of every image').split()


def text(size, seed):
    rng = random.Random(seed)
    out = []
    length = 0
    while length < size:
        word = rng.choice(WORDS)
        if rng.random() < 0.08:
            word = word.capitalize() + ('.' if rng.random() < 0.5 else ',')
        out.append(word)
        length += len(word) + 1
    return (' '.join(out).encode('ascii'))[:size]


def image_rows(width, height, seed, differenced):
    rng = random.Random(seed)
    data = bytearray()
    for y in range(height):
        row = []
        for x in range(width):
            value = int(32768 + 12000 * ((x * 3 + y * 5) % 97) / 97 + rng.gauss(0, 4)) & 0xFFFF
            row.append(value)
        if differenced:
            row = [row[0]] + [(row[i] - row[i - 1]) & 0xFFFF for i in range(1, width)]
        for value in row:
            data += struct.pack('<H', value)
    return bytes(data)


def incompressible(size, seed):
    return random.Random(seed).randbytes(size)


def runs(size, seed):
    rng = random.Random(seed)
    data = bytearray()
    while len(data) < size:
        data += bytes([rng.randrange(4)]) * rng.randrange(1, 3000)
    return bytes(data[:size])


def long_distance(seed):
    rng = random.Random(seed)
    block = rng.randbytes(50000)
    filler = runs(1200000, seed + 1)
    return block + filler + block + filler[:5000]


def literal_heavy(size, seed):
    # skewed symbols without repeats: Huffman-coded literals, few matches
    rng = random.Random(seed)
    return bytes(min(255, int(rng.expovariate(0.05))) for _ in range(size))


def compress(data, arguments):
    return subprocess.run(['zstd', '-q', '-c'] + arguments, input=data, check=True,
                          stdout=subprocess.PIPE).stdout


def compress_file(data, arguments, directory):
    # through a file, so that zstd writes the content size and a single-segment frame
    path = os.path.join(directory, 'input.tmp')
    with open(path, 'wb') as file:
        file.write(data)
    try:
        return subprocess.run(['zstd', '-q', '-c'] + arguments + [path], check=True,
                              stdout=subprocess.PIPE).stdout
    finally:
        os.remove(path)


def decompress(frame):
    return subprocess.run(['zstd', '-q', '-d', '-c'], input=frame, check=True, stdout=subprocess.PIPE).stdout


def skippable(payload, nibble):
    return struct.pack('<II', 0x184D2A50 | nibble, len(payload)) + payload


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    directory = sys.argv[1]
    os.makedirs(directory, exist_ok=True)
    print(subprocess.run(['zstd', '--version'], check=True, stdout=subprocess.PIPE).stdout.decode().strip())
    inputs = {
        'text': text(120000, 1),
        'image': image_rows(256, 96, 2, False),
        'image-diff': image_rows(256, 96, 3, True),
        'random': incompressible(20000, 4),
        'runs': runs(200000, 5),
        'literals': literal_heavy(40000, 6),
        'zeros': bytes(300000),
        'one': b'Z',
        'empty': b'',
        'short': b'abcabcabcabcabcab',
    }
    vectors = []
    for name in ('text', 'image', 'image-diff', 'literals'):
        for level, label in (('-1', 'l1'), ('-3', 'l3'), ('-9', 'l9'), ('-19', 'l19'), ('--fast=4', 'fast4')):
            vectors.append((name + '-' + label, inputs[name], compress_file(inputs[name], [level], directory)))
        vectors.append((name + '-ultra22', inputs[name], compress_file(inputs[name], ['--ultra', '-22'], directory)))
        vectors.append((name + '-stream', inputs[name], compress(inputs[name], ['-6'])))
        vectors.append((name + '-nocheck', inputs[name], compress_file(inputs[name], ['-5', '--no-check'], directory)))
    for name in ('random', 'runs', 'zeros', 'one', 'empty', 'short'):
        vectors.append((name + '-l3', inputs[name], compress_file(inputs[name], ['-3'], directory)))
        vectors.append((name + '-stream', inputs[name], compress(inputs[name], ['-19'])))
    big = long_distance(7)
    vectors.append(('long-distance', big, compress(big, ['-3', '--long=23'])))
    vectors.append(('small-blocks', inputs['text'], compress_file(inputs['text'], ['-7', '--target-compressed-block-size=1340'], directory)))
    first = compress_file(inputs['short'], ['-3'], directory)
    second = compress(inputs['image'], ['-4'])
    vectors.append(('concatenated', inputs['short'] + inputs['image'],
                    first + skippable(b'smalt skippable frame', 7) + second + skippable(b'', 15)))
    for name, data, frame in vectors:
        if decompress(frame) != data:
            raise SystemExit(name + ': zstd does not decode the frame to its input')
    corrupt = bytearray(compress_file(inputs['text'][:5000], ['-3'], directory))
    corrupt[-1] ^= 0x55
    lines = []
    for name, data, frame in vectors:
        with open(os.path.join(directory, name + '.zst'), 'wb') as file:
            file.write(frame)
        lines.append('%s\t%d\t%s' % (name + '.zst', len(data), hashlib.sha256(data).hexdigest()))
    with open(os.path.join(directory, 'bad-checksum.zst'), 'wb') as file:
        file.write(corrupt)
    with open(os.path.join(directory, 'frames.tsv'), 'w', newline='\n') as file:
        file.write('# frame\tdecoded size\tdecoded sha256\n' + '\n'.join(sorted(lines)) + '\n')
    pins = []
    for name in sorted(os.listdir(directory)):
        with open(os.path.join(directory, name), 'rb') as file:
            pins.append(hashlib.sha256(file.read()).hexdigest() + '  ' + name)
    manifest = os.path.join(os.path.dirname(os.path.abspath(directory)), 'zstd.sha256')
    with open(manifest, 'w', newline='\n') as out:
        out.write('\n'.join(pins) + '\n')


if __name__ == '__main__':
    main()
