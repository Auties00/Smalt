"""Generates VectorTransformKernel.java from VectorTransformKernel.template.

Usage: gen_transpose.py <template> <output VectorTransformKernel.java>

The template holds every hand-written part; this script writes the straight-line tile bodies at the
@@B_LEFT@@, @@B_RIGHT@@, @@I8@@ and @@I4@@ markers. A tile of n rows is transposed by log2(n) rounds that
interleave row k with row k + n / 2, each interleave spelled as two single-vector rearrangements (spread) and a
blend of the odd lanes. The 16x16 byte tile is split into two methods, one per half of the output rows; a
liveness pass keeps in each half only the vectors its outputs need. The output keeps the line endings of the file
it replaces (LF for a new file).
"""
import os
import sys

NL = '\n'


class Tile:
    """The vector classes, species and shuffles of one tile shape."""

    def __init__(self, n, letters, lo, hi, odd, load, store):
        self.n = n
        self.letters = letters
        self.lo = lo
        self.hi = hi
        self.odd = odd
        self.load = load
        self.store = store


def step(base, i, stride):
    if i == 0:
        return base
    if i == 1:
        return f"{base} + {stride}"
    return f"{base} + {i} * {stride}"


def needed_rows(tile, outputs):
    """Returns, per stage from the first, the indices of the vectors that the given outputs depend on."""
    half = tile.n // 2
    needed = [None] * len(tile.letters)
    current = set(outputs)
    for s in range(len(tile.letters) - 1, -1, -1):
        needed[s] = current
        inputs = set()
        for j in current:
            k = j // 2
            inputs.add(k)
            inputs.add(k + half)
        current = inputs
    return needed


def body(tile, outputs):
    half = tile.n // 2
    needed = needed_rows(tile, outputs)
    lines = [f"        var r{i} = {tile.load % step('base', i, 'srcStep')};" for i in range(tile.n)]
    prev = 'r'
    for s, cur in enumerate(tile.letters):
        for k in range(half):
            for j, shuffle in ((2 * k, tile.lo), (2 * k + 1, tile.hi)):
                if j in needed[s]:
                    lines.append(f"        var {cur}{j} = {prev}{k}.rearrange({shuffle})"
                                 f".blend({prev}{k + half}.rearrange({shuffle}), {tile.odd});")
        prev = cur
    for j in outputs:
        lines.append(f"        {tile.store % (prev + str(j))}.intoArray(dst, {step('out', j, 'rowStep')});")
    return NL.join(lines)


BYTES = Tile(16, 'stuv', 'SPREAD_LO_8', 'SPREAD_HI_8', 'ODD_8',
             'BYTE16.cast(ByteVector.fromArray(B16, src, %s))', 'BYTE16.cast(%s)')
INTS8 = Tile(8, 'stu', 'SPREAD_LO_32', 'SPREAD_HI_32', 'ODD_32',
             'INT8.cast(BYTE32.cast(ByteVector.fromArray(BP8, src, %s)).reinterpretShape(IntVector.SPECIES_256, 0))',
             'BYTE32.cast(INT8.cast(%s).reinterpretShape(ByteVector.SPECIES_256, 0))')
INTS4 = Tile(4, 'st', 'SPREAD_LO_32_4', 'SPREAD_HI_32_4', 'ODD_32_4',
             'INT4.cast(BYTE16.cast(ByteVector.fromArray(BP4, src, %s)).reinterpretShape(IntVector.SPECIES_128, 0))',
             'BYTE16.cast(INT4.cast(%s).reinterpretShape(ByteVector.SPECIES_128, 0))')


def main(template, output):
    code = {
        'B_LEFT': body(BYTES, range(0, 8)),
        'B_RIGHT': body(BYTES, range(8, 16)),
        'I8': body(INTS8, range(8)),
        'I4': body(INTS4, range(4)),
    }
    with open(template, encoding='utf-8', newline='') as f:
        text = f.read().replace('\r\n', NL)
    for key, value in code.items():
        marker = '@@' + key + '@@'
        if text.count(marker) != 1:
            raise SystemExit(f'{template}: expected one {marker} marker')
        text = text.replace(marker, value)
    crlf = False
    if os.path.exists(output):
        with open(output, 'rb') as f:
            crlf = b'\r\n' in f.read()
    with open(output, 'w', encoding='utf-8', newline='') as f:
        f.write(text.replace(NL, '\r\n') if crlf else text)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
