"""Regenerates the 32-bit ALAC test vectors of smalt-audio with Apple's ALAC reference encoder.

The vectors are the CAF files of alacconvert, the converter of Apple's open-source ALAC codec
(github.com/macosforge/alac, Apache License 2.0, commit c38887c5c5e64a4b31108733bd79ca9b2496d987), which is the only
available encoder of 32-bit ALAC (ffmpeg's codes at most 24 bits). Each source is a deterministic 32-bit WAV
(sines near full scale plus noise in the low 20 bits, and runs of the extreme values); alacconvert encodes it to CAF
and decodes the CAF back, and the script checks that the decode equals the source and, when ffmpeg is on PATH, that
ffmpeg's ALAC decoder agrees. apple-md5.csv holds the MD5 of the source samples, interleaved little-endian in four
bytes each, which a lossless decode must reproduce; apple.sha256 pins the files.

Usage: python -I make-vectors.py <alacconvert> <smalt checkout> [<work directory>]
alacconvert is built from the pinned commit by compiling codec/*.c with gcc, codec/*.cpp and convert-utility/*.cpp
with g++ (-O2 -Icodec) and linking the objects into one executable.
"""
import hashlib
import math
import os
import random
import shutil
import struct
import subprocess
import sys
import tempfile

VECTORS = [
    # name, channels, sample rate, frames, seed
    ('alac32-stereo', 2, 48000, 9000, 1),
    ('alac32-mono', 1, 44100, 7000, 2),
]


def signal(channels, frames, seed):
    rng = random.Random(seed)
    planes = []
    for c in range(channels):
        plane = []
        phase = rng.random() * 6
        for i in range(frames):
            if (i // 1024) % 4 == 3:
                value = 2147483647 if rng.getrandbits(1) else -2147483648
            else:
                tone = 0.6 * math.sin(phase + i * (0.011 + 0.003 * c)) + 0.3 * math.sin(i * 0.067 + c)
                value = int(round(tone * 2147483647)) + rng.getrandbits(20) - (1 << 19)
                value = max(-2147483648, min(2147483647, value))
            plane.append(value)
        planes.append(plane)
    return planes


def interleaved(planes):
    frames = len(planes[0])
    return b''.join(struct.pack('<' + 'i' * len(planes), *(plane[i] for plane in planes)) for i in range(frames))


def write_wav(path, planes, rate):
    data = interleaved(planes)
    channels = len(planes)
    fmt = struct.pack('<HHIIHH', 1, channels, rate, rate * channels * 4, channels * 4, 32)
    with open(path, 'wb') as out:
        out.write(b'RIFF' + struct.pack('<I', 4 + 8 + len(fmt) + 8 + len(data)) + b'WAVE')
        out.write(b'fmt ' + struct.pack('<I', len(fmt)) + fmt)
        out.write(b'data' + struct.pack('<I', len(data)) + data)


def wav_data(path):
    with open(path, 'rb') as source:
        raw = source.read()
    position = 12
    while position + 8 <= len(raw):
        kind = raw[position:position + 4]
        size = struct.unpack('<I', raw[position + 4:position + 8])[0]
        if kind == b'data':
            return raw[position + 8:position + 8 + size]
        position += 8 + size + (size & 1)
    sys.exit('no data chunk in ' + path)


def main():
    alacconvert, root = sys.argv[1], sys.argv[2]
    work = sys.argv[3] if len(sys.argv) > 3 else tempfile.mkdtemp()
    out = os.path.join(root, 'audio', 'src', 'test', 'resources', 'com', 'github', 'auties00', 'smalt', 'audio',
                       'alac', 'apple')
    if os.path.isdir(out):
        shutil.rmtree(out)
    os.makedirs(out)
    rows = ['# file;channels;sample rate;frames;md5 of the source samples, interleaved 32-bit little-endian',
            '# made by tools/testdata/alac-oracle/make-vectors.py with alacconvert (Apple ALAC c38887c)']
    digests = []
    ffmpeg = shutil.which('ffmpeg')
    for name, channels, rate, frames, seed in VECTORS:
        planes = signal(channels, frames, seed)
        source = os.path.join(work, name + '.wav')
        back = os.path.join(work, name + '-decoded.wav')
        caf = os.path.join(out, name + '.caf')
        write_wav(source, planes, rate)
        subprocess.run([alacconvert, source, caf], check=True, stdout=subprocess.DEVNULL)
        subprocess.run([alacconvert, caf, back], check=True, stdout=subprocess.DEVNULL)
        expected = interleaved(planes)
        if wav_data(back) != expected:
            sys.exit(name + ': alacconvert does not decode its own encode to the source')
        if ffmpeg:
            decoded = subprocess.run([ffmpeg, '-hide_banner', '-loglevel', 'error', '-i', caf, '-f', 's32le', '-'],
                                     check=True, stdout=subprocess.PIPE).stdout
            if decoded != expected:
                sys.exit(name + ': ffmpeg decodes the CAF differently')
        rows.append('%s.caf;%d;%d;%d;%s' % (name, channels, rate, frames, hashlib.md5(expected).hexdigest()))
        with open(caf, 'rb') as written:
            digests.append('%s  %s.caf' % (hashlib.sha256(written.read()).hexdigest(), name))
    with open(os.path.join(out, 'apple-md5.csv'), 'w', newline='\n') as csv:
        csv.write('\n'.join(rows) + '\n')
    with open(out + '.sha256', 'w', newline='\n') as sums:
        sums.write('\n'.join(digests) + '\n')


main()
