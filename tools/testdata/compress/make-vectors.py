#!/usr/bin/env python3
"""Writes the bzip2 and LZO1X vectors of smalt-core's Bzip2DecoderTest, Lzo1xDecoderTest and the Matroska
bzlib/LZO tests.

Each input is built here from a seeded generator (text, 16-bit audio frames, incompressible bytes, runs, every
byte value, long-distance repeats and tiny edge cases). The bzip2 streams are written by Python's bz2 module
(libbzip2, the reference implementation) at block sizes 1 and 9, plus two streams assembled here: two concatenated
streams, and a stream in the randomized block form of bzip2 0.9.0, which no current bzip2 writes, built by the
small encoder below and checked by libbzip2's decoder. The LZO1X streams are written by lzokay (MIT) through
lzo-driver.cpp of this directory, built with

    g++ -std=c++17 -O2 -I<lzokay> <lzokay>/lzokay.cpp lzo-driver.cpp -o lzo-driver

at lzokay commit db2df1fcbebc2ed06c10f727f72567d40f06a2be, plus a few streams written here instruction by
instruction for the encodings lzokay does not emit; lzokay decompresses every one of them back to its input (except
the empty stream, which lzokay neither writes nor reads correctly, so the stream is lzo1x_1_compress's end instruction
alone),
and the script checks that every LZO1X instruction kind occurs. Each stream is listed in files.tsv with the
decoded size and the SHA-256 of the decoded content, so the tests need neither the inputs nor the tools; the
streams named frame-* are the frames of the Matroska tests. compress.sha256 pins every file.

Made with Python 3.14 (libbzip2 1.0.8):

    python -I tools/testdata/compress/make-vectors.py <lzo-driver> <core/src/test/resources/com/github/auties00/smalt/internal/compress>
"""
import bz2
import hashlib
import math
import os
import random
import struct
import subprocess
import sys
import tempfile

WORDS = ('the quick brown fox jumps over a lazy dog while seven zebras quietly graze near an old '
         'river bank and matroska frames carry samples of every track').split()


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


def audio(samples, seed, phase):
    rng = random.Random(seed)
    data = bytearray()
    for i in range(samples):
        value = int(9000 * math.sin((i + phase) * 2 * math.pi * 440 / 8000) + rng.gauss(0, 30))
        data += struct.pack('<h', max(-32768, min(32767, value)))
    return bytes(data)


def runs(size, seed):
    rng = random.Random(seed)
    data = bytearray()
    while len(data) < size:
        data += bytes([rng.randrange(4)]) * rng.randrange(1, 3000)
    return bytes(data[:size])


def long_distance(seed):
    rng = random.Random(seed)
    block = rng.randbytes(20000)
    return block + text(30000, seed + 1) + block + text(2000, seed + 2) + block[:3000]


def inputs():
    rng = random.Random(7)
    return {
        'empty': b'',
        'one-byte': b'a',
        'four-same': b'zzzz',
        'text': text(20000, 1),
        'incompressible': rng.randbytes(3000),
        'runs': runs(300000, 2),
        'zeros': bytes(500000),
        'all-bytes': bytes(range(256)) * 9,
        'multi-block': text(250000, 3),
        'long-distance': long_distance(4),
        # the OpusHead of the Matroska codec private data tests: 48 kHz, one channel, 312 samples of pre-skip
        'opus-head': b'OpusHead' + bytes([1, 1, 0x38, 0x01, 0x80, 0xBB, 0, 0, 0, 0, 0]),
    }


def frames():
    result = {}
    for index in range(12):
        result['frame-%02d' % index] = audio(2048, 100 + index, index * 2048)
    result['frame-12'] = bytes(4096)
    result['frame-13'] = random.Random(9).randbytes(1500)
    result['frame-14'] = text(4096, 5)
    return result


CRC_TABLE = []
for value in range(256):
    crc = value << 24
    for _ in range(8):
        crc = ((crc << 1) ^ 0x04C11DB7) if crc & 0x80000000 else crc << 1
    CRC_TABLE.append(crc & 0xFFFFFFFF)


def bzip2_crc(data):
    crc = 0xFFFFFFFF
    for byte in data:
        crc = ((crc << 8) & 0xFFFFFFFF) ^ CRC_TABLE[(crc >> 24) ^ byte]
    return crc ^ 0xFFFFFFFF


def random_numbers():
    # BZ2_rNums of bzip2 1.0.8 randtable.c, read from the Java decoder so that the two cannot diverge unseen:
    # the libbzip2 check of the randomized stream fails if this table is wrong
    here = os.path.dirname(os.path.abspath(__file__))
    source = os.path.join(here, '..', '..', '..', 'core', 'src', 'main', 'java', 'com', 'github', 'auties00',
                          'smalt', 'internal', 'compress', 'Bzip2Decoder.java')
    text_ = open(source, encoding='ascii').read()
    body = text_[text_.index('RANDOM_NUMBERS = {') + 18:]
    body = body[:body.index('}')]
    numbers = [int(part) for part in body.replace(',', ' ').split()]
    assert len(numbers) == 512
    return numbers


class Bits:
    def __init__(self):
        self.value = 0
        self.count = 0
        self.out = bytearray()

    def put(self, count, value):
        self.value = (self.value << count) | value
        self.count += count
        while self.count >= 8:
            self.count -= 8
            self.out.append((self.value >> self.count) & 0xFF)
        self.value &= (1 << self.count) - 1

    def finish(self):
        if self.count:
            self.out.append((self.value << (8 - self.count)) & 0xFF)
        return bytes(self.out)


def randomized_bzip2(data):
    """Writes one bzip2 block in the randomized form, with plain fixed-length Huffman tables."""
    rle = bytearray()
    i = 0
    while i < len(data):
        j = i
        while j < len(data) and data[j] == data[i] and j - i < 259:
            j += 1
        run = j - i
        if run >= 4:
            rle += bytes([data[i]]) * 4 + bytes([run - 4])
        else:
            rle += bytes([data[i]]) * run
        i = j
    assert 0 < len(rle) < 100000 - 19
    numbers = random_numbers()
    block = bytearray(rle)
    left = 0
    index = 0
    for k in range(len(block)):
        if left == 0:
            left = numbers[index]
            index = (index + 1) & 511
        left -= 1
        if left == 1:
            block[k] ^= 1
    n = len(block)
    doubled = bytes(block) * 2
    order = sorted(range(n), key=lambda start: doubled[start:start + n])
    origin = order.index(0)
    last = bytes(block[(start - 1) % n] for start in order)
    used = sorted(set(last))
    mtf = list(range(len(used)))
    symbol_of = {byte: position for position, byte in enumerate(used)}
    symbols = []
    zeros = 0

    def flush():
        nonlocal zeros
        length = zeros
        while length > 0:
            if length & 1:
                symbols.append(0)
                length = (length - 1) // 2
            else:
                symbols.append(1)
                length = (length - 2) // 2
        zeros = 0

    for byte in last:
        position = mtf.index(symbol_of[byte])
        if position == 0:
            zeros += 1
            continue
        flush()
        symbols.append(position + 1)
        mtf.insert(0, mtf.pop(position))
    flush()
    alpha = len(used) + 2
    symbols.append(alpha - 1)
    width = max(2, (alpha - 1).bit_length())
    bits = Bits()
    for byte in b'BZh1':
        bits.put(8, byte)
    bits.put(48, 0x314159265359)
    crc = bzip2_crc(data)
    bits.put(32, crc)
    bits.put(1, 1)
    bits.put(24, origin)
    groups = sorted({byte >> 4 for byte in used})
    bits.put(16, sum(0x8000 >> group for group in groups))
    for group in groups:
        bits.put(16, sum(0x8000 >> (byte & 15) for byte in used if byte >> 4 == group))
    bits.put(3, 2)
    selectors = (len(symbols) + 49) // 50
    bits.put(15, selectors)
    for _ in range(selectors):
        bits.put(1, 0)
    for _ in range(2):
        bits.put(5, width)
        for _ in range(alpha):
            bits.put(1, 0)
    for symbol in symbols:
        bits.put(width, symbol)
    bits.put(48, 0x177245385090)
    bits.put(32, crc)
    return bits.finish()


def lzo_literals(literals):
    # a first literal run of 4..238 bytes; a longer one uses the long literal instruction after an empty start
    assert 4 <= len(literals) <= 238
    return bytes([len(literals) + 17]) + literals


def lzo_end():
    return bytes([0x11, 0, 0])


def handmade_lzo():
    """Streams of the instruction kinds lzokay does not write, as (stream, decoded) pairs."""
    streams = {}
    # the empty stream of lzo1x_1_compress: the end instruction alone (lzokay writes an invalid one)
    streams['empty'] = (lzo_end(), b'')
    # 18..21 first byte: one literal then a 2-byte M1 copy (state 1) at distance 1, then three literals
    stream = bytes([18]) + b'q' + bytes([0x03, 0]) + b'rst'
    streams['m1-short'] = (stream + lzo_end(), b'qqqrst')
    # a long first literal run, then a 3-byte M1 copy after 4+ literals (distance 2049 + 4 * 1 + 1 = 2054)
    head = random.Random(11).randbytes(2100)
    # a long literal instruction of 2100 bytes: 3 + 15 + 255 per zero byte + the last byte
    zeros, last = divmod(2100 - 18, 255)
    stream = bytes([0]) + bytes(zeros) + bytes([last]) + head
    assert last != 0
    distance = 2054
    low = distance - 2049
    stream += bytes([(low & 3) << 2, low >> 2])
    expected = head + bytes(head[len(head) - distance + i] for i in range(3))
    streams['m1-far'] = (stream + lzo_end(), expected)
    # an M3 copy with an extended length (zero bytes) and an M4 copy with the high distance bit
    rng = random.Random(12)
    body = rng.randbytes(40000)
    literal_zeros, literal_last = divmod(40000 - 18, 255)
    stream = bytes([0]) + bytes(literal_zeros) + bytes([literal_last]) + body
    zeros, last = divmod(4000 - 2 - 31, 255)
    out = bytearray(body)
    d = 100
    stream += bytes([0x20]) + bytes(zeros) + bytes([last]) + struct.pack('<H', (d - 1) << 2)
    for _ in range(4000):
        out.append(out[len(out) - d])
    d = 36000
    length = 300
    lz, ll = divmod(length - 2 - 7, 255)
    stream += bytes([0x10 | 0x08]) + bytes(lz) + bytes([ll]) + struct.pack('<H', (d - 16384 - 16384) << 2)
    for _ in range(length):
        out.append(out[len(out) - d])
    streams['m3-m4-long'] = (stream + lzo_end(), bytes(out))
    return streams


def lzo_kinds(stream):
    """Decodes an LZO1X stream, returning the decoded bytes and the instruction kinds met."""
    kinds = set()
    out = bytearray()
    i = 0
    state = 0
    if stream[0] >= 22:
        kinds.add('first-long')
        count = stream[0] - 17
        out += stream[1:1 + count]
        i = 1 + count
        state = 4
    elif stream[0] >= 18:
        kinds.add('first-short')
        state = stream[0] - 17
        out += stream[1:1 + state]
        i = 1 + state

    def extended(base):
        nonlocal i
        zeros = 0
        while stream[i] == 0:
            zeros += 1
            i += 1
        value = stream[i]
        i += 1
        if zeros:
            kinds.add('zero-length-bytes')
        return zeros * 255 + base + value

    while True:
        inst = stream[i]
        i += 1
        if inst >= 64:
            kinds.add('m2-long' if inst >= 128 else 'm2-short')
            distance = (stream[i] << 3) + ((inst >> 2) & 7) + 1
            i += 1
            count = (inst >> 5) + 1
            nstate = inst & 3
        elif inst >= 32:
            kinds.add('m3')
            count = (inst & 31) + 2
            if count == 2:
                count += extended(31)
            word = stream[i] | (stream[i + 1] << 8)
            i += 2
            distance = (word >> 2) + 1
            nstate = word & 3
        elif inst >= 16:
            count = (inst & 7) + 2
            if count == 2:
                count += extended(7)
            word = stream[i] | (stream[i + 1] << 8)
            i += 2
            distance = ((inst & 8) << 11) + (word >> 2)
            nstate = word & 3
            if distance == 0:
                return bytes(out), kinds
            kinds.add('m4-high' if inst & 8 else 'm4')
            distance += 16384
        elif state == 0:
            kinds.add('m1-literals')
            count = inst + 3
            if count == 3:
                count += extended(15)
            out += stream[i:i + count]
            i += count
            state = 4
            continue
        elif state != 4:
            kinds.add('m1-short')
            distance = (inst >> 2) + (stream[i] << 2) + 1
            i += 1
            count = 2
            nstate = inst & 3
        else:
            kinds.add('m1-far')
            distance = (inst >> 2) + (stream[i] << 2) + 2049
            i += 1
            count = 3
            nstate = inst & 3
        if nstate:
            kinds.add('trailing-literals')
        for _ in range(count):
            out.append(out[len(out) - distance])
        out += stream[i:i + nstate]
        i += nstate
        state = nstate


def run_driver(driver, mode, data, size=None):
    with tempfile.TemporaryDirectory() as directory:
        source = os.path.join(directory, 'in')
        target = os.path.join(directory, 'out')
        with open(source, 'wb') as file:
            file.write(data)
        command = [driver, mode, source, target] + ([str(size)] if size is not None else [])
        subprocess.run(command, check=True)
        with open(target, 'rb') as file:
            return file.read()


def main():
    driver, root = sys.argv[1], sys.argv[2]
    bzip2_dir = os.path.join(root, 'bzip2')
    lzo_dir = os.path.join(root, 'lzo')
    os.makedirs(bzip2_dir, exist_ok=True)
    os.makedirs(lzo_dir, exist_ok=True)
    data = inputs()
    data.update(frames())
    bzip2_rows = []
    lzo_rows = []

    def add(rows, directory, name, stream, decoded):
        with open(os.path.join(directory, name), 'wb') as file:
            file.write(stream)
        rows.append('%s\t%d\t%s' % (name, len(decoded), hashlib.sha256(decoded).hexdigest()))

    for name, content in data.items():
        levels = (9,) if name.startswith('frame-') else (1, 9)
        for level in levels:
            stream = bz2.compress(content, level)
            assert bz2.decompress(stream) == content
            add(bzip2_rows, bzip2_dir, '%s-%d.bz2' % (name, level) if not name.startswith('frame-') else name + '.bz2',
                stream, content)
    two = bz2.compress(data['text'], 1) + bz2.compress(data['runs'], 9)
    assert bz2.decompress(two) == data['text'] + data['runs']
    add(bzip2_rows, bzip2_dir, 'concatenated.bz2', two, data['text'] + data['runs'])
    randomized_input = text(3000, 8) + bytes(600) + bytes(range(256))
    randomized = randomized_bzip2(randomized_input)
    assert bz2.decompress(randomized) == randomized_input, 'libbzip2 rejects the randomized stream'
    add(bzip2_rows, bzip2_dir, 'randomized.bz2', randomized, randomized_input)

    kinds = set()
    for name, content in data.items():
        if not content:
            continue
        stream = run_driver(driver, 'c', content)
        assert run_driver(driver, 'd', stream, len(content) + 64) == content
        decoded, met = lzo_kinds(stream)
        assert decoded == content, name
        kinds |= met
        add(lzo_rows, lzo_dir, name + '.lzo', stream, content)
    for name, (stream, expected) in handmade_lzo().items():
        assert not expected or run_driver(driver, 'd', stream, len(expected) + 64) == expected, name
        decoded, met = lzo_kinds(stream)
        assert decoded == expected, name
        kinds |= met
        add(lzo_rows, lzo_dir, name + '.lzo', stream, expected)
    wanted = {'first-long', 'first-short', 'm1-literals', 'm1-short', 'm1-far', 'm2-short', 'm2-long', 'm3', 'm4',
              'm4-high', 'zero-length-bytes', 'trailing-literals'}
    assert wanted <= kinds, 'missing LZO1X instruction kinds: %s' % sorted(wanted - kinds)

    for directory, rows in ((bzip2_dir, bzip2_rows), (lzo_dir, lzo_rows)):
        with open(os.path.join(directory, 'files.tsv'), 'w', newline='\n') as file:
            file.write('# name\tdecoded size\tsha256 of the decoded content\n')
            file.write('\n'.join(sorted(rows)) + '\n')
    pins = []
    for directory in (bzip2_dir, lzo_dir):
        for name in sorted(os.listdir(directory)):
            with open(os.path.join(directory, name), 'rb') as file:
                pins.append('%s  %s/%s' % (hashlib.sha256(file.read()).hexdigest(), os.path.basename(directory), name))
    with open(os.path.join(root, 'compress.sha256'), 'w', newline='\n') as file:
        file.write('\n'.join(pins) + '\n')
    print('%d bzip2 and %d LZO1X streams' % (len(bzip2_rows), len(lzo_rows)))


if __name__ == '__main__':
    main()
