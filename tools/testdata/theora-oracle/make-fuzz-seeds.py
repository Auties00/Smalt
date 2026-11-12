"""Makes the seeds of video's VideoFuzzTest.theora from the Theora test vectors (make-vectors.py), as FuzzPackets
inputs (core/src/test/java/.../testkit/fuzz/FuzzPackets.java): the options byte (0), the coded frame size, the three
headers Xiph-laced as the configuration, then the first data packets behind the marker with their key frame flags, an
empty packet (a dropped frame) after the second. The smalt-*.packets listings of make-encoder-vectors.py (Smalt's
encoder) are read as well.

Usage: python -I make-fuzz-seeds.py <vector directory> <output directory>
"""
import os
import struct
import sys

MARKER = b'FUZZ-TAG'
SEEDS = [
    # vector, seed name, data packets
    ('420-q5', 'valid-420-intra-inter.bin', 4),
    ('420-motion', 'valid-420-motion.bin', 5),
    ('422-odd', 'valid-422-cropped.bin', 3),
    ('444-bitrate', 'valid-444-bitrate.bin', 3),
    ('smalt-420.packets', 'valid-420-smalt-inter.bin', 6),
]


def ogg_packets(data):
    """Returns the packets of the first logical stream of an Ogg file."""
    packets, current, position = [], b'', 0
    while position + 27 <= len(data):
        assert data[position:position + 4] == b'OggS'
        segments = data[position + 26]
        table = data[position + 27:position + 27 + segments]
        body = position + 27 + segments
        for lace in table:
            current += data[body:body + lace]
            body += lace
            if lace < 255:
                packets.append(current)
                current = b''
        position = body
    return packets


def listed_packets(data):
    """Returns the packets of a packet listing of make-encoder-vectors.py: a 32-bit little-endian length each."""
    packets, position = [], 0
    while position < len(data):
        length = struct.unpack_from('<I', data, position)[0]
        packets.append(data[position + 4:position + 4 + length])
        position += 4 + length
    return packets


def xiph_lace(packets):
    out = bytearray([len(packets) - 1])
    for packet in packets[:-1]:
        size = len(packet)
        while size >= 255:
            out.append(255)
            size -= 255
        out.append(size)
    for packet in packets:
        out += packet
    return bytes(out)


def main():
    vectors, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for vector, name, count in SEEDS:
        if vector.endswith('.packets'):
            with open(os.path.join(vectors, vector), 'rb') as f:
                packets = listed_packets(f.read())
        else:
            with open(os.path.join(vectors, vector + '.ogv'), 'rb') as f:
                packets = ogg_packets(f.read())
        headers, data = packets[:3], packets[3:3 + count]
        identification = headers[0]
        width = struct.unpack_from('>H', identification, 10)[0] << 4
        height = struct.unpack_from('>H', identification, 12)[0] << 4
        seed = bytearray(struct.pack('>BHH', 0, width, height)) + xiph_lace(headers)
        for index, packet in enumerate(data):
            assert MARKER not in packet
            seed += MARKER + bytes([0 if packet[0] & 0x40 else 1]) + packet
            if index == 1:
                seed += MARKER
        with open(os.path.join(out, name), 'wb') as f:
            f.write(seed)


if __name__ == '__main__':
    main()
