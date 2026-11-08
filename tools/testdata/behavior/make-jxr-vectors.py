"""Writes the JPEG XR test vectors of the image module and the samples the reference decoder, jxrlib, decodes
from them (jxr.tsv next to the vectors, which the tests compare Smalt's decoder with).

The vectors are encoded by jxrlib 1.1's JxrEncApp (BSD-2-Clause, github.com/4creators/jxrlib at commit
f752187) from TIFF inputs holding the samples of each pixel format, with options covering every coding tool:
overlap modes, chroma subsampling, spatial and frequency order, tiles, dropped bands, trimmed flexbits, quality
levels, planar and interleaved alpha. Two lines added to JxrEncApp give what its options cannot: with
JXR_HARD_TILES set, wmiSCP.bUseHardTileBoundaries = TRUE (codec subversion 9); with JXR_RANDOM_QP=<seed> set,
uQPMode |= 7 after srand(seed) in StrEncInit, which turns on jxrlib's own random per-tile and per-macroblock
quantizers. The 3, 5 and 8-channel vectors are encoded by imagecodecs 2026.10.10 (jxrlib). Some vectors are
patched: the codec subversion nibble set to 0 (the original transform operators) or 9 (hard tiles), an
inscribed window written into the image header, and the output color format of YCC vectors set to RGB (jxrlib
does not decode YCC output). The reference samples come from imagecodecs.jpegxr_decode(fp2int=True), and for the
packed 5, 565 and 10-bit formats from JxrDecApp through an uncompressed TIFF.

    python tools/testdata/behavior/make-jxr-vectors.py <JxrEncApp> <JxrDecApp> <scratch dir> [resources dir]

It needs numpy and imagecodecs (pip install numpy imagecodecs==2026.10.10).
"""
import hashlib
import os
import re
import struct
import subprocess
import sys

import numpy as np
import imagecodecs


def tiff(path, data, w, h, spp, bps, photometric, sample_format, extra=None):
    """Writes an uncompressed little-endian TIFF whose strip holds data as is."""
    entries = []

    def entry(tag, typ, count, value):
        entries.append([tag, typ, count, value])

    entry(256, 4, 1, w)
    entry(257, 4, 1, h)
    entry(258, 3, spp, [bps] * spp)
    entry(259, 3, 1, 1)
    entry(262, 3, 1, photometric)
    entry(273, 4, 1, 8)
    entry(277, 3, 1, spp)
    entry(278, 4, 1, h)
    entry(279, 4, 1, len(data))
    entry(284, 3, 1, 1)
    if extra is not None:
        entry(338, 3, 1, extra)
    entry(339, 3, spp, [sample_format] * spp)
    ifd = 8 + len(data)
    blobs = b""
    blob_base = ifd + 2 + 12 * len(entries) + 4
    raw = b""
    for tag, typ, count, value in entries:
        if isinstance(value, list):
            if count == 1:
                value = value[0]
            elif count == 2:
                value = value[0] | value[1] << 16
            else:
                offset = blob_base + len(blobs)
                blobs += struct.pack("<%dH" % count, *value)
                value = offset
        raw += struct.pack("<HHII", tag, typ, count, value)
    with open(path, "wb") as f:
        f.write(b"II*\0" + struct.pack("<I", ifd) + data + struct.pack("<H", len(entries)) + raw + b"\0" * 4
                + blobs)


def pattern(h, w, c, seed):
    rng = np.random.default_rng(seed)
    y, x = np.mgrid[0:h, 0:w]
    planes = []
    for k in range(c):
        base = (x * (k + 1) * 3 + y * (5 - k % 5) * 2) / float(w + h) + 0.25 * np.sin((x + 2 * y + k * 7) / 5.0)
        planes.append(base + rng.normal(0, 0.04, (h, w)))
    return np.stack(planes, -1)


def inputs(h, w):
    b = pattern(h, w, 8, 1)
    u8 = lambda a: np.clip(a * 120 + 20, 0, 255).astype(np.uint8)
    u16 = lambda a: np.clip(a * 30000, 0, 65535).astype(np.uint16)
    fx16 = lambda a: np.clip((a * 2 - 0.5) * 8192, -32768, 32767).astype(np.int16)
    fx32 = lambda a: ((a * 2 - 0.5) * 16777216).astype(np.int32)
    f32 = lambda a: (a * 3 - 1).astype(np.float32)
    f16 = lambda a: (a * 3 - 1).astype(np.float16)
    r5 = np.clip(b[..., 0] * 15, 0, 31).astype(np.uint16)
    g5 = np.clip(b[..., 1] * 15, 0, 31).astype(np.uint16)
    g6 = np.clip(b[..., 1] * 30, 0, 63).astype(np.uint16)
    b5 = np.clip(b[..., 2] * 15, 0, 31).astype(np.uint16)
    r10 = np.clip(b[..., 0] * 500, 0, 1023).astype(np.uint32)
    g10 = np.clip(b[..., 1] * 500, 0, 1023).astype(np.uint32)
    b10 = np.clip(b[..., 2] * 500, 0, 1023).astype(np.uint32)
    bw = b[..., 0] > 0.9
    rgbe = np.zeros((h, w, 4), np.uint8)
    rgbe[..., :3] = np.clip(b[..., :3] * 100 + 60, 0, 255).astype(np.uint8)
    rgbe[..., 3] = (126 + (b[..., 3] * 4).astype(int)).clip(120, 140).astype(np.uint8)
    # name: (bytes, spp, bps, photometric, sample format, extra samples, -c format)
    return {
        "bw": (b"".join(np.packbits(bw[y]).tobytes() for y in range(h)), 1, 1, 1, 1, None, 1),
        "gray8": (u8(b[..., 0]).tobytes(), 1, 8, 1, 1, None, 2),
        "gray16": (u16(b[..., 0]).tobytes(), 1, 16, 1, 1, None, 3),
        "grayfx16": (fx16(b[..., 0]).tobytes(), 1, 16, 1, 2, None, 4),
        "grayh": (f16(b[..., 0]).tobytes(), 1, 16, 1, 3, None, 5),
        "grayfx32": (fx32(b[..., 0]).tobytes(), 1, 32, 1, 2, None, 7),
        "grayf": (f32(b[..., 0]).tobytes(), 1, 32, 1, 3, None, 8),
        "bgr8": (u8(b[..., 2::-1][..., :3]).tobytes(), 3, 8, 2, 1, None, 0),
        "rgb8": (u8(b[..., :3]).tobytes(), 3, 8, 2, 1, None, 9),
        "rgb16": (u16(b[..., :3]).tobytes(), 3, 16, 2, 1, None, 10),
        "rgbfx16": (fx16(b[..., :3]).tobytes(), 3, 16, 2, 2, None, 11),
        "rgbh": (f16(b[..., :3]).tobytes(), 3, 16, 2, 3, None, 12),
        "rgbfx32": (fx32(b[..., :3]).tobytes(), 3, 32, 2, 2, None, 14),
        "rgbf": (f32(b[..., :3]).tobytes(), 3, 32, 2, 3, None, 15),
        "rgbe": (rgbe.tobytes(), 4, 8, 2, 1, 0, 16),
        "cmyk8": (u8(b[..., :4]).tobytes(), 4, 8, 5, 1, None, 17),
        "cmyk16": (u16(b[..., :4]).tobytes(), 4, 16, 5, 1, None, 18),
        "bgra8": (u8(b[..., [2, 1, 0, 3]]).tobytes(), 4, 8, 2, 1, 2, 22),
        "rgba16": (u16(b[..., :4]).tobytes(), 4, 16, 2, 1, 2, 23),
        "rgbafx16": (fx16(b[..., :4]).tobytes(), 4, 16, 2, 2, 2, 24),
        "rgbah": (f16(b[..., :4]).tobytes(), 4, 16, 2, 3, 2, 25),
        "rgbafx32": (fx32(b[..., :4]).tobytes(), 4, 32, 2, 2, 2, 27),
        "rgbaf": (f32(b[..., :4]).tobytes(), 4, 32, 2, 3, 2, 28),
        "bgr555": ((b5 | g5 << 5 | r5 << 10).astype("<u2").tobytes(), 3, 5, 2, 1, None, 29),
        "bgr565": ((b5 | g6 << 5 | r5 << 11).astype("<u2").tobytes(), 3, 6, 2, 1, None, 30),
        "bgr101010": ((b10 | g10 << 10 | r10 << 20).astype("<u4").tobytes(), 3, 10, 2, 1, None, 31),
        "cmyka8": (u8(b[..., :5]).tobytes(), 5, 8, 5, 1, 2, 32),
        "cmyka16": (u16(b[..., :5]).tobytes(), 5, 16, 5, 1, 2, 33),
    }


COMBOS = {
    "q1": ["-q", "1"], "q40": ["-q", "40"], "q40l0": ["-q", "40", "-l", "0"], "q40l2": ["-q", "40", "-l", "2"],
    "q1l2": ["-q", "1", "-l", "2"], "q1l0": ["-q", "1", "-l", "0"], "q20d1": ["-q", "20", "-d", "1"],
    "q20d2": ["-q", "20", "-d", "2"], "q20d1l2": ["-q", "20", "-d", "1", "-l", "2"],
    "q20d2l2": ["-q", "20", "-d", "2", "-l", "2"], "q20d1l0": ["-q", "20", "-d", "1", "-l", "0"],
    "q30sp": ["-q", "30", "-f"], "q30tile": ["-q", "30", "-U", "2", "2"],
    "q30tilesp": ["-q", "30", "-U", "2", "2", "-f"], "q30vh": ["-q", "30", "-V", "1", "2", "-H", "2"],
    "q30vhsp": ["-q", "30", "-V", "3", "-H", "1", "1", "-f"],
    "q30nofl": ["-q", "30", "-s", "1"], "q30nohp": ["-q", "30", "-s", "2"], "q30dc": ["-q", "30", "-s", "3"],
    "q30trim": ["-q", "30", "-F", "3"], "q30trimsp": ["-q", "30", "-F", "5", "-f"], "q1d0": ["-q", "1", "-d", "0"],
    "q8": ["-q", "8"], "q90": ["-q", "90", "-l", "2"],
}


def main():
    encapp, decapp, out = sys.argv[1], sys.argv[2], sys.argv[3]
    resources = sys.argv[4] if len(sys.argv) > 4 else "image/src/test/resources/com/github/auties00/smalt/image/jxr"
    select = None
    os.makedirs(out, exist_ok=True)
    h, w = 43, 75
    count = 0
    for iname, (data, spp, bps, photometric, sf, extra, fmt) in inputs(h, w).items():
        src = os.path.join(out, iname + ".tif")
        if fmt == 16:
            # jxrlib reads RGBE from flat Radiance files only
            src = os.path.join(out, iname + ".hdr")
            with open(src, "wb") as f:
                f.write(b"#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y %d +X %d\n" % (h, w) + data)
        else:
            tiff(src, data, w, h, spp, bps, photometric, sf, extra)
        for cname, args in COMBOS.items():
            for alpha in (["2"], ["3"]) if extra == 2 else ([None]):
                name = iname + "-" + cname + ("" if alpha is None else "-a" + alpha[0])
                if select and not select.search(name):
                    continue
                env = dict(os.environ)
                dst = os.path.join(out, name + ".jxr")
                command = [encapp, "-i", src, "-o", dst, "-c", str(fmt)] + args
                if alpha is not None:
                    command += ["-a", alpha[0], "-Q", "20" if "q1" not in cname else "1"]
                if os.path.exists(dst):
                    os.remove(dst)
                proc = subprocess.run(command, capture_output=True, env=env)
                if proc.returncode != 0 or not os.path.exists(dst) or os.path.getsize(dst) == 0:
                    if os.path.exists(dst):
                        os.remove(dst)
                    continue
                try:
                    decoded = imagecodecs.jpegxr_decode(open(dst, "rb").read(), fp2int=True)
                except Exception as error:
                    print("nodecode", name, error)
                    os.remove(dst)
                    continue
                np.save(os.path.join(out, name + ".npy"), decoded)
                count += 1
    count += extras(encapp, decapp, out, select, inputs(h, w))
    print(count, "vectors")
    finish(out, resources, decapp)


def store(out, name, data):
    """Writes a vector and the samples imagecodecs decodes from it; returns whether it decoded."""
    path = os.path.join(out, name + ".jxr")
    with open(path, "wb") as f:
        f.write(data)
    try:
        decoded = imagecodecs.jpegxr_decode(data, fp2int=True)
    except Exception as error:
        print("nodecode", name, error)
        os.remove(path)
        return False
    np.save(os.path.join(out, name + ".npy"), decoded)
    return True


def codestream_offset(data):
    count = struct.unpack("<H", data[struct.unpack("<I", data[4:8])[0]:][:2])[0]
    base = struct.unpack("<I", data[4:8])[0] + 2
    for index in range(count):
        tag, typ, n, value = struct.unpack("<HHII", data[base + 12 * index:base + 12 * index + 12])
        if tag == 0xBCC0:
            return value
    raise ValueError("no image offset")


class Bits:
    def __init__(self, data, start):
        self.data, self.pos = data, start * 8

    def read(self, n):
        value = 0
        for _ in range(n):
            value = value << 1 | (self.data[self.pos >> 3] >> (7 - (self.pos & 7))) & 1
            self.pos += 1
        return value


class Writer:
    def __init__(self):
        self.bits = []

    def write(self, value, n):
        self.bits += [(value >> (n - 1 - k)) & 1 for k in range(n)]

    def bytes(self):
        bits = self.bits + [0] * (-len(self.bits) % 8)
        return bytes(int("".join(map(str, bits[k:k + 8])), 2) for k in range(0, len(bits), 8))


def rewrite_window(data, top, left, bottom, right):
    """Rewrites the image header of a container's codestream with an inscribed window, keeping the coded
    macroblocks: the visible size shrinks by the window, the rest of the codestream is copied."""
    offset = codestream_offset(data)
    bits = Bits(data, offset + 8)
    fields = [bits.read(n) for n in (4, 4, 1, 1, 3, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 4, 4)]
    (version, subversion, tiling, frequency, orientation, index, overlap, abbreviated, long_word, inscribed,
     trim, stretch, swapped, reserved, alpha, color, depth) = fields
    if inscribed or stretch:
        return None
    size_bits = 16 if abbreviated else 32
    width = bits.read(size_bits) + 1
    height = bits.read(size_bits) + 1
    tiles = []
    if tiling:
        v, h = bits.read(12), bits.read(12)
        tiles = [v, h] + [bits.read(8 if abbreviated else 16) for _ in range(v + h)]
    bits.pos = (bits.pos + 7) & ~7
    rest = data[bits.pos >> 3:]
    coded_width = (width + 15) & ~15
    coded_height = (height + 15) & ~15
    new_width = coded_width - left - right
    new_height = coded_height - top - bottom
    out = Writer()
    for value, n in zip(fields[:9] + [1] + fields[10:], (4, 4, 1, 1, 3, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 4, 4)):
        out.write(value, n)
    out.write(new_width - 1, size_bits)
    out.write(new_height - 1, size_bits)
    if tiling:
        out.write(tiles[0], 12)
        out.write(tiles[1], 12)
        for value in tiles[2:]:
            out.write(value, 8 if abbreviated else 16)
    for value in (top, left, bottom, right):
        out.write(value, 6)
    codestream = data[offset:offset + 8] + out.bytes() + rest
    head = bytearray(data[:offset])
    count = struct.unpack("<H", head[struct.unpack("<I", head[4:8])[0]:][:2])[0]
    base = struct.unpack("<I", head[4:8])[0] + 2
    for k in range(count):
        tag = struct.unpack("<H", head[base + 12 * k:base + 12 * k + 2])[0]
        if tag == 0xBC80:
            head[base + 12 * k + 8:base + 12 * k + 12] = struct.pack("<I", new_width)
        if tag == 0xBC81:
            head[base + 12 * k + 8:base + 12 * k + 12] = struct.pack("<I", new_height)
        if tag == 0xBCC1:
            head[base + 12 * k + 8:base + 12 * k + 12] = struct.pack("<I", len(codestream))
        if tag in (0xBCC2, 0xBCC3):
            return None
    return bytes(head) + codestream


def extras(encapp, decapp, out, select, sources):
    count = 0
    wanted = lambda name: not select or select.search(name)

    def encode(name, src, fmt, args, env=None):
        dst = os.path.join(out, name + ".tmp.jxr")
        if os.path.exists(dst):
            os.remove(dst)
        environment = dict(os.environ)
        environment.update(env or {})
        proc = subprocess.run([encapp, "-i", src, "-o", dst] + (["-c", str(fmt)] if fmt is not None else []) + args,
                              capture_output=True, env=environment)
        if proc.returncode != 0 or not os.path.exists(dst) or os.path.getsize(dst) == 0:
            print("noencode", name)
            return None
        data = open(dst, "rb").read()
        os.remove(dst)
        return data

    # hard tile boundaries (codec subversion 9) and per-tile and per-macroblock quantizers
    for iname in ("rgb8", "gray8", "rgb16", "rgbaf", "bgra8", "cmyk8", "rgbh"):
        data_, spp, bps, photometric, sf, extra, fmt = sources[iname]
        src = os.path.join(out, iname + ".tif")
        for cname, args in {"hard": ["-q", "30", "-U", "3", "2"], "hardl2": ["-q", "30", "-V", "1", "3", "-H", "2", "-l", "2"],
                            "hardd1l2": ["-q", "30", "-U", "2", "3", "-d", "1", "-l", "2"],
                            "hardd2l2": ["-q", "30", "-U", "3", "2", "-d", "2", "-l", "2"],
                            "hardsp": ["-q", "30", "-U", "2", "2", "-f", "-l", "2"],
                            "hardd1l1": ["-q", "30", "-U", "2", "2", "-d", "1"]}.items():
            name = iname + "-" + cname
            if not wanted(name):
                continue
            alpha = ["-a", "3"] if extra == 2 else []
            data = encode(name, src, fmt, args + alpha, {"JXR_HARD_TILES": "1"})
            if data is not None and store(out, name, data):
                count += 1
        for seed in (1, 2, 3):
            for cname, args in {"qp": ["-q", "30", "-U", "2", "2"], "qpsp": ["-q", "30", "-U", "2", "2", "-f"],
                                "qpone": ["-q", "30", "-f"], "qpd1": ["-q", "30", "-U", "2", "2", "-d", "1", "-l", "2"]}.items():
                name = iname + "-" + cname + str(seed)
                if not wanted(name):
                    continue
                alpha = ["-a", "3"] if extra == 2 else []
                data = encode(name, src, fmt, args + alpha, {"JXR_RANDOM_QP": str(seed)})
                if data is not None and store(out, name, data):
                    count += 1
    # CMYK without alpha, which JxrEncApp does not read from TIFF, written by imagecodecs
    cmyk = pattern(43, 75, 4, 13)
    for depth, level in ((8, 1.0), (8, 0.8), (16, 0.8)):
        name = "cmyk%d-ic-%s" % (depth, "q1" if level == 1.0 else "q80")
        if wanted(name):
            array = (np.clip(cmyk * 120 + 20, 0, 255).astype(np.uint8) if depth == 8
                     else np.clip(cmyk * 30000, 0, 65535).astype(np.uint16))
            if store(out, name, imagecodecs.jpegxr_encode(array, level=level, photometric="cmyk")):
                count += 1
    # the original operators of codec subversion 0, and subversion 9 over soft-coded tiles, by patching the header
    for base in ("rgb8-q40", "rgb8-q40l2", "rgb8-q20d1l2", "rgb8-q20d2l2", "rgb8-q30tile", "gray8-q40l2",
                 "rgb16-q40l2", "rgbaf-q40l2-a3", "cmyk8-ic-q80", "rgb8-q30tilesp", "rgb8-q30nohp", "rgb8-q1",
                 "rgb8-q20d1", "rgb8-q20d2", "bgra8-q40l2-a3"):
        path = os.path.join(out, base + ".jxr")
        if not os.path.exists(path):
            continue
        original = open(path, "rb").read()
        if len(original) < 64:
            continue
        offset = codestream_offset(original)
        for version, label in ((0x10, "-v0"), (0x19, "-v9")):
            name = base + label
            if not wanted(name):
                continue
            patched = bytearray(original)
            patched[offset + 8] = version
            if store(out, name, bytes(patched)):
                count += 1
    # windows by rewriting the image header, orientations by patching it
    for base in ("rgb8-q40", "rgb8-q20d1", "rgb8-q20d2l2", "gray16-q40l2", "rgbaf-q40-a3", "rgb8-q30tile",
                 "rgb8-q30sp"):
        path = os.path.join(out, base + ".jxr")
        if not os.path.exists(path) or os.path.getsize(path) < 64:
            continue
        original = open(path, "rb").read()
        for label, window in (("-win", (5, 7, 14, 23)), ("-win2", (16, 16, 2, 30))):
            name = base + label
            if wanted(name):
                data = rewrite_window(original, *window)
                if data is not None and store(out, name, data):
                    count += 1
        for orientation in range(1, 8):
            name = base + "-or%d" % orientation
            if not wanted(name):
                continue
            patched = bytearray(original)
            at = codestream_offset(original) + 9
            patched[at] = (patched[at] & ~0x38) | orientation << 3
            if store(out, name, bytes(patched)):
                count += 1
    # independent channels, written by imagecodecs
    rng = np.random.default_rng(5)
    planes = pattern(40, 50, 8, 9)
    for channels in (3, 5, 8):
        for level in (1.0, 0.8):
            name = "nch%d-%s" % (channels, "q1" if level == 1.0 else "q80")
            if not wanted(name):
                continue
            array = np.clip(planes[..., :channels] * 120 + 20, 0, 255).astype(np.uint8)
            try:
                data = imagecodecs.jpegxr_encode(array, level=level)
            except Exception as error:
                print("noencode", name, error)
                continue
            if store(out, name, data):
                count += 1
    # raw YUV input of jxrlib's fixed 176x144 size
    yh, yw = 144, 176
    yplanes = pattern(yh, yw, 3, 11)
    luma = np.clip(yplanes[..., 0] * 100 + 30, 0, 255).astype(np.uint8)
    for ext, sub in ((".iyuv", (2, 2)), (".yuv422", (1, 2)), (".yuv444", (1, 1))):
        chroma = [np.clip(yplanes[::sub[0], ::sub[1], k] * 60 + 90, 0, 255).astype(np.uint8) for k in (1, 2)]
        src = os.path.join(out, "yuv" + ext)
        if ext == ".yuv422":
            packed = np.zeros((yh, yw * 2), np.uint8)
            packed[:, 0::4] = chroma[0]
            packed[:, 1::2] = luma
            packed[:, 2::4] = chroma[1]
            raw = packed.tobytes()
        else:
            raw = luma.tobytes() + chroma[0].tobytes() + chroma[1].tobytes()
        with open(src, "wb") as f:
            f.write(raw)
        for cname, args in {"q1": ["-q", "1"], "q30": ["-q", "30"], "q30l2": ["-q", "30", "-l", "2"]}.items():
            name = "yuv" + ext[1:] + "-" + cname
            if not wanted(name):
                continue
            data = encode(name, src, None, args)
            if data is None:
                continue
            patched = bytearray(data)
            at = codestream_offset(data)
            patched[at + 11] = 7 << 4 | patched[at + 11] & 15
            ifd = struct.unpack("<I", data[4:8])[0]
            for k in range(struct.unpack("<H", data[ifd:ifd + 2])[0]):
                tag, typ, n, value = struct.unpack("<HHII", data[ifd + 2 + 12 * k:ifd + 14 + 12 * k])
                if tag == 0xBC01:
                    patched[value + 15] = 0x0D
            if store(out, name + "-rgb", bytes(patched)):
                count += 1
    return count


# The vectors the tests keep, out of every one generated: each pixel format, and every coding tool on 24-bit RGB.
SELECTED = """
bw-q1 gray8-q1 gray16-q40 grayfx16-q40 grayh-q40 grayfx32-q40 grayf-q40 bgr8-q40 rgb8-q1 rgb16-q40 rgbfx16-q40
rgbh-q40 rgbfx32-q40 rgbf-q40 rgbe-q40 cmyk8-ic-q80 cmyk16-ic-q80 cmyk8-ic-q1 bgra8-q40-a2 bgra8-q40-a3 rgba16-q40-a3
rgbafx16-q40-a2 rgbah-q40-a3 rgbafx32-q40-a2 rgbaf-q40-a3 bgr555-q40 bgr565-q40 bgr101010-q40 cmyka8-q40-a3
cmyka8-q40-a2 nch3-q80 nch5-q1 nch8-q80
rgb8-q40l0 rgb8-q40l2 rgb8-q1l2 rgb8-q20d1 rgb8-q20d2 rgb8-q20d1l2 rgb8-q20d2l2 rgb8-q20d1l0 rgb8-q30sp
rgb8-q30tile rgb8-q30tilesp rgb8-q30vh rgb8-q30vhsp rgb8-q30nofl rgb8-q30nohp rgb8-q30dc rgb8-q30trim
rgb8-q30trimsp rgb8-q8 rgb8-q90 rgb8-q1d0 gray8-q30tilesp rgb16-q40l2 rgbh-q30dc bgra8-q30tile-a3
bgra8-q30sp-a2 rgbaf-q1-a2
rgb8-hard rgb8-hardl2 rgb8-hardd1l2 gray8-hardd2l2 rgb8-hardsp bgra8-hardd1l1 rgbaf-hardl2
rgb8-qp1 rgb8-qpsp2 rgb8-qpone3 rgb8-qpd11 bgra8-qp2 gray8-qpd12 rgbaf-qpsp1
rgb8-q40-v0 rgb8-q40l2-v0 rgb8-q20d1l2-v0 rgb8-q20d2l2-v0 rgb8-q30tile-v0 rgbaf-q40l2-a3-v0 cmyk8-ic-q80-v0
rgb8-q30nohp-v0 bgra8-q40l2-a3-v0 rgb8-q20d1l2-v9 rgb8-q30tile-v9 rgb8-q30tilesp-v9
rgb8-q40-win rgb8-q20d1-win2 rgbaf-q40-a3-win rgb8-q30tile-win rgb8-q30sp-win2
yuviyuv-q30-rgb yuvyuv422-q30-rgb yuvyuv444-q1-rgb
""".split()


def header_fields(data):
    """{@return the output color format, output bit depth, internal color format and pixel format byte}"""
    offset = codestream_offset(data)
    bits = Bits(data, offset + 8)
    fields = [bits.read(n) for n in (4, 4, 1, 1, 3, 1, 2, 1, 1, 1, 1, 1, 1, 1, 1, 4, 4)]
    tiling, abbreviated, inscribed, stretch = fields[2], fields[7], fields[9], fields[12]
    size_bits = 16 if abbreviated else 32
    bits.read(size_bits)
    bits.read(size_bits)
    if tiling:
        v, h = bits.read(12), bits.read(12)
        for _ in range(v + h):
            bits.read(8 if abbreviated else 16)
    if stretch:
        raise ValueError("tile stretching")
    if inscribed:
        bits.read(24)
    bits.pos = (bits.pos + 7) & ~7
    internal = bits.read(3)
    depth = fields[16]
    ifd = struct.unpack("<I", data[4:8])[0]
    count = struct.unpack("<H", data[ifd:ifd + 2])[0]
    pixel_format = -1
    for k in range(count):
        tag, typ, n, value = struct.unpack("<HHII", data[ifd + 2 + 12 * k:ifd + 14 + 12 * k])
        if tag == 0xBC01:
            pixel_format = data[value + 15]
    return fields[15], 0 if depth == 15 else depth, internal, pixel_format


def strip(path):
    """{@return the strip bytes and the width and height of an uncompressed TIFF jxrlib wrote}"""
    d = open(path, "rb").read()
    ifd = struct.unpack("<I", d[4:8])[0]
    n = struct.unpack("<H", d[ifd:ifd + 2])[0]
    tags = {}
    for k in range(n):
        tag, typ, cnt, val = struct.unpack("<HHII", d[ifd + 2 + 12 * k:ifd + 14 + 12 * k])
        if typ == 3 and cnt == 1:
            val &= 0xFFFF
        tags[tag] = (cnt, val)
    off = tags[273][1] if tags[273][0] == 1 else struct.unpack("<I", d[tags[273][1]:tags[273][1] + 4])[0]
    return d[off:], tags[256][1], tags[257][1]


def native(name, out, decapp, data):
    """The samples Smalt's decoder reports for a vector, derived from what jxrlib decodes: the samples of the
    output format before any pixel format conversion, the channels in RGB, gray, CMYK or stored order followed by
    alpha. imagecodecs decodes most formats; jxrlib's own decoder, through a TIFF, the packed 5, 565 and 10-bit
    formats, which imagecodecs converts with a wrong row stride."""
    out_cf, depth, internal, pixel_format = header_fields(data)
    if depth in (8, 9, 10):
        tif = os.path.join(out, name + ".dec.tif")
        subprocess.run([decapp, "-i", os.path.join(out, name + ".jxr"), "-o", tif], capture_output=True)
        raw, w, h = strip(tif)
        os.remove(tif)
        if depth == 9:
            v = np.frombuffer(raw, "<u4")[:h * w].reshape(h, w).astype(np.int64)
            return np.stack([(v >> 20) & 1023, (v >> 10) & 1023, v & 1023], -1)
        v = np.frombuffer(raw, "<u2")[:h * w].reshape(h, w).astype(np.int64)
        if depth == 8:
            return np.stack([(v >> 10) & 31, (v >> 5) & 31, v & 31], -1)
        return np.stack([(v >> 11) & 31, (v >> 5) & 63, v & 31], -1)
    a = np.load(os.path.join(out, name + ".npy"))
    if pixel_format == 0x1E and a.dtype == np.int32:
        # imagecodecs decodes 128bppRGBAFixedPoint into floats even when asked for integers
        a = np.round(a.view(np.float32).astype(np.float64) * 16777216).astype(np.int64)
    if a.ndim == 2:
        a = a[:, :, None]
    if a.dtype == np.bool_:
        a = a.astype(np.int64)
    elif a.dtype == np.float16:
        a = a.view(np.uint16).astype(np.int64)
    elif a.dtype == np.float32:
        a = a.view(np.int32).astype(np.int64)
    else:
        a = a.astype(np.int64)
    if internal == 0 and a.shape[2] >= 3 and out_cf == 7:
        # jxrlib copies the gray of a gray image into the three color channels of an RGB pixel format
        a = a[:, :, [0] + ([3] if a.shape[2] == 4 else [])]
    return a


def finish(out, resources, decapp):
    """Copies the selected vectors into the test resources and writes jxr.tsv: name, SHA-256 of the file, SHA-256
    of the native samples (every pixel row-major, every channel, as an int32 little-endian), width, height and
    channel count."""
    os.makedirs(resources, exist_ok=True)
    rows = []
    for name in SELECTED:
        path = os.path.join(out, name + ".jxr")
        if not os.path.exists(path) or os.path.getsize(path) < 64:
            raise SystemExit("missing vector " + name)
        data = open(path, "rb").read()
        samples = native(name, out, decapp, data)
        digest = hashlib.sha256((samples & 0xFFFFFFFF).astype("<u4").tobytes()).hexdigest()
        with open(os.path.join(resources, name + ".jxr"), "wb") as f:
            f.write(data)
        rows.append("\t".join([name, hashlib.sha256(data).hexdigest(), digest, str(samples.shape[1]),
                               str(samples.shape[0]), str(samples.shape[2])]))
    with open(os.path.join(resources, "jxr.tsv"), "w", newline="\n") as f:
        f.write("\n".join(rows) + "\n")
    print(len(rows), "vectors kept")


if __name__ == "__main__":
    main()
