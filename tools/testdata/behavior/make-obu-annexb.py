"""Rewrites an AV1 low-overhead OBU stream (AV1 specification section 5) in the length-delimited format of Annex B.

Each temporal unit (the OBUs from one temporal delimiter to the next) becomes a temporal_unit_size followed by frame
units: each holds one frame header or frame OBU with the tile groups after it and the OBUs since the previous frame's
(a sequence header or metadata OBU after a frame starts the next unit, OBUs after the last frame join its unit). Every OBU is
written with an obu_length and without its obu_size field, as aomenc --annexb=1 writes it.

    python -I make-obu-annexb.py <input.obu> <output.obu>
"""
import sys


def leb128(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return bytes(out)


def read_leb128(data, position):
    value = 0
    for index in range(8):
        byte = data[position + index]
        value |= (byte & 0x7F) << (7 * index)
        if not byte & 0x80:
            return value, position + index + 1
    raise ValueError("invalid leb128")


def obus(data):
    position = 0
    while position < len(data):
        header = data[position]
        header_bytes = 2 if header & 0x04 else 1
        if not header & 0x02:
            raise ValueError("an OBU without obu_size in a low-overhead stream")
        size, payload = read_leb128(data, position + header_bytes)
        obu = bytes([header & ~0x02 & 0xFF]) + data[position + 1:position + header_bytes] + data[payload:payload + size]
        yield (header >> 3) & 0x0F, obu
        position = payload + size


def main():
    data = open(sys.argv[1], "rb").read()
    units = []
    for kind, obu in obus(data):
        if kind == 2 or not units:
            units.append([])
        units[-1].append((kind, obu))
    out = bytearray()
    for unit in units:
        frames = []
        pending = bytearray()
        framed = False
        for kind, obu in unit:
            frame = kind in (3, 6)
            if framed and (frame or kind in (1, 5)):
                frames.append(pending)
                pending = bytearray()
                framed = False
            framed |= frame
            pending += leb128(len(obu)) + obu
        if pending:
            if not framed and frames:
                frames[-1] += pending
            else:
                frames.append(pending)
        temporal = bytearray()
        for frame in frames:
            temporal += leb128(len(frame)) + frame
        out += leb128(len(temporal)) + temporal
    open(sys.argv[2], "wb").write(out)


if __name__ == "__main__":
    main()
