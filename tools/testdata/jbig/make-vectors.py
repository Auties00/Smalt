#!/usr/bin/env python3
"""Writes the JBIG bi-level image entities of smalt-image's JbigDecoderTest.

The artificial test image of ITU-T T.82 clause 7.2.1 (Figure 38) is coded by JBIG-KIT's pbmtojbg (used
only as a black-box writer, never read) with the parameters of the T.82 test sequences of clauses 7.2.2
and 7.2.3; the script checks that every entity has exactly the byte count T.82 Tables 29 and 32 give,
which shows the writer implements those parameterizations as the standard requires, and keeps the
sequential test with typical prediction and adaptive template moves and the progressive test with six
differential layers. The test regenerates the artificial image itself and requires an exact decode.

Smaller images (text-like shapes, a dithered ramp that moves the adaptive template, a 3-bit gray image)
are coded with option sets covering both templates, typical and deterministic prediction, the six stripe
orders and the high-to-low order, stripe resets, comments, a new image length, adaptive template moves
and several bit-planes; the manifest holds the SHA-256 of the source raster (the PBM rows, or for gray
images the PGM samples), which JBIG reproduces losslessly, and jbgtopbm (the black-box oracle) is checked
to decode every entity to it, except the stripe orders it does not implement. The script fails unless
the entities contain every marker kind.

Made with JBIG-KIT 2.1 (pbmtojbg, jbgtopbm) and Python 3.12.

    python -I tools/testdata/jbig/make-vectors.py <image/src/test/resources/com/github/auties00/smalt/image/internal/jbig/entities>
"""
import hashlib
import os
import random
import subprocess
import sys

T82_WIDTH = 1960
T82_HEIGHT = 1951


def artificial():
    """The T.82 Figure 38 test image as rows of 0/1."""
    rows = []
    prsg = 1
    repeat = [0] * 8
    for j in range(T82_HEIGHT):
        row = []
        for i in range(T82_WIDTH):
            if j < 192:
                row.append(0)
            elif j < 1023 or ((i >> 3) & 3) == 0:
                total = (prsg & 1) + ((prsg >> 2) & 1) + ((prsg >> 11) & 1) + ((prsg >> 15) & 1)
                prsg = ((prsg << 1) + (total & 1)) & 0xFFFFFFFF
                bit = 1 if (prsg & 3) == 0 else 0
                repeat[i & 7] = bit
                row.append(bit)
            else:
                row.append(repeat[i & 7])
        rows.append(row)
    return rows


def pbm(rows):
    width = len(rows[0])
    data = bytearray(b'P4\n%d %d\n' % (width, len(rows)))
    for row in rows:
        packed = bytearray((width + 7) // 8)
        for x, bit in enumerate(row):
            if bit:
                packed[x >> 3] |= 0x80 >> (x & 7)
        data += packed
    return bytes(data)


def pgm(rows, maxval):
    data = bytearray(b'P5\n%d %d\n%d\n' % (len(rows[0]), len(rows), maxval))
    for row in rows:
        data += bytes(row)
    return bytes(data)


def shapes(width, height, seed):
    rng = random.Random(seed)
    rows = [[0] * width for _ in range(height)]
    for _ in range(width * height // 900):
        x0, y0 = rng.randrange(width), rng.randrange(height)
        w, h = rng.randrange(2, 30), rng.randrange(2, 12)
        hollow = rng.random() < 0.5
        for y in range(y0, min(height, y0 + h)):
            for x in range(x0, min(width, x0 + w)):
                if not hollow or y in (y0, y0 + h - 1) or x in (x0, x0 + w - 1):
                    rows[y][x] = 1
    for _ in range(width * height // 4000):
        cx, cy, r = rng.randrange(width), rng.randrange(height), rng.randrange(3, 20)
        for y in range(max(0, cy - r), min(height, cy + r)):
            for x in range(max(0, cx - r), min(width, cx + r)):
                if (x - cx) ** 2 + (y - cy) ** 2 <= r * r:
                    rows[y][x] ^= 1
    return rows


def dithered(width, height):
    # a ramp dithered with a 6-pixel-wide pattern, which an adaptive template 6 pixels left predicts well
    rows = []
    for y in range(height):
        level = y * 6 // height
        rows.append([1 if (x % 6) < level or (x % 6 == 5 and y % 3 == 0) else 0 for x in range(width)])
    return rows


def gray(width, height, seed):
    rng = random.Random(seed)
    return [[min(7, max(0, (x * 8 // width + y * 3 // height + (1 if rng.random() < 0.1 else 0)) % 8))
             for x in range(width)] for y in range(height)]


def encode(image, arguments, directory):
    source = os.path.join(directory, 'input.tmp')
    target = os.path.join(directory, 'output.tmp')
    with open(source, 'wb') as file:
        file.write(image)
    try:
        subprocess.run(['pbmtojbg'] + arguments + [source, target], check=True, stdout=subprocess.DEVNULL)
        with open(target, 'rb') as file:
            return file.read()
    finally:
        for path in (source, target):
            if os.path.exists(path):
                os.remove(path)


def decode(bie, arguments, directory):
    """Decodes with jbgtopbm, or returns None for the orders it does not implement."""
    source = os.path.join(directory, 'input.tmp')
    target = os.path.join(directory, 'output.tmp')
    try:
        with open(source, 'wb') as file:
            file.write(bie)
        result = subprocess.run(['jbgtopbm'] + arguments + [source, target], stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE)
        if result.returncode != 0:
            if b'unimplemented' not in result.stderr:
                raise SystemExit('jbgtopbm: ' + result.stderr.decode())
            return None
        with open(target, 'rb') as file:
            return file.read()
    finally:
        for path in (source, target):
            if os.path.exists(path):
                os.remove(path)


def checked(name, bie, source, arguments, directory):
    """The raster of the source, after checking that jbgtopbm decodes the entity to it when it can."""
    expected = raster(source)
    decoded = decode(bie, arguments, directory)
    if decoded is not None and raster(decoded) != expected:
        raise SystemExit(name + ': jbgtopbm does not decode the entity to its source')
    return expected


def raster(netpbm):
    """The samples after a P4 or P5 header."""
    fields = 0
    pos = 2
    needed = 2 if netpbm[:2] == b'P4' else 3
    while fields < needed:
        while netpbm[pos] in b' \t\r\n':
            pos += 1
        while netpbm[pos] not in b' \t\r\n':
            pos += 1
        fields += 1
    return netpbm[pos + 1:]


def markers(bie):
    found = set()
    for i in range(20, len(bie) - 1):
        if bie[i] == 0xFF and bie[i + 1] in (3, 5, 6, 7):
            found.add(bie[i + 1])
    return found


def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    directory = sys.argv[1]
    os.makedirs(directory, exist_ok=True)
    image = artificial()
    foreground = sum(map(sum, image))
    if foreground != 861965:
        raise SystemExit('the artificial image has %d foreground pixels, T.82 says 861965' % foreground)
    big = pbm(image)
    t82 = [
        ('t82-sequential-1', ['-q', '-s', '1951', '-m', '0', '-p', '0'], 317384, False),
        ('t82-sequential-2', ['-q', '-s', '1951', '-m', '0', '-p', '64'], 317132, False),
        ('t82-sequential-3', ['-q', '-s', '128', '-m', '8', '-p', '8', '-c'], 253653, True),
        ('t82-progressive', ['-d', '6', '-s', '2', '-m', '8', '-p', '28', '-c', '-o', '0'], 279314, True),
        ('t82-progressive-compatible', ['-d', '6', '-s', '2', '-m', '8', '-p', '28', '-c', '-o', '4'], 279314, False),
    ]
    for name, arguments, size, keep in t82:
        bie = encode(big, arguments, directory)
        if len(bie) != size:
            raise SystemExit('%s: %d bytes, T.82 says %d' % (name, len(bie), size))
        if keep:
            with open(os.path.join(directory, name + '.jbg'), 'wb') as file:
                file.write(bie)
    small = pbm(shapes(251, 173, 1))
    tiny = pbm(shapes(37, 29, 2))
    dither = pbm(dithered(240, 96))
    cases = [
        ('seq-three-line', small, ['-d', '0', '-p', '0']),
        ('seq-two-line', small, ['-d', '0', '-p', '64']),
        ('seq-tpbon-stripes', small, ['-d', '0', '-p', '8', '-s', '16']),
        ('seq-reset', small, ['-d', '0', '-p', '8', '-s', '5', '-r']),
        ('seq-comment', small, ['-d', '0', '-C', 'Smalt JBIG comment marker']),
        ('seq-newlen', small, ['-d', '0', '-s', '32', '-Y', '300']),
        ('seq-at', dither, ['-d', '0', '-p', '0', '-s', '24', '-m', '12']),
        ('seq-at-two-line', dither, ['-d', '0', '-p', '72', '-s', '16', '-m', '12', '-c']),
        ('seq-fax', small, ['-f']),
        ('prog-default', small, ['-d', '2', '-s', '8']),
        ('prog-order0', small, ['-d', '3', '-s', '4', '-o', '0']),
        ('prog-order2', small, ['-d', '3', '-s', '4', '-o', '2']),
        ('prog-order4', small, ['-d', '3', '-s', '4', '-o', '4']),
        ('prog-order5', small, ['-d', '3', '-s', '4', '-o', '5']),
        ('prog-order6', small, ['-d', '3', '-s', '4', '-o', '6']),
        ('prog-hitolo', small, ['-d', '3', '-s', '4', '-o', '8']),
        ('prog-hitolo-seq', small, ['-d', '2', '-s', '4', '-o', '12']),
        ('prog-dp-only', small, ['-d', '2', '-s', '8', '-p', '4']),
        ('prog-tpd-only', small, ['-d', '2', '-s', '8', '-p', '16']),
        ('prog-none', small, ['-d', '2', '-s', '8', '-p', '0']),
        ('prog-two-line', small, ['-d', '2', '-s', '8', '-p', '92']),
        ('prog-reset', small, ['-d', '2', '-s', '4', '-r']),
        ('prog-at', dither, ['-d', '1', '-s', '12', '-m', '12', '-c']),
        ('prog-auto-layers', small, ['-x', '40', '-y', '30']),
        ('prog-one-stripe', small, ['-d', '4', '-s', '20']),
        ('tiny-deep', tiny, ['-d', '6', '-s', '1']),
        ('tiny-sequential', tiny, ['-q']),
    ]
    vectors = []
    seen = set()
    for name, source, arguments in cases:
        bie = encode(source, arguments, directory)
        seen |= markers(bie)
        vectors.append((name, bie, checked(name, bie, source, [], directory)))
    gray_image = pgm(gray(97, 61, 3), 7)
    for name, arguments in (('gray-binary', ['-b', '-d', '1', '-s', '8']), ('gray-planes-seq', ['-b', '-q'])):
        bie = encode(gray_image, arguments, directory)
        vectors.append((name, bie, checked(name, bie, gray_image, ['-b'], directory)))
    missing = {3: 'SDRST', 5: 'NEWLEN', 6: 'ATMOVE', 7: 'COMMENT'}
    for marker in seen:
        missing.pop(marker, None)
    if missing:
        raise SystemExit('no entity has ' + ', '.join(missing.values()))
    lines = []
    for name, bie, decoded in vectors:
        with open(os.path.join(directory, name + '.jbg'), 'wb') as file:
            file.write(bie)
        lines.append('%s\t%d\t%s' % (name + '.jbg', len(decoded), hashlib.sha256(decoded).hexdigest()))
    with open(os.path.join(directory, 'entities.tsv'), 'w', newline='\n') as file:
        file.write('# entity\tdecoded raster size\tdecoded raster sha256 (PBM rows, or PGM samples for gray-*)\n'
                   + '\n'.join(sorted(lines)) + '\n')
    pins = []
    for name in sorted(os.listdir(directory)):
        with open(os.path.join(directory, name), 'rb') as file:
            pins.append(hashlib.sha256(file.read()).hexdigest() + '  ' + name)
    with open(os.path.join(os.path.dirname(os.path.abspath(directory)), 'jbig.sha256'), 'w', newline='\n') as out:
        out.write('\n'.join(pins) + '\n')


if __name__ == '__main__':
    main()
