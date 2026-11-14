#!/usr/bin/env python3
"""Writes the table of SrwOracleTest: the digests of the sensor data Adobe DNG Converter stores for the SRW samples of
Samsung's compressed codings and for copies of them with single fields changed.

The compressed codings were worked out, clean-room, from the CC0 samples of raw.pixls.us against the converter as a
black box. Most of them can be read from the samples alone; the rest cannot, because the cameras never use those values,
so each is settled by a copy with one field changed: quantizers other than 1 and the 2-bit changes of the quantizer, a
row starting without a quantizer, prediction modes at the ends of rows (which show the rows of a color are lines of one
plane), upper predictions in the first rows (which show what the rows before the first hold), a stated left mode in the
first two rows of 32773 (any other mode there is refused), photosites past either limit of the bit depth in 32770 and
32772, the header's prediction of a row's first block, and the header's fields that do not matter. The converter
refuses copies that fail its checks (a set block bit, a changed header magic, an upper mode in the first two rows of 32773,
random bit flips), which the decoder reports as malformed or unsupported where the check is known. A refused copy is
reported and left out of the table.

The table raw/src/test/resources/com/github/auties00/smalt/image/raw/internal/srw-oracle.tsv holds, per line: the case's
name, the sample's name, the SHA-256 of the stored image of the converter's DNG file (little-endian 16-bit words, row by
row) and the bytes the copy changes, as offset:hex items separated by commas. The test applies the edits, decodes and
compares digests, so it needs only the sample, not the converter.

    python tools/testdata/srw-oracle.py          # writes the table, with Adobe DNG Converter 18.0

Windows only, like raw-oracle.py, whose pinned converter this script fetches, unless SMALT_DNG_CONVERTER names an
extracted copy. Each digest is kept in <conformance cache>/raw-oracle/srw-oracle.cache, by the SHA-256 of the file, so
an interrupted run resumes. Nothing of the converter is redistributed. Downloaded samples are data: this script parses
them and hands them to the converter, and runs nothing from them.
"""
import hashlib
import importlib.util
import os
import struct
import subprocess
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import testdata  # noqa: E402

SPEC = importlib.util.spec_from_file_location('raw_oracle', os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                                          'raw-oracle.py'))
raw_oracle = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(raw_oracle)

TABLE = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..', 'raw', 'src', 'test', 'resources', 'com',
                     'github', 'auties00', 'smalt', 'image', 'raw', 'internal', 'srw-oracle.tsv')

PREFIX = 'rawpixls-samsung-'
SAMPLES = ['nx300-12bit-3-2', 'nx30-12bit-3-2', 'nx2000-12bit-3-2', 'ek-gn120-12bit-3-2', 'nx-mini-12bit-3-2',
           'nx3000-12bit-3-2', 'nx3300-12bit-3-2', 'nx1-14bit-3-2', 'nx500-12bit-12bit-lossless-compression-3-2',
           'nx500-12bit-12bit-normal-compression-3-2', 'nx500-14bit-14bit-lossless-compression-3-2',
           'nx500-14bit-14bit-normal-compression-3-2']
LOSSLESS = 'nx500-12bit-12bit-lossless-compression-3-2'
QUANTIZED = 'nx500-12bit-12bit-normal-compression-3-2'
INTERLEAVED = 'nx300-12bit-3-2'
PREFIXED = 'nx-mini-12bit-3-2'
ROW = 5
DEPTH = 12
PREFIX_CODES = {'110100': 0, '11011': 1, '1111': 2, '1100': 3, '000': 4, '10': 5, '01': 6, '001': 7, '1110': 8,
                '1101010': 9, '11010110': 10, '110101110': 11, '1101011110': 12}


def raw_directory(data):
    """Returns the entries of the raw directory, the second SubIFD of the first directory, and a function reading one."""
    order = '<' if data[:2] == b'II' else '>'
    first = struct.unpack(order + 'I', data[4:8])[0]

    def entries(offset):
        count = struct.unpack(order + 'H', data[offset:offset + 2])[0]
        found = {}
        for index in range(count):
            at = offset + 2 + 12 * index
            tag, kind, length = struct.unpack(order + 'HHI', data[at:at + 8])
            size = {1: 1, 2: 1, 3: 2, 4: 4, 7: 1}.get(kind, 8) * length
            place = at + 8 if size <= 4 else struct.unpack(order + 'I', data[at + 8:at + 12])[0]
            found[tag] = (kind, place)
        return found

    def value(entry, index=0):
        kind, place = entry
        if kind == 3:
            return struct.unpack(order + 'H', data[place + 2 * index:place + 2 * index + 2])[0]
        return struct.unpack(order + 'I', data[place + 4 * index:place + 4 * index + 4])[0]

    return entries(value(entries(first)[0x14A], 1)), value


def raw_strip(data):
    """Returns the offset and bytes of the strip of the raw directory, and its width."""
    raw, value = raw_directory(data)
    return value(raw[0x111]), value(raw[0x117]), value(raw[0x100])


class Bits:
    """Reads and writes the bits of 32-bit little-endian words, most significant bit first."""

    def __init__(self, data, start):
        self.data = data
        self.start = start

    def place(self, position):
        word, bit = position // 32, 31 - position % 32
        return self.start + word * 4 + bit // 8, 1 << bit % 8

    def get(self, position, count):
        value = 0
        for index in range(count):
            offset, mask = self.place(position + index)
            value = value << 1 | (1 if self.data[offset] & mask else 0)
        return value

    def put(self, position, count, value):
        for index in range(count):
            offset, mask = self.place(position + index)
            if value >> count - 1 - index & 1:
                self.data[offset] |= mask
            else:
                self.data[offset] &= ~mask & 0xFF


def fields(data, quantized, rows):
    """Returns the positions of the fields of each block of the first rows of a 32773 strip."""
    start, _, width = raw_strip(data)
    bits = Bits(data, start + 16)
    blocks = (width + 15) // 16
    position = 0
    table = []
    for y in range(rows):
        widths = [7 if y < 2 else 4] * 4
        row = []
        for block in range(blocks):
            found = {}
            if quantized and block % 4 == 0:
                found['field'] = position
                field = bits.get(position, 2)
                position += 2
                if field == 3:
                    found['quantizer'] = position
                    position += 12
            found['flag'] = position
            keep = bits.get(position, 1)
            position += 1
            if not keep:
                found['mode'] = position
                position += 3
            if quantized:
                position += 1
            codes = [bits.get(position + 2 * index, 2) for index in range(4)]
            position += 8
            for index in range(4):
                if codes[index] == 3:
                    widths[index] = bits.get(position, 4)
                    position += 4
                elif codes[index] == 1:
                    widths[index] += 1
                elif codes[index] == 2:
                    widths[index] -= 1
            position += 4 * sum(widths)
            row.append(found)
        row[-1]['end'] = position
        table.append(row)
        position = (position + 127) // 128 * 128
    return bits, table


def signed(value, width):
    """Returns a two's complement value."""
    return value - (1 << width) if width and value >> width - 1 else value


def insert_mode(bits, block, mode):
    """Makes a block that keeps the previous mode state its own, moving the rest of its row into the row's padding."""
    flag, end = block['flag'], block['end']
    if (end + 3 + 127) // 128 != (end + 127) // 128:
        sys.exit('no room for a mode at the end of a row')
    for position in range(end - 1, flag, -1):
        bits.put(position + 3, 1, bits.get(position, 1))
    bits.put(flag, 1, 0)
    bits.put(flag + 1, 3, mode)


def interleaved_limits(data):
    """Returns the flag positions of the first and last blocks of the first two rows of a 32770 strip, and the position,
    width and extreme value of the first sample from the third row on that can pass each limit of the bit depth."""
    raw, value = raw_directory(data)
    start, _, width = raw_strip(data)
    table = value(raw[0xA010])
    height = value(raw[0x101])
    bits = Bits(data, start)
    blocks = (width + 15) // 16
    samples = blocks * 8
    largest = (1 << DEPTH) - 1
    rows = [[[0] * samples, [0] * samples] for _ in range(2)]
    flags = {}
    found = {}
    for y in range(height):
        position = struct.unpack('<I', data[table + 4 * y:table + 4 * y + 4])[0] * 8
        row = [[0] * samples, [0] * samples]
        widths = [7 if y < 2 else 4] * 4
        left = [128, 128]
        for block in range(blocks):
            if y < 2 and block in (0, blocks - 1):
                flags.setdefault(y, []).append(position)
            up = bits.get(position, 1)
            position += 1
            codes = [bits.get(position + 2 * index, 2) for index in range(4)]
            position += 8
            for index in range(4):
                if codes[index] == 3:
                    widths[index] = bits.get(position, 4)
                    position += 4
                elif codes[index] == 1:
                    widths[index] += 1
                elif codes[index] == 2:
                    widths[index] -= 1
            for run in range(2):
                reference = rows[run][run]
                for part in range(2):
                    size = widths[2 * run + part]
                    for sample in range(4):
                        j = block * 8 + part * 4 + sample
                        prediction = reference[j] if up else left[run]
                        if y >= 2 and size:
                            low = -(1 << size - 1)
                            high = (1 << size - 1) - 1
                            if 'low' not in found and prediction + low < 0:
                                found['low'] = (position, size, low & (1 << size) - 1)
                            if 'high' not in found and prediction + high > largest:
                                found['high'] = (position, size, high)
                            if len(found) == 2:
                                return flags, found
                        row[run][j] = min(max(prediction + signed(bits.get(position, size), size), 0), largest)
                        position += size
                left[run] = row[run][block * 8 + 7]
        rows = [row, rows[0]]
    return flags, found


def prefixed_limits(data):
    """Returns the strip's offset and the position, width and extreme value of the magnitude bits of the first photosite
    of a 32772 strip that can pass each limit of the bit depth."""
    start, count, width = raw_strip(data)
    largest = (1 << DEPTH) - 1
    text = bin(int.from_bytes(b'\x01' + bytes(data[start:start + count]), 'big'))[3:]
    position = 0
    previous = [[0] * width, [0] * width]
    found = {}
    limit = len(text) - 64
    while position < limit and len(found) < 2:
        row = [0] * width
        for x in range(width):
            length = 2
            while text[position:position + length] not in PREFIX_CODES:
                length += 1
            category = PREFIX_CODES[text[position:position + length]]
            position += length
            prediction = row[x - 2] if x >= 2 else previous[0][x]
            difference = 0
            if category:
                extreme = (1 << category) - 1
                value = int(text[position:position + category], 2)
                difference = value - extreme if value < 1 << category - 1 else value
                if 'low' not in found and x >= 2 and prediction - extreme < 0:
                    found['low'] = (position, category, 0)
                if 'high' not in found and prediction + extreme > largest:
                    found['high'] = (position, category, extreme)
                position += category
            row[x] = min(max(prediction + difference, 0), largest)
        previous = [previous[1], row]
    return start, found


def put_msb(data, start, position, count, value):
    """Writes bits of a strip read most significant bit first."""
    for index in range(count):
        at = position + index
        mask = 0x80 >> at % 8
        if value >> count - 1 - index & 1:
            data[start + at // 8] |= mask
        else:
            data[start + at // 8] &= ~mask & 0xFF


def copies(directory):
    """Returns name -> (sample, changed bytes) of the copies with one field changed."""
    found = {}

    def load(sample):
        with open(os.path.join(directory, PREFIX + sample + '.srw'), 'rb') as stream:
            return bytearray(stream.read())

    def header(name, sample, *changes):
        data = load(sample)
        start = raw_strip(data)[0]
        for offset, value in changes:
            data[start + offset] = value
        found[name] = (sample, data)

    for quantizer in (0, 2, 3, 7):
        data = load(QUANTIZED)
        bits, table = fields(data, True, ROW + 1)
        bits.put(table[ROW][0]['quantizer'], 12, quantizer)
        found['quantizer-%d' % quantizer] = (QUANTIZED, data)
    for quantizer, field in ((1, 1), (1, 2), (5, 1), (5, 2), (0, 1), (0, 2), (4095, 2)):
        data = load(QUANTIZED)
        bits, table = fields(data, True, ROW + 1)
        bits.put(table[ROW][0]['quantizer'], 12, quantizer)
        bits.put(table[ROW][4]['field'], 2, field)
        found['quantizer-%d-field-%d' % (quantizer, field)] = (QUANTIZED, data)
    data = load(QUANTIZED)
    bits, table = fields(data, True, ROW + 1)
    bits.put(table[ROW][4]['field'], 2, 1)
    bits.put(table[ROW][8]['field'], 2, 1)
    found['field-1-twice'] = (QUANTIZED, data)
    # a row without a quantizer: its bits move up over the field's 12 bits, and the row above has another quantizer
    data = load(QUANTIZED)
    bits, table = fields(data, True, ROW + 2)
    bits.put(table[ROW - 1][0]['quantizer'], 12, 2)
    first, end = table[ROW][0]['field'], table[ROW + 1][0]['field']
    bits.put(first, 2, 0)
    for position in range(first + 14, end):
        bits.put(position - 12, 1, bits.get(position, 1))
    found['row-without-quantizer'] = (QUANTIZED, data)
    for mode in (0, 1, 2, 4, 5, 6):
        data = load(LOSSLESS)
        bits, table = fields(data, False, ROW + 1)
        for block in (table[ROW][0], table[ROW][-1]):
            if 'mode' in block:
                bits.put(block['mode'], 3, mode)
        found['row-ends-mode-%d' % mode] = (LOSSLESS, data)
    for y in (0, 1):
        data = load(LOSSLESS)
        bits, table = fields(data, False, y + 1)
        insert_mode(bits, table[y][-1], 7)
        found['row-%d-end-mode-7' % y] = (LOSSLESS, data)
    for y, mode in ((2, 0), (2, 1), (2, 2), (3, 0), (3, 1), (3, 2)):
        data = load(LOSSLESS)
        bits, table = fields(data, False, y + 1)
        bits.put(table[y][0]['mode'], 3, mode)
        found['row-%d-start-mode-%d' % (y, mode)] = (LOSSLESS, data)
    flags, limits = interleaved_limits(load(INTERLEAVED))
    for y in (0, 1):
        data = load(INTERLEAVED)
        bits = Bits(data, raw_strip(data)[0])
        for flag in flags[y]:
            bits.put(flag, 1, 1)
        found['interleaved-row-%d-up' % y] = (INTERLEAVED, data)
    for name, (position, size, value) in limits.items():
        data = load(INTERLEAVED)
        Bits(data, raw_strip(data)[0]).put(position, size, value)
        found['interleaved-limit-' + name] = (INTERLEAVED, data)
    start, limits = prefixed_limits(load(PREFIXED))
    for name, (position, size, value) in limits.items():
        data = load(PREFIXED)
        put_msb(data, start, position, size, value)
        found['prefixed-limit-' + name] = (PREFIXED, data)
    header('header-letter', LOSSLESS, (0, ord('E')))
    header('header-depth', LOSSLESS, (1, 0x6D))
    header('header-byte-8', LOSSLESS, (8, 1))
    header('header-start-low', LOSSLESS, (12, 0x40))
    header('header-start-high', LOSSLESS, (13, 1))
    header('header-byte-14', LOSSLESS, (14, 3))
    header('header-byte-15', LOSSLESS, (15, 0))
    header('header-quantized-letter', QUANTIZED, (0, ord('K')))
    header('header-quantized-start', QUANTIZED, (12, 0x40))
    header('interleaved-header-start', INTERLEAVED, (12, 0x40))
    return found


def stored(path):
    """Returns the SHA-256 of the stored image of a converter's DNG file."""
    _, _, _, samples, words = raw_oracle.image_words(path)
    if samples != 1:
        sys.exit(path + ' is not of one sample per pixel')
    if sys.byteorder != 'little':
        words.byteswap()
    return hashlib.sha256(words.tobytes()).hexdigest()


def main():
    if os.name != 'nt':
        sys.exit('Adobe DNG Converter for Windows is needed; run this script on Windows')
    directory = testdata.conformance_dir()
    vectors = {row['name']: row for row in testdata.read_tsv(testdata.VECTORS, testdata.VECTOR_COLUMNS)}
    for sample in SAMPLES:
        row = vectors[PREFIX + sample + '.srw']
        path = os.path.join(directory, row['name'])
        if not testdata.valid(path, row['sha256']):
            testdata.download(row['url'], path, row['sha256'])
    cache_path = os.path.join(directory, 'raw-oracle', 'srw-oracle.cache')
    os.makedirs(os.path.dirname(cache_path), exist_ok=True)
    cache = {}
    if os.path.isfile(cache_path):
        with open(cache_path) as stream:
            cache = dict(line.split() for line in stream if line.strip())
    with tempfile.TemporaryDirectory(prefix='srw-oracle-') as work:
        converter = os.environ.get('SMALT_DNG_CONVERTER')
        if converter is None:
            installer = os.path.join(work, 'converter.exe')
            archive = os.path.join(work, 'innoextract.zip')
            testdata.download(raw_oracle.CONVERTER_URL, installer, raw_oracle.CONVERTER_SHA256)
            testdata.download(raw_oracle.INNOEXTRACT_URL, archive, raw_oracle.INNOEXTRACT_SHA256)
            with zipfile.ZipFile(archive) as source:
                source.extract('innoextract.exe', work)
            subprocess.run([os.path.join(work, 'innoextract.exe'), '-s', '-e', '-I', 'app', '-d', work, installer],
                           check=True)
            converter = os.path.join(work, 'app', 'Adobe DNG Converter.exe')
        output = os.path.join(work, 'out')
        os.makedirs(output)

        def convert(path):
            with open(path, 'rb') as stream:
                key = hashlib.sha256(stream.read()).hexdigest()
            if key in cache:
                return cache[key]
            subprocess.run([converter, '-u', '-d', raw_oracle.windows(output), raw_oracle.windows(path)],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            target = os.path.join(output, os.path.splitext(os.path.basename(path))[0] + '.dng')
            if not os.path.isfile(target):
                print(os.path.basename(path), 'refused', flush=True)
                return None
            digest = stored(target)
            os.remove(target)
            cache[key] = digest
            with open(cache_path, 'a') as stream:
                stream.write(key + ' ' + digest + chr(10))
            return digest

        lines = []
        for sample in SAMPLES:
            lines.append('\t'.join([sample, PREFIX + sample + '.srw', convert(os.path.join(directory, PREFIX + sample + '.srw')),
                                    '']))
            print(lines[-1], flush=True)
        for name, (sample, data) in copies(directory).items():
            path = os.path.join(work, name + '.srw')
            with open(path, 'wb') as stream:
                stream.write(data)
            digest = convert(path)
            os.remove(path)
            if digest is None:
                continue
            with open(os.path.join(directory, PREFIX + sample + '.srw'), 'rb') as stream:
                original = stream.read()
            edits = ','.join('%d:%02x' % (offset, data[offset]) for offset in range(len(data)) if data[offset] != original[offset])
            lines.append('\t'.join([name, PREFIX + sample + '.srw', digest, edits]))
            print(name, digest, flush=True)
    os.makedirs(os.path.dirname(TABLE), exist_ok=True)
    with open(TABLE, 'w', newline='\n') as stream:
        stream.write('# case, sample, SHA-256 of the stored image of Adobe DNG Converter 18.0, edits; '
                     'written by tools/testdata/srw-oracle.py\n')
        stream.write('\n'.join(lines) + '\n')


if __name__ == '__main__':
    main()
