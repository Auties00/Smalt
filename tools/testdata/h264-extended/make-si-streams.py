"""Builds the hand-made SI streams of the H.264 Extended profile tests and computes their expected frames.

Usage: python make-si-streams.py <output directory>

The JM decoder cannot decode SI macroblocks and its encoder never writes them, so these streams are written bit by
bit, and their expected pictures come from the model below, written from ITU-T H.264 (8.3.1, 8.3.4, 8.5.12, 8.6.2):
Intra_4x4 prediction (vertical, horizontal and DC), Intra chroma DC prediction, the SI transform-domain
requantization of luma and chroma, and the I_NxN reconstruction of an intra macroblock without residual. Each
stream is a 32x32 Extended profile sequence: an IDR picture of four I_PCM macroblocks, then an SI picture of SI
macroblocks and one I_NxN macroblock, without deblocking. si-model uses constrained_intra_pred_flag 0 and
si-model-cip 1, under which the I_NxN macroblock may not predict from its SI neighbors.

Writes <name>.264 and prints the streams.tsv and frames.tsv rows (the MD5 of each frame as planar 4:2:0).
"""
import hashlib
import os
import sys

W = H = 32
QP, QS, CHROMA_OFFSET = 28, 30, 2
CBP_INTRA = [47, 31, 15, 0, 23, 27, 29, 30, 7, 11, 13, 14, 39, 43, 45, 46, 16, 3, 5, 10, 12, 19, 21, 26, 28, 35,
             37, 42, 44, 1, 2, 4, 8, 17, 18, 20, 24, 6, 9, 22, 25, 32, 33, 34, 36, 40, 38, 41]
CHROMA_QP = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27,
             28, 29, 29, 30, 31, 32, 32, 33, 34, 34, 35, 35, 36, 36, 37, 37, 37, 38, 38, 38, 39, 39, 39, 39]
NORM = [[10, 16, 13], [11, 18, 14], [13, 20, 16], [14, 23, 18], [16, 25, 20], [18, 29, 23]]   # 8-315
QUANT = [[13107, 5243, 8066], [11916, 4660, 7490], [10082, 4194, 6554], [9362, 3647, 5825], [8192, 3355, 5243],
         [7282, 2893, 4559]]                                                                    # 8-419
ZIGZAG = [0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15]
TOTAL_ZEROS_1 = ['1', '011', '010', '0011']    # total_zeros codes of TotalCoeff 1, Table 9-7
BLOCK_X = [0, 1, 0, 1, 2, 3, 2, 3, 0, 1, 0, 1, 2, 3, 2, 3]
BLOCK_Y = [0, 0, 1, 1, 0, 0, 1, 1, 2, 2, 3, 3, 2, 2, 3, 3]


def cls(i, j):
    if i % 2 == 0 and j % 2 == 0:
        return 0
    if i % 2 == 1 and j % 2 == 1:
        return 1
    return 2


class Bits:
    def __init__(self):
        self.bits = []

    def u(self, value, n):
        for k in range(n - 1, -1, -1):
            self.bits.append((value >> k) & 1)
        return self

    def ue(self, value):
        code = value + 1
        n = code.bit_length() - 1
        return self.u(0, n).u(code, n + 1)

    def se(self, value):
        return self.ue(2 * value - 1 if value > 0 else -2 * value)

    def align(self):
        while len(self.bits) % 8:
            self.bits.append(0)
        return self

    def rbsp(self):
        bits = self.bits + [1]
        while len(bits) % 8:
            bits.append(0)
        return bytes(int(''.join(map(str, bits[k:k + 8])), 2) for k in range(0, len(bits), 8))


def nal(ref, kind, rbsp):
    out = bytearray([(ref << 5) | kind])
    zeros = 0
    for b in rbsp:
        if zeros >= 2 and b <= 3:
            out.append(3)
            zeros = 0
        out.append(b)
        zeros = zeros + 1 if b == 0 else 0
    return b'\x00\x00\x00\x01' + bytes(out)


def sps():
    b = Bits().u(88, 8).u(0, 8).u(30, 8).ue(0).ue(0).ue(2).ue(1).u(0, 1).ue(W // 16 - 1).ue(H // 16 - 1)
    return nal(3, 7, b.u(1, 1).u(1, 1).u(0, 1).u(0, 1).rbsp())


def pps(cip):
    b = Bits().ue(0).ue(0).u(0, 1).u(0, 1).ue(0).ue(0).ue(0).u(0, 1).u(0, 2).se(0).se(0).se(CHROMA_OFFSET)
    return nal(3, 8, b.u(1, 1).u(cip, 1).u(0, 1).rbsp())


def pcm_samples(mb):
    luma = [16 + (mb * 61 + x * 9 + y * 5 + (x * y) % 7) % 200 for y in range(16) for x in range(16)]
    chroma = [32 + (mb * 37 + i * 11) % 180 for i in range(128)]
    return luma, chroma


def idr(frame):
    b = Bits().ue(0).ue(7).ue(0).u(0, 4).ue(0).u(0, 1).u(0, 1).se(QP - 26).ue(1)
    for mb in range(4):
        b.ue(25).align()
        luma, chroma = pcm_samples(mb)
        for v in luma + chroma:
            b.u(v, 8)
        mx, my = (mb % 2) * 16, (mb // 2) * 16
        for y in range(16):
            for x in range(16):
                frame['Y'][my + y][mx + x] = luma[y * 16 + x]
        for c, plane in enumerate('UV'):
            for y in range(8):
                for x in range(8):
                    frame[plane][my // 2 + y][mx // 2 + x] = chroma[c * 64 + y * 8 + x]
    return nal(3, 5, b.rbsp())


def forward(p):
    t = [[1, 1, 1, 1], [2, 1, -1, -2], [1, -1, -1, 1], [1, -2, 2, -1]]
    tmp = [[sum(t[i][k] * p[k][j] for k in range(4)) for j in range(4)] for i in range(4)]
    return [[sum(tmp[i][k] * t[j][k] for k in range(4)) for j in range(4)] for i in range(4)]


def quantize(c, scale, shift):
    magnitude = (abs(c) * scale + (1 << (shift - 1))) >> shift
    return magnitude if c >= 0 else -magnitude


def scale4(c, qp, i, j, dc_passthrough=False):
    if dc_passthrough and i == 0 and j == 0:
        return c
    ls = 16 * NORM[qp % 6][cls(i, j)]
    if qp >= 24:
        return (c * ls) << (qp // 6 - 4)
    return (c * ls + (1 << (3 - qp // 6))) >> (4 - qp // 6)


def inverse(d):
    e = [[0] * 4 for _ in range(4)]
    for i in range(4):
        a, b2, c, dd = d[i]
        e0, e1, e2, e3 = a + c, a - c, (b2 >> 1) - dd, b2 + (dd >> 1)
        e[i] = [e0 + e3, e1 + e2, e1 - e2, e0 - e3]
    r = [[0] * 4 for _ in range(4)]
    for j in range(4):
        a, b2, c, dd = e[0][j], e[1][j], e[2][j], e[3][j]
        g0, g1, g2, g3 = a + c, a - c, (b2 >> 1) - dd, b2 + (dd >> 1)
        col = [g0 + g3, g1 + g2, g1 - g2, g0 - g3]
        for i in range(4):
            r[i][j] = (col[i] + 32) >> 6
    return r


def clip(v):
    return max(0, min(255, v))


def si_luma(pred, levels):
    cp = forward(pred)
    c = [[levels[i][j] + quantize(cp[i][j], QUANT[QS % 6][cls(i, j)], 15 + QS // 6) for j in range(4)]
         for i in range(4)]
    d = [[scale4(c[i][j], QS, i, j) for j in range(4)] for i in range(4)]
    r = inverse(d)
    return [[clip(r[i][j]) for j in range(4)] for i in range(4)]


def si_chroma(pred8, dc_levels, ac_levels):
    qsc = CHROMA_QP[max(0, min(51, QS + CHROMA_OFFSET))]
    blocks = []
    for blk in range(4):
        bx, by = (blk % 2) * 4, (blk // 2) * 4
        blocks.append(forward([[pred8[by + i][bx + j] for j in range(4)] for i in range(4)]))
    p = [blk[0][0] for blk in blocks]
    dcp = [[p[0] + p[1] + p[2] + p[3], p[0] - p[1] + p[2] - p[3]],
           [p[0] + p[1] - p[2] - p[3], p[0] - p[1] - p[2] + p[3]]]
    dcr = [[quantize(dcp[i][j], QUANT[qsc % 6][0], 16 + qsc // 6) + dc_levels[2 * i + j] for j in range(2)]
           for i in range(2)]
    f = [[dcr[0][0] + dcr[0][1] + dcr[1][0] + dcr[1][1], dcr[0][0] - dcr[0][1] + dcr[1][0] - dcr[1][1]],
         [dcr[0][0] + dcr[0][1] - dcr[1][0] - dcr[1][1], dcr[0][0] - dcr[0][1] - dcr[1][0] + dcr[1][1]]]
    out = [[0] * 8 for _ in range(8)]
    for blk in range(4):
        bx, by = (blk % 2) * 4, (blk // 2) * 4
        c = [[0 if (i, j) == (0, 0) else quantize(blocks[blk][i][j], QUANT[qsc % 6][cls(i, j)], 15 + qsc // 6)
              for j in range(4)] for i in range(4)]
        value, index = ac_levels[blk]
        raster = ZIGZAG[index + 1]
        c[raster // 4][raster % 4] += value
        c[0][0] = ((f[blk // 2][blk % 2] * 16 * NORM[qsc % 6][0]) << (qsc // 6)) >> 5
        d = [[scale4(c[i][j], qsc, i, j, True) for j in range(4)] for i in range(4)]
        r = inverse(d)
        for i in range(4):
            for j in range(4):
                out[by + i][bx + j] = clip(r[i][j])
    return out


def build(cip):
    frames = []
    first = {'Y': [[0] * W for _ in range(H)], 'U': [[0] * (W // 2) for _ in range(H // 2)],
             'V': [[0] * (W // 2) for _ in range(H // 2)]}
    stream = sps() + pps(cip) + idr(first)
    frames.append(first)
    frame = {'Y': [[None] * W for _ in range(H)], 'U': [[None] * (W // 2) for _ in range(H // 2)],
             'V': [[None] * (W // 2) for _ in range(H // 2)]}
    kinds = ['SI', 'SI', 'I', 'SI']
    modes = {}
    nnz = {}
    chroma_nnz = {}
    b = Bits().ue(0).ue(4).ue(0).u(1, 4).u(0, 1).se(QP - 26).se(QS - 26).ue(1)
    for mb in range(4):
        mx, my = (mb % 2) * 16, (mb // 2) * 16
        kind = kinds[mb]
        b.ue(0 if kind == 'SI' else 1)

        def neighbor(x, y):
            return None if x < 0 or y < 0 or x >= W or y >= H else (y // 16) * 2 + x // 16

        def samples_ok(x, y):
            # sample availability for intra prediction (8.3.1.2, 8.3.4): inside the picture, decoded, and
            # under constrained_intra_pred_flag an SI neighbor serves only SI macroblocks
            n = neighbor(x, y)
            if n is None or n > mb:
                return False
            if n == mb:
                return True
            return not (cip and kinds[n] == 'SI' and kind != 'SI')

        cbp_luma = {0: 0b0101, 1: 0b1111, 2: 0, 3: 0b1010}[mb]
        cbp_chroma = 0 if kind == 'I' else 2 if mb == 3 else 1
        levels = {}
        for blk in range(16):
            x, y = mx + BLOCK_X[blk] * 4, my + BLOCK_Y[blk] * 4
            choice = (mb * 5 + blk * 3) % 3
            if kind == 'I' and cip:
                mode = 2
            elif choice == 0 and samples_ok(x, y - 1):
                mode = 0
            elif choice == 1 and samples_ok(x - 1, y):
                mode = 1
            else:
                mode = 2
            # Intra4x4PredMode prediction (8.3.1.1): the modes of the neighboring blocks of Intra_4x4
            # macroblocks, SI macroblocks included; DC when a neighboring macroblock is not available
            if neighbor(x - 1, y) is None or neighbor(x, y - 1) is None:
                predicted = 2
            else:
                predicted = min(modes[(x - 4, y)], modes[(x, y - 4)])
            if mode == predicted:
                b.u(1, 1)
            else:
                b.u(0, 1).u(mode if mode < predicted else mode - 1, 3)
            modes[(x, y)] = mode
            coded = (cbp_luma >> (blk // 4)) & 1
            value = (1 if (mb + blk) % 3 == 0 else -1 if (mb + blk) % 3 == 1 else 0) if coded else 0
            levels[blk] = (value, (mb + blk) % 4)
        b.ue(0)
        cbp = (cbp_chroma << 4) | cbp_luma
        b.ue(CBP_INTRA.index(cbp))
        if cbp:
            b.se(0)
        for blk in range(16):
            x, y = mx + BLOCK_X[blk] * 4, my + BLOCK_Y[blk] * 4
            coded = (cbp_luma >> (blk // 4)) & 1
            value, position = levels[blk]
            count = 1 if value != 0 else 0
            nnz[(x, y)] = count if coded else 0
            if not coded:
                continue
            na = nnz.get((x - 4, y)) if x > 0 else None
            nb = nnz.get((x, y - 4)) if y > 0 else None
            if na is not None and nb is not None:
                nc = (na + nb + 1) >> 1
            else:
                nc = na if na is not None else (nb if nb is not None else 0)
            assert nc < 2
            if count == 0:
                b.u(1, 1)
            else:
                b.u(1, 2).u(1 if value < 0 else 0, 1)
                for bit in TOTAL_ZEROS_1[position]:
                    b.u(int(bit), 1)
        cb_level = [1 if mb % 2 == 0 else -1, 0, 0, 0] if cbp_chroma else [0, 0, 0, 0]
        if cbp_chroma:
            b.u(1, 1).u(1 if cb_level[0] < 0 else 0, 1).u(1, 1)
            b.u(1, 2)
        # chroma AC (cbp chroma 2): one coefficient of +-1 at AC scan index 0 or 1 in some blocks, coded with the
        # nC of the neighboring chroma blocks of the same component (9.2.1), which hold 0 or 1 coefficients
        chroma_ac = {}
        if cbp_chroma == 2:
            for c in range(2):
                for blk in range(4):
                    pattern = (mb + c * 2 + blk) % 3
                    value = 1 if pattern == 0 else -1 if pattern == 1 else 0
                    chroma_ac[(c, blk)] = (value, blk % 2)
                    x, y = mx // 2 + (blk % 2) * 4, my // 2 + (blk // 2) * 4
                    na = chroma_nnz.get((c, x - 4, y)) if x > 0 else None
                    nb = chroma_nnz.get((c, x, y - 4)) if y > 0 else None
                    if na is not None and nb is not None:
                        nc = (na + nb + 1) >> 1
                    else:
                        nc = na if na is not None else (nb if nb is not None else 0)
                    assert nc < 2
                    if value == 0:
                        b.u(1, 1)
                    else:
                        b.u(1, 2).u(1 if value < 0 else 0, 1)
                        for bit in TOTAL_ZEROS_1[blk % 2]:
                            b.u(int(bit), 1)
                    chroma_nnz[(c, x, y)] = 1 if value else 0
        else:
            for c in range(2):
                for blk in range(4):
                    chroma_nnz[(c, mx // 2 + (blk % 2) * 4, my // 2 + (blk // 2) * 4)] = 0
        # reconstruction, block by block in decoding order
        for blk in range(16):
            x, y = mx + BLOCK_X[blk] * 4, my + BLOCK_Y[blk] * 4
            mode = modes[(x, y)]
            top = [frame['Y'][y - 1][x + j] for j in range(4)] if samples_ok(x, y - 1) else None
            left = [frame['Y'][y + i][x - 1] for i in range(4)] if samples_ok(x - 1, y) else None
            if mode == 0:
                pred = [list(top) for _ in range(4)]
            elif mode == 1:
                pred = [[left[i]] * 4 for i in range(4)]
            else:
                if top and left:
                    dc = (sum(top) + sum(left) + 4) >> 3
                elif left:
                    dc = (sum(left) + 2) >> 2
                elif top:
                    dc = (sum(top) + 2) >> 2
                else:
                    dc = 128
                pred = [[dc] * 4 for _ in range(4)]
            if kind == 'SI':
                lv = [[0] * 4 for _ in range(4)]
                value, position = levels[blk]
                raster = ZIGZAG[position]
                lv[raster // 4][raster % 4] = value
                out = si_luma(pred, lv)
            else:
                out = pred
            for i in range(4):
                for j in range(4):
                    frame['Y'][y + i][x + j] = out[i][j]
        cx, cy = mx // 2, my // 2
        top_ok = samples_ok(mx, my - 1)
        left_ok = samples_ok(mx - 1, my)
        for c, plane in enumerate('UV'):
            p = frame[plane]
            pred8 = [[0] * 8 for _ in range(8)]
            for blk in range(4):
                bx, by = (blk % 2) * 4, (blk // 2) * 4
                top = [p[cy - 1][cx + bx + j] for j in range(4)] if top_ok else None
                left = [p[cy + by + i][cx - 1] for i in range(4)] if left_ok else None
                if (bx, by) in ((0, 0), (4, 4)):
                    if top and left:
                        dc = (sum(top) + sum(left) + 4) >> 3
                    elif left:
                        dc = (sum(left) + 2) >> 2
                    elif top:
                        dc = (sum(top) + 2) >> 2
                    else:
                        dc = 128
                elif (bx, by) == (4, 0):
                    dc = (sum(top) + 2) >> 2 if top else (sum(left) + 2) >> 2 if left else 128
                else:
                    dc = (sum(left) + 2) >> 2 if left else (sum(top) + 2) >> 2 if top else 128
                for i in range(4):
                    for j in range(4):
                        pred8[by + i][bx + j] = dc
            ac = [chroma_ac.get((c, blk), (0, 0)) for blk in range(4)]
            out = si_chroma(pred8, cb_level if c == 0 else [0, 0, 0, 0], ac) if kind == 'SI' else pred8
            for i in range(8):
                for j in range(8):
                    p[cy + i][cx + j] = out[i][j]
    stream += nal(2, 1, b.rbsp())
    frames.append(frame)
    return stream, frames


def serialize(frame):
    out = bytearray()
    for plane in 'YUV':
        for row in frame[plane]:
            out += bytes(row)
    return bytes(out)


out_dir = sys.argv[1]
for name, cip in (('si-model', 0), ('si-model-cip', 1)):
    stream, frames = build(cip)
    open(os.path.join(out_dir, name + '.264'), 'wb').write(stream)
    print('%s\t%s\t%d\t32x32, 2 frames\thand-built (make-si-streams.py), constrained_intra_pred_flag %d' % (
        name, hashlib.sha256(stream).hexdigest(), len(stream), cip))
    for index, frame in enumerate(frames):
        print('%s\t%d\t%s' % (name, index, hashlib.md5(serialize(frame)).hexdigest()))
