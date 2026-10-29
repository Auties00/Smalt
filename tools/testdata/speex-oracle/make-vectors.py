"""Makes the Speex test vectors of audio/src/test/resources/com/github/auties00/smalt/audio/speex.

The inputs are synthesized by ffmpeg (a chirp, a sine with noise, and silence), encoded by ffmpeg's libspeex encoder
(libspeex 1.2) in the mode, quality, bit rate control and packing each vector exercises, in Ogg, and decoded by
smalt-speexdec (this directory: the fixed-point libspeex 1.2.1 decoder, BSD 3-Clause) from the packets this script
extracts from the Ogg pages. The MD5 of every decode goes to expected.tsv, with the decode of the same packets with
every third packet lost.

Usage: python make-vectors.py <smalt-speexdec> <ffmpeg> <output directory>
"""
import hashlib
import os
import struct
import subprocess
import sys

SOURCES = {
    'chirp': ['-f', 'lavfi', '-i', 'aevalsrc=0.6*sin(2*PI*(100+3000*t)*t)+0.05*random(1)-0.025:d=0.6'],
    'speechlike': ['-f', 'lavfi', '-i',
                   'aevalsrc=0.5*sin(2*PI*180*t)*(0.6+0.4*sin(2*PI*3*t))+0.2*sin(2*PI*900*t)*sin(2*PI*5*t)'
                   '+0.03*random(2)-0.015:d=0.6'],
    'stereo': ['-f', 'lavfi', '-i', 'aevalsrc=0.5*sin(2*PI*300*t)|0.3*sin(2*PI*500*t)+0.2*sin(2*PI*71*t):d=0.6'],
    'gap': ['-f', 'lavfi', '-i', 'aevalsrc=if(between(t\\,0.2\\,0.4)\\,0\\,0.4*sin(2*PI*440*t)):d=0.6'],
}

VECTORS = [
    # name, source, rate, channels, encoder options
    ('nb-q0', 'speechlike', 8000, 1, ['-cbr_quality', '0']),
    ('nb-q1', 'speechlike', 8000, 1, ['-cbr_quality', '1']),
    ('nb-q2', 'speechlike', 8000, 1, ['-cbr_quality', '2']),
    ('nb-q4', 'chirp', 8000, 1, ['-cbr_quality', '4']),
    ('nb-q6', 'speechlike', 8000, 1, ['-cbr_quality', '6']),
    ('nb-q8', 'chirp', 8000, 1, ['-cbr_quality', '8']),
    ('nb-q9', 'chirp', 8000, 1, ['-cbr_quality', '9']),
    ('nb-q10', 'speechlike', 8000, 1, ['-cbr_quality', '10']),
    ('nb-vbr', 'speechlike', 8000, 1, ['-q:a', '5']),
    ('nb-vad-dtx', 'gap', 8000, 1, ['-cbr_quality', '5', '-vad', '1', '-dtx', '1']),
    ('nb-3frames', 'chirp', 8000, 1, ['-cbr_quality', '3', '-frames_per_packet', '3']),
    ('nb-stereo', 'stereo', 8000, 2, ['-cbr_quality', '6']),
    ('wb-q0', 'speechlike', 16000, 1, ['-cbr_quality', '0']),
    ('wb-q1', 'chirp', 16000, 1, ['-cbr_quality', '1']),
    ('wb-q3', 'chirp', 16000, 1, ['-cbr_quality', '3']),
    ('wb-q5', 'speechlike', 16000, 1, ['-cbr_quality', '5']),
    ('wb-q8', 'chirp', 16000, 1, ['-cbr_quality', '8']),
    ('wb-q9', 'speechlike', 16000, 1, ['-cbr_quality', '9']),
    ('wb-q10', 'speechlike', 16000, 1, ['-cbr_quality', '10']),
    ('wb-vbr', 'chirp', 16000, 1, ['-q:a', '7']),
    ('wb-vad-dtx', 'gap', 16000, 1, ['-cbr_quality', '6', '-vad', '1', '-dtx', '1']),
    ('wb-stereo', 'stereo', 16000, 2, ['-cbr_quality', '8', '-frames_per_packet', '2']),
    ('uwb-q0', 'speechlike', 32000, 1, ['-cbr_quality', '0']),
    ('uwb-q2', 'chirp', 32000, 1, ['-cbr_quality', '2']),
    ('uwb-q6', 'speechlike', 32000, 1, ['-cbr_quality', '6']),
    ('uwb-q10', 'chirp', 32000, 1, ['-cbr_quality', '10']),
    ('uwb-vbr', 'speechlike', 32000, 1, ['-q:a', '8']),
    ('uwb-stereo', 'stereo', 32000, 2, ['-cbr_quality', '7']),
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


def main():
    oracle, ffmpeg, out = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out, exist_ok=True)
    rows = []
    for name, source, rate, channels, options in VECTORS:
        target = os.path.join(out, name + '.spx')
        subprocess.run([ffmpeg, '-v', 'error', '-y'] + SOURCES[source] + ['-ar', str(rate), '-ac', str(channels),
                       '-c:a', 'libspeex'] + options + ['-f', 'ogg', target], check=True)
        with open(target, 'rb') as f:
            packets = ogg_packets(f.read())
        header = packets[0]
        assert header[:8] == b'Speex   '
        mode = struct.unpack_from('<i', header, 40)[0]
        frames = struct.unpack_from('<i', header, 64)[0]
        extra = struct.unpack_from('<i', header, 68)[0]
        audio = packets[2 + extra:]
        for suffix, lost in (('', set()), ('#lossy', {i for i in range(len(audio)) if i % 3 == 2})):
            listing = os.path.join(out, name + '.packets')
            with open(listing, 'wb') as f:
                for index, packet in enumerate(audio):
                    if index in lost:
                        f.write(struct.pack('<I', 0xffffffff))
                    else:
                        f.write(struct.pack('<I', len(packet)) + packet)
            raw = os.path.join(out, name + '.raw')
            subprocess.run([oracle, str(mode), str(max(frames, 1)), str(channels), '1', listing, raw], check=True)
            with open(raw, 'rb') as f:
                rows.append((name + suffix, hashlib.md5(f.read()).hexdigest()))
            os.remove(listing)
            os.remove(raw)
    with open(os.path.join(out, 'expected.tsv'), 'w', newline='\n') as f:
        f.write('# name (#lossy: every third packet lost)\tMD5 of the 16-bit little-endian decode\n')
        for row in rows:
            f.write('\t'.join(row) + '\n')


if __name__ == '__main__':
    main()
