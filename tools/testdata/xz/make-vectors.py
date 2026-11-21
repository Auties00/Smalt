#!/usr/bin/env python3
"""Writes the XZ and LZMA files of smalt-image's XzDecoderTest.

Each input is built here from a seeded generator (text, smooth 16-bit image rows, incompressible
bytes, and machine-code-like bytes seeded with the instruction patterns each branch filter converts),
compressed by the xz command-line tool (used as a black-box writer) with several presets, integrity
checks, LZMA2 options, filter chains, block sizes and the legacy .lzma format, and written next to a
manifest line holding the decoded size and the SHA-256 of the decoded content, so the test needs
neither the inputs nor the tool. A few files are assembled here: concatenated streams with stream
padding, and files with a corrupted check, index or header. The script decodes every file with
xz -d and checks it against its input.

Made with XZ Utils 5.8.1 and Python 3.12; other versions may code the files differently, which is
why the files are kept with the tests and pinned in xz.sha256 next to the vector directory.

    python -I tools/testdata/xz/make-vectors.py <image/src/test/resources/com/github/auties00/smalt/image/internal/xz/files>
"""
import hashlib
import os
import random
import struct
import subprocess
import sys

WORDS = ('lzma range coder dictionary literal match rep distance chunk block index stream filter delta '
         'branch tiff strip tile predictor sample').split()


def text(size, seed):
    rng = random.Random(seed)
    out = []
    length = 0
    while length < size:
        word = rng.choice(WORDS)
        out.append(word)
        length += len(word) + 1
    return ' '.join(out).encode('ascii')[:size]


def image_rows(width, height, seed):
    rng = random.Random(seed)
    data = bytearray()
    for y in range(height):
        for x in range(width):
            data += struct.pack('<H', int(20000 + 90 * x + 40 * y + rng.gauss(0, 3)) & 0xFFFF)
    return bytes(data)


def code_like(size, seed):
    rng = random.Random(seed)
    data = bytearray(rng.randbytes(size))
    for i in range(0, size - 16, 16):
        kind = rng.randrange(12)
        if kind == 0:
            data[i:i + 5] = bytes([0xE8, rng.randrange(256), rng.randrange(256), rng.randrange(4), 0x00])
        elif kind == 1:
            data[i:i + 5] = bytes([0xE9, rng.randrange(256), rng.randrange(256), 0xFF, 0xFF])
        elif kind == 2:
            data[i:i + 4] = bytes([0x48 | rng.randrange(4), rng.randrange(256), rng.randrange(256), 0x01 | (rng.randrange(64) << 2)])
        elif kind == 3:
            data[i:i + 4] = bytes([rng.randrange(256), rng.randrange(256), rng.randrange(256), 0xEB])
        elif kind == 4:
            data[i:i + 4] = bytes([rng.randrange(256), 0xF0 | rng.randrange(8), rng.randrange(256), 0xF8 | rng.randrange(8)])
        elif kind == 5:
            data[i:i + 4] = bytes([0x40, rng.randrange(64), rng.randrange(256), rng.randrange(256)])
        elif kind == 6:
            data[i:i + 4] = bytes([0x7F, 0xC0 | rng.randrange(64), rng.randrange(256), rng.randrange(256)])
        elif kind == 7:
            data[i:i + 4] = bytes([rng.randrange(256), rng.randrange(256), rng.randrange(256), 0x94 | rng.randrange(4)])
        elif kind == 8:
            data[i:i + 4] = bytes([rng.randrange(32), rng.randrange(256), rng.randrange(4), 0x90 | (rng.randrange(4) << 5)])
        elif kind == 9:
            data[i:i + 4] = bytes([0xEF, rng.choice([0x00, 0x02, 0x10, 0x20, 0xF0]), rng.randrange(256), rng.randrange(256)])
        elif kind == 10:
            data[i:i + 8] = bytes([0x97, rng.randrange(256), rng.randrange(256), rng.randrange(256),
                                   0x93, rng.randrange(256), rng.randrange(256), rng.randrange(256)])
        else:
            data[i] = rng.choice([0x10, 0x11, 0x12, 0x13, 0x16, 0x17, 0x18, 0x19, 0x1C, 0x1D])
    return bytes(data)


def xz(data, arguments):
    return subprocess.run(['xz', '-q', '-c'] + arguments, input=data, check=True, stdout=subprocess.PIPE).stdout


def unxz(data, arguments=()):
    return subprocess.run(['xz', '-q', '-d', '-c'] + list(arguments), input=data, check=True,
                          stdout=subprocess.PIPE).stdout


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    directory = sys.argv[1]
    os.makedirs(directory, exist_ok=True)
    print(subprocess.run(['xz', '--version'], check=True, stdout=subprocess.PIPE).stdout.decode().strip())
    inputs = {
        'text': text(90000, 1),
        'image': image_rows(256, 80, 2),
        'random': random.Random(3).randbytes(70000),
        'code': code_like(48000, 4),
        'empty': b'',
        'one': b'x',
    }
    vectors = []
    for preset in ('-0', '-1', '-6', '-9e'):
        for name in ('text', 'image'):
            vectors.append(('%s%s' % (name, preset), inputs[name], xz(inputs[name], [preset])))
    for check in ('none', 'crc32', 'crc64', 'sha256'):
        vectors.append(('text-check-' + check, inputs['text'], xz(inputs['text'], ['-3', '--check=' + check])))
    for name in ('random', 'empty', 'one'):
        vectors.append((name, inputs[name], xz(inputs[name], ['-6'])))
    vectors.append(('image-delta2', inputs['image'], xz(inputs['image'], ['--delta=dist=2', '--lzma2=preset=6'])))
    vectors.append(('image-delta4-check-none', inputs['image'],
                    xz(inputs['image'], ['--check=none', '--delta=dist=4', '--lzma2=preset=2'])))
    vectors.append(('text-delta256', inputs['text'], xz(inputs['text'], ['--delta=dist=256', '--lzma2'])))
    for branch in ('x86', 'powerpc', 'ia64', 'arm', 'armthumb', 'sparc', 'arm64', 'riscv'):
        vectors.append(('code-' + branch, inputs['code'], xz(inputs['code'], ['--' + branch, '--lzma2=preset=6'])))
    vectors.append(('code-x86-start', inputs['code'], xz(inputs['code'], ['--x86=start=4096', '--lzma2'])))
    vectors.append(('code-arm64-start', inputs['code'], xz(inputs['code'], ['--arm64=start=1048576', '--lzma2'])))
    vectors.append(('code-delta-x86', inputs['code'], xz(inputs['code'], ['--delta=dist=3', '--x86', '--lzma2'])))
    for options, label in (('lc=0,lp=2,pb=0', 'lc0lp2pb0'), ('lc=4,lp=0,pb=4', 'lc4pb4'),
                           ('lc=1,lp=3,pb=1', 'lc1lp3pb1'), ('dict=4KiB', 'dict4k'), ('dict=1536KiB,nice=273', 'dict1536k'),
                           ('preset=6,mode=fast,mf=hc4', 'fast-hc4'), ('preset=1,mf=bt2', 'bt2')):
        vectors.append(('text-' + label, inputs['text'], xz(inputs['text'], ['--lzma2=' + options])))
    vectors.append(('image-blocks', inputs['image'], xz(inputs['image'], ['-6', '--block-size=12000'])))
    vectors.append(('mixed-blocks', inputs['text'] + inputs['random'] + inputs['image'],
                    xz(inputs['text'] + inputs['random'] + inputs['image'], ['-6', '--block-size=30000'])))
    first = xz(inputs['text'][:4000], ['-2'])
    second = xz(inputs['image'], ['-4', '--check=sha256'])
    vectors.append(('concatenated', inputs['text'][:4000] + inputs['image'], first + bytes(8) + second + bytes(4)))
    vectors.append(('lzma-alone', inputs['text'], xz(inputs['text'], ['--format=lzma', '-6'])))
    vectors.append(('lzma-alone-image', inputs['image'], xz(inputs['image'], ['--format=lzma', '--lzma1=preset=3,lc=0,lp=1,pb=1'])))
    for name, data, encoded in vectors:
        arguments = ['--format=lzma'] if name.startswith('lzma-alone') else []
        if unxz(encoded, arguments) != data:
            raise SystemExit(name + ': xz does not decode the file to its input')
    lines = []
    for name, data, encoded in vectors:
        with open(os.path.join(directory, name + '.xz'), 'wb') as file:
            file.write(encoded)
        lines.append('%s\t%d\t%s' % (name + '.xz', len(data), hashlib.sha256(data).hexdigest()))
    with open(os.path.join(directory, 'files.tsv'), 'w', newline='\n') as file:
        file.write('# file\tdecoded size\tdecoded sha256\n' + '\n'.join(sorted(lines)) + '\n')
    # damaged copies of text-check-crc64.xz (stream header 12 bytes, footer 12 bytes)
    base = bytearray(xz(inputs['text'][:20000], ['-3', '--check=crc64']))
    probe = os.path.join(directory, 'probe.tmp')
    with open(probe, 'wb') as file:
        file.write(base)
    try:
        listing = subprocess.run(['xz', '--robot', '--list', '-vv', probe], check=True,
                                 stdout=subprocess.PIPE).stdout.decode()
    finally:
        os.remove(probe)
    block = next(line.split('	') for line in listing.splitlines() if line.startswith('block'))
    check_offset = int(block[4]) + int(block[6]) - 8
    damaged = {
        'bad-check': (check_offset, 0x01),
        'bad-header-crc': (8, 0x01),
        'bad-footer-magic': (len(base) - 1, 0x20),
    }
    for name, (offset, mask) in damaged.items():
        copy = bytearray(base)
        copy[offset] ^= mask
        result = subprocess.run(['xz', '-d', '-c'], input=bytes(copy), stdout=subprocess.DEVNULL,
                                stderr=subprocess.PIPE)
        if result.returncode == 0:
            raise SystemExit(name + ': xz accepts the damaged file')
        print(name, result.stderr.decode().strip())
        with open(os.path.join(directory, 'damaged-' + name + '.xz'), 'wb') as file:
            file.write(copy)
    pins = []
    for name in sorted(os.listdir(directory)):
        with open(os.path.join(directory, name), 'rb') as file:
            pins.append(hashlib.sha256(file.read()).hexdigest() + '  ' + name)
    manifest = os.path.join(os.path.dirname(os.path.abspath(directory)), 'xz.sha256')
    with open(manifest, 'w', newline='\n') as out:
        out.write('\n'.join(pins) + '\n')


if __name__ == '__main__':
    main()
