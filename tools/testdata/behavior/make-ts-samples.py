"""Writes the transport stream samples ffmpeg cannot write: AV1 with SCTE 35 splice information.

    python -I make-ts-samples.py av1-scte35 IN.obu OUT.ts [--rate N]

"av1-scte35" reads an AV1 stream in the low overhead bitstream format (AV1 specification section 5.2, as ffmpeg's
"obu" muxer writes it: temporal units opened by temporal delimiters, every OBU with its size field) and writes a
transport stream carrying it as the AOM "Carriage of AV1 in MPEG-2 TS" specifies: stream_type 0x06 on PID 0x100 with
the 'AV01' registration descriptor first and the AV1 video descriptor (profile and level from the first sequence
header, 4:2:0, hdr_wcg_idc 3), one PES packet (stream_id 0xBD, data_alignment_indicator set, a PTS per temporal unit
at the given frame rate) per temporal unit, every OBU behind the start code 00 00 01 with emulation prevention bytes,
the random_access_indicator and a PCR on the packets that start a key temporal unit. The program carries the 'CUEI'
registration and an SCTE 35 stream (stream_type 0x86, PID 0x1F0) with a splice_insert section every half second
(ANSI/SCTE 35 clause 9), each whose splice time is the time of the temporal unit it precedes.
"""
import struct
import sys

PMT_PID = 0x1000
VIDEO_PID = 0x100
CUE_PID = 0x1F0


def crc32_mpeg(data):
    crc = 0xFFFFFFFF
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            crc = ((crc << 1) ^ 0x04C11DB7) & 0xFFFFFFFF if crc & 0x80000000 else (crc << 1) & 0xFFFFFFFF
    return crc


def leb128(data, position):
    value = 0
    for index in range(8):
        byte = data[position + index]
        value |= (byte & 0x7F) << (7 * index)
        if not byte & 0x80:
            return value, position + index + 1
    raise ValueError("leb128 too long")


def obus(data):
    position = 0
    while position < len(data):
        header = data[position]
        start = position
        position += 2 if header & 0x04 else 1
        if not header & 0x02:
            raise ValueError("an OBU without a size field")
        size, position = leb128(data, position)
        position += size
        yield (header >> 3) & 0x0F, data[start:position], data[position - size:position]


def temporal_units(data):
    unit = []
    for kind, obu, payload in obus(data):
        if kind == 2 and unit:
            yield unit
            unit = []
        unit.append((kind, obu, payload))
    if unit:
        yield unit


def escaped(obu):
    out = bytearray(b"\x00\x00\x01")
    zeros = 0
    for byte in obu:
        if zeros >= 2 and byte <= 3:
            out.append(3)
            zeros = 0
        out.append(byte)
        zeros = zeros + 1 if byte == 0 else 0
    return bytes(out)


def is_key(unit):
    for kind, _, payload in unit:
        if kind in (3, 6) and payload:
            first = payload[0]
            return not first & 0x80 and (first >> 5) & 3 == 0 and first & 0x10
    return False


def section(table, extension, body):
    length = 5 + len(body) + 4
    data = bytes([table, 0xB0 | length >> 8, length & 0xFF, extension >> 8, extension & 0xFF, 0xC1, 0, 0]) + body
    return data + struct.pack(">I", crc32_mpeg(data))


def timestamp(prefix, value):
    value &= (1 << 33) - 1
    return bytes([(prefix << 4) | ((value >> 29) & 0x0E) | 1, (value >> 22) & 0xFF, ((value >> 14) & 0xFE) | 1,
                  (value >> 7) & 0xFF, ((value << 1) & 0xFE) | 1])


class Writer:
    def __init__(self):
        self.out = bytearray()
        self.counters = {}

    def packet(self, pid, start, payload, adaptation=b""):
        """Writes one packet; adaptation is the adaptation field after its length byte (flags first)."""
        counter = self.counters.get(pid, 0)
        self.counters[pid] = (counter + 1) & 0x0F
        length = 184 - len(payload)
        if length == 0:
            self.out += bytes([0x47, (0x40 if start else 0) | pid >> 8, pid & 0xFF, 0x10 | counter]) + payload
            return
        body = adaptation if adaptation else (b"\x00" if length > 1 else b"")
        field = bytes([length - 1]) + body + b"\xff" * (length - 1 - len(body))
        self.out += bytes([0x47, (0x40 if start else 0) | pid >> 8, pid & 0xFF, 0x30 | counter]) + field + payload

    def section(self, pid, data):
        data = b"\x00" + data
        first = True
        while data:
            chunk, data = data[:184], data[184:]
            self.packet(pid, first, chunk + b"\xff" * (184 - len(chunk)))
            first = False

    def pes(self, pid, pts, payload, key):
        header = bytes([0, 0, 1, 0xBD, 0, 0, 0x84, 0x80, 5]) + timestamp(2, pts)
        data = header + payload
        first = True
        while data:
            adaptation = b""
            if first and key:
                pcr = pts - 9000
                adaptation = bytes([0x50]) + struct.pack(">IH", (pcr >> 1) & 0xFFFFFFFF, ((pcr & 1) << 15) | 0x7E00)
            room = 184 - (1 + len(adaptation) if adaptation else 0)
            chunk, data = data[:room], data[room:]
            self.packet(pid, first, chunk, adaptation)
            first = False


def splice_insert(event, pts_time):
    bits = []

    def put(value, count):
        for index in range(count - 1, -1, -1):
            bits.append((value >> index) & 1)

    put(event, 32); put(0, 1); put(0x7F, 7)
    put(1, 1); put(1, 1); put(0, 1); put(0, 1); put(0xF, 4)
    put(1, 1); put(0x3F, 6); put(pts_time, 33)
    put(event & 0xFFFF, 16); put(0, 8); put(0, 8)
    command = bytes(int("".join(map(str, bits[index:index + 8])), 2) for index in range(0, len(bits), 8))
    body = bytes([0, 0, 0, 0, 0, 0, 0, 0xFF, 0xF0 | len(command) >> 8, len(command) & 0xFF, 5]) + command + b"\x00\x00"
    length = len(body) + 4
    data = bytes([0xFC, 0x30 | length >> 8, length & 0xFF]) + body
    return data + struct.pack(">I", crc32_mpeg(data))


def av1_scte35(source, target, rate):
    data = open(source, "rb").read()
    units = list(temporal_units(data))
    sequence = next(payload for unit in units for kind, _, payload in unit if kind == 1)
    profile = sequence[0] >> 5
    reduced = (sequence[0] >> 3) & 1
    timing = (sequence[0] >> 2) & 1
    level = ((sequence[0] & 0x07) << 2 | sequence[1] >> 6) if reduced else (0 if timing else sequence[3] >> 3)
    descriptors = b"\x05\x04AV01" + bytes([0x80, 4, 0x81, (profile << 5) | (level & 0x1F), 0x0C, 0xC0])
    streams = bytes([0x06, 0xE0 | VIDEO_PID >> 8, VIDEO_PID & 0xFF, 0xF0, len(descriptors)]) + descriptors
    streams += bytes([0x86, 0xE0 | CUE_PID >> 8, CUE_PID & 0xFF, 0xF0, 3, 0x8A, 1, 1])
    program_info = b"\x05\x04CUEI"
    pmt = section(0x02, 1, bytes([0xE0 | VIDEO_PID >> 8, VIDEO_PID & 0xFF, 0xF0, len(program_info)]) + program_info
                  + streams)
    pat = section(0x00, 1, bytes([0, 1, 0xE0 | PMT_PID >> 8, PMT_PID & 0xFF]))
    writer = Writer()
    start = 126_000
    for index, unit in enumerate(units):
        pts = start + index * 90_000 // rate
        if index % max(1, rate // 4) == 0:
            writer.section(0, pat)
            writer.section(PMT_PID, pmt)
        if index % max(1, rate // 2) == 0 and index > 0:
            writer.section(CUE_PID, splice_insert(index, pts))
        payload = b"".join(escaped(obu) for _, obu, _ in unit)
        writer.pes(VIDEO_PID, pts, payload, is_key(unit))
    open(target, "wb").write(writer.out)


def main(arguments):
    if len(arguments) >= 3 and arguments[0] == "av1-scte35":
        rate = 15
        if len(arguments) == 5 and arguments[3] == "--rate":
            rate = int(arguments[4])
        av1_scte35(arguments[1], arguments[2], rate)
        return
    print(__doc__)
    sys.exit(2)


if __name__ == "__main__":
    main(sys.argv[1:])
