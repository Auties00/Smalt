"""Regenerates the I/O behavior samples of Dolby Vision signaling.

Writes, under the given directory (core/src/test/resources/samples by default):

- mp4/dolby-vision-p84.mp4: a 10-bit HLG HEVC stream from ffmpeg's libx265, one Dolby Vision RPU NAL unit (type
  62) appended to each access unit, in an hvc1 sample entry this script extends with a dvvC box (profile 8, level
  1, base layer compatibility 4);
- mp4/dolby-vision-p5.mp4: the same stream with profile 5 RPUs (vdr_rpu_profile 0, full range base layer), whose
  sample entry this script renames dvh1 and extends with a dvcC box (profile 5, level 1, compatibility 0);
- matroska/dolby-vision-p84.mkv: ffmpeg's stream copy of mp4/dolby-vision-p84.mp4 into Matroska, whose track entry
  ffmpeg gives a BlockAdditionMapping of type dvvC;
- ts/dolby-vision-p84.ts: ffmpeg's stream copy of mp4/dolby-vision-p84.mp4 into MPEG-TS, whose program map entry
  this script extends with the DOVI registration descriptor and the DOVI video stream descriptor (tag 0xB0).

The RPUs follow the structure of the dolby_vision crate (dovi_tool, MIT): a header, an identity polynomial
mapping of each component, the display management data of a BT.2020 PQ source and the level 1 and level 6
extension blocks of CM v2.9, then the CRC-32/MPEG-2 and the 0x80 terminator. As for make-samples.sh, the committed
files are the reference their golden dumps are tied to.

    python -I tools/testdata/behavior/make-dolby-vision-samples.py [output directory]
"""
import os
import struct
import subprocess
import sys
import tempfile

FRAMES = 6


def ffmpeg(*args):
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-bitexact", *args], check=True)


class Bits:
    def __init__(self):
        self.bits = []

    def put(self, count, value):
        for shift in range(count - 1, -1, -1):
            self.bits.append((value >> shift) & 1)

    def ue(self, value):
        code = value + 1
        length = code.bit_length()
        self.put(length - 1, 0)
        self.put(length, code)

    def se(self, value):
        self.ue(2 * value - 1 if value > 0 else -2 * value)

    def align(self):
        while len(self.bits) % 8:
            self.bits.append(0)

    def bytes(self):
        out = bytearray()
        for index in range(0, len(self.bits), 8):
            byte = 0
            for bit in self.bits[index:index + 8]:
                byte = byte << 1 | bit
            out.append(byte)
        return bytes(out)


def crc32_mpeg2(data):
    crc = 0xFFFFFFFF
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            crc = (crc << 1) ^ 0x04C11DB7 if crc & 0x80000000 else crc << 1
            crc &= 0xFFFFFFFF
    return crc


def rpu(profile5, frame):
    """Returns one RPU, prefix byte 0x19 first, without emulation prevention."""
    bits = Bits()
    bits.put(8, 0x19)
    bits.put(6, 2)
    bits.put(11, 18)
    bits.put(4, 0 if profile5 else 1)
    bits.put(4, 0)
    bits.put(1, 1)
    bits.put(1, 0)
    bits.put(2, 0)
    bits.ue(23)
    bits.put(2, 1)
    bits.put(1, 1 if profile5 else 0)
    bits.ue(2)
    bits.ue(2)
    bits.ue(4)
    bits.put(1, 0)
    bits.put(3, 0)
    bits.put(1, 0)
    bits.put(1, 1)
    bits.put(1, 1)
    bits.put(1, 0)
    bits.ue(0)
    bits.ue(0)
    bits.ue(0)
    for _ in range(3):
        bits.ue(0)
        bits.put(10, 0)
        bits.put(10, 1023)
    bits.ue(0)
    bits.ue(0)
    for _ in range(3):
        bits.ue(0)
        bits.ue(0)
        bits.put(1, 0)
        bits.se(0)
        bits.put(23, 0)
        bits.se(1)
        bits.put(23, 0)
    bits.ue(0)
    bits.ue(0)
    bits.ue(1 if frame == 0 else 0)
    for coefficient in (9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0):
        bits.put(16, coefficient & 0xFFFF)
    for offset in (16777216, 134217728, 134217728):
        bits.put(32, offset)
    for coefficient in (7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962):
        bits.put(16, coefficient & 0xFFFF)
    bits.put(16, 65535)
    bits.put(16, 0)
    bits.put(16, 0)
    bits.put(32, 0)
    bits.put(5, 12)
    bits.put(2, 0)
    bits.put(2, 0)
    bits.put(2, 1)
    bits.put(12, 62)
    bits.put(12, 3079)
    bits.put(10, 42)
    bits.ue(2)
    bits.align()
    bits.ue(5)
    bits.put(8, 1)
    bits.put(12, 0)
    bits.put(12, 2081 + 40 * frame)
    bits.put(12, 1229)
    bits.put(4, 0)
    bits.ue(8)
    bits.put(8, 6)
    bits.put(16, 1000)
    bits.put(16, 1)
    bits.put(16, 1000)
    bits.put(16, 400)
    bits.align()
    payload = bits.bytes()
    return payload + struct.pack(">I", crc32_mpeg2(payload[1:])) + b"\x80"


def escape(data):
    out = bytearray()
    zeros = 0
    for byte in data:
        if zeros >= 2 and byte <= 3:
            out.append(3)
            zeros = 0
        out.append(byte)
        zeros = zeros + 1 if byte == 0 else 0
    return bytes(out)


def access_units(stream):
    """Splits an Annex B HEVC stream into access units at each first slice segment."""
    starts = []
    position = 0
    while True:
        found = stream.find(b"\x00\x00\x01", position)
        if found < 0:
            break
        starts.append(found)
        position = found + 3
    units = []
    for index, start in enumerate(starts):
        end = starts[index + 1] if index + 1 < len(starts) else len(stream)
        while end > start and index + 1 < len(starts) and stream[end - 1] == 0:
            end -= 1
        header = stream[start + 3]
        units.append(((header >> 1) & 0x3F, stream[start + 3:end]))
    result = []
    current = []
    seen_slice = False
    for kind, unit in units:
        first_slice = kind < 32 and unit[2] & 0x80
        if (first_slice or kind in (32, 33, 34, 35, 39)) and seen_slice:
            result.append(current)
            current = []
            seen_slice = False
        current.append(unit)
        if kind < 32:
            seen_slice = True
    if current:
        result.append(current)
    return result


def with_rpus(stream, profile5):
    out = bytearray()
    for index, unit in enumerate(access_units(stream)):
        for nal in unit:
            out += b"\x00\x00\x00\x01" + nal
        out += b"\x00\x00\x01" + b"\x7c\x01" + escape(rpu(profile5, index))
    return bytes(out)


def boxes(data, start, end):
    result = []
    position = start
    while position + 8 <= end:
        size, kind = struct.unpack(">I4s", data[position:position + 8])
        result.append((position, size, kind.decode("latin-1")))
        position += size
    return result


def box(kind, payload):
    return struct.pack(">I4s", 8 + len(payload), kind.encode("latin-1")) + payload


def dovi_record(profile, level, compatibility):
    flags = profile << 9 | level << 3 | 1 << 2 | 1
    return bytes([1, 0]) + struct.pack(">H", flags) + bytes([compatibility << 4]) + bytes(19)


def signal_mp4(data, kind, record, entry_type=None):
    data = bytearray(data)
    path = []
    position, end = 0, len(data)
    for wanted in ("moov", "trak", "mdia", "minf", "stbl", "stsd"):
        for offset, size, name in boxes(data, position, end):
            if name == wanted:
                path.append(offset)
                position, end = offset + 8 + (8 if wanted == "stsd" else 0), offset + size
                break
        else:
            raise SystemExit("no " + wanted + " box")
    entry = boxes(data, position, end)[0]
    if entry_type is not None:
        data[entry[0] + 4:entry[0] + 8] = entry_type.encode("latin-1")
    children = boxes(data, entry[0] + 8 + 78, entry[0] + entry[1])
    hvcc = next(child for child in children if child[2] == "hvcC")
    added = box(kind, record)
    insert = hvcc[0] + hvcc[1]
    data[insert:insert] = added
    for offset in path + [entry[0]]:
        size = struct.unpack(">I", data[offset:offset + 4])[0]
        data[offset:offset + 4] = struct.pack(">I", size + len(added))
    if any(name == "mdat" for offset, _, name in boxes(data, 0, len(data)) if offset > path[0]):
        raise SystemExit("the movie box must follow the media data")
    return bytes(data)


def crc32_section(data):
    return crc32_mpeg2(data)


def signal_ts(data, record):
    """Adds the DOVI registration and video stream descriptors to the HEVC entry of every program map section."""
    data = bytearray(data)
    descriptor = bytes([0x05, 4]) + b"DOVI" + bytes([0xB0, 5]) + record[:5]
    pmt_pid = None
    for offset in range(0, len(data), 188):
        packet = data[offset:offset + 188]
        pid = (packet[1] & 0x1F) << 8 | packet[2]
        if pid == 0 and packet[1] & 0x40:
            section = packet[5:]
            pmt_pid = (section[10] & 0x1F) << 8 | section[11]
        if pmt_pid is None or pid != pmt_pid or not packet[1] & 0x40:
            continue
        pointer = packet[4]
        start = 5 + pointer
        length = (packet[start + 1] & 0x0F) << 8 | packet[start + 2]
        section = bytearray(packet[start:start + 3 + length - 4])
        info = (section[10] & 0x0F) << 8 | section[11]
        position = 12 + info
        while position + 5 <= len(section):
            stream_type = section[position]
            es_length = (section[position + 3] & 0x0F) << 8 | section[position + 4]
            if stream_type == 0x24:
                es_length += len(descriptor)
                section[position + 3] = 0xF0 | es_length >> 8
                section[position + 4] = es_length & 0xFF
                insert = position + 5 + es_length - len(descriptor)
                section[insert:insert] = descriptor
            position += 5 + es_length
        total = len(section) + 4 - 3
        section[1] = (section[1] & 0xF0) | total >> 8
        section[2] = total & 0xFF
        section += struct.pack(">I", crc32_section(bytes(section)))
        body = bytes(packet[:5]) + bytes(section)
        if len(body) > 188:
            raise SystemExit("the program map section no longer fits one packet")
        data[offset:offset + 188] = body + b"\xff" * (188 - len(body))
    if pmt_pid is None:
        raise SystemExit("no program map section")
    return bytes(data)


def samples(directory):
    hevc = os.path.join(directory, "clip.hevc")
    ffmpeg("-f", "lavfi", "-i", "testsrc2=size=160x96:rate=24:duration=" + str(FRAMES / 24), "-c:v", "libx265",
           "-preset", "ultrafast", "-crf", "36", "-g", "3", "-bf", "0", "-pix_fmt", "yuv420p10le",
           "-x265-params", "pools=1:frame-threads=1:log-level=error:colorprim=bt2020:transfer=arib-std-b67"
           ":colormatrix=bt2020nc", "-f", "hevc", hevc)
    stream = open(hevc, "rb").read()
    result = {}
    for name, profile5 in (("p84", False), ("p5", True)):
        annexb = os.path.join(directory, name + ".hevc")
        with open(annexb, "wb") as file:
            file.write(with_rpus(stream, profile5))
        mp4 = os.path.join(directory, name + ".mp4")
        ffmpeg("-f", "hevc", "-r", "24", "-i", annexb, "-c", "copy", "-tag:v", "hvc1", "-map_metadata", "-1", mp4)
        data = open(mp4, "rb").read()
        if profile5:
            data = signal_mp4(data, "dvcC", dovi_record(5, 1, 0), "dvh1")
        else:
            data = signal_mp4(data, "dvvC", dovi_record(8, 1, 4))
        result["mp4/dolby-vision-" + name + ".mp4"] = data
        with open(mp4, "wb") as file:
            file.write(data)
        if not profile5:
            mkv = os.path.join(directory, name + ".mkv")
            ffmpeg("-i", mp4, "-c", "copy", "-map_metadata", "-1", mkv)
            result["matroska/dolby-vision-" + name + ".mkv"] = open(mkv, "rb").read()
            ts = os.path.join(directory, name + ".ts")
            ffmpeg("-i", mp4, "-c", "copy", "-map_metadata", "-1", ts)
            result["ts/dolby-vision-" + name + ".ts"] = signal_ts(open(ts, "rb").read(), dovi_record(8, 1, 4))
    return result


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    repository = os.path.dirname(os.path.dirname(os.path.dirname(here)))
    output = sys.argv[1] if len(sys.argv) > 1 else os.path.join(repository, "core", "src", "test", "resources",
                                                                "samples")
    with tempfile.TemporaryDirectory() as scratch:
        for name, data in samples(scratch).items():
            path = os.path.join(output, name)
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as file:
                file.write(data)


if __name__ == "__main__":
    main()
