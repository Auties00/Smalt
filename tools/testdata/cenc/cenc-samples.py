"""Lists the samples of the first track of fragmented MP4 files, for the Common Encryption golden file of core's
CencVideoGoldenTest: per sample its size, the MD5 of its bytes and, when the fragment has a senc box, its
initialization vector ("-" for a constant one, which the tenc box holds) and subsample map as clear:protected pairs
(or "whole" for a sample encrypted whole). The
first argument names the listing in the output; the files are listed in the order given, samples numbered across
them.

usage: python -I cenc-samples.py <name> <fragmented MP4>...
"""
import hashlib
import struct
import sys


def boxes(data, start, end):
    position = start
    while position + 8 <= end:
        size, kind = struct.unpack('>I4s', data[position:position + 8])
        header = 8
        if size == 1:
            size = struct.unpack('>Q', data[position + 8:position + 16])[0]
            header = 16
        elif size == 0:
            size = end - position
        if size < header or position + size > end:
            raise ValueError('box %r at %d overruns its parent' % (kind, position))
        yield kind.decode('latin-1'), position, position + header, position + size
        position += size


def child(data, start, end, kind):
    for name, _, body, finish in boxes(data, start, end):
        if name == kind:
            return body, finish
    return None


def fragments(data):
    # the per-sample IV size of the track encryption box: version, flags, two reserved or pattern bytes, then
    # default_isProtected and default_Per_Sample_IV_Size
    tenc = data.find(b'tenc')
    iv_size = data[tenc + 4 + 4 + 2 + 1] if tenc >= 0 else 0
    for name, box, body, end in boxes(data, 0, len(data)):
        if name != 'moof':
            continue
        traf = child(data, body, end, 'traf')
        tfhd = child(data, traf[0], traf[1], 'tfhd')
        flags = int.from_bytes(data[tfhd[0] + 1:tfhd[0] + 4], 'big')
        position = tfhd[0] + 8
        base = box
        if flags & 1:
            base = struct.unpack('>Q', data[position:position + 8])[0]
            position += 8
        if flags & 2:
            position += 4
        if flags & 8:
            position += 4
        default_size = None
        if flags & 0x10:
            default_size = struct.unpack('>I', data[position:position + 4])[0]
        trun = child(data, traf[0], traf[1], 'trun')
        tflags = int.from_bytes(data[trun[0] + 1:trun[0] + 4], 'big')
        count = struct.unpack('>I', data[trun[0] + 4:trun[0] + 8])[0]
        position = trun[0] + 8
        offset = base
        if tflags & 1:
            offset += struct.unpack('>i', data[position:position + 4])[0]
            position += 4
        if tflags & 4:
            position += 4
        sizes = []
        for _ in range(count):
            if tflags & 0x100:
                position += 4
            if tflags & 0x200:
                sizes.append(struct.unpack('>I', data[position:position + 4])[0])
                position += 4
            else:
                sizes.append(default_size)
            if tflags & 0x400:
                position += 4
            if tflags & 0x800:
                position += 4
        senc = child(data, traf[0], traf[1], 'senc')
        entries = [None] * count
        if senc is not None:
            sflags = int.from_bytes(data[senc[0] + 1:senc[0] + 4], 'big')
            position = senc[0] + 8
            entries = []
            for _ in range(count):
                iv = data[position:position + iv_size].hex()
                position += iv_size
                pairs = None
                if sflags & 2:
                    subsamples = struct.unpack('>H', data[position:position + 2])[0]
                    position += 2
                    pairs = []
                    for _ in range(subsamples):
                        clear, protected = struct.unpack('>HI', data[position:position + 6])
                        position += 6
                        pairs.append('%d:%d' % (clear, protected))
                entries.append((iv, pairs))
            if position != senc[1]:
                raise ValueError('the senc entries do not fill the box')
        for size, entry in zip(sizes, entries):
            yield data[offset:offset + size], entry
            offset += size


def main():
    name = sys.argv[1]
    number = 0
    for path in sys.argv[2:]:
        for sample, entry in fragments(open(path, 'rb').read()):
            line = '%s %d %d %s' % (name, number, len(sample), hashlib.md5(sample).hexdigest())
            if entry is not None:
                iv, pairs = entry
                line += ' iv=%s %s' % (iv or '-', ','.join(pairs) if pairs is not None else 'whole')
            print(line)
            number += 1


main()
