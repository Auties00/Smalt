"""Makes the Theora test vectors of video/src/test/resources/com/github/auties00/smalt/video/theora.

The inputs are synthesized by ffmpeg (test patterns, a scrolling pattern for motion vectors, noise) and encoded by
ffmpeg's libtheora encoder (libtheora 1.1 or later) in Ogg, in the pixel format, size, quality or bit rate and key
frame interval each vector exercises. smalt-theoradec (this directory: libtheora 1.2.0's decoder, BSD 3-Clause, with
post-processing off) decodes the packets this script extracts from the Ogg pages; expected.tsv records the MD5 of
each decoded frame's picture region (Y, Cb, Cr, top row first), also for two altered packet sequences: '#dup' inserts
an empty packet (a dropped frame) after every third data packet, and '#nokey' drops the first data packet, so that
the inter frames before the next key frame predict from libtheora's mid-gray reference frames.

Build smalt-theoradec from libtheora's lib/ (bitpack, decinfo, decode, dequant, fragment, huffdec, idct, info,
internal, quant and state) and smalt-theoradec.c, with an ogg/ogg.h that defines ogg_packet, the ogg_int* types and
_ogg_malloc, _ogg_calloc, _ogg_realloc and _ogg_free as the C library's allocation functions.

Usage: python -I make-vectors.py <smalt-theoradec> <ffmpeg> <output directory>
"""
import hashlib
import os
import struct
import subprocess
import sys

SOURCES = {
    'pattern': ['-f', 'lavfi', '-i', 'testsrc2=size={size}:rate=10'],
    'scroll': ['-f', 'lavfi', '-i', 'testsrc2=size=320x240:rate=10,scroll=h=0.013:v=0.007,crop={size}:7:5'],
    'zoom': ['-f', 'lavfi', '-i', 'mandelbrot=size={size}:rate=10'],
    'noise': ['-f', 'lavfi', '-i', 'testsrc2=size={size}:rate=10,noise=alls=60:allf=t+u'],
}

VECTORS = [
    # name, source, size, pixel format, frames, encoder options
    ('420-q5', 'pattern', '96x64', 'yuv420p', 12, ['-q:v', '5', '-g', '6']),
    ('420-q0', 'scroll', '96x64', 'yuv420p', 12, ['-q:v', '0', '-g', '12']),
    ('420-q10', 'zoom', '96x64', 'yuv420p', 8, ['-q:v', '10', '-g', '4']),
    ('420-odd', 'scroll', '90x54', 'yuv420p', 10, ['-q:v', '6', '-g', '10']),
    ('420-bitrate', 'scroll', '128x96', 'yuv420p', 16, ['-b:v', '150k', '-g', '30']),
    ('420-noise', 'noise', '64x48', 'yuv420p', 6, ['-q:v', '9', '-g', '3']),
    ('420-motion', 'scroll', '160x112', 'yuv420p', 20, ['-q:v', '7', '-g', '100']),
    ('422-q6', 'scroll', '96x64', 'yuv422p', 10, ['-q:v', '6', '-g', '5']),
    ('422-odd', 'zoom', '70x50', 'yuv422p', 8, ['-q:v', '4', '-g', '8']),
    ('444-q6', 'scroll', '96x64', 'yuv444p', 10, ['-q:v', '6', '-g', '5']),
    ('444-bitrate', 'noise', '80x48', 'yuv444p', 8, ['-b:v', '300k', '-g', '8']),
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


def frame_size(headers):
    identification = headers[0]
    width = struct.unpack_from('>I', b'\0' + identification[14:17])[0]
    height = struct.unpack_from('>I', b'\0' + identification[17:20])[0]
    pixel_format = identification[41] >> 3 & 3
    shift_x = 0 if pixel_format == 3 else 1
    shift_y = 1 if pixel_format == 0 else 0
    chroma = ((width + shift_x) >> shift_x) * ((height + shift_y) >> shift_y)
    return width * height + 2 * chroma


def decode(oracle, headers, packets, work, name):
    listing = os.path.join(work, name + '.packets')
    with open(listing, 'wb') as f:
        for packet in headers + packets:
            f.write(struct.pack('<I', len(packet)) + packet)
    raw = os.path.join(work, name + '.raw')
    subprocess.run([oracle, listing, raw], check=True)
    with open(raw, 'rb') as f:
        data = f.read()
    os.remove(listing)
    os.remove(raw)
    size = frame_size(headers)
    assert len(data) == size * len(packets), (name, len(data), size, len(packets))
    return [hashlib.md5(data[i:i + size]).hexdigest() for i in range(0, len(data), size)]


def main():
    oracle, ffmpeg, out = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out, exist_ok=True)
    rows = []
    for name, source, size, pixel_format, frames, options in VECTORS:
        target = os.path.join(out, name + '.ogv')
        inputs = [argument.replace('{size}', size.replace('x', ':') if 'crop=' in argument else size)
                  for argument in SOURCES[source]]
        subprocess.run([ffmpeg, '-v', 'error', '-y'] + inputs + ['-frames:v', str(frames), '-pix_fmt', pixel_format,
                       '-c:v', 'libtheora'] + options + ['-f', 'ogg', target], check=True)
        with open(target, 'rb') as f:
            packets = ogg_packets(f.read())
        headers, data = packets[:3], packets[3:]
        assert [h[0] for h in headers] == [0x80, 0x81, 0x82]
        dup = []
        for index, packet in enumerate(data):
            dup.append(packet)
            if index % 3 == 2:
                dup.append(b'')
        for suffix, sequence in (('', data), ('#dup', dup), ('#nokey', data[1:])):
            rows.append((name + suffix, ' '.join(decode(oracle, headers, sequence, out, name))))
    with open(os.path.join(out, 'expected.tsv'), 'w', newline='\n') as f:
        f.write('# name (#dup: an empty packet after every third data packet; #nokey: the first data packet '
                'dropped)\tMD5 of every decoded picture (Y, Cb, Cr planes, top row first)\n')
        for row in rows:
            f.write('\t'.join(row) + '\n')


if __name__ == '__main__':
    main()
