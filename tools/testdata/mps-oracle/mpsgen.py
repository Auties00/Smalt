"""Writes synthetic MPEG Surround streams in the mpsref .bs format and their downmix:
mpsgen.py <refsoft mp4spatialdec/src> <out dir> [case names...]

Every case is a SpatialSpecificConfig with random but valid SpatialFrame() payloads: PCM coded parameters (data modes
0 to 3, pairs, coarse quantization, frequency strides), smoothing, temporal shaping (with guided envelope shaping
envelopes Huffman coded with the reference tree), arbitrary downmix gains and residual or downmix residual frames
coded as AAC individual channel streams with codebook 11 (Huffman codes read from the reference tables).
"""
import math
import os
import random
import re
import struct
import sys

SRC = sys.argv[1]
OUT = sys.argv[2]

FREQ_RES = [0, 28, 20, 14, 10, 7, 5, 4]
STRIDES = [1, 2, 5, 28]
TREE = {0: (1, 6, 5, 0, [0, 0, 0, 0, 1]), 1: (1, 6, 5, 0, [0, 0, 1, 0, 0]), 2: (2, 6, 3, 1, [1, 0, 0, 0, 0]),
        3: (2, 8, 5, 1, [1, 0, 0, 0, 0]), 4: (2, 8, 5, 1, [1, 0, 0, 0, 0]), 5: (6, 8, 2, 0, [0] * 5),
        6: (6, 8, 2, 0, [0] * 5)}
TEMP_SHAPE_CHANNELS = [[5, 5, 4, 6, 6, 4, 4], [5, 5, 5, 7, 7, 4, 4]]
RATES = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350]
LEVELS = {('cld', 0): (31, 15), ('cld', 1): (15, 7), ('icc', 0): (8, 0), ('icc', 1): (4, 0), ('cpc', 0): (51, 20),
          ('cpc', 1): (26, 10)}
GROUP = {3: 5, 7: 6, 11: 2, 13: 4, 19: 4, 25: 3, 51: 4}


class Bits:
    def __init__(self):
        self.bits = []

    def put(self, value, count):
        for i in range(count - 1, -1, -1):
            self.bits.append((value >> i) & 1)

    def align(self, start=0):
        while (len(self.bits) - start) % 8:
            self.bits.append(0)

    def data(self):
        out = bytearray()
        for i in range(0, len(self.bits), 8):
            chunk = self.bits[i:i + 8] + [0] * (8 - len(self.bits[i:i + 8]))
            out.append(int(''.join(map(str, chunk)), 2))
        return bytes(out)


def f32(x):
    return struct.unpack('<f', struct.pack('<f', x))[0]


def pcm_bits(levels, count):
    return int(math.ceil(f32(count * f32(math.log(levels) / math.log(2.0)))))


def huffman_table(name, text):
    """Reads a refsoft Huffman table {index, length, codeword} by name."""
    body = text[text.index(name + '[] = {'):]
    body = body[:body.index('};')]
    return {int(a): (int(b), int(c)) for a, b, c in re.findall(r'\{\s*(\d+),\s*(\d+),\s*(\d+)\}', body)}


def reshape_codes(text):
    body = text[text.index('huffReshapeNodes'):]
    nodes = [(int(a), int(b)) for a, b in re.findall(r'\{\s*(-?\d+),\s*(-?\d+)\}', body)]
    codes = {}

    def walk(node, path):
        for bit in (0, 1):
            child = nodes[node][bit]
            if child > 0:
                walk(child, path + [bit])
            else:
                codes[-child - 1] = path + [bit]

    walk(0, [])
    return codes


TABLES = open(os.path.join(SRC, 'sac_reshufftables.c'), encoding='latin-1').read()
BOOK11 = huffman_table('s_book11', TABLES)
BOOKSCL = huffman_table('s_bookscl', TABLES)
RESHAPE = reshape_codes(open(os.path.join(SRC, 'sac_nodes_reshape.c'), encoding='latin-1').read())

# Scalefactor band offsets of AAC long and short windows by sampling frequency index (ISO/IEC 14496-3 tables 4.129+).
SFB_LONG = {
    3: [0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 64, 72, 80, 88, 96, 108, 120, 132, 144, 160, 176, 196, 216,
        240, 264, 292, 320, 352, 384, 416, 448, 480, 512, 544, 576, 608, 640, 672, 704, 736, 768, 800, 832, 864, 896,
        928, 1024],
    6: [0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 44, 52, 60, 68, 76, 84, 92, 100, 108, 116, 124, 136, 148, 160, 172,
        188, 204, 220, 240, 260, 284, 308, 336, 364, 396, 432, 468, 508, 552, 600, 652, 704, 768, 832, 896, 960, 1024],
}
SFB_SHORT = {
    3: [0, 4, 8, 12, 16, 20, 28, 36, 44, 56, 68, 80, 96, 112, 128],
    6: [0, 4, 8, 12, 16, 20, 24, 28, 36, 44, 52, 64, 76, 92, 108, 128],
}


def ics(bits, rng, sfi, window, max_long=40, max_short=12, tns=False):
    """Writes an individual_channel_stream of AAC LC with codebook 11 sections."""
    long_offsets = SFB_LONG[sfi]
    short_offsets = SFB_SHORT[sfi]
    gain = rng.randint(90, 120)
    bits.put(gain, 8)
    bits.put(0, 1)
    bits.put(window, 2)
    bits.put(rng.randint(0, 1), 1)
    if window == 2:
        max_sfb = rng.randint(1, min(max_short, len(short_offsets) - 1))
        bits.put(max_sfb, 4)
        grouping = rng.randint(0, 127)
        bits.put(grouping, 7)
        groups = [1]
        for i in range(7):
            if (grouping >> (6 - i)) & 1:
                groups[-1] += 1
            else:
                groups.append(1)
        offsets = short_offsets
        sect_bits, esc = 3, 7
    else:
        max_sfb = rng.randint(1, min(max_long, len(long_offsets) - 1))
        bits.put(max_sfb, 6)
        bits.put(0, 1)
        groups = [1]
        offsets = long_offsets
        sect_bits, esc = 5, 31
    books = []
    for _ in groups:
        k = 0
        group_books = []
        while k < max_sfb:
            length = rng.randint(1, max_sfb - k)
            book = rng.choice([0, 11, 11, 11])
            bits.put(book, 4)
            n = length
            while n >= esc:
                bits.put(esc, sect_bits)
                n -= esc
            bits.put(n, sect_bits)
            group_books += [book] * length
            k += length
        books.append(group_books)
    previous = gain
    for group_books in books:
        for book in group_books:
            if book:
                diff = rng.randint(-6, 6)
                if not 0 <= previous + diff <= 255:
                    diff = 0
                previous += diff
                length, code = BOOKSCL[diff + 60]
                bits.put(code, length)
    bits.put(0, 1)
    if tns and window != 2:
        bits.put(1, 1)
        bits.put(1, 2)
        res = rng.randint(0, 1)
        bits.put(res, 1)
        bits.put(rng.randint(8, 30), 6)
        order = rng.randint(1, 8)
        bits.put(order, 5)
        bits.put(rng.randint(0, 1), 1)
        bits.put(0, 1)
        for _ in range(order):
            bits.put(rng.randint(0, (1 << (res + 3)) - 1), res + 3)
    else:
        bits.put(0, 1)
    bits.put(0, 1)
    for gi, group_books in enumerate(books):
        for band, book in enumerate(group_books):
            if not book:
                continue
            width = offsets[band + 1] - offsets[band]
            for _ in range(groups[gi]):
                for _ in range(0, width, 2):
                    pair = [spectral_value(rng) for _ in range(2)]
                    mags = [min(abs(v), 16) for v in pair]
                    length, code = BOOK11[mags[0] * 17 + mags[1]]
                    bits.put(code, length)
                    for v in pair:
                        if v:
                            bits.put(1 if v < 0 else 0, 1)
                    for v in pair:
                        if abs(v) >= 16:
                            escape(bits, abs(v))


def spectral_value(rng):
    r = rng.random()
    if r < 0.4:
        return 0
    if r < 0.9:
        return rng.randint(-8, 8)
    if r < 0.98:
        return rng.randint(-15, 15)
    return rng.choice([-1, 1]) * rng.randint(16, 300)


def escape(bits, value):
    n = value.bit_length() - 1
    prefix = n - 4
    bits.put((1 << prefix) - 1, prefix)
    bits.put(0, 1)
    bits.put(value - (1 << n), n)


def ssc(cfg):
    b = Bits()
    b.put(RATES.index(cfg['rate']), 4)
    b.put(cfg['slots'] - 1, 7)
    b.put(cfg['freq_res'], 3)
    b.put(cfg['tree'], 4)
    b.put(cfg['quant_mode'], 2)
    b.put(cfg['one_icc'], 1)
    b.put(1 if cfg['arbdmx'] else 0, 1)
    b.put(cfg['gain_sur'], 3)
    b.put(cfg['gain_lfe'], 3)
    b.put(cfg['gain_dmx'], 3)
    b.put(cfg['matrix_mode'], 1)
    b.put(cfg['temp_shape'], 2)
    b.put(cfg['decorr'], 2)
    b.put(0, 1)
    inputs, outputs, otts, ttts, lfe = TREE[cfg['tree']]
    for i in range(otts):
        if lfe[i]:
            b.put(cfg['lfe_bands'], 5)
    if ttts:
        b.put(cfg['ttt_dual'], 1)
        b.put(cfg['ttt_low'], 3)
        if cfg['ttt_dual']:
            b.put(cfg['ttt_high'], 3)
            b.put(cfg['ttt_bands_low'], 5)
    if cfg['temp_shape'] == 2:
        b.put(cfg['env_quant'], 1)
    b.align()
    if cfg.get('residual'):
        ext = Bits()
        ext.put(cfg['residual_sfi'], 4)
        ext.put(cfg['residual_frames'] - 1, 2)
        for i in range(otts + ttts):
            bands = cfg['residual'][i]
            ext.put(1 if bands else 0, 1)
            if bands:
                ext.put(bands, 5)
        ext.align()
        extension(b, 0, ext.data())
    if cfg['arbdmx'] == 2:
        ext = Bits()
        ext.put(cfg['dmx_sfi'], 4)
        ext.put(cfg['dmx_frames'] - 1, 2)
        ext.put(cfg['dmx_bands'], 5)
        ext.align()
        extension(b, 1, ext.data())
    return b.data()


def extension(b, kind, payload):
    b.put(kind, 4)
    n = len(payload)
    if n < 15:
        b.put(n, 4)
    else:
        b.put(15, 4)
        if n - 15 < 255:
            b.put(n - 15, 8)
        else:
            b.put(255, 8)
            b.put(n - 15 - 255, 16)
    for byte in payload:
        b.put(byte, 8)


class Stream:
    def __init__(self, cfg, rng):
        self.cfg = cfg
        self.rng = rng
        self.bands = FREQ_RES[cfg['freq_res']]
        self.first = True

    def ec_data(self, b, kind, start, stop, sets, independent, max_value=None, span=None, fine=False):
        rng = self.rng
        modes = []
        for i in range(sets):
            choices = [0, 1, 2, 3, 3, 3]
            if i == sets - 1 or self.cfg.get('residual'):
                choices = [0, 1, 3, 3, 3]
            if i == 0 and independent:
                choices = [0, 3, 3]
            modes.append(rng.choice(choices))
        for m in modes:
            b.put(m, 2)
        coded = [i for i, m in enumerate(modes) if m == 3]
        k = 0
        while k < len(coded):
            pair = 1 if k + 1 < len(coded) and rng.random() < 0.4 else 0
            coarse = 0 if fine else rng.randint(0, 1)
            stride = rng.choice([0, 0, 1, 2, 3])
            b.put(pair, 1)
            b.put(coarse, 1)
            b.put(stride, 2)
            data_bands = (stop - start - 1) // STRIDES[stride] + 1
            levels, offset = LEVELS[(kind, coarse)]
            b.put(1, 1)
            count = data_bands * (pair + 1)
            values = []
            for _ in range(count):
                lo, hi = -offset, levels - 1 - offset
                if max_value is not None:
                    hi = min(hi, max_value if not coarse else max_value // 2)
                if span is not None:
                    lo, hi = max(lo, -span), min(hi, span)
                values.append(rng.randint(lo, hi) + offset)
            group = GROUP.get(levels, 1)
            for i in range(0, count, group):
                g = values[i:i + group]
                v = 0
                for x in g:
                    v = v * levels + x
                b.put(v, pcm_bits(levels, len(g)))
            k += pair + 1

    def frame(self):
        cfg, rng = self.cfg, self.rng
        b = Bits()
        slots = cfg['slots']
        sets = rng.randint(1, cfg.get('max_sets', 4))
        framing = rng.randint(0, 1)
        b.put(framing, 1)
        b.put(sets - 1, 3)
        if framing:
            previous = -1
            for i in range(sets):
                limit = slots - sets + i - previous
                bits = 0
                while (1 << bits) < limit:
                    bits += 1
                value = rng.randint(0, limit - 1) if bits else 0
                if bits:
                    b.put(value, bits)
                previous = previous + 1 + value
        independent = 1 if self.first else rng.randint(0, 1)
        self.first = False
        b.put(independent, 1)
        inputs, outputs, otts, ttts, lfe = TREE[cfg['tree']]
        residual = cfg.get('residual') or [0] * 6
        bands = self.bands
        for i in range(otts):
            self.ec_data(b, 'cld', 0, cfg['lfe_bands'] if lfe[i] else bands, sets, independent)
        icc_limit = 3 if any(residual[:otts]) else None
        if cfg['one_icc']:
            self.ec_data(b, 'icc', 0, bands, sets, independent, icc_limit, fine=True)
        else:
            for i in range(otts):
                if not lfe[i]:
                    self.ec_data(b, 'icc', 0, cfg['lfe_bands'] if lfe[i] else bands, sets, independent, icc_limit,
                                 fine=icc_limit is not None)
        if ttts:
            ranges = [(0, cfg['ttt_bands_low'] if cfg['ttt_dual'] else bands)]
            if cfg['ttt_dual'] and cfg['ttt_bands_low'] < bands:
                ranges.append((cfg['ttt_bands_low'], bands))
            modes = [cfg['ttt_low'], cfg['ttt_high']]
            for j, (start, stop) in enumerate(ranges):
                if modes[j] < 2:
                    self.ec_data(b, 'cpc', start, stop, sets, independent)
                    self.ec_data(b, 'cpc', start, stop, sets, independent)
                    self.ec_data(b, 'icc', start, stop, sets, independent, 4)
                else:
                    self.ec_data(b, 'cld', start, stop, sets, independent)
                    self.ec_data(b, 'cld', start, stop, sets, independent)
        for _ in range(sets):
            mode = rng.choice([0, 1, 2, 3])
            b.put(mode, 2)
            if mode >= 2:
                b.put(rng.randint(0, 3), 2)
            if mode == 3:
                stride = rng.randint(0, 3)
                b.put(stride, 2)
                for _ in range((bands - 1) // STRIDES[stride] + 1):
                    b.put(rng.randint(0, 1), 1)
        if cfg['temp_shape']:
            enable = rng.randint(0, 1)
            b.put(enable, 1)
            if enable:
                channels = TEMP_SHAPE_CHANNELS[cfg['temp_shape'] - 1][cfg['tree']]
                flags = [rng.randint(0, 1) for _ in range(channels)]
                for f in flags:
                    b.put(f, 1)
                if cfg['temp_shape'] == 2:
                    for f in flags:
                        if f:
                            self.envelope(b, slots)
        if cfg['arbdmx']:
            for _ in range(inputs):
                self.ec_data(b, 'cld', 0, bands, sets, independent, span=5)
        b.align()
        if cfg.get('residual'):
            payload = Bits()
            for box in range(otts + ttts):
                if not residual[box]:
                    continue
                if box < otts:
                    for _ in range(sets):
                        present = rng.randint(0, 1)
                        payload.put(present, 1)
                        if present:
                            for _ in range(residual[box]):
                                v = rng.randint(0, 3)
                                payload.put(1, v + 1)
                for _ in range(cfg['residual_frames']):
                    self.residual_frames(payload, cfg['residual_sfi'], slots // cfg['residual_frames'], False)
            payload.align()
            self.extension_frame(b, payload.data())
        if cfg['arbdmx'] == 2:
            payload = Bits()
            ch = 0
            while ch < inputs:
                pair = inputs > 1
                payload.put(rng.randint(0, 1), 1)
                payload.put(rng.randint(0, 1), 1)
                for _ in range(cfg['dmx_frames']):
                    self.residual_frames(payload, cfg['dmx_sfi'], slots // cfg['dmx_frames'], pair)
                ch += 2 if pair else 1
            payload.align()
            self.extension_frame(b, payload.data())
        return b.data()

    def residual_frames(self, b, sfi, update, pair):
        rng = self.rng
        window = rng.choice(self.cfg.get('windows', [0, 0, 1, 2, 3]))
        count = 2 if window == 2 and update in (18, 24, 30) else 1
        for _ in range(count):
            if pair:
                b.put(rng.randint(0, 15), 4)
                b.put(0, 1)
                ics(b, rng, sfi, window, tns=rng.random() < 0.3)
                ics(b, rng, sfi, window, tns=rng.random() < 0.3)
            else:
                ics(b, rng, sfi, window, tns=rng.random() < 0.3)

    def extension_frame(self, b, payload):
        n = len(payload)
        if n < 255:
            b.put(n, 8)
        else:
            b.put(255, 8)
            b.put(n - 255, 16)
        for byte in payload:
            b.put(byte, 8)

    def envelope(self, b, slots):
        rng = self.rng
        done = 0
        while done < slots:
            options = [s for s in RESHAPE if done + (s & 15) + 1 <= slots]
            symbol = rng.choice(options)
            for bit in RESHAPE[symbol]:
                b.put(bit, 1)
            done += (symbol & 15) + 1


def wave(path, data, rate):
    channels = len(data)
    frames = len(data[0])
    raw = bytearray()
    for i in range(frames):
        for c in range(channels):
            raw += struct.pack('<h', data[c][i])
    with open(path, 'wb') as f:
        f.write(b'RIFF' + struct.pack('<I', 36 + len(raw)) + b'WAVEfmt ' +
                struct.pack('<IHHIIHH', 16, 1, channels, rate, rate * channels * 2, channels * 2, 16))
        f.write(b'data' + struct.pack('<I', len(raw)) + raw)


def downmix(channels, frames, rate, seed):
    state = seed * 2654435761 % (1 << 32) or 1
    out = []
    for c in range(channels):
        values = []
        f1 = 180.0 + 97.0 * c
        f2 = 1300.0 + 421.0 * c
        for n in range(frames):
            state = (state * 1664525 + 1013904223) % (1 << 32)
            noise = (state / (1 << 32)) - 0.5
            t = n / rate
            env = 0.6 + 0.4 * math.sin(2 * math.pi * (0.7 + 0.3 * c) * t)
            burst = 1.0 if (n // (rate // 9 + 37 * c)) % 5 == 0 else 0.15
            v = (0.35 * math.sin(2 * math.pi * f1 * t) + 0.2 * math.sin(2 * math.pi * f2 * t + c)) * env
            v += 0.5 * noise * burst
            values.append(max(-32768, min(32767, int(round(v * 9000)))))
        out.append(values)
    return out


def write_case(name, cfg, frames=24, seed=1):
    rng = random.Random(seed)
    header = Bits()
    config = ssc(cfg)
    header.put(0, 1)
    header.put(len(config), 7)
    for byte in config:
        header.put(byte, 8)
    stream = Stream(cfg, rng)
    qmf = 32 if cfg['rate'] < 27713 else 128 if cfg['rate'] >= 55426 else 64
    blocks = [header.data()] + [stream.frame() for _ in range(frames)]
    with open(os.path.join(OUT, name + '.bs'), 'wb') as f:
        for block in blocks:
            f.write(struct.pack('<I', len(block)) + block)
    inputs = TREE[cfg['tree']][0]
    wave(os.path.join(OUT, name + '_dmx.wav'), downmix(inputs, frames * cfg['slots'] * qmf, cfg['rate'], seed + 7),
         cfg['rate'])


def base(tree, **extra):
    cfg = dict(tree=tree, rate=48000, slots=32, freq_res=1, quant_mode=0, one_icc=0, arbdmx=0, gain_sur=2,
               gain_lfe=1, gain_dmx=0, matrix_mode=0, temp_shape=0, decorr=0, lfe_bands=4, ttt_dual=0, ttt_low=1,
               ttt_high=0, ttt_bands_low=0, env_quant=0)
    cfg.update(extra)
    return cfg


CASES = {
    't5151': base(0),
    't5152': base(1, freq_res=2, quant_mode=1, gain_sur=4, gain_lfe=3),
    't5151q2': base(0, quant_mode=2, one_icc=1, freq_res=3),
    't525cpc': base(2, ttt_low=0, decorr=1),
    't525cld': base(2, ttt_low=2, matrix_mode=1),
    't525dual': base(2, ttt_dual=1, ttt_low=0, ttt_high=3, ttt_bands_low=9, freq_res=2),
    't525inv': base(2, ttt_low=1, matrix_mode=1, freq_res=4),
    't525mode4': base(2, ttt_low=4, matrix_mode=1, decorr=2),
    't7271': base(3, ttt_low=0),
    't7272': base(4, ttt_low=1, matrix_mode=1),
    't7571': base(5, freq_res=2),
    't7572': base(6, freq_res=5),
    'stp5151': base(0, temp_shape=1),
    'stp525': base(2, temp_shape=1, ttt_low=1),
    'stp7271': base(3, temp_shape=1, ttt_low=2),
    'ges5152': base(1, temp_shape=2, env_quant=1),
    'ges525': base(2, temp_shape=2, ttt_low=1),
    'ges7572': base(6, temp_shape=2, env_quant=1),
    'rate24': base(0, rate=24000, slots=32, freq_res=2),
    'rate96': base(2, rate=96000, slots=16, ttt_low=1),
    'slots16': base(1, slots=16, max_sets=8),
    'arbdmx5151': base(0, arbdmx=1),
    'arbdmx525': base(2, arbdmx=1, ttt_low=1),
    'res5151': base(0, residual=[4, 8, 3, 6, 0], residual_sfi=6, residual_frames=1),
    'res525': base(2, residual=[0, 5, 5, 10], residual_sfi=6, residual_frames=1, ttt_low=1),
    'res525cpc0': base(2, residual=[2, 6, 6, 12], residual_sfi=6, residual_frames=2, ttt_low=0),
    'res7272': base(4, residual=[0, 4, 4, 3, 3, 8], residual_sfi=6, residual_frames=1, ttt_low=1),
    'res7571': base(5, residual=[6, 6], residual_sfi=6, residual_frames=1),
    'res5152x2': base(1, residual=[3, 3, 0, 5, 5], residual_sfi=3, residual_frames=2),
    'arbres5151': base(0, arbdmx=2, dmx_sfi=6, dmx_frames=1, dmx_bands=7),
    'arbres525': base(2, arbdmx=2, dmx_sfi=6, dmx_frames=2, dmx_bands=5, ttt_low=1),
    'slots18res': base(0, slots=36, residual=[3, 3, 3, 3, 0], residual_sfi=6, residual_frames=2),
    'v_q1': base(0, quant_mode=1),
    'v_q2': base(0, quant_mode=2),
    'v_oneicc': base(0, one_icc=1),
    'v_fr3': base(0, freq_res=3),
    'v_fr2': base(0, freq_res=2),
    'v_gains': base(0, gain_sur=4, gain_lfe=3, gain_dmx=5),
    'v_q1b': base(1, quant_mode=1),
    'v_5152': base(1),
}

if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    names = sys.argv[3:] or list(CASES)
    for name in names:
        write_case(name, CASES[name], seed=sum(map(ord, name)))
        print(name)
