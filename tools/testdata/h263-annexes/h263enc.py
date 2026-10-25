"""An H.263 encoder written from the text of ITU-T H.263 (01/2005) for the optional modes the TMN encoder lacks:
Continuous Presence Multipoint (Annex C), rectangular slices with arbitrary slice order (Annex K), Reference Picture
Selection (Annex N), Reference Picture Resampling (Annex P, with the incremental algorithm of P.4),
Reduced-Resolution Update (Annex Q), Independent Segment Decoding (Annex R) and Enhanced Reference Picture Selection
(Annex U), together with B-pictures (Annex O) and Improved PB-frames (Annex M) combined with them.

The encoder makes simple coding decisions over a small search; it exists to produce streams whose decoding every
mode's rules determine, and computes the decoder reconstruction with the double precision reference inverse DCT of
the test models, so a decoder using that transform reproduces it exactly. The B-part of Improved PB-frames is
deblocked like the TMN reference software does it, with BQUANT setting the filter strength, since the Recommendation
leaves that filtering open (J.1).

usage: python -I h263enc.py <Tables.java> <source.yuv> <source width> <source height> <configuration> <out prefix>

make-vectors.py runs every configuration of CONFIGS for H263AnnexStreamsTest.
"""
import math
import re
import sys

# ---------------------------------------------------------------- bit writer


class Bits:
    def __init__(self):
        self.bits = []

    def put(self, value, count):
        for i in range(count - 1, -1, -1):
            self.bits.append((value >> i) & 1)

    def code(self, text):
        for ch in text:
            if ch in '01':
                self.bits.append(int(ch))

    def align(self):
        while len(self.bits) % 8:
            self.bits.append(0)

    def data(self):
        self.align()
        out = bytearray()
        for i in range(0, len(self.bits), 8):
            v = 0
            for b in self.bits[i:i + 8]:
                v = v << 1 | b
            out.append(v)
        return bytes(out)


# ---------------------------------------------------------------- tables

MCBPC_I = {(3, 0): '1', (3, 1): '001', (3, 2): '010', (3, 3): '011', (4, 0): '0001', (4, 1): '000001',
           (4, 2): '000010', (4, 3): '000011'}
MCBPC_P = ['1', '0011', '0010', '000101', '011', '0000111', '0000110', '000000101', '010', '0000101', '0000100',
           '00000101', '00011', '00000100', '00000011', '0000011', '000100', '000000100', '000000011', '000000010']
MCBPC_P5 = ['00000000010', '0000000001100', '0000000001110', '0000000001111']
CBPY = ['0011', '00101', '00100', '1001', '00011', '0111', '000010', '1011', '00010', '000011', '0101', '1010',
        '0100', '1000', '0110', '11']
MVD_NEGATIVE = ['0000000000101', '0000000000111', '000000000101', '000000000111', '000000001001', '000000001011',
                '000000001101', '000000001111', '00000001001', '00000001011', '00000001101', '00000001111',
                '00000010001', '00000010011', '00000010101', '00000010111', '00000011001', '00000011011',
                '00000011101', '00000011111', '00000100001', '00000100011', '0000010011', '0000010101',
                '0000010111', '00000111', '00001001', '00001011', '0000111', '00011', '0011', '011']


def mvd_code(index):
    if index == 32:
        return '1'
    if index < 32:
        return MVD_NEGATIVE[index]
    c = MVD_NEGATIVE[64 - index]
    return c[:-1] + ('0' if c[-1] == '1' else '1')


TCOEF = {}


def load_tcoef(path):
    text = open(path, encoding='utf-8').read()
    block = text[text.index('INTER_ROWS = {'):]
    block = block[:block.index('};')]
    for row in re.findall(r'"([01 ]+):(\d):(\d+):(\d+)"', block):
        TCOEF[(int(row[1]), int(row[2]), int(row[3]))] = row[0].replace(' ', '')
    assert len(TCOEF) == 102, len(TCOEF)


def reversible(value):
    """Table D.3."""
    if value == 0:
        return '1'
    m = abs(value)
    b = bin(m)[3:]
    return '0' + ''.join(x + '1' for x in b) + ('1' if value < 0 else '0') + '0'


def index_code(value):
    """Table U.1."""
    if value == 0:
        return '1'
    b = bin(value + 1)[3:]
    out = '0'
    for i, x in enumerate(b):
        out += x + ('1' if i + 1 < len(b) else '0')
    return out


# ---------------------------------------------------------------- transforms

COS = [[(math.sqrt(0.125) if i == 0 else 0.5) * math.cos((2 * j + 1) * i * math.pi / 16) for j in range(8)]
       for i in range(8)]


def fdct(block):
    out = [0.0] * 64
    for v in range(8):
        for u in range(8):
            s = 0.0
            for y in range(8):
                for x in range(8):
                    s += COS[v][y] * COS[u][x] * block[y * 8 + x]
            out[v * 8 + u] = s
    return out


BASIS = [[(math.sqrt(0.125) if f == 0 else 0.5) * math.cos((math.pi / 8.0) * f * (t + 0.5)) for t in range(8)]
         for f in range(8)]


def idct(coef):
    """coef in raster order (v rows, u columns): the separable double precision reference transform of the test
    models, rounding to nearest and clipping to -256..255 (6.2.4)."""
    rows = [0.0] * 64
    for i in range(8):
        for j in range(8):
            partial = 0.0
            for k in range(8):
                partial += BASIS[k][j] * coef[i * 8 + k]
            rows[8 * i + j] = partial
    out = [0] * 64
    for j in range(8):
        for i in range(8):
            partial = 0.0
            for k in range(8):
                partial += BASIS[k][i] * rows[8 * k + j]
            v = math.floor(partial + 0.5)
            out[8 * i + j] = max(-256, min(255, int(v)))
    return out


ORDER = [0, 1, 5, 6, 14, 15, 27, 28, 2, 4, 7, 13, 16, 26, 29, 42, 3, 8, 12, 17, 25, 30, 41, 43, 9, 11, 18, 24, 31,
          40, 44, 53, 10, 19, 23, 32, 39, 45, 52, 54, 20, 22, 33, 38, 46, 51, 55, 60, 21, 34, 37, 47, 50, 56, 59, 61,
          35, 36, 48, 49, 57, 58, 62, 63]
ZIGZAG = [0] * 64
for _pos, _idx in enumerate(ORDER):
    ZIGZAG[_idx] = _pos


def quantize(coef, q, intra):
    levels = [0] * 64
    for i in range(64):
        if intra and i == 0:
            continue
        c = coef[i]
        if intra:
            lv = int(abs(c) // (2 * q))
        else:
            lv = int(max(0, abs(c) - q / 2) // (2 * q))
        lv = min(lv, 127)
        levels[i] = -lv if c < 0 else lv
    if intra:
        dc = int(round(coef[0] / 8))
        levels[0] = max(1, min(254, dc))
    return levels


def dequantize(levels, q, intra):
    rec = [0] * 64
    for i in range(64):
        if intra and i == 0:
            rec[0] = 1024 if levels[0] == 128 else levels[0] * 8
            continue
        lv = levels[i]
        if lv:
            mag = q * (2 * abs(lv) + 1) - (1 if q % 2 == 0 else 0)
            v = -mag if lv < 0 else mag
            rec[i] = max(-2048, min(2047, v))
    return rec


def write_block(bw, levels, intra):
    """INTRADC (intra) and TCOEF events in zigzag order."""
    start = 0
    if intra:
        dc = levels[0]
        bw.put(255 if dc == 128 else dc, 8)
        start = 1
    events = []
    run = 0
    for k in range(start, 64):
        lv = levels[ZIGZAG[k]]
        if lv == 0:
            run += 1
            continue
        events.append([0, run, lv])
        run = 0
    if not events:
        return False
    events[-1][0] = 1
    for last, run, lv in events:
        key = (last, run, abs(lv))
        if key in TCOEF:
            bw.code(TCOEF[key] + ('1' if lv < 0 else '0'))
        else:
            bw.code('0000011')
            bw.put(last, 1)
            bw.put(run, 6)
            bw.put(lv & 0xFF, 8)
    return True


def has_coefficients(levels, intra):
    return any(levels[i] for i in range(1 if intra else 0, 64))


# ---------------------------------------------------------------- pictures


class Picture:
    def __init__(self, width, height):
        self.width = width
        self.height = height
        self.cw = (width + 15) // 16 * 16
        self.ch = (height + 15) // 16 * 16
        self.stride = (self.cw + 31) // 32 * 32
        self.rows = (self.ch + 31) // 32 * 32
        self.y = bytearray(self.stride * self.rows)
        self.u = bytearray((self.stride // 2) * (self.rows // 2))
        self.v = bytearray((self.stride // 2) * (self.rows // 2))
        self.tr = 0
        self.pn = 0
        self.rounding = 0
        self.vectors = {}

    def plane(self, c):
        return (self.y, self.stride) if c == 0 else ((self.u if c == 1 else self.v), self.stride // 2)

    def dump(self):
        out = bytearray()
        for r in range(self.height):
            out += self.y[r * self.stride:r * self.stride + self.width]
        for p in (self.u, self.v):
            for r in range(self.height // 2):
                out += p[r * (self.stride // 2):r * (self.stride // 2) + self.width // 2]
        return bytes(out)


def source_picture(data, sw, sh, index, width, height):
    """Takes a frame of the source, cropped to width x height."""
    frame = sw * sh * 3 // 2
    base = index * frame
    p = Picture(width, height)
    for r in range(p.ch):
        for c in range(p.cw):
            p.y[r * p.stride + c] = data[base + min(r, sh - 1) * sw + min(c, sw - 1)]
    for k, plane in enumerate((p.u, p.v)):
        off = base + sw * sh + k * (sw // 2) * (sh // 2)
        for r in range(p.ch // 2):
            for c in range(p.cw // 2):
                plane[r * (p.stride // 2) + c] = data[off + min(r, sh // 2 - 1) * (sw // 2) + min(c, sw // 2 - 1)]
    return p


def sample(pic, comp, x, y, clip):
    plane, stride = pic.plane(comp)
    x = max(clip[0], min(clip[2], x))
    y = max(clip[1], min(clip[3], y))
    return plane[y * stride + x]


def predict(pic, comp, x0, y0, w, h, mvx, mvy, rounding, clip):
    """Half sample bilinear interpolation (6.1.2) with the edges of the clip region extended (D.1)."""
    out = [0] * (w * h)
    ix, fx = mvx >> 1, mvx & 1
    iy, fy = mvy >> 1, mvy & 1
    for r in range(h):
        for c in range(w):
            x = x0 + c + ix
            y = y0 + r + iy
            a = sample(pic, comp, x, y, clip)
            if fx == 0 and fy == 0:
                v = a
            elif fy == 0:
                v = (a + sample(pic, comp, x + 1, y, clip) + 1 - rounding) // 2
            elif fx == 0:
                v = (a + sample(pic, comp, x, y + 1, clip) + 1 - rounding) // 2
            else:
                v = (a + sample(pic, comp, x + 1, y, clip) + sample(pic, comp, x, y + 1, clip)
                     + sample(pic, comp, x + 1, y + 1, clip) + 2 - rounding) // 4
            out[r * w + c] = v
    return out


def chroma_vector(v):
    m = abs(v)
    r = (m // 4) * 2 + (0 if m % 4 == 0 else 1)
    return -r if v < 0 else r


H0 = [4, 5, 5, 5, 5, 5, 5, 4, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 6, 6, 6, 6, 5, 5, 5, 5, 6, 6, 6, 6, 5, 5,
      5, 5, 6, 6, 6, 6, 5, 5, 5, 5, 6, 6, 6, 6, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 4, 5, 5, 5, 5, 5, 5, 4]
H1 = [2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2]
H2 = [2, 1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 2, 2, 2, 2, 1, 1, 1, 1, 2, 2, 2, 2, 1, 1, 1, 1, 2, 2,
      2, 2, 1, 1, 1, 1, 2, 2, 2, 2, 1, 1, 1, 1, 2, 2, 2, 2, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 2]


def upsample_residual(block):
    """Q.6: an 8x8 block to 16x16, interpolating inside the block alone."""
    out = [0] * 256
    for Y in range(16):
        for X in range(16):
            # nearest source sample and the neighbour on the side of the output sample
            x0, y0 = X // 2, Y // 2
            x1 = x0 + 1 if X % 2 == 1 else x0 - 1
            y1 = y0 + 1 if Y % 2 == 1 else y0 - 1
            x_in = 0 <= x1 <= 7
            y_in = 0 <= y1 <= 7
            a = block[y0 * 8 + x0]
            if x_in and y_in:
                b = block[y0 * 8 + x1]
                c = block[y1 * 8 + x0]
                d = block[y1 * 8 + x1]
                v = trunc_div(9 * a + 3 * b + 3 * c + d + 8, 16)
            elif x_in:
                v = trunc_div(3 * a + block[y0 * 8 + x1] + 2, 4)
            elif y_in:
                v = trunc_div(3 * a + block[y1 * 8 + x0] + 2, 4)
            else:
                v = a
            out[Y * 16 + X] = v
    return out


def trunc_div(a, b):
    q = abs(a) // abs(b)
    return -q if (a < 0) != (b < 0) else q


def round_half_away(n, d):
    q = (2 * abs(n) + d) // (2 * d)
    return -q if n < 0 else q


def warp(ref, width, height, w, accuracy, fill_mode, fill, rounding):
    """Annex P resampling with the incremental algorithm of P.4."""
    out = Picture(width, height)
    H, V, HR, VR = width, height, ref.width, ref.height
    m = 1
    while (1 << m) < H:
        m += 1
    n = 1
    while (1 << n) < V:
        n += 1
    Hp, Vp = 1 << m, 1 << n
    u00 = (16 * w[0], 16 * w[1])
    uH0 = (16 * (w[0] + w[2] + 2 * (HR - H)), 16 * (w[1] + w[3]))
    u0V = (16 * (w[0] + w[4]), 16 * (w[1] + w[5] + 2 * (VR - V)))
    uHV = (16 * (w[0] + w[2] + w[4] + w[6] + 2 * (HR - H)), 16 * (w[1] + w[3] + w[5] + w[7] + 2 * (VR - V)))
    LT = u00
    RT = tuple(round_half_away((H - Hp) * u00[k] + Hp * uH0[k], H) for k in range(2))
    LB = tuple(round_half_away((V - Vp) * u00[k] + Vp * u0V[k], V) for k in range(2))
    RB = tuple(round_half_away((V - Vp) * ((H - Hp) * u00[k] + Hp * uH0[k]) + Vp * ((H - Hp) * u0V[k] + Hp * uHV[k]),
                               H * V) for k in range(2))
    P = accuracy
    D = 64 * Hp // P
    for comp in range(3):
        S = 2 if comp == 0 else 1
        plane, stride = out.plane(comp)
        cols = out.cw if comp == 0 else out.cw // 2
        rows = out.ch if comp == 0 else out.ch // 2
        rplane, rstride = ref.plane(comp)
        maxx = S * HR // 2 - 1
        maxy = S * VR // 2 - 1
        fv = None if fill_mode == 3 else fill[comp]

        def prior(ip, jp):
            if fv is None:
                ic = max(0, min(maxx, ip))
                jc = max(0, min(maxy, jp))
                return rplane[jc * rstride + ic]
            if ip < 0 or ip > maxx or jp < 0 or jp > maxy:
                return fv
            return rplane[jp * rstride + ip]

        for j in range(rows):
            uL = tuple(round_half_away((S * Vp - 2 * j - 1) * LT[k] + (2 * j + 1) * LB[k], S * Vp) for k in range(2))
            uR = tuple(round_half_away((S * Vp - 2 * j - 1) * RT[k] + (2 * j + 1) * RB[k], S * Vp) for k in range(2))
            aix = D * P + 2 * (uR[0] - uL[0])
            aiy = 2 * (uR[1] - uL[1])
            ax = uL[0] * S * (1 << m) + (uR[0] - uL[0]) + D // 2
            ay = j * D * P + uL[1] * S * (1 << m) + (uR[1] - uL[1]) + D // 2
            for i in range(cols):
                IR = ax // D
                JR = ay // D
                iR = IR // P
                jR = JR // P
                px = IR - iR * P
                py = JR - jR * P
                val = ((P - py) * ((P - px) * prior(iR, jR) + px * prior(iR + 1, jR))
                       + py * ((P - px) * prior(iR, jR + 1) + px * prior(iR + 1, jR + 1)) + P * P // 2 - 1
                       + rounding) // (P * P)
                plane[j * stride + i] = val
                ax += aix
                ay += aiy
    return out


# ---------------------------------------------------------------- encoder


class Stream:
    """One sub-bitstream: its references, timing and the configuration of its pictures."""

    def __init__(self, cfg, source, sw, sh):
        self.cfg = cfg
        self.source = source
        self.sw = sw
        self.sh = sh
        self.anchor = None
        self.store = []            # Annex N: pictures by TR, newest first
        self.short = []            # Annex U short-term pictures, newest first
        self.long = {}             # Annex U long-term pictures by index
        self.pn = 0
        self.recon = []


def write_header(bw, s, pic, ptype, q, cfg, extra=None, rru=False, rpr=False, ufep=1, psbi=None):
    bw.code('0000000000000000100000')
    bw.put(pic.tr & 255, 8)
    bw.code('10000111')
    bw.put(ufep, 3)
    fmt = cfg.get('format', 2)
    if ufep == 1:
        bw.put(fmt, 3)
        bw.put(0, 1)
        modes = cfg['modes']
        for name in ('UMV', 'SAC', 'AP', 'AIC', 'DF', 'SS', 'RPS', 'ISD', 'AIV', 'MQ'):
            bw.put(1 if name in modes else 0, 1)
        bw.put(1, 1)
        bw.put(1 if 'ERPS' in modes else 0, 1)
        bw.put(0, 2)
    bw.put(ptype, 3)
    bw.put(1 if rpr else 0, 1)
    bw.put(1 if rru else 0, 1)
    bw.put(pic.rounding, 1)
    bw.code('001')
    if psbi is not None:
        bw.put(1, 1)
        bw.put(psbi, 2)
    else:
        bw.put(0, 1)
    if ufep == 1 and fmt == 6:
        bw.put(2, 4)
        bw.put(pic.width // 4 - 1, 9)
        bw.put(1, 1)
        bw.put(pic.height // 4, 9)
    modes = cfg['modes']
    if ufep == 1 and 'UMV' in modes:
        bw.code('1')
    if ufep == 1 and 'SS' in modes:
        bw.put(1 if cfg.get('rect') else 0, 1)
        bw.put(1 if cfg.get('aso') else 0, 1)
    if extra:
        extra(bw)
    bw.put(q, 5)
    bw.put(0, 1)


class Encoder:
    def __init__(self, cfg, source, sw, sh):
        self.cfg = cfg
        self.source = source
        self.sw = sw
        self.sh = sh

    # -- motion vector prediction (6.1.1), candidates per MB (one vector per MB)
    def predictor(self, mvs, segs, cols, mbx, mby, comp):
        idx = mby * cols + mbx
        seg = segs[idx]

        def cand(x, y):
            j = y * cols + x
            v = mvs.get(j)
            return 0 if v is None else v[comp]

        left = mbx > 0 and segs.get(idx - 1) == seg
        mv1 = cand(mbx - 1, mby) if left else 0
        above = mby > 0 and segs.get(idx - cols) == seg
        if not above:
            mv2 = mv1
            mv3 = mv1
        else:
            mv2 = cand(mbx, mby - 1)
            ar = mbx + 1 < cols and segs.get(idx - cols + 1) == seg
            mv3 = cand(mbx + 1, mby - 1) if ar else mv1
        if mbx + 1 >= cols:
            mv3 = 0
        return sorted([mv1, mv2, mv3])[1]


def encode_mvd(bw, pred, value, plus_umv):
    if plus_umv:
        return reversible(value - pred)
    d = value - pred
    if d < -32:
        d += 64
    elif d > 31:
        d -= 64
    return mvd_code(d + 32)


def pseudo(v):
    if v == 0:
        return 0
    m = (abs(v) + 1) // 2
    return -m if v < 0 else m


def actual(v):
    if v == 0:
        return 0
    m = 2 * abs(v) - 1
    return -m if v < 0 else m


def segment_order(segments, cols, rows, slices):
    """Returns the macroblocks of each segment in coding order and the segment rectangles in macroblocks."""
    order = []
    rects = []
    for seg in segments:
        first, width, count = seg['first'], seg.get('width', 0), seg['count']
        x0, y0 = first % cols, first // cols
        mbs = []
        mb = first
        while len(mbs) < count:
            mbs.append(mb)
            if width:
                x = mb % cols
                if x < min(cols, x0 + width) - 1:
                    mb += 1
                else:
                    mb = (mb // cols + 1) * cols + x0
            else:
                mb += 1
        order.append(mbs)
        if width:
            rects.append((x0, y0, min(cols, x0 + width) - 1, mbs[-1] // cols))
        elif slices:
            rects.append((0, y0, cols - 1, mbs[-1] // cols))
        else:
            rects.append((0, y0, cols - 1, mbs[-1] // cols))
    return order, rects


def encode_picture(enc, stream, index, ptype, q, refs, opts):
    """Encodes one I or P picture; refs are the reference pictures in relative index order. Returns (bits, recon)."""
    cfg = enc.cfg
    width = opts.get('width', cfg['width'])
    height = opts.get('height', cfg['height'])
    src = source_picture(enc.source, enc.sw, enc.sh, index, width, height)
    rec = Picture(width, height)
    rec.tr = opts['tr']
    rec.pn = opts.get('pn', 0)
    rec.rounding = opts.get('rounding', 0)
    rru = opts.get('rru', False)
    size = 32 if rru else 16
    cols = (rec.cw + size - 1) // size
    rows = (rec.ch + size - 1) // size
    modes = cfg['modes']
    plus_umv = 'UMV' in modes
    ap = 'AP' in modes and ptype == 1
    df = 'DF' in modes
    isd = 'ISD' in modes
    slices = 'SS' in modes
    pred_refs = list(refs)
    warp_params = opts.get('warp')
    implicit = ptype == 1 and refs and (refs[0].width != width or refs[0].height != height) and not warp_params
    if ptype == 1 and (warp_params or implicit):
        params = warp_params['w'] if warp_params else [0] * 8
        acc = warp_params['accuracy'] if warp_params else 16
        fm = warp_params['fill'] if warp_params else 3
        color = warp_params.get('color', (16, 128, 128)) if warp_params else (16, 128, 128)
        fillv = {0: color, 1: (16, 128, 128), 2: (128, 128, 128), 3: (0, 0, 0)}[fm]
        pred_refs[0] = warp(refs[0], width, height, params, acc, fm, fillv, rec.rounding)
    segments = opts.get('segments') or [{'first': 0, 'count': cols * rows}]
    order, rects = segment_order(segments, cols, rows, slices)
    seg_of = {}
    for sidx, mbs in enumerate(order):
        for mb in mbs:
            seg_of[mb] = sidx

    def clip_for(mb, comp):
        sh = 0 if comp == 0 else 1
        cw = rec.cw >> sh
        ch = rec.ch >> sh
        if not isd:
            return (0, 0, cw - 1, ch - 1)
        x0, y0, x1, y1 = rects[seg_of[mb]]
        unit = size >> sh
        return (x0 * unit, y0 * unit, min(cw - 1, (x1 + 1) * unit - 1), min(ch - 1, (y1 + 1) * unit - 1))

    segment_refs = opts.get('segment_refs', {})

    def refs_for(mb):
        sidx = seg_of[mb]
        return [segment_refs[sidx]] if sidx in segment_refs else pred_refs

    decisions = {}
    search = opts.get('search', [(0, 0)])
    for mbs in order:
        for mb in mbs:
            mbx, mby = mb % cols, mb // cols
            if ptype == 0 or mb in opts.get('intra_mbs', ()):
                decisions[mb] = ('intra', 0, 0, 0)
                continue
            if mb in opts.get('force', {}):
                r, vx, vy = opts['force'][mb]
                decisions[mb] = ('inter', r, vx, vy)
                continue
            best = None
            for r, ref in enumerate(refs_for(mb)[:opts.get('max_refs', 1)]):
                for (vx, vy) in search:
                    if rru and ((vx % 2 == 0 and vx != 0) or (vy % 2 == 0 and vy != 0)):
                        continue
                    p = predict(ref, 0, mbx * size, mby * size, size, size, vx, vy, rec.rounding, clip_for(mb, 0))
                    sad = r * 64
                    for rr in range(0, size, 2):
                        for cc in range(0, size, 2):
                            yy = min(mby * size + rr, rec.ch - 1)
                            xx = min(mbx * size + cc, rec.cw - 1)
                            sad += abs(src.y[yy * src.stride + xx] - p[rr * size + cc])
                    if best is None or sad < best[0]:
                        best = (sad, r, vx, vy)
            decisions[mb] = ('inter', best[1], best[2], best[3])
    mvs = {mb: (None if d[0] == 'intra' else (d[2], d[3])) for mb, d in decisions.items()}
    bw = Bits()
    write_picture_header(bw, enc, stream, rec, ptype, q, opts, rru)
    mvs_written = {}
    segs_written = {}
    coded_flags = {}
    quant_of = {}
    chunks = []
    for sidx, mbs in enumerate(order):
        seg = segments[sidx]
        if sidx > 0:
            if opts.get('chunks'):
                chunks.append(bw)
                bw = Bits()
            write_segment_header(bw, cfg, opts, seg['first'], seg.get('width', 0), q, cols, rows, rru, sidx)
        elif slices:
            bw.code('1')
            bw.put(seg['first'], mba_bits(cols * rows, rru))
            if cfg.get('rect'):
                bw.code('1')
                bw.put(seg['width'] - 1, swi_bits(width, rru))
            bw.code('1')
            if 'ERPS' in modes:
                bw.code('0')
                bw.code('1')
        for mb in mbs:
            segs_written[mb] = sidx
            encode_mb(bw, enc, src, rec, refs_for(mb), mb, cols, rows, size, decisions, mvs_written, segs_written,
                      ptype, q, plus_umv, rru, ap, clip_for, mvs, seg_of, opts, coded_flags, quant_of)
    if df or rru:
        loop_filter(rec, cols, rows, size, coded_flags, quant_of, seg_of if isd else None, df)
    if opts.get('chunks'):
        chunks.append(bw)
        return chunks, rec
    return bw, rec


def mba_bits(count, rru):
    if rru:
        for limit, bits in ((12, 5), (30, 6), (99, 7), (396, 9), (1584, 11)):
            if count <= limit:
                return bits
        return 12
    for limit, bits in ((48, 6), (99, 7), (396, 9), (1584, 11), (6336, 13)):
        if count <= limit:
            return bits
    return 14


def swi_bits(width, rru):
    if rru:
        return 3 if width <= 176 else 4 if width <= 352 else 5 if width <= 704 else 6
    return 4 if width <= 176 else 5 if width <= 352 else 6 if width <= 704 else 7


def write_segment_header(bw, cfg, opts, first, swidth, q, cols, rows, rru, sidx):
    if cfg.get('slices'):
        bw.align()
        bw.code('00000000000000001')
        bw.code('1')
        if opts.get('psbi') is not None:
            bw.put([0b1001, 0b1010, 0b1011, 0b1101][opts['psbi']], 4)
        bits = mba_bits(cols * rows, rru)
        bw.put(first, bits)
        if (bits > 11 and opts.get('psbi') is None) or (bits > 9 and opts.get('psbi') is not None):
            bw.code('1')
        bw.put(q, 5)
        if cfg.get('rect'):
            bw.put(swidth - 1, swi_bits(cfg['width'], rru))
        bw.code('1')
        bw.put(0, 2)
    else:
        if opts.get('gob_align'):
            bw.align()
        bw.code('00000000000000001')
        gob_rows = opts.get('gob_rows', 1)
        bw.put(first // (cols * gob_rows), 5)
        if opts.get('psbi') is not None:
            bw.put(opts['psbi'], 2)
        bw.put(0, 2)
        bw.put(q, 5)
    if 'RPS' in cfg['modes']:
        bw.code('0')
        trp = opts.get('segment_trp', {}).get(sidx)
        if trp is None:
            bw.code('0')
        else:
            bw.code('1')
            bw.put(trp, 10)
        bw.code('01')
    if 'ERPS' in cfg['modes']:
        bw.code('0')
        layer = opts.get('segment_erps', {}).get(sidx)
        if layer is None:
            bw.code('1')
        else:
            bw.code('0')
            layer(bw)


def write_picture_header(bw, enc, stream, rec, ptype, q, opts, rru):
    cfg = enc.cfg

    def extra(b):
        modes = cfg['modes']
        if 'RPS' in modes:
            b.put(4, 3)
            if opts.get('trp') is None:
                b.code('0')
            else:
                b.code('1')
                b.put(opts['trp'], 10)
            b.code('01')
        if 'ERPS' in modes:
            b.put(4, 3)
            b.put(rec.pn, 10)
            opts['erps'](b)
        if opts.get('warp'):
            w = opts['warp']
            b.put(2 if w['accuracy'] == 2 else 3, 2)
            for i in range(0, 8, 2):
                b.code(reversible(w['w'][i]))
                b.code(reversible(w['w'][i + 1]))
                if w['w'][i] == 1 and w['w'][i + 1] == 1:
                    b.code('1')
            b.put(w['fill'], 2)
            if w['fill'] == 0:
                c = w['color']
                b.put(c[0], 8)
                b.code('1')
                b.put(c[1], 8)
                b.code('1')
                b.put(c[2], 8)

    write_header(bw, stream, rec, ptype, q, {**cfg, 'format': opts.get('format', cfg.get('format', 2))}, extra=extra,
                 rru=rru, rpr=bool(opts.get('warp')), ufep=1, psbi=opts.get('psbi'))




def encode_mb(bw, enc, src, rec, refs, mb, cols, rows, size, decisions, mvs_written, segs_written, ptype, q,
              plus_umv, rru, ap, clip_for, mvs, seg_of, opts, coded_flags, quant_of):
    mbx, mby = mb % cols, mb // cols
    d = decisions[mb]
    intra = d[0] == 'intra'
    block = size // 2
    erps_multi = opts.get('mrpa', False)
    # prediction
    pred = {}
    if not intra:
        r = d[1]
        vx, vy = d[2], d[3]
        ref = refs[r]
        for k in range(4):
            bx = mbx * size + (k % 2) * block
            by = mby * size + (k // 2) * block
            if ap:
                pred[k] = obmc(ref, mb, k, bx, by, block, vx, vy, mvs, seg_of, cols, rows, rec.rounding, clip_for,
                               opts)
            else:
                pred[k] = predict(ref, 0, bx, by, block, block, vx, vy, rec.rounding, clip_for(mb, 0))
        cvx, cvy = chroma_vector(vx), chroma_vector(vy)
        for k in (4, 5):
            pred[k] = predict(ref, k - 3, mbx * block, mby * block, block, block, cvx, cvy, rec.rounding,
                              clip_for(mb, 1))
    # residual
    levels = {}
    recon_res = {}
    q_c = q
    for k in range(6):
        if k < 4:
            bx = mbx * size + (k % 2) * block
            by = mby * size + (k // 2) * block
            comp = 0
        else:
            bx = mbx * block
            by = mby * block
            comp = k - 3
        plane, stride = src.plane(comp)
        cw = src.cw if comp == 0 else src.cw // 2
        chh = src.ch if comp == 0 else src.ch // 2
        res = [0] * (block * block)
        for rr in range(block):
            for cc in range(block):
                yy = min(by + rr, chh - 1)
                xx = min(bx + cc, cw - 1)
                p = 0 if intra else pred[k][rr * block + cc]
                res[rr * block + cc] = plane[yy * stride + xx] - p
        if rru:
            small = [0] * 64
            for rr in range(8):
                for cc in range(8):
                    small[rr * 8 + cc] = (res[(2 * rr) * 16 + 2 * cc] + res[(2 * rr) * 16 + 2 * cc + 1]
                                          + res[(2 * rr + 1) * 16 + 2 * cc] + res[(2 * rr + 1) * 16 + 2 * cc + 1]) / 4
            res = small
        coef = fdct(res)
        lv = quantize(coef, q_c, intra)
        levels[k] = lv
    pattern = 0
    for k in range(6):
        if has_coefficients(levels[k], intra):
            pattern |= 32 >> k
    skip = (not intra and d[1] == 0 and d[2] == 0 and d[3] == 0 and pattern == 0 and not opts.get('no_skip'))
    # syntax
    if ptype != 0:
        bw.put(1 if skip else 0, 1)
    if skip:
        mvs_written[mb] = (0, 0)
        record_vectors(rec, mb, cols, size, 'skip', 0, 0)
        coded_flags[mb] = False
        quant_of[mb] = q
    else:
        if ptype != 0 and erps_multi:
            bw.code(index_code(0))
        cbpc = pattern & 3
        cbpy = pattern >> 2
        if ptype == 0:
            bw.code(MCBPC_I[(3, cbpc)])
        else:
            bw.code(MCBPC_P[(3 if intra else 0) * 4 + cbpc])
        bw.code(CBPY[cbpy if intra else 15 - cbpy])
        if not intra:
            if erps_multi:
                r = d[1]
                bw.code(index_code(r))
                if r == 1 and not plus_umv:
                    bw.code('1')
            vx, vy = d[2], d[3]
            px = enc.predictor(mvs_written, segs_written, cols, mbx, mby, 0)
            py = enc.predictor(mvs_written, segs_written, cols, mbx, mby, 1)
            if rru:
                cx = encode_mvd(bw, pseudo(px), pseudo(vx), plus_umv)
                cy = encode_mvd(bw, pseudo(py), pseudo(vy), plus_umv)
            else:
                cx = encode_mvd(bw, px, vx, plus_umv)
                cy = encode_mvd(bw, py, vy, plus_umv)
            bw.code(cx)
            bw.code(cy)
            if plus_umv and cx == '000' and cy == '000':
                bw.code('1')
            mvs_written[mb] = (vx, vy)
            record_vectors(rec, mb, cols, size, 'inter', vx, vy)
        else:
            mvs_written[mb] = None
            record_vectors(rec, mb, cols, size, 'intra', 0, 0)
        for k in range(6):
            if intra or pattern & (32 >> k):
                write_block(bw, levels[k] if (intra or pattern & (32 >> k)) else levels[k], intra) \
                    if (pattern & (32 >> k)) else bw.put(255 if levels[k][0] == 128 else levels[k][0], 8)
        coded_flags[mb] = True
        quant_of[mb] = q
    # reconstruction
    for k in range(6):
        if k < 4:
            bx = mbx * size + (k % 2) * block
            by = mby * size + (k // 2) * block
            comp = 0
        else:
            bx = mbx * block
            by = mby * block
            comp = k - 3
        coded = intra or bool(pattern & (32 >> k))
        if skip:
            coded = False
        if coded:
            lv = levels[k]
            if not intra and not (pattern & (32 >> k)):
                lv = [0] * 64
            if intra and not (pattern & (32 >> k)):
                lv = [lv[0]] + [0] * 63
            rec_c = dequantize(lv, q, intra)
            res = idct(rec_c)
            if rru:
                res = upsample_residual(res)
        else:
            res = [0] * (block * block)
        plane, stride = rec.plane(comp)
        for rr in range(block):
            for cc in range(block):
                p = 0 if intra else pred[k][rr * block + cc]
                v = p + res[rr * block + cc]
                plane[(by + rr) * stride + bx + cc] = max(0, min(255, v))


def obmc(ref, mb, k, bx, by, block, vx, vy, mvs, seg_of, cols, rows, rounding, clip_for, opts):
    """F.3 / Q.5 with one vector per macroblock: remote vectors of the neighbouring macroblocks."""
    mbx, mby = mb % cols, mb // cols
    separated = opts.get('separated', False)

    def remote(nx, ny):
        if nx < 0 or ny < 0 or nx >= cols or ny >= rows:
            return (vx, vy)
        n = ny * cols + nx
        if separated and seg_of.get(n) != seg_of.get(mb):
            return (vx, vy)
        v = mvs.get(n, 'missing')
        if v == 'missing':
            return (vx, vy)
        if v is None:
            return (vx, vy)
        return v

    top = k < 2
    leftcol = k % 2 == 0
    above = (vx, vy) if not top else remote(mbx, mby - 1)
    below = (vx, vy)
    left = (vx, vy) if not leftcol else remote(mbx - 1, mby)
    right = (vx, vy) if leftcol else remote(mbx + 1, mby)
    clip = clip_for(mb, 0)
    q = predict(ref, 0, bx, by, block, block, vx, vy, rounding, clip)
    ra = predict(ref, 0, bx, by, block, block, above[0], above[1], rounding, clip)
    rb = predict(ref, 0, bx, by, block, block, below[0], below[1], rounding, clip)
    sl = predict(ref, 0, bx, by, block, block, left[0], left[1], rounding, clip)
    sr = predict(ref, 0, bx, by, block, block, right[0], right[1], rounding, clip)
    out = [0] * (block * block)
    shift = 1 if block == 16 else 0
    for r in range(block):
        for c in range(block):
            w = (r >> shift) * 8 + (c >> shift)
            vert = ra if r < block // 2 else rb
            hor = sl if c < block // 2 else sr
            i = r * block + c
            out[i] = (q[i] * H0[w] + vert[i] * H1[w] + hor[i] * H2[w] + 4) // 8
    return out


STRENGTH = [0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 7, 7, 8, 8, 8, 9, 9, 9, 10, 10, 10, 11, 11, 11, 12, 12, 12]


def loop_filter(rec, cols, rows, size, coded, quant, segments, df):
    rru = size == 32
    for comp in range(3):
        plane, stride = rec.plane(comp)
        bs = size // 2
        per = 1 if comp else 2
        bcols, brows = cols * per, rows * per
        sh = 0 if comp else 1

        def mbof(bx, by):
            return (by >> sh) * cols + (bx >> sh)

        def active(a, b):
            if not coded.get(a, False) and not coded.get(b, False):
                return False
            return segments is None or segments.get(a) == segments.get(b)

        def strength(a, b):
            qq = quant[b] if coded.get(b, False) else quant[a]
            return STRENGTH[qq]

        def filt(at, step, st):
            if rru and not df:
                A = plane[at - step]
                B = plane[at]
                plane[at - step] = (3 * A + B + 2) // 4
                plane[at] = (A + 3 * B + 2) // 4
                return
            A = plane[at - 2 * step]
            B = plane[at - step]
            C = plane[at]
            D = plane[at + step]
            dd = trunc_div(A - 4 * B + 4 * C - D, 8)
            if rru:
                d1 = dd
            else:
                m = abs(dd)
                d1 = (1 if dd > 0 else -1 if dd < 0 else 0) * max(0, m - max(0, 2 * (m - st)))
            lim = abs(trunc_div(d1, 2))
            d2 = max(-lim, min(lim, trunc_div(A - D, 4)))
            plane[at - 2 * step] = max(0, min(255, A - d2))
            plane[at - step] = max(0, min(255, B + d1))
            plane[at] = max(0, min(255, C - d1))
            plane[at + step] = max(0, min(255, D + d2))

        for by in range(1, brows):
            for bx in range(bcols):
                a, b = mbof(bx, by - 1), mbof(bx, by)
                if not active(a, b):
                    continue
                st = strength(a, b)
                for i in range(bs):
                    filt((by * bs) * stride + bx * bs + i, stride, st)
        for by in range(brows):
            for bx in range(1, bcols):
                a, b = mbof(bx - 1, by), mbof(bx, by)
                if not active(a, b):
                    continue
                st = strength(a, b)
                for i in range(bs):
                    filt((by * bs + i) * stride + bx * bs, 1, st)


# ---------------------------------------------------------------- Annex U buffer model


class ErpsBuffer:
    def __init__(self):
        self.short = []
        self.long = {}
        self.capacity = None

    def order(self, remaps, pnc):
        remaining = list(self.short) + [self.long[k] for k in sorted(self.long)]
        out = []
        pred = pnc
        for op, val in remaps:
            if op == 'long':
                found = next((e for e in remaining if e[2] == val), None)
            else:
                pred = (pred - val) % 1024 if op == 'neg' else (pred + val) % 1024
                found = next((e for e in remaining if e[2] is None and e[1] == pred), None)
            if found is not None:
                remaining.remove(found)
                out.append(found)
        return [e[0] for e in out + remaining]

    def store(self, pic, pn, sliding, mmco):
        if sliding:
            limit = self.capacity if self.capacity is not None else 16
            while len(self.short) + len(self.long) + 1 > limit and self.short:
                self.short.pop()
            self.short.insert(0, (pic, pn, None))
            return
        self.short.insert(0, (pic, pn, None))
        for op in mmco:
            kind = op[0]
            if kind == 'buffer':
                self.capacity = op[3]
                if op[4]:
                    self.short = self.short[:1]
                    self.long = {}
            elif kind == 'maxlong':
                for k in [k for k in self.long if k >= op[1]]:
                    del self.long[k]
            elif kind == 'short':
                target = (pn - op[1]) % 1024
                self.short = [e for e in self.short if e[1] != target]
            elif kind == 'long':
                self.long.pop(op[1], None)
            elif kind == 'assign':
                target = (pn - op[1]) % 1024
                for e in self.short:
                    if e[1] == target:
                        self.short.remove(e)
                        self.long[op[2]] = (e[0], e[1], op[2])
                        break


def erps_writer(ptype, multiple=False, remaps=(), sliding=True, mmco=()):
    def writer(b):
        if ptype != 0:
            b.put(1 if multiple else 0, 1)
            for op, val in remaps:
                b.code({'neg': '1', 'pos': '010', 'long': '011'}[op])
                b.code(index_code(val if op == 'long' else val - 1))
            b.code('001')
        b.put(1 if sliding else 0, 1)
        if not sliding:
            for op in mmco:
                kind = op[0]
                if kind == 'short':
                    b.code('011')
                    b.code(index_code(op[1]))
                elif kind == 'long':
                    b.code('0100')
                    b.code(index_code(op[1]))
                elif kind == 'assign':
                    b.code('0101')
                    b.code(index_code(op[1]))
                    b.code(index_code(op[2]))
                elif kind == 'maxlong':
                    b.code('00110')
                    b.code(index_code(op[1]))
                elif kind == 'buffer':
                    b.code('00111')
                    b.put(op[1], 7)
                    b.put(op[2], 7)
                    b.code(index_code(op[3] - 1))
                    b.put(op[4], 1)
            b.code('1')
    return writer


# ---------------------------------------------------------------- stream recipes

SEARCH = [(0, 0), (2, 0), (3, 0), (4, 0), (5, 0), (6, 0), (2, 1), (4, -1), (6, 2), (-3, 1), (1, -1), (5, 3), (7, -2),
          (9, 1), (-7, -5), (11, 3), (3, 7)]


def append(out, bw):
    out.bits += bw.bits
    out.align()


def recipe_rpr(enc):
    """I-picture, then P-pictures that change size implicitly and warp explicitly with each fill mode."""
    out = Bits()
    recon = []
    stream = Stream(enc.cfg, enc.source, enc.sw, enc.sh)
    bw, ref = encode_picture(enc, stream, 0, 0, 8, [], {'tr': 0})
    append(out, bw)
    recon.append(ref)
    steps = [
        {'tr': 1, 'width': 128, 'height': 96, 'format': 1},
        {'tr': 2, 'width': 128, 'height': 96, 'format': 1,
         'warp': {'w': [3, -2, 4, 1, -3, 2, 1, -1], 'accuracy': 16, 'fill': 0, 'color': (200, 60, 180)}},
        {'tr': 3, 'width': 128, 'height': 96, 'format': 1,
         'warp': {'w': [-5, 4, -6, 3, 5, -4, 2, 2], 'accuracy': 2, 'fill': 1}},
        {'tr': 4, 'width': 128, 'height': 96, 'format': 1,
         'warp': {'w': [1, 1, 0, 0, 0, 0, 1, 1], 'accuracy': 16, 'fill': 2}, 'rounding': 1},
        {'tr': 5, 'width': 176, 'height': 144, 'format': 2, 'rounding': 0},
        {'tr': 6, 'width': 176, 'height': 144, 'format': 2,
         'warp': {'w': [7, -3, -9, 5, 6, 8, -4, -2], 'accuracy': 16, 'fill': 3}, 'rounding': 1},
    ]
    for i, opts in enumerate(steps):
        opts['search'] = SEARCH
        bw, ref = encode_picture(enc, stream, i + 1, 1, 8, [ref], opts)
        append(out, bw)
        recon.append(ref)
    return out, recon


CONFIGS = {
    'rpr': {'width': 176, 'height': 144, 'modes': {'UMV'}, 'recipe': recipe_rpr},
}


def main():
    load_tcoef(sys.argv[1])
    source = open(sys.argv[2], 'rb').read()
    sw, sh = int(sys.argv[3]), int(sys.argv[4])
    cfg = CONFIGS[sys.argv[5]]
    enc = Encoder(cfg, source, sw, sh)
    out, recon = cfg['recipe'](enc)
    prefix = sys.argv[6]
    data = out.data()
    open(prefix + '.263', 'wb').write(data)
    if recon and isinstance(recon[0], list):
        for sb, pictures in enumerate(recon):
            with open(prefix + '.rec' + ('' if sb == 0 else '.sb%d' % sb) + '.yuv', 'wb') as f:
                for pic in pictures:
                    f.write(pic.dump())
        print(prefix, len(data), 'bytes', [len(r) for r in recon], 'pictures per sub-bitstream')
        return
    with open(prefix + '.rec.yuv', 'wb') as f:
        for pic in recon:
            f.write(pic.dump())
    print(prefix, len(data), 'bytes', len(recon), 'pictures', [(p.width, p.height) for p in recon])



def gob_segments(cols, rows, header_rows, size_rows=1):
    """Segments starting at the given macroblock rows (GOB headers), in order."""
    out = []
    starts = sorted(set([0] + list(header_rows)))
    for k, r in enumerate(starts):
        end = starts[k + 1] if k + 1 < len(starts) else rows
        out.append({'first': r * cols, 'count': (end - r) * cols})
    return out


def recipe_sequence(enc, pictures, refs_of=None):
    """Encodes I then P pictures with per-picture options; the previous picture is the reference."""
    out = Bits()
    recon = []
    stream = Stream(enc.cfg, enc.source, enc.sw, enc.sh)
    ref = None
    for i, opts in enumerate(pictures):
        ptype = 0 if i == 0 or opts.get('intra') else 1
        opts.setdefault('tr', i)
        opts.setdefault('search', SEARCH)
        bw, pic = encode_picture(enc, stream, opts.get('source', i), ptype, opts.get('q', 8),
                                 [] if ptype == 0 else [ref], opts)
        append(out, bw)
        recon.append(pic)
        ref = pic
    return out, recon


RRU_SEARCH = [(0, 0), (1, 1), (3, 1), (5, 1), (3, -1), (7, 3), (-3, 1), (1, -3), (9, 1), (5, 5), (11, -1), (13, 3),
              (-5, -3), (17, 1), (3, 9)]


def recipe_rru(enc):
    pics = [{}]
    for i in range(1, 6):
        opts = {'rru': i != 3, 'search': RRU_SEARCH if i != 3 else SEARCH, 'rounding': i % 2, 'q': 6 + i}
        if i in (2, 4):
            opts['force'] = {7: (0, 21, -13), 12: (0, -31, 29), 29: (0, 45, 33)}
            opts['intra_mbs'] = (14, 22)
        pics.append(opts)
    return recipe_sequence(enc, pics)


def recipe_isd(enc):
    cols, rows = 11, 9
    segs = gob_segments(cols, rows, [3, 6])
    pics = [{'segments': segs, 'separated': True}]
    for i in range(1, 6):
        pics.append({'segments': segs, 'separated': True, 'rounding': i % 2, 'search': SEARCH,
                     'force': {25: (0, 3, 31), 36: (0, -5, -29), 61: (0, 30, 25), 70: (0, -22, -40),
                               98: (0, 41, 37)}, 'intra_mbs': (40, 47)})
    return recipe_sequence(enc, pics)


def rect_segments(order):
    layout = {'A': {'first': 0, 'width': 6, 'count': 30}, 'B': {'first': 6, 'width': 5, 'count': 25},
              'C': {'first': 55, 'width': 7, 'count': 28}, 'D': {'first': 62, 'width': 4, 'count': 16}}
    return [dict(layout[k]) for k in order]


def recipe_rect(enc):
    pics = [{'segments': rect_segments('ABCD'), 'separated': True}]
    orders = ['BADC', 'CDAB', 'DCBA', 'ACBD', 'BDAC']
    for i in range(1, 6):
        pics.append({'segments': rect_segments(orders[i - 1]), 'separated': True, 'rounding': i % 2,
                     'force': {5: (0, 25, 3), 6: (0, -27, 1), 49: (0, 9, 30), 61: (0, -31, -19), 66: (0, 17, 21)},
                     'intra_mbs': (17, 72)})
    return recipe_sequence(enc, pics)


def recipe_rps(enc):
    """Annex N: references chosen by TRP at the picture and the GOB level, and a redundant picture."""
    out = Bits()
    recon = []
    stream = Stream(enc.cfg, enc.source, enc.sw, enc.sh)
    cols, rows = 11, 9
    store = {}
    bw, pic = encode_picture(enc, stream, 0, 0, 8, [], {'tr': 0})
    append(out, bw)
    recon.append(pic)
    store[0] = pic
    plan = [
        (1, None, None),
        (2, 0, None),
        (3, None, {1: 1, 2: 0}),
        (4, 2, {1: 3}),
    ]
    last = pic
    for tr, trp, gob_trp in plan:
        segs = gob_segments(cols, rows, [3, 6]) if gob_trp else None
        ref = store[trp] if trp is not None else last
        opts = {'tr': tr, 'trp': trp, 'search': SEARCH}
        if gob_trp:
            opts['segments'] = segs
            opts['segment_trp'] = gob_trp
            # a group of blocks header without TRP predicts from the most recent picture again (N.4.1.4)
            opts['segment_refs'] = {k: (store[gob_trp[k]] if k in gob_trp else last) for k in range(1, len(segs))}
        bw, pic = encode_picture(enc, stream, tr, 1, 8, [ref], opts)
        append(out, bw)
        recon.append(pic)
        store[tr] = pic
        last = pic
    # a redundant copy of the last picture predicted from another picture, which the decoder discards
    bw, _ = encode_picture(enc, stream, 4, 1, 8, [store[1]], {'tr': 4, 'trp': 1, 'search': SEARCH})
    append(out, bw)
    bw, pic = encode_picture(enc, stream, 5, 1, 8, [last], {'tr': 5, 'search': SEARCH})
    append(out, bw)
    recon.append(pic)
    return out, recon


def recipe_erps(enc):
    """Annex U: multiple references per macroblock, sliding window, long-term pictures, re-mapping."""
    out = Bits()
    recon = []
    stream = Stream(enc.cfg, enc.source, enc.sw, enc.sh)
    buf = ErpsBuffer()
    pn = 0
    mmco0 = [('buffer', 10, 9, 4, 1)]
    bw, pic = encode_picture(enc, stream, 0, 0, 8, [], {'tr': 0, 'pn': pn,
                                                        'erps': erps_writer(0, sliding=False, mmco=mmco0)})
    append(out, bw)
    recon.append(pic)
    buf.store(pic, pn, False, mmco0)
    steps = [
        {'multiple': True, 'sliding': True, 'mmco': (), 'remaps': ()},
        {'multiple': True, 'sliding': False, 'mmco': [('maxlong', 2), ('assign', 1, 0)], 'remaps': ()},
        {'multiple': True, 'sliding': True, 'mmco': (), 'remaps': [('neg', 3), ('long', 0)]},
        {'multiple': True, 'sliding': False, 'mmco': [('short', 2)], 'remaps': [('neg', 2), ('pos', 1)]},
        {'multiple': False, 'sliding': True, 'mmco': (), 'remaps': [('long', 0)]},
        {'multiple': True, 'sliding': True, 'mmco': (), 'remaps': ()},
    ]
    for i, st in enumerate(steps):
        pn = (pn + 1) % 1024
        refs = buf.order(st['remaps'], pn)
        opts = {'tr': i + 1, 'pn': pn, 'search': SEARCH, 'mrpa': st['multiple'],
                'max_refs': len(refs) if st['multiple'] else 1,
                'erps': erps_writer(1, multiple=st['multiple'], remaps=st['remaps'], sliding=st['sliding'],
                                    mmco=st['mmco'])}
        bw, pic = encode_picture(enc, stream, i + 1, 1, 8, refs, opts)
        append(out, bw)
        recon.append(pic)
        buf.store(pic, pn, st['sliding'], st['mmco'])
    return out, recon


def recipe_cpm(enc):
    """Annex C: two sub-bitstreams interleaved at the group of blocks level, the second ended and restarted."""
    out = Bits()
    cols, rows = 11, 9
    segs = gob_segments(cols, rows, range(1, 9))
    streams = [Stream(enc.cfg, enc.source, enc.sw, enc.sh), Stream(enc.cfg, enc.source, enc.sw, enc.sh)]
    refs = [None, None]
    recon = [[], []]
    for t in range(4):
        chunks = []
        for sb in range(2):
            ptype = 0 if t == 0 or (sb == 1 and t == 2) else 1
            opts = {'tr': t, 'psbi': sb, 'segments': segs, 'chunks': True, 'search': SEARCH, 'gob_align': True,
                    'rounding': (t + sb) % 2}
            parts, pic = encode_picture(enc, streams[sb], t + 8 * sb, ptype, 8 + 2 * sb,
                                        [] if ptype == 0 else [refs[sb]], opts)
            chunks.append(parts)
            refs[sb] = pic
            recon[sb].append(pic)
        a, b = chunks
        merged = []
        for k in range(max(len(a), len(b))):
            if k < len(a):
                merged.append(a[k])
            if k < len(b):
                merged.append(b[k])
        for part in merged:
            append(out, part)
        if t == 1:
            out.code('0000000000000000111110')
            out.code('0')
            out.put(1, 2)
            out.align()
    return out, recon


CONFIGS.update({
    'rru': {'width': 176, 'height': 144, 'modes': {'UMV'}, 'recipe': recipe_rru},
    'rruap': {'width': 176, 'height': 144, 'modes': {'UMV', 'AP'}, 'recipe': recipe_rru},
    'rrudf': {'width': 176, 'height': 144, 'modes': {'UMV', 'DF'}, 'recipe': recipe_rru},
    'rrucif': {'width': 352, 'height': 288, 'modes': {'UMV', 'AP', 'DF'}, 'recipe': recipe_rru},
    'isd': {'width': 176, 'height': 144, 'modes': {'UMV', 'ISD', 'AP', 'DF'}, 'recipe': recipe_isd},
    'rect': {'width': 176, 'height': 144, 'modes': {'UMV', 'SS', 'AP'}, 'rect': True, 'aso': True,
             'slices': True, 'recipe': recipe_rect},
    'isdrect': {'width': 176, 'height': 144, 'modes': {'UMV', 'SS', 'ISD', 'DF'}, 'rect': True, 'aso': True,
                'slices': True, 'recipe': recipe_rect},
    'rps': {'width': 176, 'height': 144, 'modes': {'RPS'}, 'recipe': recipe_rps},
    'erps': {'width': 176, 'height': 144, 'modes': {'ERPS', 'UMV'}, 'recipe': recipe_erps},
    'cpm': {'width': 176, 'height': 144, 'modes': {'UMV'}, 'recipe': recipe_cpm},
})



# ---------------------------------------------------------------- B-pictures (Annex O) and Improved PB-frames (M)

MBTYPE_B = {0: '11', 1: '0001', 2: '100', 3: '101', 4: '00110', 5: '010', 6: '011', 7: '00111', 8: '00100',
            9: '00101', 10: '00001', 11: '000001', 12: '0000001'}
CBPC_O4 = {0: '0', 1: '10', 2: '111', 3: '110'}
MODB_M = {0: '0', 1: '10', 2: '110', 3: '1110', 4: '11110', 5: '11111'}
SIXTEENTH = [0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2, 2]


def chroma_of_four(total):
    m = abs(total)
    r = (m // 16) * 2 + SIXTEENTH[m % 16]
    return -r if total < 0 else r


def cell_vector(pic, cx, cy):
    """The mode and vector a picture recorded for a 16 by 16 cell."""
    return pic.vectors.get((cx, cy), ('skip', 0, 0))


def record_vectors(rec, mb, cols, size, kind, vx, vy):
    mbx, mby = mb % cols, mb // cols
    cells = size // 16
    for dy in range(cells):
        for dx in range(cells):
            rec.vectors[(mbx * cells + dx, mby * cells + dy)] = (kind, vx, vy)


def residual_levels(src, pred, mbx, mby, size, q, intra, rru):
    """Forward transform and quantization of the six blocks of a macroblock against a prediction."""
    block = size // 2
    levels = {}
    for k in range(6):
        if k < 4:
            bx = mbx * size + (k % 2) * block
            by = mby * size + (k // 2) * block
            comp = 0
        else:
            bx = mbx * block
            by = mby * block
            comp = k - 3
        plane, stride = src.plane(comp)
        cw = src.cw if comp == 0 else src.cw // 2
        chh = src.ch if comp == 0 else src.ch // 2
        res = [0] * (block * block)
        for rr in range(block):
            for cc in range(block):
                yy = min(by + rr, chh - 1)
                xx = min(bx + cc, cw - 1)
                p = 0 if intra else pred[k][rr * block + cc]
                res[rr * block + cc] = plane[yy * stride + xx] - p
        if rru:
            small = [0] * 64
            for rr in range(8):
                for cc in range(8):
                    small[rr * 8 + cc] = (res[(2 * rr) * 16 + 2 * cc] + res[(2 * rr) * 16 + 2 * cc + 1]
                                          + res[(2 * rr + 1) * 16 + 2 * cc] + res[(2 * rr + 1) * 16 + 2 * cc + 1]) / 4
            res = small
        levels[k] = quantize(fdct(res), q, intra)
    pattern = 0
    for k in range(6):
        if has_coefficients(levels[k], intra):
            pattern |= 32 >> k
    return levels, pattern


def reconstruct(rec, pred, levels, pattern, mbx, mby, size, q, intra, rru):
    block = size // 2
    for k in range(6):
        if k < 4:
            bx = mbx * size + (k % 2) * block
            by = mby * size + (k // 2) * block
            comp = 0
        else:
            bx = mbx * block
            by = mby * block
            comp = k - 3
        coded = intra or bool(pattern & (32 >> k))
        if coded:
            lv = levels[k] if (pattern & (32 >> k)) else ([levels[k][0]] + [0] * 63 if intra else [0] * 64)
            res = idct(dequantize(lv, q, intra))
            if rru:
                res = upsample_residual(res)
        else:
            res = [0] * (block * block)
        plane, stride = rec.plane(comp)
        for rr in range(block):
            for cc in range(block):
                p = 0 if intra else pred[k][rr * block + cc]
                plane[(by + rr) * stride + bx + cc] = max(0, min(255, p + res[rr * block + cc]))


def predict_mb(ref, mbx, mby, size, vectors, rounding, clip_luma, clip_chroma, four=False):
    """Predictions of the six blocks: vectors is one (vx, vy) or four of them."""
    block = size // 2
    if not four:
        vectors = [vectors] * 4
    pred = {}
    for k in range(4):
        bx = mbx * size + (k % 2) * block
        by = mby * size + (k // 2) * block
        pred[k] = predict(ref, 0, bx, by, block, block, vectors[k][0], vectors[k][1], rounding, clip_luma)
    if four:
        cvx = chroma_of_four(sum(v[0] for v in vectors))
        cvy = chroma_of_four(sum(v[1] for v in vectors))
    else:
        cvx, cvy = chroma_vector(vectors[0][0]), chroma_vector(vectors[0][1])
    for k in (4, 5):
        pred[k] = predict(ref, k - 3, mbx * block, mby * block, block, block, cvx, cvy, rounding, clip_chroma)
    return pred


def average(a, b):
    return {k: [(x + y) // 2 for x, y in zip(a[k], b[k])] for k in a}


def sad(src, pred, mbx, mby, size):
    total = 0
    for rr in range(0, size, 2):
        for cc in range(0, size, 2):
            yy = min(mby * size + rr, src.ch - 1)
            xx = min(mbx * size + cc, src.cw - 1)
            total += abs(src.y[yy * src.stride + xx] - pred[0 if rr < size // 2 and cc < size // 2 else
                                                            1 if rr < size // 2 else
                                                            2 if cc < size // 2 else 3][
                (rr % (size // 2)) * (size // 2) + cc % (size // 2)])
    return total


def scale_direct(mv, trb, trd, rru):
    if rru:
        p = pseudo(mv)
        return actual(trunc_div(trb * p, trd)), actual(trunc_div((trb - trd) * p, trd))
    return trunc_div(trb * mv, trd), trunc_div((trb - trd) * mv, trd)


def vector_predictor(vectors, segs, cols, mbx, mby, comp):
    """Median prediction (6.1.1) from the forward or backward vectors of the neighbours; None marks none."""
    idx = mby * cols + mbx
    seg = segs[idx]

    def cand(x, y):
        v = vectors.get(y * cols + x)
        return 0 if v is None else v[comp]

    left = mbx > 0 and segs.get(idx - 1) == seg
    mv1 = cand(mbx - 1, mby) if left else 0
    above = mby > 0 and segs.get(idx - cols) == seg
    if not above:
        mv2 = mv3 = mv1
    else:
        mv2 = cand(mbx, mby - 1)
        mv3 = cand(mbx + 1, mby - 1) if mbx + 1 < cols and segs.get(idx - cols + 1) == seg else mv1
    if mbx + 1 >= cols:
        mv3 = 0
    return sorted([mv1, mv2, mv3])[1]


def write_vector(bw, pred, value, plus_umv, rru):
    if rru:
        code_x = encode_mvd(bw, pseudo(pred[0]), pseudo(value[0]), plus_umv)
        code_y = encode_mvd(bw, pseudo(pred[1]), pseudo(value[1]), plus_umv)
    else:
        code_x = encode_mvd(bw, pred[0], value[0], plus_umv)
        code_y = encode_mvd(bw, pred[1], value[1], plus_umv)
    bw.code(code_x)
    bw.code(code_y)
    if plus_umv and code_x == '000' and code_y == '000':
        bw.code('1')


def encode_b_picture(enc, index, opts, forward_refs, backward_refs, trb, trd):
    """A B-picture of the base layer: direct, forward, backward and bidirectional macroblocks."""
    cfg = enc.cfg
    modes = cfg['modes']
    width = opts.get('width', cfg['width'])
    height = opts.get('height', cfg['height'])
    src = source_picture(enc.source, enc.sw, enc.sh, index, width, height)
    rec = Picture(width, height)
    rec.tr = opts['tr']
    rru = opts.get('rru', False)
    size = 32 if rru else 16
    cols = (rec.cw + size - 1) // size
    rows = (rec.ch + size - 1) // size
    plus_umv = 'UMV' in modes
    erps = 'ERPS' in modes
    multiple = opts.get('mrpa', False)
    two = opts.get('two_backward', False)
    q = opts.get('q', 8)
    rounding = opts.get('rounding', 0)
    clip_l = (0, 0, rec.cw - 1, rec.ch - 1)
    clip_c = (0, 0, rec.cw // 2 - 1, rec.ch // 2 - 1)
    fwd = list(forward_refs)
    implicit = fwd[0].width != width or fwd[0].height != height
    if implicit or opts.get('warp'):
        w = opts.get('warp') or {'w': [0] * 8, 'accuracy': 16, 'fill': 3}
        fillv = {0: w.get('color', (16, 128, 128)), 1: (16, 128, 128), 2: (128, 128, 128), 3: (0, 0, 0)}[w['fill']]
        fwd[0] = warp(fwd[0], width, height, w['w'], w['accuracy'], w['fill'], fillv, backward_refs[0].rounding)
    bw = Bits()

    def extra(b):
        b.put(1, 4)
        b.put(1, 4)
        if erps:
            b.put(4, 3)
            b.put(opts['pn'], 10)
            b.put(1 if multiple else 0, 1)
            for op, val in opts.get('remaps', ()):
                b.code({'neg': '1', 'pos': '010', 'long': '011'}[op])
                b.code(index_code(val if op == 'long' else val - 1))
            b.code('001')
            if multiple:
                b.put(1 if two else 0, 1)
        if opts.get('warp'):
            w = opts['warp']
            b.put(2 if w['accuracy'] == 2 else 3, 2)
            for i in range(0, 8, 2):
                b.code(reversible(w['w'][i]))
                b.code(reversible(w['w'][i + 1]))
                if w['w'][i] == 1 and w['w'][i + 1] == 1:
                    b.code('1')
            b.put(w['fill'], 2)

    rec.rounding = rounding
    write_header(bw, None, rec, 3, q, {**cfg, 'format': opts.get('format', cfg.get('format', 2))}, extra=extra,
                 rru=rru, rpr=bool(opts.get('warp')), ufep=1)
    fwd_vectors = {}
    bwd_vectors = {}
    segs = {}
    coded = {}
    for mb in range(cols * rows):
        segs[mb] = 0
        mbx, mby = mb % cols, mb // cols
        # direct: the subsequent reference's vectors, block by block
        direct_f, direct_b = [], []
        cells = size // 16
        for k in range(4):
            if rru:
                kind, mvx, mvy = cell_vector(backward_refs[0], mbx * 2 + (k % 2), mby * 2 + (k // 2))
            else:
                kind, mvx, mvy = cell_vector(backward_refs[0], mbx, mby)
            if kind != 'inter':
                mvx = mvy = 0
            fx, bx_ = scale_direct(mvx, trb, trd, rru)
            fy, by_ = scale_direct(mvy, trb, trd, rru)
            direct_f.append((fx, fy))
            direct_b.append((bx_, by_))
        candidates = []
        pd = average(predict_mb(fwd[0], mbx, mby, size, direct_f, rounding, clip_l, clip_c, True),
                     predict_mb(backward_refs[0], mbx, mby, size, direct_b, rounding, clip_l, clip_c, True))
        candidates.append((sad(src, pd, mbx, mby, size) - 200, 'direct', pd, None, None, 0, 0))
        best_f = None
        for r, ref in enumerate(fwd[:(len(fwd) if multiple else 1)]):
            for v in opts.get('search', SEARCH):
                if rru and ((v[0] % 2 == 0 and v[0]) or (v[1] % 2 == 0 and v[1])):
                    continue
                p = predict_mb(ref, mbx, mby, size, v, rounding, clip_l, clip_c)
                s = sad(src, p, mbx, mby, size) + 64 * r
                if best_f is None or s < best_f[0]:
                    best_f = (s, p, v, r)
        best_b = None
        for r, ref in enumerate(backward_refs[:(2 if two else 1)]):
            for v in opts.get('search', SEARCH):
                if rru and ((v[0] % 2 == 0 and v[0]) or (v[1] % 2 == 0 and v[1])):
                    continue
                p = predict_mb(ref, mbx, mby, size, v, rounding, clip_l, clip_c)
                s = sad(src, p, mbx, mby, size) + 64 * r
                if best_b is None or s < best_b[0]:
                    best_b = (s, p, v, r)
        candidates.append((best_f[0], 'forward', best_f[1], best_f[2], None, best_f[3], 0))
        candidates.append((best_b[0], 'backward', best_b[1], None, best_b[2], 0, best_b[3]))
        pbi = average(best_f[1], best_b[1])
        candidates.append((sad(src, pbi, mbx, mby, size) + 50, 'bidir', pbi, best_f[2], best_b[2], best_f[3],
                           best_b[3]))
        forced = opts.get('force', {}).get(mb)
        if forced:
            choice = next(c for c in candidates if c[1] == forced)
        else:
            choice = min(candidates, key=lambda c: c[0])
        _, kind, pred, fv, bv, fr, br = choice
        levels, pattern = residual_levels(src, pred, mbx, mby, size, q, False, rru)
        if kind == 'direct' and pattern == 0:
            bw.put(1, 1)
            reconstruct(rec, pred, levels, 0, mbx, mby, size, q, False, rru)
            record_vectors(rec, mb, cols, size, 'skip', 0, 0)
            coded[mb] = False
            continue
        bw.put(0, 1)
        coded[mb] = True
        if erps and multiple:
            bw.code(index_code(0))
        texture = pattern != 0
        mbtype = {'direct': 0, 'forward': 3 if texture else 2, 'backward': 6 if texture else 5,
                  'bidir': 9 if texture else 8}[kind]
        if kind == 'direct':
            texture = True
        bw.code(MBTYPE_B[mbtype])
        if texture:
            bw.code(CBPC_O4[pattern & 3])
            bw.code(CBPY[15 - (pattern >> 2)])
        if fv is not None:
            if erps and multiple:
                bw.code(index_code(fr))
                if fr == 1 and not plus_umv:
                    bw.code('1')
            pred_f = (vector_predictor(fwd_vectors, segs, cols, mbx, mby, 0),
                      vector_predictor(fwd_vectors, segs, cols, mbx, mby, 1))
            write_vector(bw, pred_f, fv, plus_umv, rru)
            fwd_vectors[mb] = fv
        if bv is not None:
            if erps and two:
                bw.put(br, 1)
                if br == 0 and not plus_umv:
                    tail = bw.bits[-6:-1]
                    if len(tail) == 5 and not any(tail):
                        bw.code('1')
            pred_b = (vector_predictor(bwd_vectors, segs, cols, mbx, mby, 0),
                      vector_predictor(bwd_vectors, segs, cols, mbx, mby, 1))
            write_vector(bw, pred_b, bv, plus_umv, rru)
            bwd_vectors[mb] = bv
        for k in range(6):
            if pattern & (32 >> k):
                write_block(bw, levels[k], False)
        reconstruct(rec, pred, levels, pattern, mbx, mby, size, q, False, rru)
        record_vectors(rec, mb, cols, size, 'inter', 0, 0)
    if rru:
        loop_filter(rec, cols, rows, size, coded, {mb: q for mb in range(cols * rows)}, None, False)
    return bw, rec


def recipe_b(enc):
    """Base layer B-pictures between P-pictures: I0 P3 B1 B2 P6 B4 B5."""
    out = Bits()
    stream = Stream(enc.cfg, enc.source, enc.sw, enc.sh)
    display = {}
    bw, i0 = encode_picture(enc, stream, 0, 0, 8, [], {'tr': 0})
    append(out, bw)
    display[0] = i0
    previous = i0
    for anchor_tr in (3, 6):
        bw, p = encode_picture(enc, stream, anchor_tr, 1, 8, [previous], {'tr': anchor_tr, 'search': SEARCH,
                                                                            'rounding': anchor_tr % 2})
        append(out, bw)
        display[anchor_tr] = p
        for tr in (anchor_tr - 2, anchor_tr - 1):
            opts = {'tr': tr, 'search': SEARCH, 'rounding': tr % 2}
            if tr == 1:
                opts['force'] = {0: 'forward', 1: 'backward', 2: 'bidir', 12: 'direct', 40: 'backward'}
            bw, b = encode_b_picture(enc, tr, opts, [previous], [p], tr - previous.tr, p.tr - previous.tr)
            append(out, bw)
            display[tr] = b
        previous = p
    return out, [display[k] for k in sorted(display)]


CONFIGS.update({
    'b': {'width': 176, 'height': 144, 'modes': {'UMV'}, 'recipe': recipe_b},
    'bnoumv': {'width': 176, 'height': 144, 'modes': set(), 'recipe': recipe_b},
})



def recipe_erps_b(enc):
    """Annex U with B-pictures: two-picture backward prediction, several forward references, re-mapping."""
    out = Bits()
    stream = Stream(enc.cfg, enc.source, enc.sw, enc.sh)
    buf = ErpsBuffer()
    display = {}
    mmco0 = [('buffer', 10, 9, 4, 1)]
    bw, i0 = encode_picture(enc, stream, 0, 0, 8, [], {'tr': 0, 'pn': 0,
                                                       'erps': erps_writer(0, sliding=False, mmco=mmco0)})
    append(out, bw)
    buf.store(i0, 0, False, mmco0)
    display[0] = i0
    pn = 0
    anchors = {}
    for tr in (2, 4):
        pn += 1
        refs = buf.order((), pn)
        bw, p = encode_picture(enc, stream, tr, 1, 8, refs, {'tr': tr, 'pn': pn, 'search': SEARCH,
                                                            'erps': erps_writer(1), 'rounding': tr // 2 % 2})
        append(out, bw)
        buf.store(p, pn, True, ())
        display[tr] = p
        anchors[tr] = p
    # B1: two-picture backward prediction from P4 and P2, forward from I0
    order = buf.order((), pn + 1)
    opts = {'tr': 1, 'pn': pn + 1, 'mrpa': True, 'two_backward': True, 'search': SEARCH,
            'force': {0: 'backward', 1: 'forward', 2: 'bidir', 5: 'direct', 30: 'backward', 31: 'bidir'}}
    bw, b1 = encode_b_picture(enc, 1, opts, order[2:], order[:2], 1 - order[2].tr, order[0].tr - order[2].tr)
    append(out, bw)
    display[1] = b1
    # B3: single backward prediction from the re-mapped first picture P4, forward from P2 and I0
    remaps = [('neg', 1)]
    order = buf.order(remaps, pn + 1)
    opts = {'tr': 3, 'pn': pn + 1, 'mrpa': True, 'two_backward': False, 'remaps': remaps, 'search': SEARCH,
            'force': {3: 'forward', 4: 'bidir', 50: 'direct'}}
    bw, b3 = encode_b_picture(enc, 3, opts, order[1:], order[:1], 3 - order[1].tr, order[0].tr - order[1].tr)
    append(out, bw)
    display[3] = b3
    return out, [display[k] for k in sorted(display)]


def recipe_b_rpr(enc):
    """B-picture whose forward reference is resampled implicitly to its size (P.1)."""
    out = Bits()
    stream = Stream(enc.cfg, enc.source, enc.sw, enc.sh)
    bw, i0 = encode_picture(enc, stream, 0, 0, 8, [], {'tr': 0})
    append(out, bw)
    bw, p2 = encode_picture(enc, stream, 2, 1, 8, [i0], {'tr': 2, 'width': 128, 'height': 96, 'format': 1,
                                                         'search': SEARCH, 'rounding': 1})
    append(out, bw)
    opts = {'tr': 1, 'width': 128, 'height': 96, 'format': 1, 'search': SEARCH,
            'force': {0: 'forward', 1: 'bidir', 2: 'backward'}}
    bw, b1 = encode_b_picture(enc, 1, opts, [i0], [p2], 1, 2)
    append(out, bw)
    bw, p4 = encode_picture(enc, stream, 4, 1, 8, [p2], {'tr': 4, 'width': 128, 'height': 96, 'format': 1,
                                                         'search': SEARCH})
    append(out, bw)
    opts = {'tr': 3, 'width': 128, 'height': 96, 'format': 1, 'search': SEARCH, 'rounding': 1,
            'warp': {'w': [2, -1, 3, 1, -2, 2, 1, 0], 'accuracy': 16, 'fill': 2}}
    bw, b3 = encode_b_picture(enc, 3, opts, [p2], [p4], 1, 2)
    append(out, bw)
    return out, [i0, b1, p2, b3, p4]


def recipe_b_rru(enc):
    """Reduced-Resolution Update B-pictures with the direct mode of pseudo vectors (Q.4)."""
    out = Bits()
    stream = Stream(enc.cfg, enc.source, enc.sw, enc.sh)
    bw, i0 = encode_picture(enc, stream, 0, 0, 8, [], {'tr': 0})
    append(out, bw)
    bw, p3 = encode_picture(enc, stream, 3, 1, 8, [i0], {'tr': 3, 'rru': True, 'search': RRU_SEARCH,
                                                         'force': {4: (0, 9, -5), 13: (0, -7, 3)}})
    append(out, bw)
    shown = [i0]
    for tr in (1, 2):
        opts = {'tr': tr, 'rru': True, 'search': RRU_SEARCH, 'rounding': tr % 2,
                'force': {4: 'direct', 13: 'direct', 0: 'forward', 1: 'backward', 2: 'bidir'}}
        bw, b = encode_b_picture(enc, tr, opts, [i0], [p3], tr, 3)
        append(out, bw)
        shown.append(b)
    shown.append(p3)
    return out, shown


CONFIGS.update({
    'erpsb': {'width': 176, 'height': 144, 'modes': {'ERPS', 'UMV'}, 'recipe': recipe_erps_b},
    'erpsbnoumv': {'width': 176, 'height': 144, 'modes': {'ERPS'}, 'recipe': recipe_erps_b},
    'brpr': {'width': 176, 'height': 144, 'modes': {'UMV'}, 'recipe': recipe_b_rpr},
    'brru': {'width': 176, 'height': 144, 'modes': {'UMV'}, 'recipe': recipe_b_rru},
})





def predict_from_prec(prec, comp, ox, oy, n, vx, vy, forward):
    """G.5: averages the forward prediction with the backward one from the reconstructed P-macroblock where the
    backward vector stays inside it."""
    plane, width = prec[comp]
    ix, fx = vx >> 1, vx & 1
    iy, fy = vy >> 1, vy & 1
    result = list(forward)
    for r in range(n):
        y0 = oy + r + iy
        y1 = y0 + fy
        if y0 < 0 or y1 > width - 1:
            continue
        for c in range(n):
            x0 = ox + c + ix
            x1 = x0 + fx
            if x0 < 0 or x1 > width - 1:
                continue
            a = plane[y0 * width + x0]
            if fx == 0 and fy == 0:
                v = a
            elif fy == 0:
                v = (a + plane[y0 * width + x1] + 1) // 2
            elif fx == 0:
                v = (a + plane[y1 * width + x0] + 1) // 2
            else:
                v = (a + plane[y0 * width + x1] + plane[y1 * width + x0] + plane[y1 * width + x1] + 2) // 4
            result[r * n + c] = (forward[r * n + c] + v) // 2
    return result


def encode_ipb_picture(enc, index_p, index_b, opts, refs, previous_tr):
    """An Improved PB-frame (Annex M): every macroblock's P-part with one vector, and its B-part in the
    bidirectional, forward or backward mode."""
    cfg = enc.cfg
    modes = cfg['modes']
    width, height = cfg['width'], cfg['height']
    src_p = source_picture(enc.source, enc.sw, enc.sh, index_p, width, height)
    src_b = source_picture(enc.source, enc.sw, enc.sh, index_b, width, height)
    rec = Picture(width, height)
    part = Picture(width, height)
    rec.tr = opts['tr']
    part.tr = opts['tr']
    rec.rounding = opts.get('rounding', 0)
    rec.pn = opts.get('pn', 0)
    rru = opts.get('rru', False)
    size = 32 if rru else 16
    block = size // 2
    cols = (rec.cw + size - 1) // size
    rows = (rec.ch + size - 1) // size
    plus_umv = 'UMV' in modes
    erps = 'ERPS' in modes
    multiple = opts.get('mrpa', False)
    df = 'DF' in modes
    q = opts.get('q', 8)
    dbq = opts.get('dbquant', 1)
    bq = min(31, (5 + dbq) * q // 4)
    trb = opts['trb']
    trd = (opts['tr'] - previous_tr) % 256
    clip_l = (0, 0, rec.cw - 1, rec.ch - 1)
    clip_c = (0, 0, rec.cw // 2 - 1, rec.ch // 2 - 1)
    bw = Bits()

    def extra(b):
        if erps:
            b.put(4, 3)
            b.put(rec.pn, 10)
            opts['erps'](b)

    cfgh = dict(cfg)
    write_header(bw, None, rec, 2, q, cfgh, extra=extra, rru=rru, ufep=1)
    # TRB and DBQUANT follow PQUANT: rewrite the PEI written by write_header
    bw.bits.pop()
    bw.put(trb, 3)
    bw.put(dbq, 2)
    bw.put(0, 1)
    mvs = {}
    segs = {}
    part_fwd = {}
    coded = {}
    quant = {}
    for mb in range(cols * rows):
        segs[mb] = 0
        mbx, mby = mb % cols, mb // cols
        # P-part
        best = None
        for r, ref in enumerate(refs[:(len(refs) if multiple else 1)]):
            for v in opts.get('search', SEARCH):
                if rru and ((v[0] % 2 == 0 and v[0]) or (v[1] % 2 == 0 and v[1])):
                    continue
                p = predict_mb(ref, mbx, mby, size, v, rec.rounding, clip_l, clip_c)
                s = sad(src_p, p, mbx, mby, size) + 64 * r
                if best is None or s < best[0]:
                    best = (s, p, v, r)
        _, ppred, pv, pr = best
        forced = opts.get('force', {}).get(mb, {})
        if 'p' in forced:
            pr, pv = forced['p']
            ppred = predict_mb(refs[pr], mbx, mby, size, pv, rec.rounding, clip_l, clip_c)
        intra = mb in opts.get('intra_mbs', ())
        plevels, ppattern = residual_levels(src_p, ppred, mbx, mby, size, q, intra, rru)
        # reconstruct the P-part (PREC) now: the B-part predicts from it
        reconstruct(rec, ppred, plevels, ppattern, mbx, mby, size, q, intra, rru)
        prec = {0: (bytearray(size * size), size), 1: (bytearray(block * block), block),
                2: (bytearray(block * block), block)}
        for comp in range(3):
            plane, stride = rec.plane(comp)
            n = size if comp == 0 else block
            ox = mbx * size if comp == 0 else mbx * block
            oy = mby * size if comp == 0 else mby * block
            for r in range(n):
                for c in range(n):
                    prec[comp][0][r * n + c] = plane[(oy + r) * stride + ox + c]
        # B-part candidates
        mvp = (0, 0) if intra else pv
        bfwd_ref = refs[pr]
        fx, bx_ = scale_direct(mvp[0], trb, trd, rru)
        fy, by_ = scale_direct(mvp[1], trb, trd, rru)
        fpred = predict_mb(bfwd_ref, mbx, mby, size, (fx, fy), 0, clip_l, clip_c)
        bi = {}
        for k in range(4):
            bi[k] = predict_from_prec(prec, 0, (k % 2) * block, (k // 2) * block, block, bx_, by_, fpred[k])
        cbx, cby = chroma_vector(bx_), chroma_vector(by_)
        for k in (4, 5):
            bi[k] = predict_from_prec(prec, k - 3, 0, 0, block, cbx, cby, fpred[k])
        back = {}
        for k in range(4):
            back[k] = [prec[0][0][((k // 2) * block + r) * size + (k % 2) * block + c]
                       for r in range(block) for c in range(block)]
        for k in (4, 5):
            back[k] = list(prec[k - 3][0])
        bestf = None
        for r, ref in enumerate(refs[:(len(refs) if multiple else 1)]):
            for v in opts.get('search', SEARCH):
                if rru and ((v[0] % 2 == 0 and v[0]) or (v[1] % 2 == 0 and v[1])):
                    continue
                p = predict_mb(ref, mbx, mby, size, v, 0, clip_l, clip_c)
                s = sad(src_b, p, mbx, mby, size) + 64 * r
                if bestf is None or s < bestf[0]:
                    bestf = (s, p, v, r)
        cands = [(sad(src_b, bi, mbx, mby, size) - 100, 'bidir', bi), (bestf[0], 'forward', bestf[1]),
                 (sad(src_b, back, mbx, mby, size), 'backward', back)]
        kind = forced.get('b') or min(cands, key=lambda c: c[0])[1]
        bpred = next(c[2] for c in cands if c[1] == kind)
        blevels, bpattern = residual_levels(src_b, bpred, mbx, mby, size, bq, False, rru)
        # syntax
        skip = (not intra and pv == (0, 0) and pr == 0 and ppattern == 0 and kind == 'bidir' and bpattern == 0
                and not forced)
        if skip:
            bw.put(1, 1)
            mvs[mb] = (0, 0)
            record_vectors(rec, mb, cols, size, 'skip', 0, 0)
            coded[mb] = False
            quant[mb] = q
        else:
            bw.put(0, 1)
            if erps and multiple:
                bw.code(index_code(0))
            cbpc = ppattern & 3
            bw.code(MCBPC_P[(3 if intra else 0) * 4 + cbpc])
            modb = {'bidir': 0, 'forward': 2, 'backward': 4}[kind] + (1 if bpattern else 0)
            bw.code(MODB_M[modb])
            if bpattern:
                bw.put(bpattern, 6)
            cbpy = ppattern >> 2
            bw.code(CBPY[cbpy if intra else 15 - cbpy])
            pred_p = (vector_predictor(mvs, segs, cols, mbx, mby, 0), vector_predictor(mvs, segs, cols, mbx, mby, 1))
            if erps and multiple:
                bw.code(index_code(pr))
                if pr == 1 and not plus_umv:
                    bw.code('1')
            write_vector(bw, pred_p, mvp, plus_umv, rru)
            mvs[mb] = mvp if (not intra or kind == 'bidir') else None
            if kind == 'forward':
                left = part_fwd.get(mb - 1) if mbx > 0 else None
                bpv = bestf[2]
                if erps and multiple:
                    bw.code(index_code(bestf[3]))
                    if bestf[3] == 1 and not plus_umv:
                        bw.code('1')
                write_vector(bw, left if left else (0, 0), bpv, plus_umv, rru)
                part_fwd[mb] = bpv
            for k in range(6):
                if ppattern & (32 >> k):
                    write_block(bw, plevels[k], intra)
                elif intra:
                    bw.put(255 if plevels[k][0] == 128 else plevels[k][0], 8)
            for k in range(6):
                if bpattern & (32 >> k):
                    write_block(bw, blevels[k], False)
            record_vectors(rec, mb, cols, size, 'intra' if intra else 'inter', pv[0], pv[1])
            coded[mb] = True
            quant[mb] = q
        reconstruct(part, bpred, blevels, bpattern if not skip else 0, mbx, mby, size, bq, False, rru)
    if df or rru:
        loop_filter(rec, cols, rows, size, coded, quant, None, df)
        loop_filter(part, cols, rows, size, coded, {mb: min(31, (5 + dbq) * quant[mb] // 4) for mb in quant},
                    None, df)
    return bw, rec, part


def recipe_ipb(enc):
    """Improved PB-frames: I0, then frames whose B-part lies one picture before the P-part."""
    out = Bits()
    stream = Stream(enc.cfg, enc.source, enc.sw, enc.sh)
    erps = 'ERPS' in enc.cfg['modes']
    buf = ErpsBuffer()
    mmco0 = [('buffer', 10, 9, 3, 1)]
    opts0 = {'tr': 0}
    if erps:
        opts0['erps'] = erps_writer(0, sliding=False, mmco=mmco0)
    bw, i0 = encode_picture(enc, stream, 0, 0, 8, [], opts0)
    append(out, bw)
    if erps:
        buf.store(i0, 0, False, mmco0)
    shown = [i0]
    previous = i0
    pn = 0
    for k, tr in enumerate((2, 4, 6)):
        pn += 1
        refs = buf.order((), pn) if erps else [previous]
        opts = {'tr': tr, 'trb': 1, 'pn': pn, 'search': SEARCH, 'rounding': k % 2, 'rru': enc.cfg.get('rru', False),
                'mrpa': erps, 'dbquant': k % 4,
                'force': {0: {'b': 'forward'}, 1: {'b': 'backward'}, 2: {'b': 'bidir'}}}
        if erps:
            opts['erps'] = erps_writer(1, multiple=True)
        if enc.cfg.get('rru'):
            opts['search'] = RRU_SEARCH
        opts['intra_mbs'] = (5,)
        bw, p, part = encode_ipb_picture(enc, tr, tr - 1, opts, refs, previous.tr)
        append(out, bw)
        if erps:
            buf.store(p, pn, True, ())
        shown += [part, p]
        previous = p
    return out, shown


CONFIGS.update({
    'ipb': {'width': 176, 'height': 144, 'modes': {'UMV'}, 'recipe': recipe_ipb},
    'ipbdf': {'width': 176, 'height': 144, 'modes': {'UMV', 'DF'}, 'recipe': recipe_ipb},
    'ipberps': {'width': 176, 'height': 144, 'modes': {'UMV', 'ERPS'}, 'recipe': recipe_ipb},
    'ipbrru': {'width': 176, 'height': 144, 'modes': {'UMV'}, 'rru': True, 'recipe': recipe_ipb},
})


if __name__ == '__main__':
    main()
