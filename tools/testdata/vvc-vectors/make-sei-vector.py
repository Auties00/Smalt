#!/usr/bin/env python3
"""Writes sei-h274-420-10bit.vvc, the VVC vector of smalt-mpeg's VvcViewingSeiTest, and the dump VVdeC makes of it.

The vector is ld-420-10bit.vvc (written by make-vectors.py) with prefix SEI NAL units of the Rec. ITU-T H.274 viewing
messages inserted before the first NAL unit of its pictures (which the low-delay stream outputs in decoding order):
ambient viewing environment, content colour volume, sample aspect ratio information, equirectangular and generalized
cubemap projection, sphere rotation, region-wise packing and omnidirectional viewport messages, with persistence and
cancellation, an omnidirectional viewport of a reserved identifier, and frame packing arrangements under which a
projection applies (side by side) and does not (top and bottom with a mirrored view). The payloads are
written here from the H.274 syntax tables, independently of Smalt's parser.

sei-h274-420-10bit.vvdec.txt lists the raw syntax values of the messages VVdeC (The Clear BSD License, used as a
black-box decoder) attaches to each output frame, as printed by seidump.c, a small program on VVdeC's public API
(vvdec_find_frame_sei) built against libvvdec of the master commit of 2026-10-28 with vvdec-sei-read.patch applied:

    g++ -fpermissive -x c++ seidump.c -I <vvdec build>/ -I <vvdec>/include -DVVDEC_STATIC_BUILD -L <vvdec lib> -lvvdec

The patch fixes four reading errors of that VVdeC: its signed 32-bit reads (i(32), used by the content colour volume,
sphere rotation and omnidirectional viewport messages) fail an assertion, it skips neither rwp_cancel_flag nor
ccv_reserved_zero_2bits, and it stores the viewport tilt from the wrong variable. The base stream is 10-bit because
VVdeC finds no SEI message for frames it copies to 8-bit output buffers.

Usage: python -I make-sei-vector.py <mpeg/src/test/resources/com/github/auties00/smalt/video/vvc> [seidump executable]
"""
import hashlib
import os
import subprocess
import sys


class Bits:
    def __init__(self):
        self.value = 0
        self.count = 0

    def u(self, n, v):
        self.value = (self.value << n) | (v & ((1 << n) - 1))
        self.count += n
        return self

    def i(self, n, v):
        return self.u(n, v & ((1 << n) - 1))

    def bytes(self):
        bits = Bits()
        bits.value, bits.count = self.value, self.count
        if bits.count % 8:
            bits.u(1, 1)
            while bits.count % 8:
                bits.u(1, 0)
        return bits.value.to_bytes(bits.count // 8, 'big')


def ambient(illuminance, x, y):
    return 148, Bits().u(32, illuminance).u(16, x).u(16, y).bytes()


def ccv(persist, primaries=None, minimum=None, maximum=None, average=None):
    bits = Bits().u(1, 0).u(1, persist).u(1, primaries is not None).u(1, minimum is not None)
    bits.u(1, maximum is not None).u(1, average is not None).u(2, 0)
    for x, y in primaries or []:
        bits.i(32, x).i(32, y)
    for value in (minimum, maximum, average):
        if value is not None:
            bits.u(32, value)
    return 149, bits.bytes()


def ccv_cancel():
    return 149, Bits().u(1, 1).bytes()


def sari(persist, idc, width=0, height=0):
    bits = Bits().u(1, 0).u(1, persist).u(8, idc)
    if idc == 255:
        bits.u(16, width).u(16, height)
    return 204, bits.bytes()


def sari_cancel():
    return 204, Bits().u(1, 1).bytes()


def erp(persist, guard=None):
    bits = Bits().u(1, 0).u(1, persist).u(1, guard is not None).u(2, 0)
    if guard is not None:
        bits.u(3, guard[0]).u(8, guard[1]).u(8, guard[2])
    return 150, bits.bytes()


def erp_cancel():
    return 150, Bits().u(1, 1).bytes()


def gcmp(persist, packing, mapping, faces, guard=None):
    bits = Bits().u(1, 0).u(1, persist).u(3, packing).u(2, mapping)
    for index, rotation, coeff_u, u_by_v, coeff_v, v_by_u in faces:
        bits.u(3, index).u(2, rotation)
        if mapping == 2:
            bits.u(7, coeff_u).u(1, u_by_v).u(7, coeff_v).u(1, v_by_u)
    bits.u(1, guard is not None)
    if guard is not None:
        bits.u(3, guard[0]).u(1, guard[1]).u(4, guard[2] - 1)
    return 153, bits.bytes()


def rotation(persist, yaw, pitch, roll):
    return 154, Bits().u(1, 0).u(1, persist).u(6, 0).i(32, yaw).i(32, pitch).i(32, roll).bytes()


def rotation_cancel():
    return 154, Bits().u(1, 1).bytes()


def rwp(persist, matching, projected, packed, regions):
    bits = Bits().u(1, 0).u(1, persist).u(1, matching).u(5, 0).u(8, len(regions))
    bits.u(32, projected[0]).u(32, projected[1]).u(16, packed[0]).u(16, packed[1])
    for transform, projected_rect, packed_rect, guard in regions:
        bits.u(4, 0).u(3, transform).u(1, guard is not None)
        for value in projected_rect:
            bits.u(32, value)
        for value in packed_rect:
            bits.u(16, value)
        if guard is not None:
            for value in guard[:4]:
                bits.u(8, value)
            bits.u(1, guard[4])
            for value in guard[5]:
                bits.u(3, value)
            bits.u(3, 0)
    return 155, bits.bytes()


def viewport(identifier, persist, viewports):
    bits = Bits().u(10, identifier).u(1, 0).u(1, persist).u(4, len(viewports) - 1)
    for azimuth, elevation, tilt, horizontal, vertical in viewports:
        bits.i(32, azimuth).i(32, elevation).i(32, tilt).u(32, horizontal).u(32, vertical)
    return 156, bits.bytes()


def frame_packing(arrangement, flipping, persist):
    """A frame packing arrangement payload (H.274 clause 8.6): id 0, the type, plain grid positions."""
    bits = Bits()
    bits.u(1, 1)  # fp_arrangement_id ue(v) 0
    bits.u(1, 0).u(7, arrangement).u(1, 0).u(6, 1).u(1, flipping).u(1, 0).u(1, 0).u(1, 0).u(1, 0).u(1, 0)
    if arrangement != 5:
        bits.u(16, 0)
    bits.u(8, 0).u(1, persist).u(1, 0)
    return 45, bits.bytes()


DEGREE = 65536

MESSAGES = {
    0: [ambient(100_000, 15_635, 16_450),
        ccv(1, [(15_000, 30_000), (7_500, 3_000), (32_000, 16_500)], 100, 9_000_000, 1_000_000),
        sari(1, 255, 4, 3),
        erp(1, (2, 8, 6)),
        rotation(1, -90 * DEGREE - DEGREE // 2, 45 * DEGREE, 1),
        rwp(1, 0, (256, 128), (128, 96), [
            (0, (128, 128, 0, 0), (64, 96, 0, 0), None),
            (5, (128, 128, 0, 128), (64, 96, 0, 64), (2, 2, 0, 0, 1, (1, 2, 3, 0)))]),
        viewport(0, 1, [(10 * DEGREE, -20 * DEGREE, 0, 90 * DEGREE, 60 * DEGREE),
                        (-170 * DEGREE, 80 * DEGREE, 5 * DEGREE, 360 * DEGREE, 180 * DEGREE)])],
    2: [sari(0, 2)],
    3: [ccv(0, maximum=5_000_000)],
    4: [viewport(600, 1, [(0, 0, 0, DEGREE, DEGREE)]), sari_cancel()],
    5: [rotation_cancel()],
    6: [erp_cancel(), ccv_cancel()],
    7: [gcmp(0, 1, 2, [(5, 0, 10, 1, 20, 0), (0, 1, 0, 0, 127, 1), (4, 2, 64, 1, 64, 1), (3, 3, 1, 0, 2, 0),
                       (1, 0, 0, 0, 0, 0), (2, 1, 30, 1, 40, 0)], (1, 1, 4))],
    8: [frame_packing(4, 1, 0), erp(0)],
    9: [frame_packing(3, 0, 0), erp(0)],
}


def escape(rbsp):
    out = bytearray()
    zeros = 0
    for byte in rbsp:
        if zeros >= 2 and byte <= 3:
            out.append(3)
            zeros = 0
        out.append(byte)
        zeros = zeros + 1 if byte == 0 else 0
    return bytes(out)


def sei_nal(messages):
    rbsp = bytearray()
    for payload_type, payload in messages:
        for value in (payload_type, len(payload)):
            while value >= 255:
                rbsp.append(255)
                value -= 255
            rbsp.append(value)
        rbsp += payload
    rbsp.append(0x80)
    return bytes([0, (23 << 3) | 1]) + escape(bytes(rbsp))


def units(stream):
    starts = []
    index = 0
    while index + 3 <= len(stream):
        if stream[index] == 0 and stream[index + 1] == 0 and stream[index + 2] == 1:
            starts.append(index + 3)
            index += 3
        else:
            index += 1
    for k, start in enumerate(starts):
        end = starts[k + 1] - 3 if k + 1 < len(starts) else len(stream)
        while end > start and stream[end - 1] == 0:
            end -= 1
        yield stream[start:end]


def main():
    if len(sys.argv) not in (2, 3):
        sys.exit(__doc__)
    directory = sys.argv[1]
    with open(os.path.join(directory, 'ld-420-10bit.vvc'), 'rb') as file:
        stream = file.read()
    out = bytearray()
    picture = -1
    first = True
    for unit in units(stream):
        nal_type = unit[1] >> 3
        # a picture starts at its picture header or at a slice carrying the picture header
        if nal_type == 19 or (nal_type <= 11 and unit[2] & 0x80):
            picture += 1
            if picture in MESSAGES:
                out += b'\x00\x00\x00\x01' + sei_nal(MESSAGES[picture])
        out += b'\x00\x00\x00\x01' + unit
    vector = os.path.join(directory, 'sei-h274-420-10bit.vvc')
    with open(vector, 'wb') as file:
        file.write(out)
    print('sei-h274-420-10bit.vvc', hashlib.sha256(out).hexdigest())
    if len(sys.argv) == 3:
        dump = subprocess.run([sys.argv[2], vector], capture_output=True, text=True, check=True).stdout
        with open(os.path.join(directory, 'sei-h274-420-10bit.vvdec.txt'), 'w', newline='\n') as file:
            file.write('# ' + hashlib.sha256(out).hexdigest() + '\n' + dump)


if __name__ == '__main__':
    main()
