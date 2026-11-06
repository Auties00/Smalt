"""Writes the DirectXTex fixtures of Smalt's DDS decoder tests.

    python make-fixtures.py <smalt-dxtex executable> [output directory]

Each fixture is a one-surface DDS file of seeded random texels, <name>.dds, and DirectXTex's decode of it,
<name>.f32: four little-endian binary32 values (red, green, blue, alpha) per texel, row-major, written by
smalt-dxtex (smalt-dxtex.cpp). expected.tsv records each fixture's DXGI format number as DirectXTex reports it, its
size and the SHA-256 of both files. The output directory defaults to the image module's test resources.
"""
import hashlib
import os
import random
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
DEFAULT_OUT = os.path.join(ROOT, 'image', 'src', 'test', 'resources', 'com', 'github', 'auties00', 'smalt', 'image',
                           'dds', 'dxtex')

DDPF_ALPHAPIXELS = 0x1
DDPF_FOURCC = 0x4
DDPF_PALETTEINDEXED8 = 0x20


def fourcc(code):
    return struct.unpack('<I', code.encode('ascii'))[0]


def header(width, height, flags, code, bits, masks):
    data = bytearray(128)
    data[0:4] = b'DDS '
    struct.pack_into('<7I', data, 4, 124, 0x1007, height, width, 0, 1, 1)
    struct.pack_into('<8I', data, 76, 32, flags, code, bits, *masks)
    struct.pack_into('<I', data, 108, 0x1000)
    return bytes(data)


def dx10(width, height, dxgi):
    return header(width, height, DDPF_FOURCC, fourcc('DX10'), 0, (0, 0, 0, 0)) + struct.pack('<5I', dxgi, 3, 0, 1, 0)


def floats(rng, count, low, high):
    return b''.join(struct.pack('<f', rng.uniform(low, high)) for _ in range(count))


def depth32_stencil(rng, count):
    """Texels of a 32-bit float depth, an 8-bit stencil and 24 unused bits."""
    return b''.join(struct.pack('<f', rng.uniform(-0.5, 1.5)) + bytes(rng.randrange(256) for _ in range(4))
                    for _ in range(count))


def noise(rng, size):
    return bytes(rng.randrange(256) for _ in range(size))


def packed(pairs_width, height, bytes_per_pair):
    return pairs_width * bytes_per_pair * height


# name, file maker taking a seeded generator
def fixtures():
    w, h = 13, 7
    count = w * h
    pairs = (w + 1) // 2
    plain = [
        ('d32-float-s8x24-uint', 20, lambda r: depth32_stencil(r, count)),
        ('r32-float-x8x24-typeless', 21, lambda r: depth32_stencil(r, count)),
        ('x32-typeless-g8x24-uint', 22, lambda r: noise(r, count * 8)),
        ('d32-float', 40, lambda r: floats(r, count, -0.5, 1.5)),
        ('d24-unorm-s8-uint', 45, lambda r: noise(r, count * 4)),
        ('r24-unorm-x8-typeless', 46, lambda r: noise(r, count * 4)),
        ('x24-typeless-g8-uint', 47, lambda r: noise(r, count * 4)),
        ('d16-unorm', 55, lambda r: noise(r, count * 2)),
        ('r1-unorm', 66, lambda r: noise(r, (w + 7) // 8 * h)),
        ('ayuv', 100, lambda r: noise(r, count * 4)),
        ('y410', 101, lambda r: noise(r, count * 4)),
        ('y416', 102, lambda r: noise(r, count * 8)),
        ('yuy2', 107, lambda r: noise(r, packed(pairs, h, 4))),
        ('y210', 108, lambda r: noise(r, packed(pairs, h, 8))),
        ('y216', 109, lambda r: noise(r, packed(pairs, h, 8))),
    ]
    result = []
    for name, dxgi, make in plain:
        result.append((name, lambda r, dxgi=dxgi, make=make: dx10(w, h, dxgi) + make(r)))
    # 4:2:0 planes need an even width and height in DirectXTex, 4:1:1 a width that is a multiple of 4
    pw, ph = 14, 8
    result.append(('nv12', lambda r: dx10(pw, ph, 103) + noise(r, pw * (ph + ph // 2))))
    result.append(('p010', lambda r: dx10(pw, ph, 104) + noise(r, pw * 2 * (ph + ph // 2))))
    result.append(('p016', lambda r: dx10(pw, ph, 105) + noise(r, pw * 2 * (ph + ph // 2))))
    nw, nh = 12, 5
    result.append(('nv11', lambda r: dx10(nw, nh, 110) + noise(r, nw * nh * 2)))
    result.append(('legacy-yuy2', lambda r: header(w, h, DDPF_FOURCC, fourcc('YUY2'), 0, (0, 0, 0, 0))
                   + noise(r, packed(pairs, h, 4))))
    result.append(('legacy-uyvy', lambda r: header(w, h, DDPF_FOURCC, fourcc('UYVY'), 0, (0, 0, 0, 0))
                   + noise(r, packed(pairs, h, 4))))
    result.append(('legacy-p8', lambda r: header(w, h, DDPF_PALETTEINDEXED8, 0, 8, (0, 0, 0, 0))
                   + noise(r, 1024) + noise(r, count)))
    # the palette's fourth byte is left zero: DirectXTex ORs it into the alpha of A8P8 texels
    result.append(('legacy-a8p8', lambda r: header(w, h, DDPF_PALETTEINDEXED8 | DDPF_ALPHAPIXELS, 0, 16,
                                                   (0, 0, 0, 0xFF00))
                   + b''.join(noise(r, 3) + b'\0' for _ in range(256)) + noise(r, count * 2)))
    return result


def sha256(path):
    with open(path, 'rb') as file:
        return hashlib.sha256(file.read()).hexdigest()


def main():
    if len(sys.argv) not in (2, 3):
        raise SystemExit(__doc__)
    tool = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) == 3 else DEFAULT_OUT
    os.makedirs(out, exist_ok=True)
    rows = []
    for index, (name, make) in enumerate(fixtures()):
        rng = random.Random(0x5D5 + index)
        dds = os.path.join(out, name + '.dds')
        raw = os.path.join(out, name + '.f32')
        with open(dds, 'wb') as file:
            file.write(make(rng))
        printed = subprocess.run([tool, dds, raw], check=True, capture_output=True, text=True).stdout.split()
        width, height, dxgi = printed
        rows.append('\t'.join([name, dxgi, width, height, sha256(dds), sha256(raw)]))
    with open(os.path.join(out, 'expected.tsv'), 'w', newline='\n') as file:
        file.write('# name\tDXGI format (as DirectXTex reports it)\twidth\theight\tsha256 of <name>.dds'
                   '\tsha256 of <name>.f32\n')
        file.write('\n'.join(rows) + '\n')


if __name__ == '__main__':
    main()
