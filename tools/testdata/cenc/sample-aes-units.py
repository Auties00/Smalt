"""Lists the units of the elementary streams of MPEG-2 transport streams, for the SAMPLE-AES golden file of core's
SampleAesGoldenTest: per H.264 slice (NAL unit types 1 and 5) its length and MD5 as carried, emulation prevention
bytes included; per ADTS frame the length and MD5 of what follows its header; per AC-3 or E-AC-3 syncframe its
length and MD5. The streams are taken by stream_type, clear or SAMPLE-AES, in the order of their PIDs.

usage: python -I sample-aes-units.py <transport stream>...
"""
import hashlib
import sys

KINDS = {0x1B: 'h264', 0xDB: 'h264', 0x0F: 'aac', 0xCF: 'aac', 0x81: 'ac3', 0xC1: 'ac3', 0x87: 'eac3', 0xC2: 'eac3'}
AC3_KBPS = [32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640]


def streams(data):
    pmt = None
    kinds = {}
    units = {}
    for offset in range(0, len(data) - 187, 188):
        packet = data[offset:offset + 188]
        if packet[0] != 0x47:
            raise ValueError('lost sync at %d' % offset)
        pid = ((packet[1] & 0x1F) << 8) | packet[2]
        start = packet[1] & 0x40
        control = (packet[3] >> 4) & 3
        position = 4
        if control in (2, 3):
            position += 1 + packet[4]
        if control == 2 or position >= 188:
            continue
        payload = packet[position:]
        if pid == 0 and start:
            section = payload[1 + payload[0]:]
            pmt = ((section[10] & 0x1F) << 8) | section[11]
        elif pid == pmt and start:
            section = payload[1 + payload[0]:]
            end = 3 + (((section[1] & 0x0F) << 8) | section[2]) - 4
            index = 12 + (((section[10] & 0x0F) << 8) | section[11])
            while index < end:
                if section[index] in KINDS:
                    kinds[((section[index + 1] & 0x1F) << 8) | section[index + 2]] = KINDS[section[index]]
                index += 5 + (((section[index + 3] & 0x0F) << 8) | section[index + 4])
        elif pid in kinds:
            if start:
                units.setdefault(pid, []).append(bytearray())
            if pid in units:
                units[pid][-1] += payload
    result = []
    for pid in sorted(units):
        stream = b''.join(bytes(unit[9 + unit[8]:]) for unit in units[pid])
        result.append((kinds[pid], stream))
    return result


def slices(stream):
    starts = []
    index = 0
    while index < len(stream) - 3:
        if stream[index] == 0 and stream[index + 1] == 0 and stream[index + 2] == 1:
            starts.append(index + 3)
            index += 3
        else:
            index += 1
    for number, start in enumerate(starts):
        end = starts[number + 1] - 3 if number + 1 < len(starts) else len(stream)
        # the zero byte of a four-byte start code belongs to the next unit; an escaped unit never ends with zero
        while end > start and stream[end - 1] == 0:
            end -= 1
        if stream[start] & 0x1F in (1, 5):
            yield stream[start:end]


def frames(kind, stream):
    position = 0
    while position < len(stream):
        if kind == 'aac':
            header = 7 if stream[position + 1] & 1 else 9
            length = ((stream[position + 3] & 3) << 11) | (stream[position + 4] << 3) | (stream[position + 5] >> 5)
            yield stream[position + header:position + length]
        else:
            if stream[position] != 0x0B or stream[position + 1] != 0x77:
                raise ValueError('lost the %s sync at %d' % (kind, position))
            if kind == 'ac3':
                if stream[position + 4] >> 6 != 0:
                    raise ValueError('only 48 kHz AC-3 is listed')
                length = 4 * AC3_KBPS[(stream[position + 4] & 0x3F) >> 1]
            else:
                length = 2 * ((((stream[position + 2] & 7) << 8) | stream[position + 3]) + 1)
            yield stream[position:position + length]
        position += length


def main():
    for path in sys.argv[1:]:
        for kind, stream in streams(open(path, 'rb').read()):
            units = slices(stream) if kind == 'h264' else frames(kind, stream)
            for number, unit in enumerate(units):
                print(kind, number, len(unit), hashlib.md5(unit).hexdigest())


main()
