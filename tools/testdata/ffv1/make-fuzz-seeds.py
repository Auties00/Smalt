"""Makes the seeds of video's VideoFuzzTest.ffv1 as FuzzPackets inputs
(core/src/test/java/.../testkit/fuzz/FuzzPackets.java): ffmpeg 7.1 encodes a few testsrc2 frames with its FFV1
encoder into AVI, and each seed holds the options byte (bit 0: high-bit-depth output), the frame size, the
configuration record of a version 3 stream (the strf bytes after the BITMAPINFOHEADER; none for versions 0 and 1),
then the frames behind the marker with their key frame flags. Every stream has a key frame interval of 2, so the
seeds hold frames that continue the context states of the previous one.

Usage: python -I make-fuzz-seeds.py <scratch directory> <output directory>
"""
import os
import struct
import subprocess
import sys

MARKER = b'FUZZ-TAG'
SEEDS = [
    # seed name, options byte, width, height, pixel format, ffmpeg encoder options
    ('valid-v0-golomb-420.bin', 0, 32, 24, 'yuv420p', ['-level', '0', '-coder', '0']),
    ('valid-v1-range-422-10bit.bin', 1, 32, 24, 'yuv422p10le', ['-level', '1', '-coder', '1', '-context', '1']),
    ('valid-v3-slices-crc-444.bin', 0, 48, 32, 'yuv444p', ['-level', '3', '-coder', '1', '-slices', '4',
                                                            '-slicecrc', '1']),
    ('valid-v3-custom-states-rgba.bin', 1, 32, 32, 'bgra', ['-level', '3', '-coder', '2', '-slices', '4',
                                                            '-slicecrc', '1']),
    ('valid-v3-gray16.bin', 1, 32, 16, 'gray16le', ['-level', '3', '-coder', '1', '-slices', '4', '-slicecrc',
                                                    '0']),
]


def chunks(data, start, end):
    """Yields the (id, offset, size) of the RIFF chunks between two offsets, lists included."""
    position = start
    while position + 8 <= end:
        ident = data[position:position + 4]
        size = struct.unpack_from('<I', data, position + 4)[0]
        yield ident, position + 8, size
        position += 8 + size + (size & 1)


def avi_stream(data):
    """Returns the codec configuration after the BITMAPINFOHEADER and the frames of the first video stream."""
    config, frames = None, []

    def walk(start, end):
        nonlocal config
        for ident, offset, size in chunks(data, start, end):
            if ident == b'LIST':
                walk(offset + 4, offset + size)
            elif ident == b'strf' and config is None:
                # biSize counts the extradata too, so the bitmap header is taken as its fixed 40 bytes
                config = data[offset + 40:offset + size]
            elif ident[2:] in (b'dc', b'db') and ident[:2] == b'00':
                frames.append(data[offset:offset + size])

    walk(12, len(data))
    return config, frames


def main():
    scratch, out = sys.argv[1], sys.argv[2]
    os.makedirs(scratch, exist_ok=True)
    os.makedirs(out, exist_ok=True)
    for name, options, width, height, pixel_format, encoder in SEEDS:
        path = os.path.join(scratch, name[:-4] + '.avi')
        subprocess.run(['ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i',
                        'testsrc2=size=%dx%d:rate=25:duration=0.12' % (width, height), '-pix_fmt', pixel_format,
                        '-c:v', 'ffv1', '-g', '2'] + encoder + [path], check=True)
        with open(path, 'rb') as f:
            config, frames = avi_stream(f.read())
        assert frames, name
        level = encoder[encoder.index('-level') + 1]
        if level != '3':
            config = b''
        seed = bytearray(struct.pack('>BHH', options, width, height)) + config
        for index, frame in enumerate(frames):
            assert MARKER not in frame
            seed += MARKER + bytes([1 if index % 2 == 0 else 0]) + frame
        with open(os.path.join(out, name), 'wb') as f:
            f.write(seed)


if __name__ == '__main__':
    main()
