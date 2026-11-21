"""Writes the HRTF data sets of the ISO/IEC 23003-3 reference software's sac_hrtf.c as the resource of Smalt's binaural
MPEG Surround rendering: hrtftable.py <sac_hrtf.c> <mps-hrtf.bin.gz>

For each of the sets Kemar, Vast and MPSVT the resource holds, in big-endian, the 32-bit impulse response length and
reverb length, then for each of the five directions (left front, right front, center, left surround, right surround)
28 floats each of the left and right powers, the ICCs and the IPDs and the left and right impulse responses, and last
the left and right reverb responses.
"""
import gzip
import re
import struct
import sys

NUMBER = r'[-+]?\d+\.\d*(?:[eE][-+]?\d+)?f?'
SETS = ('Kemar', 'Vast', 'MPSVT')
BANDS = 28


def floats(text):
    return [float(token.rstrip('f')) for token in re.findall(NUMBER, text)]


def array(source, name):
    start = source.index('static const float ' + name + '[]')
    start = source.index('{', start)
    return floats(source[start:source.index('}', start)])


def data_set(source, name):
    start = source.index('const HRTF_DATA SpatialDec' + name + 'HRTF')
    body = source[source.index('{', start):source.index('};', start)]
    header = re.match(r'\{\s*(\d+),\s*(\d+),', body)
    if int(header.group(1)) != 44100:
        raise SystemExit(name + ' is not a 44.1 kHz set')
    length = int(header.group(2))
    groups = re.findall(r'\{\s*(' + NUMBER + r'(?:\s*,\s*' + NUMBER + r')*)\s*,?\s*\}', body)
    values = [floats(group) for group in groups]
    if len(values) != 20 or any(len(value) != BANDS for value in values):
        raise SystemExit(name + ': expected 20 tables of 28 bands, found ' + str([len(value) for value in values]))
    out = bytearray()
    reverb = re.search(r',\s*(\d+),\s*(\w+),\s*(\w+)\s*\}?\s*$', body.strip())
    reverb_length = int(reverb.group(1))
    out += struct.pack('>ii', length, reverb_length)
    for direction in range(5):
        for table in values[4 * direction:4 * direction + 4]:
            out += struct.pack('>28f', *table)
        for side in ('Left', 'Right'):
            impulse = array(source, name + 'Impulse' + side + str(direction + 1))
            if len(impulse) != length:
                raise SystemExit(name + ' impulse has ' + str(len(impulse)) + ' values, expected ' + str(length))
            out += struct.pack('>%df' % length, *impulse)
    if reverb_length:
        for side in ('Left', 'Right'):
            response = array(source, name + 'Reverb' + side)
            if len(response) != reverb_length:
                raise SystemExit(name + ' reverb has ' + str(len(response)) + ' values')
            out += struct.pack('>%df' % reverb_length, *response)
    return bytes(out)


def main():
    source = open(sys.argv[1], encoding='latin-1').read()
    data = b''.join(data_set(source, name) for name in SETS)
    with open(sys.argv[2], 'wb') as out:
        out.write(gzip.compress(data, 9, mtime=0))
    print(len(data), 'bytes')


if __name__ == '__main__':
    main()
