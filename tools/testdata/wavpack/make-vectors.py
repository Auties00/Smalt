"""Makes the WavPack test vectors of audio/src/test/resources/wavpack and their expected decodes.

Every input is synthesized here (sines, chirps and seeded noise, written as WAVE or DSF), encoded with the WavPack
5.8.1 command line encoder (BSD 3-Clause) in the mode each vector exercises, and decoded with the matching wvunpack
(raw output, 24-bit PCM for DSD) to record the MD5 of the reference decode. The vectors are self-generated, so they
carry Smalt's license; the expected decodes are the reference library's.

Usage: python make-vectors.py <directory of wavpack and wvunpack> <output directory>
Writes <name>.wv (and <name>.wvc for the hybrid lossless vectors) and expected.tsv: name, wvunpack options, MD5 of
the raw decode, raw sample format.
"""
import hashlib
import math
import os
import random
import struct
import subprocess
import sys


def samples(rate, seconds, channels, seed, peak):
    rng = random.Random(seed)
    count = int(rate * seconds)
    out = []
    for i in range(count):
        t = i / rate
        frame = []
        for c in range(channels):
            tone = math.sin(2 * math.pi * (220 * (c + 1)) * t + c)
            chirp = math.sin(2 * math.pi * (100 + 4000 * t) * t)
            noise = rng.uniform(-1, 1)
            frame.append((0.5 * tone + 0.3 * chirp + 0.15 * noise) * peak)
        out.append(frame)
    return out


def wave(path, rate, channels, bits, container, frames, floating=False, mask=None):
    """Writes a WAVE file; bits valid bits in container-bit samples, left justified."""
    block = channels * container // 8
    data = bytearray()
    full = (1 << (bits - 1)) - 1
    for frame in frames:
        for value in frame:
            if floating:
                data += struct.pack('<f', value)
                continue
            v = max(-full - 1, min(full, int(round(value * full))))
            v <<= container - bits
            if container == 8:
                data += struct.pack('<B', (v + 128) & 0xFF)
            else:
                data += (v & ((1 << container) - 1)).to_bytes(container // 8, 'little')
    extensible = channels > 2 or bits != container or container > 16 or mask is not None
    if extensible:
        guid = (b'\x03\x00' if floating else b'\x01\x00') + b'\x00\x00\x00\x00\x10\x00\x80\x00\x00\xaa\x00\x38\x9b\x71'
        fmt = struct.pack('<HHIIHHHHI', 0xFFFE, channels, rate, rate * block, block, container, 22, bits,
                          mask if mask is not None else 0) + guid
    else:
        fmt = struct.pack('<HHIIHH', 3 if floating else 1, channels, rate, rate * block, block, container)
    body = b'WAVE' + b'fmt ' + struct.pack('<I', len(fmt)) + fmt + b'data' + struct.pack('<I', len(data)) + bytes(data)
    with open(path, 'wb') as f:
        f.write(b'RIFF' + struct.pack('<I', len(body)) + body)


def dsf(path, rate, channels, seconds, seed):
    """Writes a DSF file of a second-order sigma-delta modulated tone, LSB first, 4096-byte channel blocks."""
    rng = random.Random(seed)
    count = int(rate * seconds) // 8 * 8
    bits = []
    for c in range(channels):
        i1 = i2 = 0.0
        y = 0.0
        line = []
        for i in range(count):
            x = 0.5 * math.sin(2 * math.pi * (1000 * (c + 1)) * i / rate) + 0.001 * rng.uniform(-1, 1)
            i1 += x - y
            i2 += i1 - y
            y = 1.0 if i2 >= 0 else -1.0
            line.append(1 if y > 0 else 0)
        bits.append(line)
    blocks = []
    per_block = 4096 * 8
    total_blocks = (count + per_block - 1) // per_block
    for b in range(total_blocks):
        for c in range(channels):
            chunk = bytearray(4096)
            for k in range(4096):
                byte = 0
                for bit in range(8):
                    index = b * per_block + k * 8 + bit
                    if index < count and bits[c][index]:
                        byte |= 1 << bit
                chunk[k] = byte
            blocks.append(bytes(chunk))
    data = b''.join(blocks)
    fmt = struct.pack('<4sQIIIIIIQII', b'fmt ', 52, 1, 0, 2 if channels == 2 else 1, channels, rate, 1, count,
                      4096, 0)
    head = struct.pack('<4sQQQ', b'DSD ', 28, 28 + 52 + 12 + len(data), 0)
    with open(path, 'wb') as f:
        f.write(head + fmt + struct.pack('<4sQ', b'data', 12 + len(data)) + data)


VECTORS = [
    # name, writer, encoder options, decoder options
    ('s16-stereo', lambda p: wave(p, 44100, 2, 16, 16, samples(44100, 0.1, 2, 1, 0.9)), [], []),
    ('s16-mono-high', lambda p: wave(p, 22050, 1, 16, 16, samples(22050, 0.133, 1, 2, 0.9)), ['-h'], []),
    ('s16-stereo-veryhigh-extra', lambda p: wave(p, 44100, 2, 16, 16, samples(44100, 0.1, 2, 3, 0.9)),
     ['-hh', '-x6'], []),
    ('s16-stereo-fast', lambda p: wave(p, 48000, 2, 16, 16, samples(48000, 0.1, 2, 4, 0.9)), ['-f'], []),
    ('s16-stereo-md5', lambda p: wave(p, 44100, 2, 16, 16, samples(44100, 0.067, 2, 5, 0.9)), ['-m', '-w', 'Title=Smalt'],
     []),
    ('u8-mono', lambda p: wave(p, 8000, 1, 8, 8, samples(8000, 0.167, 1, 6, 0.9)), [], []),
    ('s12-mono', lambda p: wave(p, 16000, 1, 12, 16, samples(16000, 0.133, 1, 7, 0.9)), [], []),
    ('s24-stereo-high', lambda p: wave(p, 96000, 2, 24, 24, samples(96000, 0.05, 2, 8, 0.9)), ['-h'], []),
    ('s20-stereo', lambda p: wave(p, 48000, 2, 20, 24, samples(48000, 0.067, 2, 9, 0.9)), [], []),
    ('s32-stereo', lambda p: wave(p, 44100, 2, 32, 32, samples(44100, 0.067, 2, 10, 0.9)), [], []),
    ('s32-optimized', lambda p: wave(p, 44100, 2, 32, 32, samples(44100, 0.067, 2, 11, 0.9)),
     ['--optimize-int32'], []),
    ('f32-stereo', lambda p: wave(p, 44100, 2, 32, 32, samples(44100, 0.067, 2, 12, 0.9), floating=True), [], []),
    ('f32-mono-high', lambda p: wave(p, 32000, 1, 32, 32, samples(32000, 0.1, 1, 13, 1.7), floating=True),
     ['-h'], []),
    ('hybrid-s16-stereo', lambda p: wave(p, 44100, 2, 16, 16, samples(44100, 0.1, 2, 14, 0.9)), ['-b3'], []),
    ('hybrid-s16-stereo-lossless', lambda p: wave(p, 44100, 2, 16, 16, samples(44100, 0.1, 2, 15, 0.9)),
     ['-b2.5', '-c'], []),
    ('hybrid-s16-mono-lossless', lambda p: wave(p, 22050, 1, 16, 16, samples(22050, 0.133, 1, 16, 0.9)),
     ['-b3', '-c'], []),
    ('hybrid-shaped-lossless', lambda p: wave(p, 44100, 2, 16, 16, samples(44100, 0.1, 2, 17, 0.9)),
     ['-b2.4', '-s0.6', '-c'], []),
    ('hybrid-negative-shaping', lambda p: wave(p, 44100, 2, 16, 16, samples(44100, 0.1, 2, 18, 0.9)),
     ['-b2.4', '-s-0.5', '-c'], []),
    ('hybrid-dynamic-shaping', lambda p: wave(p, 44100, 2, 16, 16, samples(44100, 0.1, 2, 19, 0.9)),
     ['-b3', '--use-dns', '-c'], []),
    ('hybrid-s24-high', lambda p: wave(p, 48000, 2, 24, 24, samples(48000, 0.067, 2, 20, 0.9)), ['-b4', '-h', '-c'],
     []),
    ('hybrid-f32-lossless', lambda p: wave(p, 44100, 2, 32, 32, samples(44100, 0.067, 2, 21, 0.9), floating=True),
     ['-b3', '-c'], []),
    ('hybrid-s32-lossless', lambda p: wave(p, 44100, 2, 32, 32, samples(44100, 0.067, 2, 22, 0.9)), ['-b5', '-c'],
     []),
    ('s16-5.1', lambda p: wave(p, 48000, 6, 16, 16, samples(48000, 0.05, 6, 23, 0.9), mask=0x3F), [], []),
    ('s24-3ch', lambda p: wave(p, 44100, 3, 24, 24, samples(44100, 0.05, 3, 24, 0.9), mask=0x7), ['-h'], []),
    ('hybrid-5.1-lossless', lambda p: wave(p, 48000, 6, 16, 16, samples(48000, 0.05, 6, 25, 0.9), mask=0x3F),
     ['-b3', '-c'], []),
    ('s16-small-blocks', lambda p: wave(p, 44100, 2, 16, 16, samples(44100, 0.067, 2, 26, 0.9)),
     ['--blocksize=1000'], []),
    ('s16-silence', lambda p: wave(p, 44100, 2, 16, 16, [[0.0, 0.0]] * 4410 + samples(44100, 0.05, 2, 27, 0.1)),
     [], []),
    ('dsd64-stereo', lambda p: dsf(p, 2822400, 2, 0.02, 28), [], ['--raw-pcm']),
    ('dsd64-stereo-fast', lambda p: dsf(p, 2822400, 2, 0.02, 29), ['-f'], ['--raw-pcm']),
    ('dsd64-mono', lambda p: dsf(p, 2822400, 1, 0.02, 30), ['-h'], ['--raw-pcm']),
]


def main():
    tools, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    rows = []
    for name, writer, encode, decode in VECTORS:
        source = os.path.join(out, name + ('.dsf' if name.startswith('dsd') else '.wav'))
        writer(source)
        target = os.path.join(out, name + '.wv')
        subprocess.run([os.path.join(tools, 'wavpack'), '-q', '-y'] + encode + [source, '-o', target], check=True)
        os.remove(source)
        raw = os.path.join(out, name + '.raw')
        subprocess.run([os.path.join(tools, 'wvunpack'), '-q', '-y', '-r'] + decode + [target, '-o', raw], check=True)
        with open(raw, 'rb') as f:
            rows.append((name, ' '.join(decode), hashlib.md5(f.read()).hexdigest()))
        if '-c' in encode:
            lossy = os.path.join(out, name + '.lossy.raw')
            subprocess.run([os.path.join(tools, 'wvunpack'), '-q', '-y', '-r', '-i'] + decode + [target, '-o', lossy],
                           check=True)
            with open(lossy, 'rb') as f:
                rows.append((name + '#lossy', '-i ' + ' '.join(decode), hashlib.md5(f.read()).hexdigest()))
            os.remove(lossy)
        os.remove(raw)
    with open(os.path.join(out, 'expected.tsv'), 'w', newline='\n') as f:
        f.write('# name\twvunpack options\tMD5 of the raw decode\n')
        for row in rows:
            f.write('\t'.join(row) + '\n')


if __name__ == '__main__':
    main()
