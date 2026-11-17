"""Makes sprite-brightness.m4v, the static sprite stream sprite-basic.m4v with the sprite brightness change of ISO/IEC
14496-2 (sprite_brightness_change in the video object layer header, brightness_change_factor after the trajectory of
every sprite VOP), and brightness.tsv, the factor of each sprite VOP.

usage: python -I sprite-brightness.py <directory of sprite-basic.m4v>

The encoder of the reference software codes no brightness change, so the stream is rewritten bit by bit: the flag of
the layer header is set, and each S-VOP gets the code of its factor (6.3.5.4, Table V2-16) after its warping points,
followed by fresh stuffing to the next start code. The S-VOPs of a basic sprite carry nothing else, and the sprite
brightness change scales only the luminance of the warped sprite (7.8.7), so every frame decoded from the rewritten
stream is the frame of sprite-basic.m4v with each luminance sample Y replaced by min(255, floor(Y * (1 + factor /
100) + 0.5)): Mpeg4ObjectConformanceTest checks that. The factors cover every code length of the table.
"""
import os
import sys

FACTORS = [10, -12, 30, -40, 100, -90, 200, 113, 700, 1648]
WARPING = {0: '00', 1: '010', 2: '011', 3: '100', 4: '101', 5: '110', 6: '1110', 7: '11110', 8: '111110',
           9: '1111110', 10: '11111110', 11: '111111110', 12: '1111111110', 13: '11111111110', 14: '111111111110'}


class Bits:
    """Reads a string of '0' and '1' characters."""

    def __init__(self, bits, at):
        self.bits = bits
        self.at = at

    def read(self, count):
        value = int(self.bits[self.at:self.at + count] or '0', 2)
        self.at += count
        return value

    def marker(self):
        if self.read(1) != 1:
            sys.exit('missing marker bit at %d' % (self.at - 1))


def brightness_code(value):
    """Returns the code of a brightness_change_factor (Table V2-16)."""
    if -16 <= value <= 16 and value != 0:
        return '0' + format(value + 16 if value < 0 else value + 15, '05b')
    if -48 <= value <= 48 and value != 0:
        return '10' + format(value + 48 if value < 0 else value + 15, '06b')
    if -112 <= value <= 112 and value != 0:
        return '110' + format(value + 112 if value < 0 else value + 15, '07b')
    if 113 <= value <= 624:
        return '1110' + format(value - 113, '09b')
    if 625 <= value <= 1648:
        return '1111' + format(value - 625, '010b')
    sys.exit('no code for factor %d' % value)


def warping_points(reader, points):
    """Skips the sprite trajectory of an S-VOP."""
    codes = {code: size for size, code in WARPING.items()}
    for _ in range(points * 2):
        code = ''
        while code not in codes:
            code += reader.bits[reader.at]
            reader.at += 1
            if len(code) > 12:
                sys.exit('invalid warping point code')
        reader.read(codes[code])
        reader.marker()


def main():
    directory = sys.argv[1]
    data = open(os.path.join(directory, 'sprite-basic.m4v'), 'rb').read()
    bits = ''.join(format(byte, '08b') for byte in data)
    starts = [i for i in range(len(data) - 3) if data[i:i + 3] == b'\x00\x00\x01']
    out = []
    previous = 0
    layer = None
    factors = iter(FACTORS)
    used = []
    for index, start in enumerate(starts):
        code = data[start + 3]
        end = starts[index + 1] if index + 1 < len(starts) else len(data)
        if 0x20 <= code <= 0x2F:
            reader = Bits(bits, (start + 4) * 8)
            reader.read(1)
            reader.read(8)
            verid = 1
            if reader.read(1):
                verid = reader.read(4)
                reader.read(3)
            if reader.read(4) == 15:
                reader.read(16)
            if reader.read(1):
                reader.read(3)
                if reader.read(1):
                    sys.exit('vbv parameters are not expected')
            shape = reader.read(2)
            if shape != 1 or verid != 2:
                sys.exit('a version 2 layer with a binary shape is expected')
            reader.marker()
            clock = reader.read(16)
            reader.marker()
            increment_bits = max(1, (clock - 1).bit_length())
            if reader.read(1):
                reader.read(increment_bits)
            if reader.read(1):
                sys.exit('interlaced layers are not expected')
            reader.read(1)
            sprite = reader.read(2)
            if sprite != 1:
                sys.exit('a static sprite is expected')
            reader.read(13)
            reader.marker()
            reader.read(13)
            reader.marker()
            reader.read(13)
            reader.marker()
            reader.read(13)
            reader.marker()
            points = reader.read(6)
            reader.read(2)
            flag = reader.at
            if bits[flag] != '0' or reader.read(1) != 0:
                sys.exit('the stream already has the brightness change')
            if reader.read(1):
                sys.exit('a basic sprite is expected')
            reader.read(1)
            if reader.read(1):
                sys.exit('8-bit samples are expected')
            if reader.read(1):
                sys.exit('H.263 quantization is expected')
            reader.read(1)
            if not reader.read(1):
                sys.exit('complexity estimation is not expected')
            layer = (increment_bits, points)
            out.append(bits[previous:flag] + '1')
            previous = flag + 1
            continue
        if code != 0xB6 or layer is None:
            continue
        increment_bits, points = layer
        reader = Bits(bits, (start + 4) * 8)
        coding_type = reader.read(2)
        if coding_type != 3:
            continue
        while reader.read(1):
            pass
        reader.marker()
        reader.read(increment_bits)
        reader.marker()
        if not reader.read(1):
            continue
        reader.read(13)
        reader.marker()
        reader.read(13)
        reader.marker()
        reader.read(13)
        reader.marker()
        reader.read(13)
        reader.marker()
        reader.read(1)
        if reader.read(1):
            reader.read(8)
        reader.read(3)
        warping_points(reader, points)
        stuffing = bits[reader.at:end * 8]
        if not (1 <= len(stuffing) <= 8 and stuffing == '0' + '1' * (len(stuffing) - 1)):
            sys.exit('the S-VOP at %d holds more than its trajectory' % start)
        factor = next(factors)
        used.append(factor)
        out.append(bits[previous:reader.at] + brightness_code(factor))
        out.append('0' + '1' * ((-(len(''.join(out)) + 1)) % 8))
        previous = end * 8
    out.append(bits[previous:])
    stream = ''.join(out)
    if len(stream) % 8 or len(used) != 10:
        sys.exit('rewriting failed')
    open(os.path.join(directory, 'sprite-brightness.m4v'), 'wb').write(
        bytes(int(stream[i:i + 8], 2) for i in range(0, len(stream), 8)))
    with open(os.path.join(directory, 'brightness.tsv'), 'w', newline='\n') as sink:
        sink.write('# brightness_change_factor of each S-VOP of sprite-brightness.m4v, written by '
                   'tools/testdata/mpeg4-object/sprite-brightness.py\n')
        for factor in used:
            sink.write('%d\n' % factor)
    print(len(used), 'sprite VOPs rewritten')


if __name__ == '__main__':
    main()
