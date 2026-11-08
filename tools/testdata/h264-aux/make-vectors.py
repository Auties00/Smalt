"""Makes the H.264 streams with alpha auxiliary coded pictures of H264AuxiliaryAlphaTest.

usage: python -I make-vectors.py <JM bin directory> <JM cfg directory> <output directory>

The JM reference encoder (lencod) cannot code auxiliary pictures, so each stream is assembled from two JM
encodes made with the same parameters: the primary pictures, 8-bit 4:2:0, and the alpha pictures, monochrome
at the auxiliary bit depth. Since the slice headers of both encodes have the same syntax and refer to identical
picture parameter sets, the slices of the second become the auxiliary coded slices (nal_unit_type 19) of the
first, appended to the access unit of each primary picture, and a sequence parameter set extension declaring
the alpha follows the sequence parameter set. The script checks that the two picture parameter sets are equal
and that the JM decoder reproduces each encoder's reconstruction, then writes each stream, the rows of
streams.tsv and frames.tsv: per frame the MD5 of the primary picture's planar 4:2:0 samples, as the JM decoder
outputs them, and the MD5 of its alpha plane, the JM decoder's output of the alpha pictures mapped to 8-bit
straight alpha as ITU-T H.264 7.4.2.1.2 interprets it (the alpha_incr_flag increment, clipping to the opaque and
transparent values, and scaling to 0 and 255 with rounding to nearest). The inputs are synthetic and the JM
encoder is deterministic, so the streams' SHA-256 checksums are reproduced.
"""
import hashlib
import math
import os
import struct
import subprocess
import sys

WIDTH, HEIGHT = 176, 160

# name, frames, primary depth, auxiliary depth, alpha_incr_flag, alpha_opaque_value, alpha_transparent_value,
# JM parameters
VECTORS = [
    ('aux10-cabac-ibp', 10, 8, 10, 0, 1023, 0,
     ['SymbolMode=1', 'NumberBFrames=1', 'IntraPeriod=5', 'IDRPeriod=10']),
    ('aux8-cavlc-fields-inverted', 8, 8, 8, 1, 0, 128,
     ['SymbolMode=0', 'PicInterlace=1', 'NumberBFrames=0', 'Transform8x8Mode=0']),
    ('aux9-cabac-slices', 6, 8, 9, 0, 400, 100,
     ['SymbolMode=1', 'NumberBFrames=1', 'SliceMode=1', 'SliceArgument=30']),
    ('aux10-high10', 6, 10, 10, 0, 0, 1023,
     ['SymbolMode=1', 'NumberBFrames=1']),
]


def primary(frame, depth):
    """Returns the 4:2:0 samples of a moving color pattern at a bit depth, little-endian 16-bit above 8 bits."""
    y = bytearray(WIDTH * HEIGHT)
    for row in range(HEIGHT):
        for column in range(WIDTH):
            value = (column * 3 + row * 2 + frame * 7) & 0xFF
            if (column - 40 - 4 * frame) ** 2 + (row - 70) ** 2 < 900:
                value = 235 - value // 4
            y[row * WIDTH + column] = value
    cw, ch = WIDTH // 2, HEIGHT // 2
    u = bytearray(cw * ch)
    v = bytearray(cw * ch)
    for row in range(ch):
        for column in range(cw):
            u[row * cw + column] = (128 + int(60 * math.sin((column + frame * 3) / 9.0))) & 0xFF
            v[row * cw + column] = (128 + int(60 * math.cos((row - frame * 2) / 7.0))) & 0xFF
    samples = bytes(y + u + v)
    if depth > 8:
        shift = depth - 8
        return struct.pack('<%dH' % len(samples), *((s << shift) | (s >> (8 - shift)) for s in samples))
    return samples


def alpha(frame, depth):
    """Returns the samples of a soft-edged moving matte at a bit depth, little-endian 16-bit above 8 bits."""
    top = (1 << depth) - 1
    values = []
    for row in range(HEIGHT):
        for column in range(WIDTH):
            distance = math.hypot(column - 88 - 5 * frame, row - 72 + 3 * frame)
            weight = min(1.0, max(0.0, (60 - distance) / 25.0))
            values.append(int(round(weight * top)) ^ ((column * 5 + row) & 3))
    values = [min(top, max(0, value)) for value in values]
    if depth > 8:
        return struct.pack('<%dH' % len(values), *values)
    return bytes(values)


def nal_units(data):
    """Splits an Annex B stream into NAL units without start codes or trailing zeros."""
    starts = []
    i = 0
    while i + 2 < len(data):
        if data[i] == 0 and data[i + 1] == 0 and data[i + 2] == 1:
            starts.append(i + 3)
            i += 3
        else:
            i += 1
    units = []
    for k, start in enumerate(starts):
        end = starts[k + 1] - 3 if k + 1 < len(starts) else len(data)
        while end > start and data[end - 1] == 0:
            end -= 1
        units.append(data[start:end])
    return units


def first_mb_is_zero(nal):
    return nal[1] & 0x80 != 0


def access_units(nals):
    """Groups the slices of a stream into access units, the parameter sets ahead of the first."""
    header, units, current = [], [], None
    for nal in nals:
        kind = nal[0] & 0x1F
        if kind in (1, 5):
            if first_mb_is_zero(nal) or current is None:
                current = []
                units.append(current)
            current.append(nal)
        elif current is None:
            header.append(nal)
    return header, units


class Bits:
    def __init__(self):
        self.bits = []

    def u(self, value, count):
        for i in range(count - 1, -1, -1):
            self.bits.append((value >> i) & 1)

    def ue(self, value):
        code = value + 1
        length = code.bit_length() - 1
        self.u(0, length)
        self.u(code, length + 1)

    def rbsp(self):
        self.bits.append(1)
        while len(self.bits) % 8:
            self.bits.append(0)
        return bytes(int(''.join(map(str, self.bits[i:i + 8])), 2) for i in range(0, len(self.bits), 8))


def escape(rbsp):
    out = bytearray()
    zeros = 0
    for value in rbsp:
        if zeros >= 2 and value <= 3:
            out.append(3)
            zeros = 0
        out.append(value)
        zeros = zeros + 1 if value == 0 else 0
    return bytes(out)


def extension(depth, incr, opaque, transparent):
    """Builds a sequence parameter set extension NAL unit for sequence parameter set 0."""
    bits = Bits()
    bits.ue(0)
    bits.ue(1)
    bits.ue(depth - 8)
    bits.u(incr, 1)
    bits.u(opaque, depth + 1)
    bits.u(transparent, depth + 1)
    bits.u(0, 1)
    return bytes([0x6D]) + escape(bits.rbsp())


def interpret(sample, incr, opaque, transparent, depth):
    low, high = min(opaque, transparent), max(opaque, transparent)
    value = sample + 1 if incr and sample > low else sample
    value = min(high, max(low, value))
    top = (1 << depth) - 1
    return (abs(value - transparent) * top + (high - low) // 2) // (high - low)


def run(command, cwd):
    result = subprocess.run(command, cwd=cwd, capture_output=True, text=True)
    # lencod 19.1 can fail releasing its buffers after writing a monochrome field stream; it reports success first
    if result.returncode != 0 and 'Exit JM 19 (FRExt) encoder' not in result.stdout:
        sys.exit(f'{command[0]} failed: {result.stdout[-2000:]} {result.stderr[-2000:]}')


def main():
    jm, cfg, out = (os.path.abspath(arg) for arg in sys.argv[1:4])
    os.makedirs(out, exist_ok=True)
    work = os.path.join(out, 'work')
    os.makedirs(work, exist_ok=True)
    streams, frames = [], []
    for name, count, color_depth, depth, incr, opaque, transparent, params in VECTORS:
        with open(os.path.join(work, 'primary.yuv'), 'wb') as f:
            for k in range(count):
                f.write(primary(k, color_depth))
        with open(os.path.join(work, 'alpha.yuv'), 'wb') as f:
            for k in range(count):
                f.write(alpha(k, depth))
        common = ['-p', f'SourceWidth={WIDTH}', '-p', f'SourceHeight={HEIGHT}', '-p', f'OutputWidth={WIDTH}',
                  '-p', f'OutputHeight={HEIGHT}', '-p', f'FramesToBeEncoded={count}', '-p', 'LevelIDC=30',
                  '-p', 'QPISlice=30', '-p', 'QPPSlice=31', '-p', 'QPBSlice=32', '-p', 'RDOptimization=0',
                  '-p', 'HierarchicalCoding=0']
        for param in params:
            common += ['-p', param]
        encodes = {}
        for kind, profile, fmt, bits in (('primary', 100 if color_depth == 8 else 110, 1, color_depth),
                                         ('alpha', 110 if depth <= 10 else 244, 0, depth)):
            stream = os.path.join(work, kind + '.264')
            rec = os.path.join(work, kind + '-rec.yuv')
            run([os.path.join(jm, 'lencod.exe'), '-d', os.path.join(cfg, 'encoder.cfg'),
                 '-p', f'InputFile={kind}.yuv', '-p', f'OutputFile={kind}.264', '-p', f'ReconFile={kind}-rec.yuv',
                 '-p', f'ProfileIDC={profile}', '-p', f'YUVFormat={fmt}', '-p', f'SourceBitDepthLuma={bits}',
                 '-p', f'SourceBitDepthChroma={bits}', '-p', f'OutputBitDepthLuma={bits}',
                 '-p', f'OutputBitDepthChroma={bits}', '-p', f'StatsFile={kind}-stats.dat'] + common, work)
            decoded = os.path.join(work, kind + '-dec.yuv')
            run([os.path.join(jm, 'ldecod.exe'), '-i', stream, '-o', decoded], work)
            output = open(decoded, 'rb').read()
            if fmt == 0:
                # the JM decoder writes monochrome pictures as 4:2:0 with gray chroma, the encoder luma only
                size = WIDTH * HEIGHT * (2 if bits > 8 else 1)
                frame = size * 3 // 2
                output = b''.join(output[k * frame:k * frame + size] for k in range(count))
            if output != open(rec, 'rb').read():
                sys.exit(f'{name}: the JM decoder output of the {kind} pictures differs from the reconstruction')
            encodes[kind] = (open(stream, 'rb').read(), output)
        header, units = access_units(nal_units(encodes['primary'][0]))
        aux_header, aux_units = access_units(nal_units(encodes['alpha'][0]))
        pps = [nal for nal in header if nal[0] & 0x1F == 8]
        aux_pps = [nal for nal in aux_header if nal[0] & 0x1F == 8]
        if pps != aux_pps:
            sys.exit(f'{name}: the picture parameter sets of the two encodes differ')
        if len(units) != len(aux_units):
            sys.exit(f'{name}: the encodes have different numbers of pictures')
        merged = bytearray()
        for nal in header:
            merged += b'\x00\x00\x00\x01' + nal
            if nal[0] & 0x1F == 7:
                merged += b'\x00\x00\x00\x01' + extension(depth, incr, opaque, transparent)
        for unit, aux in zip(units, aux_units):
            for nal in unit:
                merged += b'\x00\x00\x00\x01' + nal
            for nal in aux:
                merged += b'\x00\x00\x00\x01' + bytes([nal[0] & 0xE0 | 19]) + nal[1:]
        path = os.path.join(out, name + '.264')
        open(path, 'wb').write(merged)
        streams.append(f'{name}\t{hashlib.sha256(merged).hexdigest()}\t{len(merged)}\t{color_depth}\t{depth}'
                       f'\t{incr}\t{opaque}\t{transparent}\t{count}\t{" ".join(params)}')
        luma = WIDTH * HEIGHT
        chroma = (WIDTH // 2) * (HEIGHT // 2)
        color = encodes['primary'][1]
        color_size = (luma + 2 * chroma) * (2 if color_depth > 8 else 1)
        size = 2 if depth > 8 else 1
        aux_frame = luma * size
        aux_data = encodes['alpha'][1]
        for k in range(count):
            picture = color[k * color_size:(k + 1) * color_size]
            base = k * aux_frame
            if size == 2:
                samples = struct.unpack_from('<%dH' % luma, aux_data, base)
            else:
                samples = aux_data[base:base + luma]
            values = [interpret(s, incr, opaque, transparent, color_depth) for s in samples]
            plane = struct.pack('<%dH' % luma, *values) if color_depth > 8 else bytes(values)
            frames.append(f'{name}\t{k}\t{hashlib.md5(picture).hexdigest()}\t{hashlib.md5(plane).hexdigest()}')
    with open(os.path.join(out, 'streams.tsv'), 'w', newline='\n') as f:
        f.write('# H.264 streams with alpha auxiliary pictures, made by tools/testdata/h264-aux/make-vectors.py.'
                ' Columns:\n# name\tsha256\tbytes\tprimary bit depth\tbit_depth_aux\talpha_incr_flag'
                '\talpha_opaque_value\talpha_transparent_value\tframes\tJM lencod parameters\n')
        f.write('\n'.join(streams) + '\n')
    with open(os.path.join(out, 'frames.tsv'), 'w', newline='\n') as f:
        f.write('# name\tframe\tMD5 of the primary picture (planar 4:2:0, 16-bit little-endian samples above 8'
                ' bits)\tMD5 of the alpha plane at the primary bit depth\n')
        f.write('\n'.join(frames) + '\n')


if __name__ == '__main__':
    main()
