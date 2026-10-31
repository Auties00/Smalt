"""Makes the Musepack test vectors of audio/src/test/resources/.../audio/musepack and their expected decodes.

Every input is synthesized here (tones, chirps, seeded noise and silence, written as WAVE) and encoded with mpcenc
1.30.1 (musepack_src_r475), run only as a black-box encoder, at the quality and mid/side mode each vector exercises;
the encoder writes stream version 8. Each vector, and with --listed the Musepack files of the FFmpeg FATE suite and
samples archive that tools/testdata/vectors.tsv lists (stream versions 7 and 8), is decoded with
tools/testdata/musepack-oracle/smalt-mpcdec, a driver of libmpcdec r475 (BSD 3-Clause) in its floating-point
build, which records the MD5 of the raw float samples and their count per channel. The vectors are self-generated,
so they carry Smalt's license; the expected decodes are the reference library's.

Usage: python make-vectors.py <mpcenc> <smalt-mpcdec> <output directory> [--listed <directory of the listed files>]
Writes <name>.mpc and expected.tsv: name, MD5 of the raw float decode, samples per channel, channels.
"""
import hashlib
import math
import os
import random
import struct
import subprocess
import sys

# name, rate, channels, seconds, kind, encoder options
VECTORS = [
    ('stereo-standard', 44100, 2, 3, 'music', ['--standard']),
    ('stereo-telephone', 44100, 2, 3, 'music', ['--telephone']),
    ('stereo-braindead', 44100, 2, 2, 'music', ['--braindead']),
    ('stereo-thumb-ms0', 44100, 2, 2, 'music', ['--thumb', '--ms', '0']),
    ('stereo-radio-ms3', 44100, 2, 2, 'music', ['--radio', '--ms', '3']),
    ('stereo-pns', 44100, 2, 2, 'noise', ['--quality', '4', '--pns', '1']),
    ('mono-standard', 44100, 1, 3, 'music', ['--standard']),
    ('mono-48k', 48000, 1, 2, 'music', ['--extreme']),
    ('stereo-32k', 32000, 2, 2, 'music', ['--radio']),
    ('stereo-silence', 44100, 2, 1, 'silence', ['--standard']),
    ('stereo-short', 44100, 2, 0.01, 'music', ['--standard']),
    ('stereo-tagged', 44100, 2, 1, 'music', ['--standard', '--tag', 'Artist=Smalt Vectors', '--tag',
                                              'Title=Tagged Musepack']),
]


def frames_of(rate, seconds, channels, kind, seed):
    rng = random.Random(seed)
    out = []
    for i in range(max(1, int(rate * seconds))):
        t = i / rate
        frame = []
        for c in range(channels):
            if kind == 'silence':
                value = 0.0
            elif kind == 'noise':
                value = 0.3 * rng.uniform(-1, 1)
            else:
                value = (0.3 * math.sin(2 * math.pi * 220 * (c + 1) * t) + 0.2 * math.sin(2 * math.pi * (300 + 3000 * t)
                         * t) + 0.05 * rng.uniform(-1, 1))
            frame.append(value)
        out.append(frame)
    return out


def write_wave(path, rate, channels, frames):
    data = bytearray()
    for frame in frames:
        for value in frame:
            data += struct.pack('<h', max(-32768, min(32767, int(round(value * 32767)))))
    fmt = struct.pack('<HHIIHH', 1, channels, rate, rate * channels * 2, channels * 2, 16)
    body = b'WAVE' + b'fmt ' + struct.pack('<I', len(fmt)) + fmt + b'data' + struct.pack('<I', len(data)) + bytes(data)
    with open(path, 'wb') as f:
        f.write(b'RIFF' + struct.pack('<I', len(body)) + body)


def decode(oracle, path, work):
    raw = os.path.join(work, 'decoded.raw')
    result = subprocess.run([oracle, path, raw], check=True, capture_output=True, text=True)
    fields = result.stdout.split()
    with open(raw, 'rb') as f:
        return hashlib.md5(f.read()).hexdigest(), fields[0], fields[1]


def main():
    encoder, oracle, target = sys.argv[1], sys.argv[2], sys.argv[3]
    listed = sys.argv[sys.argv.index('--listed') + 1] if '--listed' in sys.argv else None
    os.makedirs(target, exist_ok=True)
    work = os.path.join(target, '.work')
    os.makedirs(work, exist_ok=True)
    rows = ['# name\tMD5 of the raw float decode\tsamples\tchannels']
    for seed, (name, rate, channels, seconds, kind, options) in enumerate(VECTORS):
        wav = os.path.join(work, name + '.wav')
        mpc = os.path.join(target, name + '.mpc')
        write_wave(wav, rate, channels, frames_of(rate, seconds, channels, kind, seed))
        subprocess.run([encoder, '--silent', '--overwrite'] + options + [wav, mpc], check=True)
        md5, samples, decoded_channels = decode(oracle, mpc, work)
        rows.append('%s\t%s\t%s\t%s' % (name, md5, samples, decoded_channels))
        os.remove(wav)
    with open(os.path.join(target, 'expected.tsv'), 'w', newline='\n') as f:
        f.write('\n'.join(rows) + '\n')
    if listed:
        for name in sorted(os.listdir(listed)):
            if name.endswith('.mpc'):
                md5, samples, decoded_channels = decode(oracle, os.path.join(listed, name), work)
                print('%s\t%s\t%s\t%s' % (name, md5, samples, decoded_channels))
    for leftover in os.listdir(work):
        os.remove(os.path.join(work, leftover))
    os.rmdir(work)


if __name__ == '__main__':
    main()
