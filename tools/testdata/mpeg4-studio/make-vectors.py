"""Writes the MPEG-4 Studio profile test vectors of Smalt (self-generated; see the README beside this script).

The streams are intra-only Simple Studio layers coded by this script, in two modes:

- DPCM macroblocks code the source losslessly, so a conforming decode equals the source exactly; the vectors cover
  every chroma format, sample precision, frame and field VOPs, progressive layers, both DPCM scan orders, R'G'B'
  layers, quant matrix extensions and slice layouts.
- DCT macroblocks quantize the source with the syntax that FFmpeg (black box) and the ISO/IEC 14496-4 conformance
  streams use, for comparison with FFmpeg's decode within the inverse DCT noise floor.

Usage: python -I make-vectors.py <output directory>
Writes <name>.m4v for every vector and vectors.tsv (name, mode, width, height, chroma, bits, frames, MD5 of the
expected planar output of a lossless vector or '-').
"""
import hashlib
import math
import os
import sys
AC_CODES = {
    0: {0: '0101', 1: '0011', 2: '00011', 3: '000101', 4: '0000100', 5: '0000011', 6: '00000101', 8: '0100', 9: '0000101', 10: '000001001', 11: '00000100001', 12: '000001000001', 13: '0000010000001', 17: '11', 18: '101', 19: '100', 20: '011', 21: '0010', 22: '000100', 23: '000011', 24: '0000010001', 25: '0000010000000'},
    8: {18: '1', 19: '01', 20: '001', 21: '0001', 22: '00001', 23: '0000011', 24: '00000101', 25: '00000100'},
    16: {0: '101', 1: '100', 2: '011', 3: '0011', 4: '0010', 5: '000011', 6: '0000010001', 8: '00011', 9: '00010', 10: '0000011', 11: '000001001', 12: '00000100000011', 13: '00000100000010', 17: '11', 18: '010', 19: '000010', 20: '00000101', 21: '00000100001', 22: '0000010000011', 23: '00000100000001', 24: '000001000000001', 25: '0000010000010'},
    17: {0: '00101', 1: '011', 2: '0100', 3: '0011', 4: '00011', 5: '00000101', 6: '0000010000001', 8: '0101', 9: '00100', 10: '000011', 11: '000001001', 12: '0000010000011', 13: '0000010000010', 17: '11', 18: '10', 19: '00010', 20: '000010', 21: '0000011', 22: '0000010001', 23: '000001000011', 24: '0000010000000', 25: '000001000010'},
    18: {0: '0000101', 1: '011', 2: '0010', 3: '000101', 4: '0000011', 5: '0000010001', 6: '000001000000001', 8: '0011', 9: '00011', 10: '0000100', 11: '000001001', 12: '0000010000011', 13: '00000100000001', 17: '11', 18: '10', 19: '010', 20: '000100', 21: '000011', 22: '00000101', 23: '00000100001', 24: '0000010000010', 25: '0000010000001'},
    19: {0: '0000010011', 1: '00010', 2: '0000100', 3: '00000101', 4: '00000100011', 5: '0000010000001', 6: '00000100000001', 8: '00011', 9: '0000101', 10: '0000010010', 11: '000001000011', 12: '0000010000011', 13: '0000010000010', 17: '11', 18: '10', 19: '01', 20: '001', 21: '000011', 22: '0000011', 23: '00000100010', 24: '000001000000001', 25: '000001000010'},
    20: {0: '00000100011', 1: '000011', 2: '00000110', 3: '0000010010', 4: '000001000011', 5: '00000100000001', 6: '000001000000001', 8: '0001', 9: '00000111', 10: '0000010011', 11: '00000100010', 12: '00000100000011', 13: '00000100000010', 17: '011', 18: '010', 19: '11', 20: '10', 21: '001', 22: '000010', 23: '00000101', 24: '000001000010', 25: '000001000001'},
    21: {0: '000001011', 1: '0000101', 2: '00000110', 3: '000001010', 4: '0000010011', 5: '00000100010', 6: '000001000000', 8: '00011', 9: '00000111', 10: '0000111', 11: '0000110', 12: '00000100011', 13: '000001000001', 17: '0011', 18: '0010', 19: '11', 20: '10', 21: '01', 22: '00010', 23: '0000100', 24: '0000010010', 25: '00000100001'},
    22: {0: '0000010101', 1: '00000110', 2: '000001011', 3: '0000010011', 4: '0000010010', 5: '000001000010', 6: '0000010000000', 8: '000011', 9: '0000010100', 10: '00000100011', 11: '00000111', 12: '000001000011', 13: '0000010000001', 17: '0001', 18: '011', 19: '010', 20: '11', 21: '10', 22: '001', 23: '000010', 24: '00000100010', 25: '000001000001'},
    23: {0: '000001000011', 1: '0000101', 2: '0000100', 3: '00000110', 4: '0000010010', 5: '000001000001', 6: '000001000000', 8: '000101', 9: '00000111', 10: '000100', 11: '000011', 12: '0000010011', 13: '000001000010', 17: '00011', 18: '0011', 19: '011', 20: '010', 21: '11', 22: '10', 23: '0010', 24: '00000101', 25: '0000010001'},
    24: {0: '0000010000011', 1: '0000100', 2: '00000110', 3: '000001011', 4: '000001010', 5: '0000010001', 6: '0000010000000', 8: '0000101', 9: '00000111', 10: '0011', 11: '00000100001', 12: '0000010000010', 13: '0000010000001', 17: '00011', 18: '00010', 19: '0010', 20: '011', 21: '010', 22: '11', 23: '10', 24: '000011', 25: '000001001'},
    25: {0: '0000010011', 1: '00010', 2: '000010', 3: '00000111', 4: '000001011', 5: '0000010010', 6: '0000010001', 8: '00011', 9: '000011', 10: '000001101', 11: '000001100', 12: '00000100001', 13: '00000100000', 17: '101', 18: '100', 19: '0101', 20: '0100', 21: '0011', 22: '0010', 23: '011', 24: '11', 25: '000001010'},
}
DC_LUMA = ['001110', '00110', '0000', '0010', '111', '101', '011', '010', '100', '110', '0001', '0011110', '00111110', '001111110', '0011111110', '00111111110', '001111111110', '0011111111110', '0011111111111']
DC_CHROMA = ['0000', '0010', '111', '101', '011', '010', '100', '110', '0001', '00110', '001110', '0011110', '00111110', '001111110', '0011111110', '00111111110', '001111111110', '0011111111110', '0011111111111']

ZIGZAG = [0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14,
          21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60,
          61, 54, 47, 55, 62, 63]
ALTERNATE = [0, 8, 16, 24, 1, 9, 2, 10, 17, 25, 32, 40, 48, 56, 57, 49, 41, 33, 26, 18, 3, 11, 4, 12, 19, 27, 34, 42,
             50, 58, 35, 43, 51, 59, 20, 28, 5, 13, 6, 14, 21, 29, 36, 44, 52, 60, 37, 45, 53, 61, 22, 30, 7, 15, 23,
             31, 38, 46, 54, 62, 39, 47, 55, 63]
DEFAULT_INTRA = [8, 17, 18, 19, 21, 23, 25, 27, 17, 18, 19, 21, 23, 25, 27, 28, 20, 21, 22, 23, 24, 26, 28, 30, 21, 22,
                 23, 24, 26, 28, 30, 32, 22, 23, 24, 26, 28, 30, 32, 35, 23, 24, 26, 28, 30, 32, 35, 38, 25, 26, 28,
                 30, 32, 35, 38, 41, 27, 28, 30, 32, 35, 38, 41, 45]
CHROMA_CODES = {420: 1, 422: 2, 444: 3}


class Bits:
    def __init__(self):
        self.bits = []

    def put(self, value, count):
        for i in range(count - 1, -1, -1):
            self.bits.append((value >> i) & 1)

    def code(self, text):
        self.bits.extend(int(c) for c in text)

    def start(self, code):
        while len(self.bits) % 8:
            self.bits.append(0)
        self.put(0x000001, 24)
        self.put(code, 8)

    def data(self):
        while len(self.bits) % 8:
            self.bits.append(0)
        out = bytearray()
        for i in range(0, len(self.bits), 8):
            value = 0
            for bit in self.bits[i:i + 8]:
                value = (value << 1) | bit
            out.append(value)
        return bytes(out)


def source(width, height, chroma, bits, frames, seed, flat=False):
    """Deterministic test pictures: gradients, a moving checker and noise, per component, in [0, 2^bits)."""
    cw = width if chroma == 444 else width // 2
    ch = height // 2 if chroma == 420 else height
    state = [seed * 2654435761 % 4294967296 or 1]

    def noise():
        state[0] = (state[0] * 1103515245 + 12345) % 2147483648
        return state[0] >> 16

    top = (1 << bits) - 1
    pictures = []
    for f in range(frames):
        planes = []
        for p, (w, h) in enumerate(((width, height), (cw, ch), (cw, ch))):
            plane = []
            for y in range(h):
                for x in range(w):
                    if flat:
                        v = (top * (x + 2 * y + 3 * f + 5 * p)) // (2 * w + 4 * h + 16)
                    else:
                        v = (x * 7 + y * 3 + f * 11 + p * 50) * top // (7 * w + 3 * h + 120)
                        if ((x >> 3) + (y >> 3) + f) & 1:
                            v = top - v
                        v += noise() % 9 - 4
                    plane.append(max(0, min(top, v)))
            planes.append(plane)
        pictures.append(planes)
    return pictures, cw, ch


def write_vol(b, layer):
    if layer.get('vos'):
        b.start(0xB0)
        b.put(layer['vos'], 8)
        b.start(0xB5)
        b.put(0, 1)
        b.put(1, 4)
        b.put(0, 1)
    b.start(0x00)
    b.start(0x20)
    b.put(0, 1)
    b.put(layer.get('object', 0x0F), 8)
    b.put(4, 4)
    b.put(0, 2)
    b.put(15, 4)
    b.put(1 if layer['progressive'] else 0, 1)
    b.put(1 if layer.get('rgb') else 0, 1)
    b.put(CHROMA_CODES[layer['chroma']], 2)
    b.put(layer['bits'], 4)
    b.put(1, 1)
    b.put(layer['width'], 14)
    b.put(1, 1)
    b.put(layer['height'], 14)
    b.put(1, 1)
    b.put(1, 4)
    b.put(layer.get('rate', 3), 4)
    for value, count in ((1000, 15), (1, 1), (0, 15), (1, 1), (100, 15), (1, 1), (0, 3), (50, 11), (1, 1), (0, 15),
                         (1, 1)):
        b.put(value, count)
    b.put(1, 1)
    b.put(0, 1)
    b.put(0, 1)
    if layer.get('matrix'):
        b.start(0xB8)
        b.put(3, 4)
        b.put(1, 1)
        for i in range(64):
            b.put(layer['matrix'][ZIGZAG[i]], 8)
        b.put(0, 1)
        b.put(1 if layer.get('chroma_matrix') else 0, 1)
        if layer.get('chroma_matrix'):
            for i in range(64):
                b.put(layer['chroma_matrix'][ZIGZAG[i]], 8)
        b.put(0, 1)
    if layer.get('display'):
        b.start(0xB8)
        b.put(2, 4)
        b.put(5, 3)
        b.put(0, 1)
        b.put(1, 1)
        b.put(layer['display'][0], 8)
        b.put(layer['display'][1], 8)
        b.put(layer['display'][2], 8)
        b.put(layer['width'], 14)
        b.put(1, 1)
        b.put(layer['height'], 14)
    if layer.get('user'):
        b.start(0xB2)
        for c in b'smalt studio vector':
            b.put(c, 8)


def write_vop_header(b, vop, structure, coded=True):
    b.start(0xB6)
    for _ in range(4):
        b.put(0xFFFF, 16)
        b.put(1, 1)
    b.put(0, 4)
    b.put(vop['tr'] % 1024, 10)
    b.put(structure, 2)
    b.put(0, 2)
    b.put(1 if coded else 0, 1)
    if not coded:
        return
    b.put(1 if vop.get('tff', 1) else 0, 1)
    b.put(0, 1)
    b.put(1 if structure == 3 and vop.get('progressive_frame') else 0, 1)
    b.put(1 if vop.get('reset') else 0, 1)
    b.put(1 if vop.get('alternate') else 0, 1)
    b.put(1 if vop.get('frame_dct', True) else 0, 1)
    b.put(vop.get('dp', 0), 2)
    b.put(vop.get('idc', 0), 2)
    b.put(1 if vop.get('nonlinear') else 0, 1)
    b.put(0, 1)
    b.put(0, 1)
    if vop.get('user'):
        b.start(0xB2)
        for c in b'vop user data':
            b.put(c, 8)


def dpcm_value(target, pred, pred2, mask, bits):
    delta = (target - pred) & mask
    if delta >= 1 << (bits - 1):
        delta -= 1 << bits
    if pred2 > pred:
        delta = -delta
    return 2 * delta if delta >= 0 else -2 * delta - 1


def dpcm_block(block, width, height, bits, reverse, rice_choice):
    """Codes one block losslessly; returns the symbols (residual values) and the block mean."""
    mask = (1 << bits) - 1
    mean = sum(block) // len(block)
    start = 1 << (bits - 1)
    values = [0] * (width * height)
    for i in range(height):
        line = height - 1 - i if reverse else i
        current = start
        top = start
        for j in range(width):
            left = current
            top_left = top
            if i != 0:
                top = block[(line + 1 if reverse else line - 1) * width + j]
            pred = top + left - top_left
            lo = min(top, left)
            hi = top + left - lo
            pred = max(lo, min(hi, pred))
            pred2 = (min(top_left, lo) + max(top_left, hi)) >> 1
            if pred == pred2:
                pred2 = mean
            target = block[line * width + j]
            values[i * width + j] = dpcm_value(target, pred, pred2, mask, bits)
            current = target
    best = None
    for rice in ([rice_choice] if rice_choice is not None else range(0, 12)):
        cost = 0
        for v in values:
            p = v >> rice
            cost += 12 + bits if p >= 11 else p + 1 + rice
        if best is None or cost < best[0]:
            best = (cost, rice)
    return values, mean, best[1]


def put_dpcm(b, values, mean, rice, bits, rice15):
    b.put(mean, bits)
    b.put(15 if rice == 0 and rice15 else rice, 4)
    for v in values:
        p = v >> rice
        if p >= 11:
            b.code('0' * 11 + '1')
            b.put(v, bits)
        else:
            b.code('0' * p + '1')
            if rice:
                b.put(v & ((1 << rice) - 1), rice)


COS = [[(math.sqrt(0.125) if u == 0 else 0.5) * math.cos((2 * x + 1) * u * math.pi / 16) for x in range(8)]
       for u in range(8)]


def fdct(block):
    out = [0.0] * 64
    for v in range(8):
        for u in range(8):
            s = 0.0
            for y in range(8):
                cy = COS[v][y]
                for x in range(8):
                    s += cy * COS[u][x] * block[y * 8 + x]
            out[v * 8 + u] = s
    return out


def put_dc(b, diff, chroma):
    size = 0 if diff == 0 else abs(diff).bit_length()
    b.code((DC_CHROMA if chroma else DC_LUMA)[size])
    if size:
        b.put(diff if diff > 0 else diff + (1 << size) - 1, size)
        if size > 8:
            b.put(1, 1)


def put_symbol(b, context, group):
    b.code(AC_CODES[context][group])


def put_ac(b, levels, escape_bits):
    """Codes the 63 AC levels (scan order, index 1..63) of one intra block."""
    context = 0
    i = 1
    while True:
        nxt = i
        while nxt < 64 and levels[nxt] == 0:
            nxt += 1
        if nxt == 64:
            put_symbol(b, context, 0)
            return
        run = nxt - i
        level = levels[nxt]
        if abs(level) == 1 and run > 0:
            size = run.bit_length()
            put_symbol(b, context, size)
            add = ((run - (1 << (size - 1))) << 1) | (1 if level > 0 else 0)
            b.put(add, size)
            context = 16
        else:
            if run > 0:
                size = run.bit_length() - 1
                put_symbol(b, context, 8 + size)
                if size:
                    b.put(run - (1 << size), size)
                context = 8
            magnitude = abs(level)
            if magnitude < 256:
                size = magnitude.bit_length()
                put_symbol(b, context, 16 + size)
                b.put(level if level > 0 else level + (1 << size) - 1, size)
                context = 16 + size
            else:
                put_symbol(b, context, 25)
                b.put(level & ((1 << escape_bits) - 1), escape_bits)
                context = 25
        i = nxt + 1


def dct_macroblock(b, blocks, chroma_flags, vop, layer, q, predictors, matrices):
    """Codes the 8x8 blocks of a DCT macroblock; blocks are sample lists in coding order."""
    bits = layer['bits']
    dp = vop.get('dp', 0)
    idc = vop.get('idc', 0)
    scan = ALTERNATE if vop.get('alternate') else ZIGZAG
    qs = q * 2 if not vop.get('nonlinear') else q
    escape_bits = bits + dp + 4
    limit = (1 << (escape_bits - 1)) - 1
    for k, block in enumerate(blocks):
        chroma = chroma_flags[k]
        component = 0 if not chroma else 1 + (k % 2)
        f = fdct(block)
        dc = int(math.floor(f[0] * (1 << dp) / (8 >> idc) + 0.5))
        put_dc(b, dc - predictors[component], chroma)
        predictors[component] = dc
        matrix = matrices[1 if chroma else 0]
        levels = [0] * 64
        for i in range(1, 64):
            pos = scan[i]
            value = f[pos] * 32 * (1 << dp) / (2 * matrix[pos] * qs)
            levels[i] = max(-limit, min(limit, int(round(value))))
        put_ac(b, levels, escape_bits)


def blocks_of(planes, width, cw, mbx, mby, chroma, field_dct):
    """The 8x8 blocks of a macroblock in coding order, with their chroma flags."""
    y = planes[0]
    out = []
    flags = []
    for k in range(4):
        bx = (k & 1) * 8
        block = []
        for r in range(8):
            row = mby * 16 + (2 * r + (k >> 1) if field_dct else (k >> 1) * 8 + r)
            block.extend(y[row * width + mbx * 16 + bx + c] for c in range(8))
        out.append(block)
        flags.append(False)
    mcw = 16 if chroma == 444 else 8
    mch = 8 if chroma == 420 else 16
    count = (mcw // 8) * (mch // 8)
    chroma_field = field_dct and chroma != 420
    for n in range(count):
        if mch == 8:
            block_row, block_column = 0, n
        elif mcw == 8:
            block_row, block_column = n & 1, 0
        else:
            block_row, block_column = n & 1, n >> 1
        for p in (1, 2):
            block = []
            for r in range(8):
                row = mby * mch + (2 * r + block_row if chroma_field else block_row * 8 + r)
                block.extend(planes[p][row * cw + mbx * mcw + block_column * 8 + c] for c in range(8))
            out.append(block)
            flags.append(True)
    return out, flags


def code_picture(b, planes, width, height, cw, ch, layer, vop, structure, rows):
    """Codes one frame or field picture of the given planes (already split into a field when needed)."""
    chroma = layer['chroma']
    bits = layer['bits']
    mbw = (width + 15) // 16
    total = mbw * rows
    address_bits = max(1, total.bit_length())
    mode = vop.get('mode', 'dpcm')
    dp = vop.get('dp', 0)
    idc = vop.get('idc', 0)
    reset = 1 << (bits + dp + idc - 1)
    mcw = 16 if chroma == 444 else 8
    mch = 8 if chroma == 420 else 16
    matrices = (layer.get('matrix') or DEFAULT_INTRA,
                layer.get('chroma_matrix') or layer.get('matrix') or DEFAULT_INTRA)
    slice_rows = vop.get('slice_rows', 1)
    q = vop.get('q', 8)
    mb = 0
    while mb < total:
        b.start(0xB7)
        b.put(mb, address_bits)
        b.put(q, 5)
        if vop.get('slice_ext'):
            b.put(1, 1)
            b.put(1, 1)
            b.put(0, 1)
            b.put(5, 6)
            b.put(1, 1)
            b.put(0x5A, 8)
            b.put(0, 1)
        else:
            b.put(0, 1)
        predictors = [reset, reset, reset]
        current_q = q
        end = min(total, mb + slice_rows * mbw)
        while mb < end:
            if vop.get('reset'):
                predictors = [reset, reset, reset]
            mbx, mby = mb % mbw, mb // mbw
            use_dct = mode == 'dct' or (mode == 'mixed' and (mbx + mby) % 3 != 0)
            new_q = current_q
            if use_dct and vop.get('vary_q'):
                new_q = 1 + (mb * 7) % 31
            b.put(1 if use_dct else 0, 1)
            if new_q != current_q:
                b.code('01')
            else:
                b.code('1')
            field_dct = False
            if use_dct and structure == 3 and not layer['progressive'] and not vop.get('frame_dct', True):
                field_dct = (mbx + mby) % 2 == 1
                b.put(1 if field_dct else 0, 1)
            if new_q != current_q:
                b.put(new_q, 5)
                current_q = new_q
            if use_dct:
                blocks, flags = blocks_of(planes, width, cw, mbx, mby, chroma, field_dct)
                dct_macroblock(b, blocks, flags, vop, layer, current_q, predictors, matrices)
            else:
                reverse = vop.get('reverse', False) ^ ((mbx + mby) % 2 == 1 and vop.get('mixed_scan', False))
                b.put(1 if reverse else 0, 1)
                for p, (w, h) in enumerate(((16, 16), (mcw, mch), (mcw, mch))):
                    stride = width if p == 0 else cw
                    block = [planes[p][(mby * h + r) * stride + mbx * w + c] for r in range(h) for c in range(w)]
                    values, mean, rice = dpcm_block(block, w, h, bits, reverse, vop.get('rice'))
                    put_dpcm(b, values, mean, rice, bits, vop.get('rice15', False))
                    predictors[p] = mean << (dp + idc)
            mb += 1


def field(plane, width, height, parity):
    return [plane[(2 * r + parity) * width + c] for r in range(height // 2) for c in range(width)]


def make(name, layer, vops, out_dir, rows_out):
    display_w, display_h = layer['width'], layer['height']
    rows = (display_h + 15) // 16 if layer['progressive'] else ((display_h + 31) // 32) * 2
    width, height = (display_w + 15) // 16 * 16, rows * 16
    pictures, cw, ch = source(width, height, layer['chroma'], layer['bits'], len(vops), layer.get('seed', 1),
                              layer.get('flat', False))
    dcw = display_w if layer['chroma'] == 444 else display_w // 2
    dch = display_h // 2 if layer['chroma'] == 420 else display_h
    b = Bits()
    write_vol(b, layer)
    expected = hashlib.md5()
    lossless = all(v.get('mode', 'dpcm') == 'dpcm' for v in vops)
    for index, (vop, planes) in enumerate(zip(vops, pictures)):
        vop.setdefault('tr', index)
        if vop.get('not_coded'):
            write_vop_header(b, vop, 3, coded=False)
            continue
        if vop.get('fields'):
            order = (0, 1) if vop.get('tff', 1) else (1, 0)
            for parity in order:
                halves = [field(planes[0], width, height, parity), field(planes[1], cw, ch, parity),
                          field(planes[2], cw, ch, parity)]
                write_vop_header(b, vop, 1 + parity)
                code_picture(b, halves, width, height // 2, cw, ch // 2, layer, vop, 1 + parity, rows // 2)
        else:
            write_vop_header(b, vop, 3)
            code_picture(b, planes, width, height, cw, ch, layer, vop, 3, rows)
        if lossless:
            for p, plane in enumerate(planes):
                w, h, stride = (display_w, display_h, width) if p == 0 else (dcw, dch, cw)
                plane = [plane[r * stride + c] for r in range(h) for c in range(w)]
                if layer['bits'] == 8:
                    expected.update(bytes(plane))
                else:
                    expected.update(b''.join(v.to_bytes(2, 'little') for v in plane))
    b.start(0xB1)
    data = b.data()
    with open(os.path.join(out_dir, name + '.m4v'), 'wb') as f:
        f.write(data)
    frames = sum(1 for v in vops if not v.get('not_coded'))
    rows_out.append((name, 'dpcm' if lossless else 'dct', display_w, display_h, layer['chroma'], layer['bits'], frames,
                     expected.hexdigest() if lossless else '-', hashlib.sha256(data).hexdigest()))


def vectors():
    def frames(n, **kw):
        return [dict(kw) for _ in range(n)]

    yield 'studio-dpcm-422-10-interlaced', dict(width=64, height=64, chroma=422, bits=10, progressive=False), \
        frames(2)
    yield 'studio-dpcm-420-8-progressive', dict(width=48, height=32, chroma=420, bits=8, progressive=True), \
        frames(2)
    yield 'studio-dpcm-444-12-progressive', dict(width=32, height=32, chroma=444, bits=12, progressive=True), \
        frames(2)
    yield 'studio-dpcm-444-10-rgb', dict(width=32, height=32, chroma=444, bits=10, progressive=True, rgb=True), \
        frames(1)
    yield 'studio-dpcm-422-10-fields-tff', dict(width=48, height=64, chroma=422, bits=10, progressive=False), \
        frames(2, fields=True, tff=1)
    yield 'studio-dpcm-422-10-fields-bff', dict(width=48, height=64, chroma=422, bits=10, progressive=False), \
        frames(2, fields=True, tff=0)
    yield 'studio-dpcm-420-12-fields', dict(width=32, height=64, chroma=420, bits=12, progressive=False), \
        frames(1, fields=True)
    yield 'studio-dpcm-422-8-cropped', dict(width=40, height=24, chroma=422, bits=8, progressive=True), frames(2)
    yield 'studio-dpcm-options', dict(width=48, height=64, chroma=422, bits=10, progressive=False, vos=0xE1,
                                      object=0x10, user=True, rate=5, seed=7), \
        [dict(reverse=True, rice15=True, slice_ext=True, slice_rows=2, reset=True, user=True, rice=0),
         dict(not_coded=True), dict(mixed_scan=True, slice_rows=4)]
    yield 'studio-dct-422-10-interlaced', dict(width=64, height=64, chroma=422, bits=10, progressive=False,
                                               flat=True), frames(2, mode='dct', q=4)
    yield 'studio-dct-420-8-progressive', dict(width=64, height=48, chroma=420, bits=8, progressive=True,
                                               flat=True), frames(2, mode='dct', q=3)
    yield 'studio-dct-444-12-progressive', dict(width=48, height=32, chroma=444, bits=12, progressive=True,
                                                flat=True), frames(1, mode='dct', q=2)
    yield 'studio-dct-444-10-progressive', dict(width=48, height=32, chroma=444, bits=10, progressive=True),         frames(2, mode='dct', q=3)
    yield 'studio-dct-422-10-field-dct', dict(width=64, height=64, chroma=422, bits=10, progressive=False), \
        frames(1, mode='dct', q=6, frame_dct=False)
    yield 'studio-dct-422-10-tools', dict(width=64, height=64, chroma=422, bits=10, progressive=False,
                                          matrix=[16 + (i % 23) for i in range(64)],
                                          chroma_matrix=[12 + (i % 31) for i in range(64)],
                                          display=(1, 1, 1)), \
        frames(2, mode='dct', q=5, alternate=True, dp=2, idc=3, nonlinear=True, vary_q=True)
    yield 'studio-mixed-422-10', dict(width=64, height=64, chroma=422, bits=10, progressive=False), \
        frames(2, mode='mixed', q=3)
    yield 'studio-dct-422-10-fields', dict(width=48, height=64, chroma=422, bits=10, progressive=False,
                                           flat=True), frames(1, mode='dct', fields=True, q=4)


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    rows = []
    for name, layer, vops in vectors():
        make(name, layer, vops, out_dir, rows)
    with open(os.path.join(out_dir, 'vectors.tsv'), 'w', newline='\n') as f:
        f.write('# name\tmode\twidth\theight\tchroma\tbits\tframes\toutput md5\tstream sha256\n')
        for row in rows:
            f.write('\t'.join(str(v) for v in row) + '\n')


if __name__ == '__main__':
    main()
