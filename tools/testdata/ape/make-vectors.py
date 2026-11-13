"""Makes the Monkey's Audio test vectors of audio/src/test/resources/.../audio/ape and their expected decodes.

Every input is synthesized here (sines, chirps, seeded noise, silence and identical channels, written as WAVE),
encoded with the Monkey's Audio 13.45 command line tool (MAC, BSD 3-Clause, built from MAC_1345_SDK.zip) at the
compression level each vector exercises, and decoded back with the same tool to record the MD5 of the reference
decode (the bytes of its WAVE data chunk). The vectors are self-generated, so they carry Smalt's license; the
expected decodes are the reference library's. With --fate, the files of the FFmpeg FATE samples that older Monkey's
Audio versions wrote (fetched by the tests through tools/testdata/vectors.tsv) are decoded too, frame by frame up
to the first frame the truncated excerpts cut, with tools/testdata/ape-oracle/smalt-apedec (built against the same
SDK), and their sample counts and MD5s printed for ApeDecoderTest.

Usage: python make-vectors.py <MAC executable> <output directory> [--fate <directory of the FATE .ape files>
       <smalt-apedec executable>]
Writes <name>.ape and expected.tsv: name, MD5 of the decode, bits per sample, "float" for floating-point vectors.
"""
import hashlib
import math
import os
import random
import struct
import subprocess
import sys

# name, rate, channels, bits, floating, seconds, level, kind
VECTORS = [
    ('s16-stereo-c1000', 8000, 2, 16, False, 4, 1000, 'tone'),
    ('s16-stereo-c2000', 8000, 2, 16, False, 4, 2000, 'tone'),
    ('s16-stereo-c3000', 8000, 2, 16, False, 4, 3000, 'tone'),
    ('s16-stereo-c4000', 8000, 2, 16, False, 4, 4000, 'tone'),
    ('s16-stereo-c5000', 8000, 2, 16, False, 4, 5000, 'tone'),
    ('s16-mono-long-c2000', 8000, 1, 16, False, 38, 2000, 'quiet'),
    ('s16-stereo-loud-c5000', 8000, 2, 16, False, 0.5, 5000, 'loud'),
    ('s16-stereo-loud-c1000', 8000, 2, 16, False, 0.5, 1000, 'loud'),
    ('s16-mono-c2000', 11025, 1, 16, False, 3, 2000, 'tone'),
    ('s8-stereo-c2000', 8000, 2, 8, False, 3, 2000, 'tone'),
    ('s8-mono-c3000', 8000, 1, 8, False, 3, 3000, 'tone'),
    ('s8-4ch-c1000', 8000, 4, 8, False, 2, 1000, 'tone'),
    ('s24-stereo-c4000', 8000, 2, 24, False, 1, 4000, 'tone'),
    ('s24-mono-c2000', 8000, 1, 24, False, 1, 2000, 'tone'),
    ('s24-stereo-loud-c3000', 8000, 2, 24, False, 0.5, 3000, 'loud'),
    ('s32-stereo-c3000', 8000, 2, 32, False, 1, 3000, 'tone'),
    ('s32-mono-c1000', 8000, 1, 32, False, 1, 1000, 'tone'),
    ('s32-stereo-loud-c4000', 8000, 2, 32, False, 0.5, 4000, 'loud'),
    ('f32-stereo-c2000', 8000, 2, 32, True, 1, 2000, 'tone'),
    ('f32-mono-c4000', 8000, 1, 32, True, 1, 4000, 'tone'),
    ('s16-6ch-c2000', 8000, 6, 16, False, 2, 2000, 'tone'),
    ('s24-6ch-c4000', 8000, 6, 24, False, 1, 4000, 'tone'),
    ('s16-3ch-c1000', 8000, 3, 16, False, 2, 1000, 'tone'),
    ('s24-4ch-c3000', 8000, 4, 24, False, 1, 3000, 'tone'),
    ('s16-5ch-c3000', 8000, 5, 16, False, 2, 3000, 'tone'),
    ('s16-7ch-c2000', 8000, 7, 16, False, 1, 2000, 'tone'),
    ('s16-8ch-c2000', 8000, 8, 16, False, 1, 2000, 'tone'),
    ('s32-6ch-c2000', 8000, 6, 32, False, 1, 2000, 'tone'),
    ('s16-stereo-silence-c2000', 8000, 2, 16, False, 2, 2000, 'silence'),
    ('s16-mono-silence-c3000', 8000, 1, 16, False, 2, 3000, 'silence'),
    ('s16-stereo-dual-c2000', 8000, 2, 16, False, 2, 2000, 'dual'),
    ('s16-stereo-short-c4000', 44100, 2, 16, False, 0.001, 4000, 'tone'),
    ('s16-stereo-tagged-c2000', 8000, 2, 16, False, 0.5, 2000, 'tone'),
]

# APEv2 fields written by MAC's -t option, by vector
TAGS = {'s16-stereo-tagged-c2000': 'Artist=Smalt Vectors|Title=Tagged Tone|Album=Monkey Test|Year=2026'}


def frames_of(rate, seconds, channels, kind, seed):
    rng = random.Random(seed)
    count = max(1, int(rate * seconds))
    out = []
    for i in range(count):
        t = i / rate
        frame = []
        for c in range(channels):
            if kind == 'silence':
                value = 0.0
            elif kind == 'quiet':
                value = 0.002 * math.sin(2 * math.pi * 97 * t) + 0.0002 * rng.uniform(-1, 1)
            elif kind == 'loud':
                value = rng.uniform(-1, 1)
            else:
                tone = math.sin(2 * math.pi * (110 * (c + 1)) * t + c)
                chirp = math.sin(2 * math.pi * (50 + 300 * t) * t)
                value = 0.01 * tone + 0.004 * chirp + 0.0005 * rng.uniform(-1, 1)
            frame.append(value)
        if kind == 'dual':
            frame = [frame[0]] * channels
        out.append(frame)
    return out


def write_wave(path, rate, channels, bits, floating, frames):
    data = bytearray()
    full = (1 << (bits - 1)) - 1
    for frame in frames:
        for value in frame:
            if floating:
                data += struct.pack('<f', value)
            elif bits == 8:
                v = max(-128, min(127, int(round(value * full))))
                data += struct.pack('<B', (v + 128) & 0xFF)
            else:
                v = max(-full - 1, min(full, int(round(value * full))))
                data += (v & ((1 << bits) - 1)).to_bytes(bits // 8, 'little')
    block = channels * bits // 8
    fmt = struct.pack('<HHIIHH', 3 if floating else 1, channels, rate, rate * block, block, bits)
    body = b'WAVE' + b'fmt ' + struct.pack('<I', len(fmt)) + fmt + b'data' + struct.pack('<I', len(data)) + bytes(data)
    with open(path, 'wb') as f:
        f.write(b'RIFF' + struct.pack('<I', len(body)) + body)


def wave_data(path):
    with open(path, 'rb') as f:
        data = f.read()
    at = 12
    while at + 8 <= len(data):
        tag = data[at:at + 4]
        size = struct.unpack('<I', data[at + 4:at + 8])[0]
        if tag == b'data':
            return data[at + 8:at + 8 + size]
        at += 8 + size + (size & 1)
    raise ValueError('no data chunk in ' + path)


def decode_md5(mac, ape, work):
    out = os.path.join(work, 'decoded.wav')
    if os.path.exists(out):
        os.remove(out)
    subprocess.run([mac, ape, out, '-d'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return hashlib.md5(wave_data(out)).hexdigest()


def main():
    mac, target = sys.argv[1], sys.argv[2]
    fate = sys.argv[sys.argv.index('--fate') + 1] if '--fate' in sys.argv else None
    oracle = sys.argv[sys.argv.index('--fate') + 2] if fate else None
    os.makedirs(target, exist_ok=True)
    work = os.path.join(target, '.work')
    os.makedirs(work, exist_ok=True)
    rows = ['# name\tMD5 of the reference decode\tbits\tformat']
    for seed, (name, rate, channels, bits, floating, seconds, level, kind) in enumerate(VECTORS):
        wav = os.path.join(work, name + '.wav')
        ape = os.path.join(target, name + '.ape')
        write_wave(wav, rate, channels, bits, floating, frames_of(rate, seconds, channels, kind, seed))
        if os.path.exists(ape):
            os.remove(ape)
        command = [mac, wav, ape, '-c%d' % level]
        if name in TAGS:
            command += ['-t', TAGS[name]]
        subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        md5 = decode_md5(mac, ape, work)
        if md5 != hashlib.md5(wave_data(wav)).hexdigest():
            raise SystemExit('round trip mismatch for ' + name)
        rows.append('%s\t%s\t%d\t%s' % (name, md5, bits, 'float' if floating else 'int'))
        os.remove(wav)
    with open(os.path.join(target, 'expected.tsv'), 'w', newline='\n') as f:
        f.write('\n'.join(rows) + '\n')
    if fate:
        for name in sorted(os.listdir(fate)):
            if name.endswith('.ape'):
                raw = os.path.join(work, 'decoded.raw')
                result = subprocess.run([oracle, os.path.join(fate, name), raw], check=True, capture_output=True,
                                        text=True)
                with open(raw, 'rb') as f:
                    print('%s\t%s\t%s' % (name, result.stdout.strip(), hashlib.md5(f.read()).hexdigest()))
    for leftover in os.listdir(work):
        os.remove(os.path.join(work, leftover))
    os.rmdir(work)


if __name__ == '__main__':
    main()
