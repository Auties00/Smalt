"""Makes the MPEG-4 Visual object streams of Mpeg4ObjectConformanceTest, which exercise the tools of ISO/IEC 14496-2
beyond the rectangular Simple and Advanced Simple layers, with the reference software of ISO/IEC 14496-5.

usage: python -I make-vectors.py <smalt checkout> <work directory> [--tools <directory>] [name ...]

The script builds, in the work directory, the encoder and the decoder of the Microsoft reference software (the archive
mpeg4-oracle/build.py pins, modernized the way that script does) in an 8-bit build and an N-bit build (-D__NBIT_, 16-bit
samples), with the source fixes of PATCHES, or takes msenc, msenc-nbit, mpeg4ref and mpeg4ref-nbit from --tools. It
then encodes a synthetic 176x144 sequence (a textured moving luminance pattern, smooth moving chrominance, a moving
elliptical object with a hole as the binary shape and a radial grayscale alpha) with each configuration of CONFIGS,
decodes every stream with the reference decoder, and writes <name>.m4v and golden.tsv into
mpeg/src/test/resources/com/github/auties00/smalt/video/mpeg4/object/ (all configurations of COMMITTED without names).

golden.tsv holds per stream the size, the sample depth, the number of frames and the MD5s of the reference decode in
the layout Smalt hands out: planar Y, U, V of 8-bit samples (16-bit little-endian samples above 8 bits, as decoded with
high bit depth output), samples of fewer than 8 bits shifted up to 8 bits, and the alpha plane, the binary shape (0 or
255) or the first grayscale alpha plane scaled to the full range of the depth. The reference decoder cannot decode the
shape only stream with B-VOPs; its shape is lossless, so the source mask is its expected alpha. The reference decoder
writes texture samples for shape only layers, which have none; Smalt hands out black luminance and mid-gray
chrominance, which golden.tsv holds instead. The content is deterministic and so is the encoder, apart from the N-bit
encoder coding a binary shape (nbit10-shape), which reads memory it never wrote and codes a different stream on every
run: the committed nbit10-shape.m4v is the output of one run, and its row of golden.tsv the reference decode of that
stream. The other streams and every checksum are reproduced.

Needs g++ (an MSYS2 UCRT64 shell on Windows) on PATH.
"""
import hashlib
import importlib.util
import math
import os
import re
import shutil
import struct
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location('oracle', os.path.join(HERE, '..', 'mpeg4-oracle', 'build.py'))
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)

WIDTH, HEIGHT = 176, 144

# Fixes of the reference software sources, after the modernization of mpeg4-oracle/build.py: (file, old, new, count).
PATCHES = [
    # The encoder's padding of shaped blocks keeps its work block in a global object whose constructor runs after the
    # first use; the block is created on first use instead. Its loop counters run below zero, so they are signed.
    ('sys/encoder/padenc.cpp',
     'CU8Image uciBuff (CRct (0, 0, BLOCK_SIZE + 2, BLOCK_SIZE + 2), OUTSIDE_BLOCK_VALUE);\n'
     'PixelC* ppxlcBuff = (PixelC*) uciBuff.pixels () + BLOCK_SIZE + 3;\n',
     'static PixelC* lpeBuffer () {\n'
     '\tstatic CU8Image* puciBuff = new CU8Image (CRct (0, 0, BLOCK_SIZE + 2, BLOCK_SIZE + 2), OUTSIDE_BLOCK_VALUE);\n'
     '\treturn (PixelC*) puciBuff->pixels () + BLOCK_SIZE + 3;\n'
     '}\n', 1),
    ('sys/encoder/padenc.cpp', '\tUInt ix, iy;\n\tUInt uiSum = 0;\n',
     '\tInt ix, iy;\n\tUInt uiSum = 0;\n\tPixelC* ppxlcBuff = lpeBuffer ();\n', 1),
    ('sys/encoder/padenc.cpp', 'ppxlcBlk [ix - uiSize]', 'ppxlcBlk [ix - (Int) uiSize]', 1),
    ('sys/encoder/padenc.cpp', 'ppxlcBlk [ix + uiSize]', 'ppxlcBlk [ix + (Int) uiSize]', 1),
    # The sprite padding reads the alpha plane of a sprite without one.
    ('sys/spt.cpp',
     'PixelC* ppxlcRefA  = (PixelC*) m_pvopcSptQ->pixelsA (0) + m_iStartInRefToCurrRctY;',
     'PixelC* ppxlcRefA  = (m_volmd.fAUsage == EIGHT_BIT) ? (PixelC*) m_pvopcSptQ->pixelsA (0) + '
     'm_iStartInRefToCurrRctY : NULL;', 1),
    # The binary shape of temporal scalability is copied through a byte pointer, which the N-bit build widens.
    ('sys/tps_bfshape.cpp', 'unsigned char* destBUV_tmp = destBUV;', 'PixelC* destBUV_tmp = destBUV;', 1),
    # The decoder writes its output with a scale of 256 + 1, which multiplies N-bit samples by 257/256; it writes them
    # unscaled with 255 + 1 (8-bit samples are the same either way).
    ('app/decoder/decoder.cpp', 'rct, 256, (PixelC)0);', 'rct, 255, (PixelC)0);', 1),
    ('app/decoder/decoder.cpp', 'rct / 2, 256, pxlUVZero);', 'rct / 2, 255, pxlUVZero);', 2),
    ('type/grayc.hpp', 'Int iScale = 256', 'Int iScale = 255', 2),
]

BASE = {
    'VersionID': '2',
    'Source.Width': str(WIDTH), 'Source.Height': str(HEIGHT), 'Source.FirstFrame': '0', 'Source.LastFrame': '9',
    'Source.ObjectIndex.First': '1', 'Source.FilePrefix': '"src"', 'Source.Directory': '"SRC"',
    'Source.FrameRate [0]': '30', 'Source.SamplingRate [0]': '1',
    'Output.Directory.Bitstream': '"CMP"', 'Output.Directory.DecodedFrames': '"REC"',
    'Header.ProfileAndLevel': '3',
    'Motion.BBetweenPCount': '1',
    'Texture.QuantStep.IVOP [0]': '8', 'Texture.QuantStep.PVOP [0]': '10', 'Texture.QuantStep.BVOP [0]': '12',
    'Alpha.QuantStep.IVOP [0]': '8', 'Alpha.QuantStep.PVOP [0]': '10', 'Alpha.QuantStep.BVOP [0]': '12',
}

CONFIGS = {
    # binary shape, version 1 syntax, I/P/B
    'shape-v1': {'VersionID': '1', 'Alpha.Type [0]': '"Binary"'},
    # binary shape with the shape-adaptive DCT, quarter sample motion and four vectors
    'shape-sadct': {'Alpha.Type [0]': '"Binary"', 'Texture.SADCT.Enable [0]': '1',
                    'Motion.QuarterSample.Enable [0]': '1', 'Motion.AdvancedPrediction.Enable [0]': '1'},
    # binary shape, interlaced coding
    'shape-interlaced': {'VersionID': '1', 'Alpha.Type [0]': '"Binary"', 'Motion.Interlaced.Enable [0]': '1',
                         'Source.Height': '144', 'Motion.Interlaced.AlternativeScan.Enable [0]': '1'},
    # binary shape with video packets, data partitioning and reversible VLCs
    'shape-resilient': {'Alpha.Type [0]': '"Binary"', 'ErrorResil.VideoPacket.Enable [0]': '1',
                        'ErrorResil.VideoPacket.Length [0]': '600', 'ErrorResil.DataPartition.Enable [0]': '1',
                        'ErrorResil.RVLC.Enable [0]': '1', 'Motion.BBetweenPCount': '0'},
    # binary shape with global motion compensation and quarter sample motion
    'shape-gmc': {'Alpha.Type [0]': '"Binary"', 'Sprite.Type [0]': '"GMC"', 'Sprite.Points [0]': '2',
                  'Sprite.WarpAccuracy [0]': '"1/4"', 'Motion.QuarterSample.Enable [0]': '1'},
    # grayscale alpha with two auxiliary components (multiple auxiliary components, shape extension 1)
    'gray-mac': {'Alpha.Type [0]': '"Gray"', 'Alpha.MAC.Enable [0]': '1', 'Alpha.ShapeExtension [0]': '4'},
    # shape only, with and without B-VOPs
    'shape-only': {'Alpha.Type [0]': '"ShapeOnly"'},
    'shape-only-ip': {'Alpha.Type [0]': '"ShapeOnly"', 'Motion.BBetweenPCount': '0'},
    # grayscale alpha, with MPEG quantization and the shape-adaptive DCT
    'gray': {'Alpha.Type [0]': '"Gray"'},
    'gray-mpeg': {'Alpha.Type [0]': '"Gray"', 'Quant.Type [0]': '"MPEG"', 'Texture.SADCT.Enable [0]': '1'},
    # 4-bit, 12-bit and 10-bit samples, the last with shape
    'nbit4': {'Not8Bit.Enable': '1', 'Source.BitsPerPel': '4', 'Not8Bit.QuantPrecision': '3', 'nbit': True,
              'Texture.QuantStep.IVOP [0]': '3', 'Texture.QuantStep.PVOP [0]': '4',
              'Texture.QuantStep.BVOP [0]': '5'},
    'nbit12': {'Not8Bit.Enable': '1', 'Source.BitsPerPel': '12', 'Not8Bit.QuantPrecision': '7',
               'Source.LastFrame': '5', 'nbit': True, 'Texture.QuantStep.IVOP [0]': '40',
               'Texture.QuantStep.PVOP [0]': '48', 'Texture.QuantStep.BVOP [0]': '56'},
    'nbit10-shape': {'Not8Bit.Enable': '1', 'Source.BitsPerPel': '10', 'Not8Bit.QuantPrecision': '6',
                     'Source.LastFrame': '5', 'Alpha.Type [0]': '"Binary"', 'nbit': True},
    # reduced resolution VOPs every other VOP
    'rrv': {'RRVMode.Enable [0]': '1', 'RRVMode.Cycle [0]': '2', 'Motion.BBetweenPCount': '0'},
    # NEWPRED with video packet segments
    'newpred': {'Newpred.Enable [0]': '1', 'Newpred.SegmentType [0]': '"VideoPacket"',
                'Newpred.Filename [0]': '"NP"', 'Newpred.SliceList [0]': '"0,33,66"', 'Motion.BBetweenPCount': '0',
                'ErrorResil.VideoPacket.Enable [0]': '1', 'ErrorResil.VideoPacket.Length [0]': '0',
                'Motion.UseSourceForME.Enable [0]': '0'},
    # static sprites with 3, 4 and 1 warping points, shaped
    'sprite-basic': {'Sprite.Type [0]': '"Static"', 'Sprite.Points [0]': '3', 'Sprite.Mode [0]': '"Basic"',
                     'Sprite.WarpAccuracy [0]': '"1/4"', 'Alpha.Type [0]': '"Binary"', 'sprite': True},
    'sprite-perspective': {'Sprite.Type [0]': '"Static"', 'Sprite.Points [0]': '4', 'Sprite.Mode [0]': '"Basic"',
                           'Sprite.WarpAccuracy [0]': '"1/16"', 'Alpha.Type [0]': '"Binary"', 'sprite': True},
    'sprite-shape': {'Sprite.Type [0]': '"Static"', 'Sprite.Points [0]': '1', 'Sprite.Mode [0]': '"Basic"',
                     'Sprite.WarpAccuracy [0]': '"1/2"', 'Alpha.Type [0]': '"Binary"', 'sprite': True},
}

COMMITTED = sorted(CONFIGS)

SPRITE_WIDTH = 256
SPRITE_HEIGHT = 192


def noise(x, y, t):
    return (((x * 73856093) ^ (y * 19349663) ^ (t * 83492791)) >> 7) & 15


def sample(v, bits, wide):
    s = max(0, min((1 << bits) - 1, int(round(v * ((1 << bits) - 1)))))
    return bytes([s & 0xFF, s >> 8]) if wide else bytes([s])


def content(width, height, frames, bits=8, wide=False):
    """Returns the 4:2:0 frames, the binary shape and the grayscale alpha of the sequence."""
    yuv = bytearray()
    seg = bytearray()
    gray = bytearray()
    for t in range(frames):
        cx = width * 0.35 + 3.0 * t
        cy = height * 0.5 + 2.0 * math.sin(t / 2.0)
        rx = width * 0.25
        ry = height * 0.28
        for y in range(height):
            for x in range(width):
                v = (0.5 + 0.23 * math.sin((x + 2 * t) / 7.0) * math.cos((y - t) / 9.0)
                     + 0.12 * ((x // 8 + y // 8 + t // 2) % 2) + (noise(x, y, t) - 8) / 400.0)
                yuv += sample(v, bits, wide)
        for plane in range(2):
            for y in range(height // 2):
                for x in range(width // 2):
                    v = 0.5 + 0.3 * (math.sin((x - t) / 11.0) if plane == 0 else math.cos((y + t) / 13.0))
                    yuv += sample(v, bits, wide)
        for y in range(height):
            for x in range(width):
                d = ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2
                hole = ((x - cx - rx * 0.3) / (rx * 0.18)) ** 2 + ((y - cy) / (ry * 0.2)) ** 2 < 1
                inside = d < 1 and not hole
                seg.append(1 if inside else 0)
                gray.append(max(1, min(255, int(255 - 170 * d))) if inside else 0)
    return bytes(yuv), bytes(seg), bytes(gray)


def write_sprite(work, frames, points, shaped):
    """Writes the sprite (SPT/01/src.spt and .vdl) and the trajectories of its warping points (PNT/01/src.pnt)."""
    w, h = SPRITE_WIDTH, SPRITE_HEIGHT
    data = bytearray(b'SPT')
    for value in (0, 0, w, h, 1 if shaped else 0):
        data += value.to_bytes(4, 'little', signed=True)
    for y in range(h):
        for x in range(w):
            v = 0.5 + 0.3 * math.sin(x / 9.0) * math.cos(y / 7.0) + 0.1 * ((x // 16 + y // 16) % 2)
            data.append(max(0, min(255, int(v * 255))))
    for plane in range(2):
        for y in range(h // 2):
            for x in range(w // 2):
                v = 0.5 + 0.25 * (math.sin(x / 13.0) if plane == 0 else math.cos(y / 11.0))
                data.append(max(0, min(255, int(v * 255))))
    if shaped:
        for y in range(h):
            for x in range(w):
                inside = ((x - w / 2) / (w * 0.45)) ** 2 + ((y - h / 2) / (h * 0.45)) ** 2 < 1
                data.append(255 if inside else 0)
    for name in ('src.spt', 'src.vdl'):
        open(os.path.join(work, 'SPT', '01', name), 'wb').write(data)
    corners = [(0, 0), (176, 0), (0, 144), (176, 144)][:points]
    with open(os.path.join(work, 'PNT', '01', 'src.pnt'), 'w', newline='\n') as f:
        f.write(f'{points}\n')
        for x, y in corners:
            f.write(f'{x} {y}\n')
        for t in range(frames):
            angle = 0.02 * t
            zoom = 1.0 + 0.015 * t
            f.write(f'{t}')
            for i, (x, y) in enumerate(corners):
                px = 30 + 5.5 * t + zoom * (x * math.cos(angle) - y * math.sin(angle))
                py = 20 + 2.25 * t + zoom * (x * math.sin(angle) + y * math.cos(angle))
                if i == 3:
                    px += 0.5 * t
                f.write(f' {px:.4f} {py:.4f}')
            f.write('\n')


def write_par(template, path, overrides):
    """Writes an encoder parameter file: the template with the overridden keys replaced and the others appended."""
    lines = open(template, encoding='latin-1').read().split('\n')
    keys = dict(overrides)
    out = []
    for line in lines:
        key = line.split('=')[0].strip() if '=' in line and not line.lstrip().startswith('//') else None
        if key is not None and key + ' [0]' in keys:
            line = f'{key} = {keys.pop(key + " [0]")}'
        elif key in keys:
            line = f'{key} = {keys.pop(key)}'
        out.append(line)
    for key, value in keys.items():
        out.append(f'{key} = {value}')
    open(path, 'w', encoding='latin-1', newline='\n').write('\n'.join(out) + '\n')


def patch(source):
    for name, old, new, count in PATCHES:
        path = os.path.join(source, *name.split('/'))
        text = open(path, encoding='latin-1').read()
        if text.count(old) != count:
            sys.exit(f'{name}: expected {count} of {old!r}')
        open(path, 'w', encoding='latin-1', newline='\n').write(text.replace(old, new))


def sources(source, encoder):
    """Lists the objects of the encoder or the decoder from the Makefile of the tree."""
    makefile = os.path.join(source, 'Makefile')
    text = open(makefile, encoding='latin-1').read()
    if encoder:
        swapped = text.replace('DECODER_OBJECTS', 'XDECODER_OBJECTS').replace('ENCODER_OBJECTS', 'DECODER_OBJECTS')
        open(makefile, 'w', encoding='latin-1').write(swapped)
    try:
        return oracle.decoder_sources(source)
    finally:
        open(makefile, 'w', encoding='latin-1').write(text)


def build_tool(tree, objects, binary, encoder, defines):
    """Compiles the encoder or the decoder of an extracted tree, hoisting the for-statement declarations that the
    sources use past their loops (forscope.py)."""
    source = os.path.join(tree, 'src')
    shim = os.path.join(tree, 'shim')
    os.makedirs(objects, exist_ok=True)
    flags = ['-std=gnu++98', '-fpermissive', '-fno-operator-names', '-w', '-O2', '-fno-strict-aliasing', '-fwrapv',
             '-ffp-contract=off']
    flags += oracle.DEFINES + defines + ['-include', os.path.join(shim, 'prelude.h'), '-I' + shim]
    flags += ['-I' + os.path.join(source, *path.split('/')) for path in oracle.INCLUDES]
    built = []
    for name, path, extra in sources(source, encoder):
        target = os.path.join(objects, name + '.o')
        language = ['-x', 'c++'] if path.endswith('.c') else []
        command = ['g++'] + language + flags + extra + ['-c', path, '-o', target]
        result = subprocess.run([sys.executable, '-I', os.path.join(HERE, 'forscope.py')] + command + ['--', path],
                                capture_output=True, text=True)
        if result.returncode != 0:
            sys.exit(result.stdout + result.stderr + f'\ncompiling {path} failed')
        built.append(target)
    zero_new = os.path.join(shim, 'zero_new.cpp')
    open(zero_new, 'w', encoding='ascii', newline='\n').write(oracle.ZERO_NEW)
    target = os.path.join(objects, 'zero_new.o')
    subprocess.run(['g++', '-std=gnu++98', '-w', '-O2', '-c', zero_new, '-o', target], check=True)
    os.makedirs(os.path.dirname(binary), exist_ok=True)
    subprocess.run(['g++', '-o', binary] + built + [target, '-lm'] + (['-static'] if os.name == 'nt' else []),
                   check=True)


def build_tools(work):
    """Builds msenc, msenc-nbit, mpeg4ref and mpeg4ref-nbit from one patched tree."""
    tree = os.path.join(work, 'refsw')
    archive = oracle.fetch(work)
    oracle.extract(archive, os.path.join(tree, 'src'))
    oracle.shims(os.path.join(tree, 'shim'))
    patch(os.path.join(tree, 'src'))
    for directory, _, names in os.walk(os.path.join(tree, 'src')):
        for name in names:
            if name.endswith(('.cpp', '.hpp', '.h', '.c')):
                path = os.path.join(directory, name)
                text = open(path, encoding='latin-1').read()
                fixed = encoder_modernize(text)
                if fixed != text:
                    open(path, 'w', encoding='latin-1', newline='\n').write(fixed)
    tools = os.path.join(work, 'bin')
    suffix = '.exe' if os.name == 'nt' else ''
    for name, encoder, defines in (('msenc', True, []), ('msenc-nbit', True, ['-D__NBIT_']),
                                   ('mpeg4ref', False, []), ('mpeg4ref-nbit', False, ['-D__NBIT_'])):
        binary = os.path.join(tools, name + suffix)
        if not os.path.isfile(binary):
            build_tool(tree, os.path.join(tree, 'obj-' + name), binary, encoder, defines)
    return tools, os.path.join(tree, 'src', 'demo', 'example.par')


# A backslash-newline in front of a preprocessor line, which g++ splices into the line before it.
SPLICE = re.compile(r'\\\n(?=[ \t]*#)')


def encoder_modernize(text):
    """Rewrites what the encoder sources add to the constructs mpeg4-oracle/build.py modernizes: the pre-standard
    ios::bin on a line of its own, backslash-newlines in front of preprocessor lines, and void main()."""
    text = text.replace('ios::bin\n', 'ios::binary\n')
    text = SPLICE.sub('\n', text)
    return re.sub(r'\bvoid\s+main\s*\(', 'int main(', text)


def md5(data):
    return hashlib.md5(data).hexdigest()


def words(data):
    return struct.unpack('<%dH' % (len(data) // 2), data)


def tool(tools, name):
    return os.path.join(tools, name + ('.exe' if os.name == 'nt' else ''))


def make(name, config, tools, template, work, out):
    """Encodes one configuration, writes <name>.m4v and returns its golden.tsv row."""
    config = dict(config)
    nbit = config.pop('nbit', False)
    sprite = config.pop('sprite', False)
    directory = os.path.join(work, 'gen', name)
    shutil.rmtree(directory, ignore_errors=True)
    for d in ('SRC', 'CMP/01', 'REC/01', 'SPT/01', 'PNT/01'):
        os.makedirs(os.path.join(directory, d))
    settings = dict(BASE)
    settings.update(config)
    bits = int(settings.get('Source.BitsPerPel', '8'))
    frames = int(settings['Source.LastFrame']) + 1
    yuv, seg, gray = content(WIDTH, HEIGHT, frames, bits, wide=nbit)
    open(os.path.join(directory, 'SRC', 'src.yuv'), 'wb').write(yuv)
    alpha_type = settings.get('Alpha.Type [0]', '"None"')
    if alpha_type != '"None"':
        open(os.path.join(directory, 'SRC', 'src.seg'), 'wb').write(gray if alpha_type == '"Gray"' else seg)
    if settings.get('Alpha.MAC.Enable [0]') == '1':
        for component in range(3):
            aux = bytes((v * (component + 1)) % 256 if v else 0 for v in gray)
            open(os.path.join(directory, 'SRC', f'src.{component}.aux'), 'wb').write(aux)
    if sprite:
        settings['Sprite.Directory'] = '"SPT"'
        settings['Sprite.Points.Directory'] = '"PNT"'
        write_sprite(directory, frames, int(settings['Sprite.Points [0]']), alpha_type != '"None"')
    if 'Newpred.Filename [0]' in settings:
        # NPsegment 1 of every P-VOP predicts from the VOP two before, the other segments from the previous one
        with open(os.path.join(directory, 'NP'), 'w', newline='\n') as f:
            for frame in range(frames):
                vop = frame + 1
                f.write(f'{vop} {max(1, vop - 1)} {max(1, vop - 2)} {max(1, vop - 1)}\n')
    write_par(template, os.path.join(directory, 'enc.par'), settings)
    subprocess.run([tool(tools, 'msenc-nbit' if nbit else 'msenc'), 'enc.par'], cwd=directory, capture_output=True)
    stream = os.path.join(directory, 'CMP', '01', 'src.cmp')
    if not os.path.isfile(stream):
        sys.exit(name + ': the encoder wrote no stream')
    target = os.path.join(out, name + '.m4v')
    shutil.copyfile(stream, target)
    depth = bits if nbit else 8
    luma = WIDTH * HEIGHT
    if name == 'shape-only':
        decoded_frames = len(seg) // luma
        alpha = bytes(255 if v else 0 for v in seg)
        planes = (bytes(luma) + bytes([128]) * (luma // 2)) * decoded_frames
    else:
        base = os.path.join(directory, 'ref')
        subprocess.run([tool(tools, 'mpeg4ref-nbit' if nbit else 'mpeg4ref'), target, base, str(WIDTH), str(HEIGHT)],
                       capture_output=True, check=True)
        planes = open(base + '.yuv', 'rb').read()
        alpha = b''
        for candidate in (base + '.0.aux', base + '.seg'):
            if os.path.isfile(candidate):
                alpha = open(candidate, 'rb').read()
                break
        if nbit:
            samples = words(planes)
            alpha_samples = words(alpha) if alpha else ()
            if depth < 8:
                planes = bytes(v << (8 - depth) for v in samples)
                alpha = bytes(alpha_samples)
            else:
                top = (1 << depth) - 1
                alpha = b''.join(struct.pack('<H', (v * top + 127) // 255) for v in alpha_samples)
        decoded_frames = len(planes) // (luma * 3 // 2 * (2 if depth > 8 else 1))
        if alpha_type == '"ShapeOnly"':
            planes = (bytes(luma) + bytes([128]) * (luma // 2)) * decoded_frames
    shutil.rmtree(directory, ignore_errors=True)
    row = (name, str(WIDTH), str(HEIGHT), str(depth), str(decoded_frames), md5(planes), md5(alpha) if alpha else '-')
    print('\t'.join(row))
    return row


def main():
    args = sys.argv[1:]
    tools = None
    if '--tools' in args:
        at = args.index('--tools')
        tools = os.path.abspath(args[at + 1])
        del args[at:at + 2]
    checkout, work = os.path.abspath(args[0]), os.path.abspath(args[1])
    names = args[2:] or COMMITTED
    os.makedirs(work, exist_ok=True)
    if tools is None:
        tools, template = build_tools(work)
    else:
        archive = oracle.fetch(work)
        template = os.path.join(work, 'example.par')
        with zipfile.ZipFile(archive) as zip:
            open(template, 'wb').write(zip.read(oracle.ROOT + 'demo/example.par'))
    out = os.path.join(checkout, 'mpeg/src/test/resources/com/github/auties00/smalt/video/mpeg4/object')
    os.makedirs(out, exist_ok=True)
    names = sorted(names, key=lambda name: name + '.m4v')
    rows = [make(name, CONFIGS[name], tools, template, work, out) for name in names]
    if sorted(names) == sorted(COMMITTED):
        with open(os.path.join(out, 'golden.tsv'), 'w', newline='\n') as sink:
            sink.write('# Golden decodes of the object decoder test streams, written by the reference decoder of '
                       'ISO/IEC 14496-5; see Mpeg4ObjectConformanceTest. Columns:\n')
            sink.write('# name\twidth\theight\tdepth\tframes\tyuv md5\talpha md5\n')
            for row in rows:
                sink.write('\t'.join(row) + '\n')


if __name__ == '__main__':
    main()
