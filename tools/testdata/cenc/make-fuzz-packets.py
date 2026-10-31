"""Makes the seeds of core's EncryptionFuzzTest.encryptedMux: small H.264, HEVC, VP9, AV1 and MPEG-4 Part 2 clips
made by ffmpeg from its synthetic sources (the VP9 clip with superframes, the AV1 clip with tiles, the H.264 clip with
several slices), as FuzzPackets inputs (core/src/test/java/.../testkit/fuzz/FuzzPackets.java): the options byte
(codec index | scheme << 3), width and height, the codec configuration (the avcC, hvcC or av1C record; none for VP9
and MPEG-4 Part 2, whose configuration the muxer derives from the first key frame), then each sample behind the
marker with its key frame flag.

usage: python -I make-fuzz-packets.py <output directory>
"""
import os
import struct
import subprocess
import sys
import tempfile

MARKER = b'FUZZ-TAG'
SOURCE = ['-f', 'lavfi', '-i', 'testsrc2=size=%s:rate=15:duration=0.4']
CLIPS = [
    # name, codec index, scheme, size, encoder arguments, configuration box
    ('h264-cenc', 0, 0, '64x48', ['-c:v', 'libx264', '-g', '4', '-bf', '1', '-x264-params', 'slices=2'], b'avcC'),
    ('h264-cbcs', 0, 3, '64x48', ['-c:v', 'libx264', '-g', '4', '-bf', '1'], b'avcC'),
    ('hevc-cens', 1, 2, '64x48', ['-c:v', 'libx265', '-tag:v', 'hvc1', '-x265-params', 'log-level=error'], b'hvcC'),
    ('vp9-cbc1', 2, 1, '64x48', ['-c:v', 'libvpx-vp9', '-b:v', '100k', '-auto-alt-ref', '1', '-lag-in-frames', '4'],
     None),
    ('vp9-cbcs', 2, 3, '64x48', ['-c:v', 'libvpx-vp9', '-b:v', '100k', '-auto-alt-ref', '1', '-lag-in-frames', '4'],
     None),
    ('av1-cenc', 3, 0, '128x64', ['-c:v', 'libaom-av1', '-cpu-used', '8', '-b:v', '100k', '-tile-columns', '1'],
     b'av1C'),
    ('av1-cbcs', 3, 3, '128x64', ['-c:v', 'libaom-av1', '-cpu-used', '8', '-b:v', '100k'], b'av1C'),
    ('mpeg4-cbcs', 4, 3, '64x48', ['-c:v', 'mpeg4', '-q:v', '6', '-bf', '1'], None),
]


def boxes(data, start, end):
    position = start
    while position + 8 <= end:
        size, kind = struct.unpack('>I4s', data[position:position + 8])
        if size < 8 or position + size > end:
            return
        yield kind, position, position + 8, position + size
        position += size


def child(data, start, end, kind):
    for name, _, body, finish in boxes(data, start, end):
        if name == kind:
            return body, finish
    return None


def samples(data):
    result = []
    for name, box, body, end in boxes(data, 0, len(data)):
        if name != b'moof':
            continue
        traf = child(data, body, end, b'traf')
        tfhd = child(data, traf[0], traf[1], b'tfhd')
        flags = int.from_bytes(data[tfhd[0] + 1:tfhd[0] + 4], 'big')
        position = tfhd[0] + 8 + (8 if flags & 1 else 0) + (4 if flags & 2 else 0) + (4 if flags & 8 else 0)
        default_size = struct.unpack('>I', data[position:position + 4])[0] if flags & 0x10 else None
        position += 4 if flags & 0x10 else 0
        default_flags = struct.unpack('>I', data[position:position + 4])[0] if flags & 0x20 else 0
        trun = child(data, traf[0], traf[1], b'trun')
        tflags = int.from_bytes(data[trun[0] + 1:trun[0] + 4], 'big')
        count = struct.unpack('>I', data[trun[0] + 4:trun[0] + 8])[0]
        position = trun[0] + 8
        offset = box
        if tflags & 1:
            offset += struct.unpack('>i', data[position:position + 4])[0]
            position += 4
        first_flags = None
        if tflags & 4:
            first_flags = struct.unpack('>I', data[position:position + 4])[0]
            position += 4
        for index in range(count):
            if tflags & 0x100:
                position += 4
            size = default_size
            if tflags & 0x200:
                size = struct.unpack('>I', data[position:position + 4])[0]
                position += 4
            sample_flags = default_flags
            if tflags & 0x400:
                sample_flags = struct.unpack('>I', data[position:position + 4])[0]
                position += 4
            elif index == 0 and first_flags is not None:
                sample_flags = first_flags
            if tflags & 0x800:
                position += 4
            result.append((data[offset:offset + size], not sample_flags & 0x10000))
            offset += size
    return result


def config(data, kind):
    found = data.find(kind)
    if found < 0:
        raise ValueError('no %r box' % kind)
    size = struct.unpack('>I', data[found - 4:found])[0]
    return data[found + 4:found - 4 + size]


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    with tempfile.TemporaryDirectory() as scratch:
        for name, codec, scheme, size, arguments, box in CLIPS:
            clip = os.path.join(scratch, name + '.mp4')
            source = list(SOURCE)
            source[3] = source[3] % size
            subprocess.run(['ffmpeg', '-v', 'error', '-y'] + source + arguments
                           + ['-an', '-movflags', '+frag_keyframe+empty_moov+default_base_moof', clip], check=True)
            data = open(clip, 'rb').read()
            width, height = (int(value) for value in size.split('x'))
            seed = bytearray([codec | scheme << 3]) + struct.pack('>HH', width, height)
            if box is not None:
                seed += config(data, box)
            for sample, keyframe in samples(data):
                seed += MARKER + bytes([1 if keyframe else 0]) + sample
            with open(os.path.join(out, 'valid-' + name), 'wb') as file:
                file.write(seed)
            print(name, len(seed))


main()
