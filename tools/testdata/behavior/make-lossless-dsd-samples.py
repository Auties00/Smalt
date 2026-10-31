"""Makes the I/O behavior samples of Monkey's Audio, Musepack, DSF and DSDIFF under core/src/test/resources/samples.

ffmpeg synthesizes a stereo tone, which Monkey's Audio 13.45 (MAC, BSD 3-Clause) encodes to ape/ape.ape and mpcenc
1.30.1 (musepack_src_r475) to musepack/musepack.mpc (stream version 8, with an APEv2 tag), both run only as
black-box encoders. The DSD samples are modulated here by a second-order sigma-delta modulator (two integrators and
a one-bit quantizer) from two sines: dsd/dsd.dsf (DSD64, 1 bit per sample, with an ID3v2.3 title) and dsd/dsd.dff
(DSD64, uncompressed, with an artist, a title, a comment and a track marker). Pin the result with pin-samples.py.

Usage: python -I make-lossless-dsd-samples.py <ffmpeg> <MAC> <mpcenc> <repository root>
"""
import math
import os
import struct
import subprocess
import sys

DSD_RATE = 2822400


def run(arguments):
    subprocess.run(arguments, check=True)


def modulate(seconds, amplitude, frequencies):
    """Returns one plane of bytes per sine, the oldest sample in the most significant bit."""
    bits = int(DSD_RATE * seconds) // 8 * 8
    planes = []
    for frequency in frequencies:
        plane = bytearray(bits // 8)
        first = second = 0.0
        y = 1.0
        step = 2 * math.pi * frequency / DSD_RATE
        for i in range(bits):
            x = amplitude * math.sin(step * i)
            first += x - y
            second += first - y
            y = 1.0 if second >= 0 else -1.0
            if y > 0:
                plane[i >> 3] |= 0x80 >> (i & 7)
        planes.append(bytes(plane))
    return planes


def reverse(plane):
    return bytes(int('{:08b}'.format(b)[::-1], 2) for b in plane)


def id3(frames):
    body = b''
    for name, text in frames:
        data = b'\x00' + text.encode('latin-1')
        body += name.encode('ascii') + struct.pack('>IH', len(data), 0) + data
    size = len(body)
    synchsafe = bytes([(size >> 21) & 0x7F, (size >> 14) & 0x7F, (size >> 7) & 0x7F, size & 0x7F])
    return b'ID3\x03\x00\x00' + synchsafe + body


def dsf(planes, tag):
    count = len(planes[0])
    blocks = (count + 4095) // 4096
    data = bytearray()
    for b in range(blocks):
        for plane in planes:
            block = reverse(plane[b * 4096:(b + 1) * 4096])
            data += block + bytes(4096 - len(block))
    data_size = 12 + len(data)
    total = 28 + 52 + data_size + len(tag)
    out = b'DSD ' + struct.pack('<QQQ', 28, total, 28 + 52 + data_size)
    out += b'fmt ' + struct.pack('<QIIIIIIQII', 52, 1, 0, 2, len(planes), DSD_RATE, 1, count * 8, 4096, 0)
    out += b'data' + struct.pack('<Q', data_size) + bytes(data)
    return out + tag


def chunk(identifier, body):
    return identifier.encode('ascii') + struct.pack('>Q', len(body)) + body + (b'\x00' if len(body) & 1 else b'')


def text(value):
    data = value.encode('ascii')
    return struct.pack('>I', len(data)) + data + (b'\x00' if len(data) & 1 else b'')


def dsdiff(planes, marker):
    properties = b'SND ' + chunk('FS  ', struct.pack('>I', DSD_RATE))
    properties += chunk('CHNL', struct.pack('>H', len(planes)) + b'SLFTSRGT')
    properties += chunk('CMPR', b'DSD \x0enot compressed\x00')
    sound = bytes(b for frame in zip(*planes) for b in frame)
    seconds = marker // DSD_RATE
    title = b'Part two'
    mark = struct.pack('>HBBIIHHHI', seconds // 3600, seconds // 60 % 60, seconds % 60, marker % DSD_RATE, 0, 0, 0,
                       0, len(title)) + title
    info = chunk('DIAR', text('Smalt Behavior')) + chunk('DITI', text('Sigma Delta')) + chunk('MARK', mark)
    comment = b'Behavior sample'
    comments = struct.pack('>HHBBBBHHI', 1, 2026, 10, 31, 12, 0, 0, 0, len(comment)) + comment
    form = b'DSD ' + chunk('FVER', struct.pack('>I', 0x01050000)) + chunk('PROP', properties)
    form += chunk('DSD ', sound) + chunk('COMT', comments) + chunk('DIIN', info)
    return chunk('FRM8', form)


def main():
    ffmpeg, mac, mpcenc, root = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
    samples = os.path.join(root, 'core', 'src', 'test', 'resources', 'samples')
    for directory in ('ape', 'musepack', 'dsd'):
        os.makedirs(os.path.join(samples, directory), exist_ok=True)
    tone = ['-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=44100:duration=0.6', '-f', 'lavfi', '-i',
            'sine=frequency=660:sample_rate=44100:duration=0.6', '-filter_complex', '[0][1]amerge=inputs=2']
    wav = os.path.join(samples, 'ape', 'source.wav')
    run([ffmpeg, '-v', 'error', '-y'] + tone + ['-c:a', 'pcm_s16le', wav])
    run([mac, wav, os.path.join(samples, 'ape', 'ape.ape'), '-c2000'])
    run([mpcenc, '--silent', '--overwrite', '--standard', '--tag', 'Title=Behavior', wav,
         os.path.join(samples, 'musepack', 'musepack.mpc')])
    os.remove(wav)
    planes = modulate(0.06, 0.5, [1000, 1500])
    with open(os.path.join(samples, 'dsd', 'dsd.dsf'), 'wb') as out:
        out.write(dsf(planes, id3([('TIT2', 'Behavior')])))
    with open(os.path.join(samples, 'dsd', 'dsd.dff'), 'wb') as out:
        out.write(dsdiff(planes, DSD_RATE // 50))


if __name__ == '__main__':
    main()
