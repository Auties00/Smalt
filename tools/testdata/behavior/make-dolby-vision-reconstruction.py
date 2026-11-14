"""Regenerates the Dolby Vision reconstruction fixture of mpeg's HevcDolbyVisionTest.

Writes, under the given directory (mpeg/src/test/resources/dolbyvision by default):

- profile5.hevc: an Annex B 10-bit HEVC stream of smooth synthetic content from ffmpeg's libx265, a profile 5 RPU
  NAL unit (type 62) appended to each access unit. The RPUs carry the IPT-PQ-c2 matrices of profile 5, 12-bit output,
  polynomial mappings, order 3 MMR chroma mappings on the second access unit, a reused mapping on the third and CM
  v2.9 display management data; they follow the syntax of the dolby_vision crate (dovi_tool, MIT). The last two
  access units carry the RPUs of dovi_tool's assets/tests/profile5.bin and profile5-02.bin (commit 41dae64, the
  dovi-tool-* rows of tools/testdata/vectors.tsv), taken from real streams, as they are;
- profile5-libplacebo.gbr16.gz: gzip of what ffmpeg's libplacebo filter, run as a black box on a Vulkan device, makes
  of that stream: gbrp16le planes (G, B, R) of BT.2020 RGB coded with the SMPTE ST 2084 inverse EOTF, one frame after
  another.

libplacebo needs an ffmpeg built with it and a Vulkan device, which CI does not have, so the output is a committed
fixture. The fixture's provenance: BtbN's ffmpeg N-124279-g0f6ba39122 (LGPL/GPL build, run only as a binary) through
winget's yt-dlp.FFmpeg package on Windows 11, with the filter options below; libx265 from the same binary.

    python -I tools/testdata/behavior/make-dolby-vision-reconstruction.py <ffmpeg with libplacebo> \
        <dovi_tool assets/tests directory> [output directory]
"""
import gzip
import os
import struct
import subprocess
import sys
import tempfile

WIDTH = 96
HEIGHT = 56
FRAMES = 6
DENOMINATOR = 23
ONE = 1 << DENOMINATOR

# Smooth content inside the gamut, since libplacebo reshapes after upsampling chroma; the last picture stays between
# the pivots of the RPU it is mapped by, since libplacebo extrapolates past the last pivot where ETSI GS CCM 001 clamps.
LUMA = "if(eq(N,5),150+120*sin(2*PI*X/W),500+200*sin(2*PI*X/W)*cos(PI*Y/H)+50*N)"
CB = "512+60*sin(2*PI*X/W+N/2)"
CR = "512+50*cos(PI*Y/H+N/3)"

# Filter options: no tone or gamut mapping, no dithering and no peak detection, so that only the Dolby Vision
# conversion changes the samples.
LIBPLACEBO = ("libplacebo=format=gbrp16le:colorspace=gbr:color_primaries=bt2020:color_trc=smpte2084:range=pc"
              ":apply_dolbyvision=1:upscaler=bilinear:peak_detect=0:tonemapping=clip:gamut_mode=clip"
              ":dithering=-1:contrast_recovery=0:skip_aa=1")


class Bits:
    def __init__(self):
        self.bits = []

    def put(self, count, value):
        for shift in range(count - 1, -1, -1):
            self.bits.append((value >> shift) & 1)

    def ue(self, value):
        code = value + 1
        length = code.bit_length()
        self.put(length - 1, 0)
        self.put(length, code)

    def se(self, value):
        self.ue(2 * value - 1 if value > 0 else -2 * value)

    def align(self):
        while len(self.bits) % 8:
            self.bits.append(0)

    def bytes(self):
        out = bytearray()
        for index in range(0, len(self.bits), 8):
            byte = 0
            for bit in self.bits[index:index + 8]:
                byte = byte << 1 | bit
            out.append(byte)
        return bytes(out)

    def coefficient(self, value):
        integer = value >> DENOMINATOR
        self.se(integer)
        self.put(DENOMINATOR, value - (integer << DENOMINATOR))


def crc32_mpeg2(data):
    crc = 0xFFFFFFFF
    for byte in data:
        crc ^= byte << 24
        for _ in range(8):
            crc = (crc << 1) ^ 0x04C11DB7 if crc & 0x80000000 else crc << 1
            crc &= 0xFFFFFFFF
    return crc


def fixed(value):
    return round(value * ONE)


def polynomial(*coefficients):
    return ("poly", [fixed(value) for value in coefficients])


def mmr(constant, *orders):
    return ("mmr", fixed(constant), [[fixed(value) for value in order] for order in orders])


# The mapping of each frame: pivots and pieces per component, or None to reuse the mapping with identifier 1.
MAPPINGS = [
    (0, [
        ([0, 512, 1023], [polynomial(0.002, 1.02, -0.02), polynomial(0.0, 1.03, -0.03)]),
        ([0, 1023], [polynomial(0.05, 0.9)]),
        ([0, 1023], [polynomial(-0.04, 1.08)]),
    ]),
    (1, [
        ([0, 1023], [polynomial(0.0, 1.0)]),
        ([0, 1023], [mmr(0.01, [0.0, 0.98, 0.0, 0.01, 0.0, 0.0, 0.0], [0.0] * 7, [0.0] * 7)]),
        ([0, 1023], [mmr(-0.01, [0.01, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0], [0.0] * 7, [0.0] * 7)]),
    ]),
    None,
    (2, [
        ([0, 300, 700, 1023], [polynomial(0.0, 1.0), polynomial(0.01, 0.97), polynomial(-0.005, 1.005)]),
        ([0, 1023], [polynomial(0.0, 1.0)]),
        ([0, 1023], [polynomial(0.0, 1.0)]),
    ]),
]


def rpu(frame):
    """Returns one RPU, prefix byte 0x19 first, without emulation prevention."""
    mapping = MAPPINGS[frame]
    bits = Bits()
    bits.put(8, 0x19)
    bits.put(6, 2)
    bits.put(11, 18)
    bits.put(4, 0)
    bits.put(4, 0)
    bits.put(1, 1)
    bits.put(1, 0)
    bits.put(2, 0)
    bits.ue(DENOMINATOR)
    bits.put(2, 1)
    bits.put(1, 1)
    bits.ue(2)
    bits.ue(2)
    bits.ue(4)
    bits.put(1, 0)
    bits.put(3, 0)
    bits.put(1, 0)
    bits.put(1, 1)
    bits.put(1, 1)
    bits.put(1, 1 if mapping is None else 0)
    if mapping is None:
        bits.ue(1)
    else:
        rpu_id, curves = mapping
        bits.ue(rpu_id)
        bits.ue(0)
        bits.ue(0)
        for pivots, _ in curves:
            bits.ue(len(pivots) - 2)
            previous = 0
            for pivot in pivots:
                bits.put(10, pivot - previous)
                previous = pivot
        bits.ue(0)
        bits.ue(0)
        for _, pieces in curves:
            for piece in pieces:
                if piece[0] == "poly":
                    coefficients = piece[1]
                    bits.ue(0)
                    bits.ue(len(coefficients) - 2)
                    if len(coefficients) == 2:
                        bits.put(1, 0)
                    for coefficient in coefficients:
                        bits.coefficient(coefficient)
                else:
                    orders = piece[2]
                    bits.ue(1)
                    bits.put(2, len(orders) - 1)
                    bits.coefficient(piece[1])
                    for order in orders:
                        for coefficient in order:
                            bits.coefficient(coefficient)
    bits.ue(0)
    bits.ue(0)
    bits.ue(1 if frame == 0 else 0)
    for coefficient in (8192, 799, 1681, 8192, -933, 1091, 8192, 267, -5545):
        bits.put(16, coefficient & 0xFFFF)
    for offset in (0, 134217728, 134217728):
        bits.put(32, offset)
    for coefficient in (17081, -349, -349, -349, 17081, -349, -349, -349, 17081):
        bits.put(16, coefficient & 0xFFFF)
    bits.put(16, 65535)
    bits.put(16, 0)
    bits.put(16, 0)
    bits.put(32, 0)
    bits.put(5, 12)
    bits.put(2, 0)
    bits.put(2, 2)
    bits.put(2, 1)
    # no tone mapping: the source range of the metadata is the whole of PQ
    bits.put(12, 0)
    bits.put(12, 4095)
    bits.put(10, 42)
    bits.ue(2)
    bits.align()
    bits.ue(5)
    bits.put(8, 1)
    bits.put(12, 0)
    bits.put(12, 4095)
    bits.put(12, 1229)
    bits.put(4, 0)
    bits.ue(8)
    bits.put(8, 6)
    bits.put(16, 10000)
    bits.put(16, 1)
    bits.put(16, 10000)
    bits.put(16, 400)
    bits.align()
    payload = bits.bytes()
    return payload + struct.pack(">I", crc32_mpeg2(payload[1:])) + b"\x80"


def escape(data):
    out = bytearray()
    zeros = 0
    for byte in data:
        if zeros >= 2 and byte <= 3:
            out.append(3)
            zeros = 0
        out.append(byte)
        zeros = zeros + 1 if byte == 0 else 0
    return bytes(out)


def nal_units(stream):
    starts = []
    position = 0
    while True:
        found = stream.find(b"\x00\x00\x01", position)
        if found < 0:
            break
        starts.append(found)
        position = found + 3
    for index, start in enumerate(starts):
        end = starts[index + 1] if index + 1 < len(starts) else len(stream)
        while end > start and index + 1 < len(starts) and stream[end - 1] == 0:
            end -= 1
        yield stream[start + 3:end]


def main():
    ffmpeg = sys.argv[1]
    real = []
    for name in ("profile5.bin", "profile5-02.bin"):
        with open(os.path.join(sys.argv[2], name), "rb") as file:
            real.append(file.read()[4:])
    here = os.path.dirname(os.path.abspath(__file__))
    target = sys.argv[3] if len(sys.argv) > 3 else os.path.join(
        here, "..", "..", "..", "mpeg", "src", "test", "resources", "dolbyvision")
    os.makedirs(target, exist_ok=True)

    def run(*args):
        subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", *args], check=True)

    with tempfile.TemporaryDirectory(dir=target) as scratch:
        base = os.path.join(scratch, "base.hevc")
        run("-f", "lavfi", "-i", f"color=c=black:s={WIDTH}x{HEIGHT}:r=25:d={FRAMES / 25},format=yuv420p10le,"
                                 f"geq=lum='{LUMA}':cb='{CB}':cr='{CR}'",
            "-frames:v", str(FRAMES), "-c:v", "libx265", "-preset", "medium",
            "-x265-params", "log-level=error:keyint=1:bframes=0:crf=4:info=0:repeat-headers=0",
            "-pix_fmt", "yuv420p10le", "-color_range", "pc", base)
        with open(base, "rb") as file:
            stream = file.read()
        out = bytearray()
        frame = 0
        for unit in nal_units(stream):
            out += b"\x00\x00\x00\x01" + unit
            if ((unit[0] >> 1) & 0x3F) < 32:
                body = escape(rpu(frame)) if frame < len(MAPPINGS) else real[frame - len(MAPPINGS)]
                out += b"\x00\x00\x00\x01\x7C\x01" + body
                frame += 1
        if frame != FRAMES:
            raise SystemExit(f"expected {FRAMES} access units, found {frame}")
        hevc = os.path.join(target, "profile5.hevc")
        with open(hevc, "wb") as file:
            file.write(out)
        raw = os.path.join(scratch, "out.gbr")
        run("-init_hw_device", "vulkan", "-i", hevc, "-vf", LIBPLACEBO, "-f", "rawvideo", raw)
        with open(raw, "rb") as file:
            pixels = file.read()
        expected = WIDTH * HEIGHT * 3 * 2 * FRAMES
        if len(pixels) != expected:
            raise SystemExit(f"libplacebo wrote {len(pixels)} bytes, expected {expected}")
        with open(os.path.join(target, "profile5-libplacebo.gbr16.gz"), "wb") as file:
            file.write(gzip.compress(pixels, mtime=0))


if __name__ == "__main__":
    main()
