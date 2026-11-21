#!/usr/bin/env python3
"""Writes the JPEG XL vectors of smalt-image's JxlFeaturesTest: preview frames, spot colors in RGB and gray
images, progressive passes, a gray ICC profile, gain map and metadata boxes, and the jxli frame index.

libjxl 0.11.2's cjxl and djxl (BSD-3-Clause) are the black-box encoder and the reference decoder; ffmpeg 7.1
renders the synthetic source pictures (its mandelbrot and testsrc2 sources). Neither cjxl nor libjxl's encoder can
write a preview frame or a spot color channel, so the script splices them: it parses the image header of the
codestreams cjxl writes (the subset of ISO/IEC 18181-1 annex A that those headers use, and nothing else), writes
it back with the preview size or the extra channel changed, and copies the frames as they are. A preview frame has
the syntax of a regular frame whose default size is the preview size, so the frame of a codestream cjxl wrote for
a picture of the preview size, with the same metadata, is a valid preview frame. Each splice is checked against
djxl: the spliced file must decode to the donor's pixels, and its preview (djxl --preview_out) to the preview
donor's.

Files written into the output directory (each with the references the tests compare against):
  preview-vardct.jxl       160x120 VarDCT image with a 40x30 VarDCT preview; .full.png, .preview.npy
  preview-modular.jxl      120x90 lossless image with a 30x23 lossless preview; .full.png, .preview.npy
  alpha-unsqueezed.jxl     320x96 VarDCT image whose alpha channel is not squeezed (cjxl -R 0), so that it is
                           complete only after the last pass
  spot-xyb.jxl             96x64 XYB image whose alpha channel was turned into a spot color channel;
                           .png (djxl's default render, spot colors shown) and .nospot.png (--norender_spotcolors)
  spot-gray-xyb.jxl        96x64 XYB gray image whose alpha channel was turned into a spot color channel; .png and
                           .nospot.png as for spot-xyb
  spot-gray.jxl            the same picture coded losslessly (no XYB), with the same spot channel; .png, .nospot.png
  progressive.jxl          160x120 VarDCT image with progressive passes (cjxl -p); .png, .s2.png, .s8.png (djxl -s)
  progressive-lossless.jxl 160x120 squeezed lossless image (cjxl -d 0 -p); .png
  gainmap-plain.jxl        container with a jhgm box (gain map codestream, color encoding, no ICC) and a jumb box
  gainmap-brob.jxl         the same file re-encoded by cjxl, which stores both boxes Brotli-compressed in brob boxes
  gainmap.jxl              the gain map codestream of the jhgm box (cjxl); gainmap.png its djxl decode
  gray-icc.jxl             99x70 XYB gray image whose original color space is an embedded gray ICC profile with a
                           parametric curve (gamma 1.8 with a linear toe); .linear.png, djxl's output in linear gray
  indexed.jxl              8-frame animation, every frame replacing the whole canvas, with every frame in its jxli
                           index (cjxl --frame_indexing); indexed.apng its source
  indexed-cropped.jxl      the same animation from ffmpeg's APNG muxer, whose frames are cropped and depend on the
                           frame before, with every frame in its jxli index
The PNG references are 16-bit (djxl --bits_per_sample=16). features.sha256 pins every file.

    python -I tools/testdata/jxl-features/make-vectors.py <cjxl/djxl directory> \\
        <image/src/test/resources/com/github/auties00/smalt/image/jxl/features>
"""
import hashlib
import os
import struct
import subprocess
import sys
import zlib


class BitReader:
    """Reads a JPEG XL bitstream, least significant bit first."""

    def __init__(self, data):
        self.data = data
        self.pos = 0

    def bits(self, n):
        value = 0
        for i in range(n):
            byte = self.data[self.pos >> 3]
            value |= ((byte >> (self.pos & 7)) & 1) << i
            self.pos += 1
        return value

    def u32(self, *dist):
        selector = self.bits(2)
        c, u = dist[2 * selector], dist[2 * selector + 1]
        return c + self.bits(u)

    def u64(self):
        selector = self.bits(2)
        if selector == 0:
            return 0
        if selector == 1:
            return 1 + self.bits(4)
        if selector == 2:
            return 17 + self.bits(8)
        value = self.bits(12)
        shift = 12
        while self.bits(1):
            if shift == 60:
                value |= self.bits(4) << shift
                break
            value |= self.bits(8) << shift
            shift += 8
        return value

    def enum(self):
        return self.u32(0, 0, 1, 0, 2, 4, 18, 6)

    def f16(self):
        return self.bits(16)


class BitWriter:
    """Writes a JPEG XL bitstream, least significant bit first."""

    def __init__(self):
        self.out = bytearray()
        self.pos = 0

    def bits(self, n, value):
        assert 0 <= value < (1 << n) or n == 0 and value == 0, (n, value)
        for i in range(n):
            if self.pos >> 3 == len(self.out):
                self.out.append(0)
            self.out[self.pos >> 3] |= ((value >> i) & 1) << (self.pos & 7)
            self.pos += 1

    def u32(self, value, *dist):
        for selector in range(4):
            c, u = dist[2 * selector], dist[2 * selector + 1]
            if c <= value < c + (1 << u):
                self.bits(2, selector)
                self.bits(u, value - c)
                return
        raise ValueError(f"{value} does not fit {dist}")

    def enum(self, value):
        self.u32(value, 0, 0, 1, 0, 2, 4, 18, 6)

    def pad(self):
        while self.pos & 7:
            self.bits(1, 0)

    def bytes(self):
        return bytes(self.out)


SIZE = (1, 9, 1, 13, 1, 18, 1, 30)
PREVIEW = (1, 6, 65, 8, 321, 10, 1345, 12)
RATIOS = {1: (1, 1), 2: (12, 10), 3: (4, 3), 4: (3, 2), 5: (16, 9), 6: (5, 4), 7: (2, 1)}


def read_image_size(r):
    """Reads a SizeHeader."""
    div8 = r.bits(1)
    height = 8 * (1 + r.bits(5)) if div8 else r.u32(*SIZE)
    ratio = r.bits(3)
    if ratio:
        num, den = RATIOS[ratio]
        width = height * num // den
    else:
        width = 8 * (1 + r.bits(5)) if div8 else r.u32(*SIZE)
    return width, height


def write_size(w, size, dist):
    w.bits(1, 0)
    w.u32(size[1], *dist)
    w.bits(3, 0)
    w.u32(size[0], *dist)


def read_bit_depth(r):
    if r.bits(1):
        return ('float', r.u32(32, 0, 16, 0, 24, 0, 1, 6), r.bits(4))
    return ('int', r.u32(8, 0, 10, 0, 12, 0, 1, 6))


def write_bit_depth(w, depth):
    if depth[0] == 'float':
        w.bits(1, 1)
        w.u32(depth[1], 32, 0, 16, 0, 24, 0, 1, 6)
        w.bits(4, depth[2])
    else:
        w.bits(1, 0)
        w.u32(depth[1], 8, 0, 10, 0, 12, 0, 1, 6)


def read_name(r):
    length = r.u32(0, 0, 0, 4, 16, 5, 48, 10)
    return bytes(r.bits(8) for _ in range(length))


def read_extra_channel(r):
    if r.bits(1):
        return {'type': 0, 'depth': ('int', 8), 'shift': 0, 'name': b'', 'associated': 0}
    channel = {'type': r.enum(), 'depth': read_bit_depth(r), 'shift': r.u32(0, 0, 3, 0, 4, 0, 1, 3),
               'name': read_name(r)}
    if channel['type'] == 0:
        channel['associated'] = r.bits(1)
    elif channel['type'] == 2:
        channel['spot'] = [r.f16() for _ in range(4)]
    elif channel['type'] == 5:
        channel['cfa'] = r.u32(1, 0, 0, 2, 3, 4, 19, 8)
    return channel


def write_extra_channel(w, channel):
    w.bits(1, 0)
    w.enum(channel['type'])
    write_bit_depth(w, channel['depth'])
    w.u32(channel['shift'], 0, 0, 3, 0, 4, 0, 1, 3)
    w.u32(len(channel['name']), 0, 0, 0, 4, 16, 5, 48, 10)
    for b in channel['name']:
        w.bits(8, b)
    if channel['type'] == 0:
        w.bits(1, channel['associated'])
    elif channel['type'] == 2:
        for v in channel['spot']:
            w.bits(16, v)
    elif channel['type'] == 5:
        w.u32(channel['cfa'], 1, 0, 0, 2, 3, 4, 19, 8)


def read_color_encoding(r):
    """Reads a ColorEncoding and returns the raw bits it took, which are written back as they are."""
    start = r.pos
    if not r.bits(1):
        want_icc = r.bits(1)
        assert not want_icc, "the script handles enumerated color encodings only"
        space = r.enum()
        if space != 2:
            white = r.enum()
            assert white != 2, "custom white points are not handled"
        if space not in (1, 2):
            primaries = r.enum()
            assert primaries != 2, "custom primaries are not handled"
        if r.bits(1):
            r.bits(24)
        else:
            r.enum()
        r.enum()
    return start, r.pos


def parse(codestream):
    """Parses the image header; returns the fields, the raw color encoding bits and the offset of the first frame."""
    r = BitReader(codestream)
    assert r.bits(16) == 0x0AFF, "not a bare codestream"
    meta = {'size': read_image_size(r)}
    all_default = r.bits(1)
    meta.update(orientation=0, preview=None, animation=None, depth=('int', 8), modular16=1, extra=[], xyb=1,
                color_bits=None, tone_default=True)
    if not all_default:
        extra_fields = r.bits(1)
        if extra_fields:
            meta['orientation'] = r.bits(3)
            assert not r.bits(1), "intrinsic sizes are not handled"
            if r.bits(1):
                raise AssertionError("the donor already has a preview")
            if r.bits(1):
                meta['animation'] = (r.u32(100, 0, 1000, 0, 1, 10, 1, 30), r.u32(1, 0, 1001, 0, 1, 8, 1, 10),
                                     r.u32(0, 0, 0, 3, 0, 16, 0, 32), r.bits(1))
                assert not meta['animation'][3], "timecodes are not handled"
        meta['depth'] = read_bit_depth(r)
        meta['modular16'] = r.bits(1)
        count = r.u32(0, 0, 1, 0, 2, 4, 1, 12)
        meta['extra'] = [read_extra_channel(r) for _ in range(count)]
        meta['xyb'] = r.bits(1)
        start, end = read_color_encoding(r)
        meta['color_bits'] = (start, end)
        if extra_fields:
            meta['tone_default'] = bool(r.bits(1))
            assert meta['tone_default'], "custom tone mapping is not handled"
        assert r.u64() == 0, "extensions are not handled"
    assert r.bits(1) == 1, "custom transform data is not handled"
    while r.pos & 7:
        assert r.bits(1) == 0
    return meta, r.pos >> 3


def write_header(codestream, meta, preview=None, extra=None):
    """Writes an image header with every field explicit, the color encoding bits copied from the donor."""
    w = BitWriter()
    w.bits(16, 0x0AFF)
    write_size(w, meta['size'], SIZE)
    w.bits(1, 0)
    w.bits(1, 1)
    w.bits(3, meta['orientation'])
    w.bits(1, 0)
    if preview:
        w.bits(1, 1)
        write_size(w, preview, PREVIEW)
    else:
        w.bits(1, 0)
    if meta['animation']:
        w.bits(1, 1)
        num, den, loops, _ = meta['animation']
        w.u32(num, 100, 0, 1000, 0, 1, 10, 1, 30)
        w.u32(den, 1, 0, 1001, 0, 1, 8, 1, 10)
        w.u32(loops, 0, 0, 0, 3, 0, 16, 0, 32)
        w.bits(1, 0)
    else:
        w.bits(1, 0)
    write_bit_depth(w, meta['depth'])
    w.bits(1, meta['modular16'])
    channels = meta['extra'] if extra is None else extra
    w.u32(len(channels), 0, 0, 1, 0, 2, 4, 1, 12)
    for channel in channels:
        write_extra_channel(w, channel)
    w.bits(1, meta['xyb'])
    if meta['color_bits'] is None:
        w.bits(1, 1)
    else:
        r = BitReader(codestream)
        r.pos = meta['color_bits'][0]
        for _ in range(meta['color_bits'][1] - meta['color_bits'][0]):
            w.bits(1, r.bits(1))
    w.bits(1, 1)
    w.bits(2, 0)
    w.bits(1, 1)
    w.pad()
    return w.bytes()


def run(*command):
    subprocess.run(command, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def box(kind, payload):
    return struct.pack('>I', 8 + len(payload)) + kind + payload


SIGNATURE = bytes([0, 0, 0, 12]) + b'JXL ' + bytes([13, 10, 0x87, 10])
FTYP = box(b'ftyp', b'jxl ' + bytes(4) + b'jxl ')


def png_chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xFFFFFFFF)


def write_apng(path, frames, width, height, delay_ms):
    """Writes an APNG whose every frame covers the whole canvas, blend_op SOURCE and dispose_op NONE."""
    out = bytearray(b'\x89PNG\r\n\x1a\n')
    out += png_chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0))
    out += png_chunk(b'acTL', struct.pack('>II', len(frames), 0))
    sequence = 0
    for index, rgb in enumerate(frames):
        raw = b''.join(b'\x00' + rgb[y * width * 3:(y + 1) * width * 3] for y in range(height))
        out += png_chunk(b'fcTL', struct.pack('>IIIIIHHBB', sequence, width, height, 0, 0, delay_ms, 1000, 0, 0))
        sequence += 1
        data = zlib.compress(raw, 9)
        if index == 0:
            out += png_chunk(b'IDAT', data)
        else:
            out += png_chunk(b'fdAT', struct.pack('>I', sequence) + data)
            sequence += 1
    out += png_chunk(b'IEND', b'')
    open(path, 'wb').write(out)


def s15(value):
    return struct.pack('>i', round(value * 65536))


def gray_icc():
    """A version 4 display profile of gray with a parametric curve that no enumerated JPEG XL encoding matches:
    Y = (X / 1.1 + 0.1 / 1.1) ^ 1.8 above X = 0.06 and Y = X / 6 below, and a D50 white."""
    name = 'Gray 1.8 toe'.encode('utf-16-be')
    desc = b'mluc' + bytes(4) + struct.pack('>II', 1, 12) + b'enUS' + struct.pack('>II', len(name), 28) + name
    desc += bytes(-len(desc) % 4)
    wtpt = b'XYZ ' + bytes(4) + s15(0.9642) + s15(1.0) + s15(0.8249)
    trc = b'para' + bytes(4) + struct.pack('>HH', 3, 0) + s15(1.8) + s15(1 / 1.1) + s15(0.1 / 1.1) + s15(1 / 6) \
        + s15(0.06)
    tags = [(b'desc', desc), (b'wtpt', wtpt), (b'kTRC', trc)]
    offset = 128 + 4 + 12 * len(tags)
    table = struct.pack('>I', len(tags))
    body = b''
    for signature, data in tags:
        table += signature + struct.pack('>II', offset + len(body), len(data))
        body += data
    total = offset + len(body)
    header = struct.pack('>II', total, 0) + bytes([4, 0x30, 0, 0]) + b'mntrGRAYXYZ ' \
        + struct.pack('>HHHHHH', 2026, 1, 1, 0, 0, 0) + b'acsp'
    header += bytes(64 - len(header)) + struct.pack('>I', 0) + s15(0.9642) + s15(1.0) + s15(0.8249)
    header += bytes(128 - len(header))
    return header + table + body


def write_gray_png(path, width, height, icc):
    """Writes an 8-bit gray PNG of a gradient with a checkerboard and light noise, with an iCCP chunk."""
    rows = bytearray()
    seed = 7
    for y in range(height):
        rows.append(0)
        for x in range(width):
            seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF
            value = x * 255 // width // 2 + y * 255 // height // 2 + ((x // 16 + y // 16) % 2) * 20 + seed % 6
            rows.append(min(255, value))
    out = bytearray(b'\x89PNG\r\n\x1a\n')
    out += png_chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 0, 0, 0, 0))
    out += png_chunk(b'iCCP', b'gray\x00\x00' + zlib.compress(icc, 9))
    out += png_chunk(b'IDAT', zlib.compress(bytes(rows), 9))
    out += png_chunk(b'IEND', b'')
    open(path, 'wb').write(out)


def raw_frames(width, height, count):
    """Renders ffmpeg's testsrc2 frames as raw RGB."""
    data = subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', f'testsrc2=size={width}x{height}:rate=10',
                           '-frames:v', str(count), '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
                          check=True, capture_output=True).stdout
    size = width * height * 3
    return [data[i * size:(i + 1) * size] for i in range(count)]


def same_png_pixels(a, b):
    """Returns whether two PNG files hold the same pixels, as ffmpeg decodes them to 16-bit RGBA."""
    def pixels(path):
        return subprocess.run(['ffmpeg', '-v', 'error', '-i', path, '-f', 'rawvideo', '-pix_fmt', 'rgba64le', '-'],
                              check=True, capture_output=True).stdout
    return pixels(a) == pixels(b)


def main():
    tools, out = sys.argv[1], sys.argv[2]
    cjxl = os.path.join(tools, 'cjxl')
    djxl = os.path.join(tools, 'djxl')
    os.makedirs(out, exist_ok=True)
    scratch = os.path.join(out, 'scratch')
    os.makedirs(scratch, exist_ok=True)

    def path(name):
        return os.path.join(out, name)

    def tmp(name):
        return os.path.join(scratch, name)

    def ffmpeg_png(name, source, size):
        run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', f'{source}=size={size}', '-frames:v', '1',
            '-pix_fmt', 'rgb24', tmp(name))

    def djxl16(source, target, *extra):
        run(djxl, source, target, '--bits_per_sample=16', *extra)

    def splice_preview(name, main_flags, preview_flags, full_size, preview_size, source):
        ffmpeg_png('full.png', source, full_size)
        run('ffmpeg', '-v', 'error', '-y', '-i', tmp('full.png'), '-vf',
            f'scale={preview_size.replace("x", ":")}:flags=area', '-pix_fmt', 'rgb24', tmp('small.png'))
        run(cjxl, tmp('full.png'), tmp('main.jxl'), *main_flags)
        run(cjxl, tmp('small.png'), tmp('preview.jxl'), *preview_flags)
        main = open(tmp('main.jxl'), 'rb').read()
        small = open(tmp('preview.jxl'), 'rb').read()
        main_meta, main_frames = parse(main)
        small_meta, small_frames = parse(small)
        for key in ('depth', 'modular16', 'extra', 'xyb', 'orientation', 'animation'):
            assert main_meta[key] == small_meta[key], key
        width, height = (int(v) for v in preview_size.split('x'))
        header = write_header(main, main_meta, preview=(width, height))
        open(path(name + '.jxl'), 'wb').write(header + small[small_frames:] + main[main_frames:])
        djxl16(path(name + '.jxl'), path(name + '.full.png'))
        # Only djxl's npy writer outputs the preview: float32 samples, frames x rows x columns x channels.
        run(djxl, path(name + '.jxl'), tmp('spliced.npy'), '--preview_out=' + path(name + '.preview.npy'))
        djxl16(tmp('main.jxl'), tmp('main.png'))
        run(djxl, tmp('preview.jxl'), tmp('preview.npy'))
        assert same_png_pixels(path(name + '.full.png'), tmp('main.png')), name + ' full'
        assert open(path(name + '.preview.npy'), 'rb').read() == open(tmp('preview.npy'), 'rb').read(), name

    splice_preview('preview-vardct', ['-d', '1', '--quiet'], ['-d', '1', '--quiet'], '160x120', '40x30',
                   'mandelbrot')
    splice_preview('preview-modular', ['-d', '0', '--quiet'], ['-d', '0', '--quiet'], '120x90', '30x23',
                   'testsrc2')

    # An RGBA picture with an alpha pattern; its alpha channel becomes a spot color channel.
    run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=96x64', '-frames:v', '1', '-vf',
        "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='clip(X*4+Y*2-40,0,255)'", tmp('rgba.png'))
    run(cjxl, tmp('rgba.png'), tmp('rgba.jxl'), '-d', '1', '--quiet')
    rgba = open(tmp('rgba.jxl'), 'rb').read()
    meta, frames = parse(rgba)
    assert meta['xyb'] and len(meta['extra']) == 1 and meta['extra'][0]['type'] == 0, meta
    # spot color (0.2, 0.6, 0.9) at solidity 0.75, as binary16 values
    spot = dict(meta['extra'][0], type=2, name=b'spot', spot=[0x3266, 0x38CD, 0x3B33, 0x3A00])
    spot.pop('associated', None)
    open(path('spot-xyb.jxl'), 'wb').write(write_header(rgba, meta, extra=[spot]) + rgba[frames:])
    djxl16(path('spot-xyb.jxl'), path('spot-xyb.png'))
    djxl16(path('spot-xyb.jxl'), path('spot-xyb.nospot.png'), '--norender_spotcolors')

    # Gray images with a spot color channel, XYB (lossy) and not (lossless): libjxl renders a gray image as three
    # equal channels and writes the first, so the spot color's red component is what shows.
    run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=96x64', '-frames:v', '1',
        '-filter_complex', "[0]format=gray,split[a][b];[b]geq=lum='clip(X*4+Y*2-40,0,255)'[al];"
        "[a][al]alphamerge,format=ya8", tmp('gray-alpha.png'))
    for name, flags, xyb in (('spot-gray-xyb', ('-d', '1'), 1), ('spot-gray', ('-d', '0'), 0)):
        run(cjxl, tmp('gray-alpha.png'), tmp(name + '.jxl'), *flags, '--quiet')
        donor = open(tmp(name + '.jxl'), 'rb').read()
        meta, frames = parse(donor)
        assert meta['xyb'] == xyb and len(meta['extra']) == 1 and meta['extra'][0]['type'] == 0, meta
        spot = dict(meta['extra'][0], type=2, name=b'spot', spot=[0x3266, 0x38CD, 0x3B33, 0x3A00])
        spot.pop('associated', None)
        open(path(name + '.jxl'), 'wb').write(write_header(donor, meta, extra=[spot]) + donor[frames:])
        djxl16(path(name + '.jxl'), path(name + '.png'))
        djxl16(path(name + '.jxl'), path(name + '.nospot.png'), '--norender_spotcolors')

    run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=320x96', '-frames:v', '1', '-vf',
        "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='clip(X+Y*2,0,255)'", tmp('alpha.png'))
    run(cjxl, tmp('alpha.png'), path('alpha-unsqueezed.jxl'), '-d', '1', '-R', '0', '--quiet')

    ffmpeg_png('mandel.png', 'mandelbrot', '160x120')
    run(cjxl, tmp('mandel.png'), path('progressive.jxl'), '-d', '1', '-p', '--quiet')
    djxl16(path('progressive.jxl'), path('progressive.png'))
    djxl16(path('progressive.jxl'), path('progressive.s2.png'), '-s', '2')
    djxl16(path('progressive.jxl'), path('progressive.s8.png'), '-s', '8')
    run(cjxl, tmp('mandel.png'), path('progressive-lossless.jxl'), '-d', '0', '-p', '--quiet')
    djxl16(path('progressive-lossless.jxl'), path('progressive-lossless.png'))

    # The gain map: a small gray picture coded on its own.
    run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'mandelbrot=size=64x48', '-frames:v', '1',
        '-pix_fmt', 'gray', tmp('gain.png'))
    run(cjxl, tmp('gain.png'), path('gainmap.jxl'), '-d', '1', '--quiet')
    djxl16(path('gainmap.jxl'), path('gainmap.png'))
    base = open(path('progressive.jxl'), 'rb').read()
    gain = open(path('gainmap.jxl'), 'rb').read()
    metadata = b'Smalt test gain map metadata, opaque to the decoder'
    # linear sRGB as a ColorEncoding bundle, as libjxl's gain map test writes it
    color = bytes([0x50, 0xB4, 0x00])
    jhgm = bytes([0]) + struct.pack('>H', len(metadata)) + metadata + bytes([len(color)]) + color \
        + struct.pack('>I', 0) + gain
    jumbf = b'Smalt test JUMBF payload. ' * 8
    open(path('gainmap-plain.jxl'), 'wb').write(SIGNATURE + FTYP + box(b'jxlc', base) + box(b'jhgm', jhgm)
                                               + box(b'jumb', jumbf))
    run(cjxl, path('gainmap-plain.jxl'), path('gainmap-brob.jxl'), '-d', '1', '--quiet')

    write_gray_png(tmp('gray.png'), 99, 70, gray_icc())
    run(cjxl, tmp('gray.png'), path('gray-icc.jxl'), '-d', '1', '--quiet')
    # djxl writes an XYB image in linear light (its data color space), here linear gray
    djxl16(path('gray-icc.jxl'), path('gray-icc.linear.png'))

    frames = raw_frames(96, 64, 8)
    write_apng(path('indexed.apng'), frames, 96, 64, 100)
    # cjxl 0.11.2 keeps indexing every frame after the first one it indexes, so the index lists all eight
    run(cjxl, path('indexed.apng'), path('indexed.jxl'), '-d', '2', '--container=1', '--frame_indexing=11111111',
        '--quiet')
    run('ffmpeg', '-v', 'error', '-y', '-f', 'lavfi', '-i', 'testsrc2=size=96x64:rate=10', '-frames:v', '8',
        '-plays', '0', '-f', 'apng', tmp('cropped.apng'))
    run(cjxl, tmp('cropped.apng'), path('indexed-cropped.jxl'), '-d', '2', '--container=1',
        '--frame_indexing=11111111', '--quiet')

    for name in os.listdir(scratch):
        os.remove(os.path.join(scratch, name))
    os.rmdir(scratch)
    with open(os.path.join(out, 'features.sha256'), 'w', newline='\n') as manifest:
        for name in sorted(os.listdir(out)):
            if name == 'features.sha256':
                continue
            digest = hashlib.sha256(open(os.path.join(out, name), 'rb').read()).hexdigest()
            manifest.write(f'{digest}  {name}\n')


if __name__ == '__main__':
    main()
