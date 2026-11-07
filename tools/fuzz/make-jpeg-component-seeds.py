"""Writes the ImageMetadataFuzzTest.jpegTransform seeds of JPEG frames with two or more than four components.

libjpeg-turbo's cjpeg cannot write such frames (its inputs have 1, 3 or 4 channels), and its decoder rejects a scan
of any component after the fourth, so this script writes them
itself: baseline (SOF0) frames whose scans each hold one component, every block coding a DC difference and the
first AC coefficient, under two small Huffman tables of its own. They are not named valid-*, since the
target's grayscale conversion rejects them.

- two-components-12-blocks.jpg: 40x24, components sampled 4x2 and 2x2, 12 blocks per MCU, more than one
  interleaved scan may hold (T.81 B.2.3).
- six-components.jpg: 29x21, six components sampled 1x1, 2x1, 1x2, 1x1, 2x2 and 1x1.

    python -I tools/fuzz/make-jpeg-component-seeds.py [seed directory]
"""
import os
import struct
import sys

# DC table: categories 0 to 8 as the 4-bit codes 0000 to 1000. AC table: end of block 00, run 0 size 1 01.
DC_BITS = [0, 0, 0, 9] + [0] * 12
DC_VALUES = list(range(9))
AC_BITS = [0, 2] + [0] * 14
AC_VALUES = [0x00, 0x01]


def segment(marker, payload):
    return struct.pack(">BBH", 0xFF, marker, len(payload) + 2) + payload


class Bits:
    def __init__(self):
        self.out = bytearray()
        self.accumulator = 0
        self.count = 0

    def put(self, value, length):
        for shift in range(length - 1, -1, -1):
            self.accumulator = (self.accumulator << 1) | ((value >> shift) & 1)
            self.count += 1
            if self.count == 8:
                self.out.append(self.accumulator)
                if self.accumulator == 0xFF:
                    self.out.append(0)
                self.accumulator = 0
                self.count = 0

    def flush(self):
        while self.count:
            self.put(1, 1)
        return bytes(self.out)


def magnitude(value):
    category = abs(value).bit_length()
    bits = value if value >= 0 else value + (1 << category) - 1
    return category, bits


def write(width, height, sampling):
    h_max = max(h for h, _ in sampling)
    v_max = max(v for _, v in sampling)
    data = b"\xFF\xD8"
    data += segment(0xDB, bytes([0]) + bytes(range(2, 66)))
    frame = struct.pack(">BHHB", 8, height, width, len(sampling))
    for index, (h, v) in enumerate(sampling):
        frame += bytes([index + 1, (h << 4) | v, 0])
    data += segment(0xC0, frame)
    data += segment(0xC4, bytes([0x00] + DC_BITS + DC_VALUES))
    data += segment(0xC4, bytes([0x10] + AC_BITS + AC_VALUES))
    for index, (h, v) in enumerate(sampling):
        component_width = -(-width * h // h_max)
        component_height = -(-height * v // v_max)
        blocks_wide = -(-component_width // 8)
        blocks_high = -(-component_height // 8)
        data += segment(0xDA, bytes([1, index + 1, 0x00, 0, 63, 0]))
        bits = Bits()
        previous = 0
        for row in range(blocks_high):
            for column in range(blocks_wide):
                dc = (index * 37 + row * 11 + column * 7) % 61 - 30
                category, value = magnitude(dc - previous)
                previous = dc
                bits.put(category, 4)
                bits.put(value, category)
                if (row + column + index) % 3:
                    bits.put(1, 2)
                    bits.put(1 if (row ^ column) & 1 else 0, 1)
                bits.put(0, 2)
        data += bits.flush()
    return data + b"\xFF\xD9"


def main():
    directory = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        "image", "src", "test", "resources", "com", "github", "auties00", "smalt", "image", "fuzz",
        "ImageMetadataFuzzTestInputs", "jpegTransform")
    seeds = {
        "two-components-12-blocks.jpg": write(40, 24, [(4, 2), (2, 2)]),
        "six-components.jpg": write(29, 21, [(1, 1), (2, 1), (1, 2), (1, 1), (2, 2), (1, 1)]),
    }
    for name, data in seeds.items():
        with open(os.path.join(directory, name), "wb") as file:
            file.write(data)


if __name__ == "__main__":
    main()
