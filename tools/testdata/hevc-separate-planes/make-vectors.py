#!/usr/bin/env python3
"""Writes the HEVC separate color plane vectors of smalt-mpeg's HevcSeparatePlanesTest.

No encoder codes separate_colour_plane_flag, so each vector is assembled from three monochrome (4:0:0) streams that the
HM reference encoder codes with one configuration, one for each plane of a 4:4:4 source made by ffmpeg (testsrc2): the
three streams share their parameter sets and their slice, picture and reference structure. The assembled stream keeps
the parameter sets of the first, rewritten to chroma_format_idc 3 with separate_colour_plane_flag 1, and the slice
segments of all three, each independent slice segment header rewritten to carry its colour_plane_id; the slice
segment data is copied unchanged. Each access unit ends with a decoded picture hash SEI message holding the MD5 of the
three planes of the picture, as HM's decoder reconstructs the monochrome streams. The HM decoder checks the MD5 SEI
messages of every monochrome stream while it decodes it, and the script fails unless it reports every picture as
matching. Its outputs are the expected planes of the assembled stream.

    planes-ld-8     HM low delay B, 8 bits, wavefronts, two slices per plane, the planes one after the other; the
                    first slice segment of the Cb and Cr planes is flagged as a continuation (first_slice_segment_in_pic_flag
                    0, slice_segment_address 0)
    planes-ra-10    HM random access, 10 bits, temporal motion vector prediction, two slices per plane, the slices of the
                    planes interleaved; the first slice segment of every plane carries first_slice_segment_in_pic_flag 1
    planes-intra-8  one intra picture, 8 bits, for HEIC

For each vector, vectors.tsv next to the streams holds:

    name    width    height    frames    bit depth    stream sha256    decoded sha256    frame MD5s

where the decoded SHA-256 and the comma-separated frame MD5s hash the expected output in output order (planar Y, Cb,
Cr; 8-bit samples, or 16-bit little-endian ones above 8 bits). The three monochrome streams of each vector are written
too (<name>-<plane>.bit), so that a test can decode each plane alone.

Made with HM-16.21+SCM-8.8 (https://vcgit.hhi.fraunhofer.de/jvet/HM, branch HM-SCC-extensions, BSD-3-Clause, used as a
black box) built with gcc 15 (cmake -DCMAKE_BUILD_TYPE=Release, targets TAppEncoder and TAppDecoder) and ffmpeg 7.1.
Pass the directory holding TAppEncoder and TAppDecoder, the HM cfg directory and the output directory:

    python -I tools/testdata/hevc-separate-planes/make-vectors.py <HM bin> <HM cfg> \\
        <mpeg/src/test/resources/com/github/auties00/smalt/video/hevc/planes>
"""
import hashlib
import os
import re
import subprocess
import sys
import tempfile

WIDTH = 128
HEIGHT = 96

# name: (configuration, frames, bit depth, interleave slices, flag the first slice of every plane, options)
VECTORS = {
    'planes-ld-8': ('encoder_lowdelay_main_rext.cfg', 6, 8, False, False,
                    ['--WaveFrontSynchro=1', '--SliceMode=1', '--SliceArgument=2']),
    'planes-ra-10': ('misc/encoder_randomaccess_main_rext_GOP8.cfg', 10, 10, True, True,
                     ['--SliceMode=1', '--SliceArgument=2', '--IntraPeriod=8']),
    'planes-intra-8': ('encoder_intra_main_rext.cfg', 1, 8, False, False, []),
}


class Reader:
    def __init__(self, data):
        self.data = data
        self.pos = 0

    def u(self, n):
        value = 0
        for _ in range(n):
            value = (value << 1) | ((self.data[self.pos >> 3] >> (7 - (self.pos & 7))) & 1)
            self.pos += 1
        return value

    def ue(self):
        zeros = 0
        while self.u(1) == 0:
            zeros += 1
        return (1 << zeros) - 1 + self.u(zeros)

    def se(self):
        value = self.ue()
        return (value + 1) // 2 if value & 1 else -(value // 2)


class Writer:
    def __init__(self):
        self.bits = []

    def u(self, n, value):
        for k in range(n - 1, -1, -1):
            self.bits.append((value >> k) & 1)

    def ue(self, value):
        value += 1
        n = value.bit_length()
        self.u(n - 1, 0)
        self.u(n, value)

    def copy(self, data, start, end):
        for pos in range(start, end):
            self.bits.append((data[pos >> 3] >> (7 - (pos & 7))) & 1)

    def align(self):
        self.bits.append(1)
        while len(self.bits) % 8:
            self.bits.append(0)

    def bytes(self):
        assert len(self.bits) % 8 == 0
        out = bytearray()
        for k in range(0, len(self.bits), 8):
            value = 0
            for b in self.bits[k:k + 8]:
                value = (value << 1) | b
            out.append(value)
        return bytes(out)


def unescape(payload):
    out = bytearray()
    zeros = 0
    for b in payload:
        if zeros >= 2 and b == 3:
            zeros = 0
            continue
        out.append(b)
        zeros = zeros + 1 if b == 0 else 0
    return bytes(out)


def escape(rbsp):
    out = bytearray()
    zeros = 0
    for b in rbsp:
        if zeros >= 2 and b <= 3:
            out.append(3)
            zeros = 0
        out.append(b)
        zeros = zeros + 1 if b == 0 else 0
    return bytes(out)


def nal_units(stream):
    starts = [m.start() for m in re.finditer(b'\x00\x00\x01', stream)]
    units = []
    for k, start in enumerate(starts):
        end = starts[k + 1] if k + 1 < len(starts) else len(stream)
        nal = stream[start + 3:end]
        while nal and nal[-1] == 0:
            nal = nal[:-1]
        units.append(nal)
    return units


def nal_type(nal):
    return (nal[0] >> 1) & 0x3F


def ceil_log2(value):
    return (value - 1).bit_length() if value > 1 else 0


def skip_profile_tier_level(r, max_sub_layers_minus1):
    r.u(8)
    r.u(32)
    r.u(48)
    r.u(8)
    present = [(r.u(1), r.u(1)) for _ in range(max_sub_layers_minus1)]
    if max_sub_layers_minus1 > 0:
        for _ in range(max_sub_layers_minus1, 8):
            r.u(2)
    for profile, level in present:
        if profile:
            r.u(88)
        if level:
            r.u(8)


def parse_rps(r, idx, sets, num_sets):
    inter = r.u(1) if idx != 0 else 0
    if inter:
        delta_idx = r.ue() + 1 if idx == num_sets else 1
        sign = r.u(1)
        abs_delta = r.ue() + 1
        delta_rps = (1 - 2 * sign) * abs_delta
        ref = sets[idx - delta_idx]
        used = []
        use_delta = []
        for _ in range(len(ref['s0']) + len(ref['s1']) + 1):
            u = r.u(1)
            used.append(u)
            use_delta.append(1 if u else r.u(1))
        ref_deltas = ref['s0'] + ref['s1']
        n0 = len(ref['s0'])
        s0 = []
        s1 = []
        # equations 7-61 and 7-62
        for j in range(len(ref['s1']) - 1, -1, -1):
            d = ref['s1'][j] + delta_rps
            if d < 0 and use_delta[n0 + j]:
                s0.append((d, used[n0 + j]))
        if delta_rps < 0 and use_delta[len(ref_deltas)]:
            s0.append((delta_rps, used[len(ref_deltas)]))
        for j in range(n0):
            d = ref['s0'][j] + delta_rps
            if d < 0 and use_delta[j]:
                s0.append((d, used[j]))
        for j in range(n0 - 1, -1, -1):
            d = ref['s0'][j] + delta_rps
            if d > 0 and use_delta[j]:
                s1.append((d, used[j]))
        if delta_rps > 0 and use_delta[len(ref_deltas)]:
            s1.append((delta_rps, used[len(ref_deltas)]))
        for j in range(len(ref['s1'])):
            d = ref['s1'][j] + delta_rps
            if d > 0 and use_delta[n0 + j]:
                s1.append((d, used[n0 + j]))
        return {'s0': [d for d, _ in s0], 'u0': [u for _, u in s0], 's1': [d for d, _ in s1],
                'u1': [u for _, u in s1]}
    neg = r.ue()
    pos = r.ue()
    s0, u0, s1, u1 = [], [], [], []
    poc = 0
    for _ in range(neg):
        poc -= r.ue() + 1
        s0.append(poc)
        u0.append(r.u(1))
    poc = 0
    for _ in range(pos):
        poc += r.ue() + 1
        s1.append(poc)
        u1.append(r.u(1))
    return {'s0': s0, 'u0': u0, 's1': s1, 'u1': u1}


def parse_sps(rbsp):
    r = Reader(rbsp)
    r.u(4)
    max_sub_layers_minus1 = r.u(3)
    r.u(1)
    skip_profile_tier_level(r, max_sub_layers_minus1)
    r.ue()
    chroma_start = r.pos
    assert r.ue() == 0, 'not a monochrome sequence'
    chroma_end = r.pos
    width = r.ue()
    height = r.ue()
    if r.u(1):
        r.ue(), r.ue(), r.ue(), r.ue()
    r.ue(), r.ue()
    log2_max_poc_lsb = r.ue() + 4
    ordering = r.u(1)
    for _ in range(0 if ordering else max_sub_layers_minus1, max_sub_layers_minus1 + 1):
        r.ue(), r.ue(), r.ue()
    log2_min_cb = r.ue() + 3
    log2_ctb = log2_min_cb + r.ue()
    r.ue(), r.ue(), r.ue(), r.ue()
    assert r.u(1) == 0, 'scaling lists'
    r.u(1)
    sao = r.u(1)
    assert r.u(1) == 0, 'pcm'
    sets = []
    num_sets = r.ue()
    for idx in range(num_sets):
        sets.append(parse_rps(r, idx, sets, num_sets))
    assert r.u(1) == 0, 'long-term references'
    tmvp = r.u(1)
    ctbs = ((width + (1 << log2_ctb) - 1) >> log2_ctb) * ((height + (1 << log2_ctb) - 1) >> log2_ctb)
    return {'chroma': (chroma_start, chroma_end), 'log2_max_poc_lsb': log2_max_poc_lsb, 'sets': sets, 'sao': sao,
            'tmvp': tmvp, 'ctbs': ctbs}


def parse_pps(rbsp):
    r = Reader(rbsp)
    pps = {'id': r.ue()}
    r.ue()
    pps['dependent'] = r.u(1)
    pps['output_flag'] = r.u(1)
    pps['extra_bits'] = r.u(3)
    r.u(1)
    pps['cabac_init'] = r.u(1)
    r.ue(), r.ue()
    r.se()
    r.u(1)
    transform_skip = r.u(1)
    if r.u(1):
        r.ue()
    r.se(), r.se()
    pps['chroma_offsets'] = r.u(1)
    pps['weighted'] = r.u(1) | r.u(1)
    r.u(1)
    tiles = r.u(1)
    sync = r.u(1)
    assert not tiles, 'tiles'
    pps['entry_points'] = tiles or sync
    pps['lf_across_slices'] = r.u(1)
    pps['deblocking_override'] = 0
    pps['deblocking_disabled'] = 0
    if r.u(1):
        pps['deblocking_override'] = r.u(1)
        pps['deblocking_disabled'] = r.u(1)
        if not pps['deblocking_disabled']:
            r.se(), r.se()
    assert r.u(1) == 0, 'PPS scaling lists'
    pps['lists_modification'] = r.u(1)
    r.ue()
    pps['header_extension'] = r.u(1)
    if r.u(1):
        rext = r.u(1)
        r.u(7)
        if rext:
            if transform_skip:
                r.ue()
            r.u(1)
            assert r.u(1) == 0, 'chroma QP offset lists'
    assert not pps['weighted'], 'weighted prediction'
    return pps


def slice_header_end(rbsp, sps, pps, nal):
    """Returns the bit positions of the slice segment header: after pic_output_flag (where colour_plane_id goes),
    of the start of slice_segment_address or of the rest of the header when the slice is first, and the end of the
    header before byte_alignment()."""
    r = Reader(rbsp)
    first = r.u(1)
    if 16 <= nal <= 23:
        r.u(1)
    r.ue()
    after_pps_id = r.pos
    dependent = 0
    if not first:
        if pps['dependent']:
            dependent = r.u(1)
        r.u(ceil_log2(sps['ctbs']))
    after_address = r.pos
    plane_at = None
    if not dependent:
        r.u(pps['extra_bits'])
        slice_type = r.ue()
        if pps['output_flag']:
            r.u(1)
        plane_at = r.pos
        rps = None
        lt_used = 0
        tmvp = 0
        if nal not in (19, 20):
            r.u(sps['log2_max_poc_lsb'])
            if not r.u(1):
                rps = parse_rps(r, len(sps['sets']), sps['sets'], len(sps['sets']))
            else:
                rps = sps['sets'][r.u(ceil_log2(len(sps['sets'])))]
            if sps['tmvp']:
                tmvp = r.u(1)
        sao_luma = 0
        if sps['sao']:
            sao_luma = r.u(1)
        if slice_type != 2:
            counts = [0, 0]
            if r.u(1):
                counts[0] = r.ue() + 1
                if slice_type == 0:
                    counts[1] = r.ue() + 1
            else:
                counts = None
            total = (sum(rps['u0']) + sum(rps['u1']) + lt_used) if rps else 0
            if pps['lists_modification'] and total > 1:
                raise AssertionError('list modification')
            if slice_type == 0:
                r.u(1)
            if pps['cabac_init']:
                r.u(1)
            if tmvp:
                from_l0 = r.u(1) if slice_type == 0 else 1
                if counts is None:
                    counts = [DEFAULTS['l0'], DEFAULTS['l1']]
                if (from_l0 and counts[0] > 1) or (not from_l0 and counts[1] > 1):
                    r.ue()
            r.ue()
        r.se()
        if pps['chroma_offsets']:
            r.se(), r.se()
        disabled = pps['deblocking_disabled']
        if pps['deblocking_override'] and r.u(1):
            disabled = r.u(1)
            if not disabled:
                r.se(), r.se()
        if pps['lf_across_slices'] and (sao_luma or not disabled):
            r.u(1)
    if pps['entry_points']:
        count = r.ue()
        if count:
            length = r.ue() + 1
            r.u(length * count)
    if pps['header_extension']:
        r.u(8 * r.ue())
    end = r.pos
    assert r.u(1) == 1, 'byte_alignment'
    while r.pos % 8:
        assert r.u(1) == 0
    return first, dependent, after_pps_id, after_address, plane_at, end, r.pos // 8


DEFAULTS = {}


def pps_defaults(rbsp):
    r = Reader(rbsp)
    r.ue(), r.ue(), r.u(1), r.u(1), r.u(3), r.u(1), r.u(1)
    DEFAULTS['l0'] = r.ue() + 1
    DEFAULTS['l1'] = r.ue() + 1


def rewrite_sps(nal, sps):
    rbsp = unescape(nal[2:])
    start, end = sps['chroma']
    stop = max(i for i in range(len(rbsp) * 8) if (rbsp[i >> 3] >> (7 - (i & 7))) & 1)
    w = Writer()
    w.copy(rbsp, 0, start)
    w.ue(3)
    w.u(1, 1)
    w.copy(rbsp, end, stop)
    w.align()
    return nal[:2] + escape(w.bytes())


def rewrite_slice(nal, sps, pps, plane, flag_first):
    rbsp = unescape(nal[2:])
    first, dependent, after_pps_id, after_address, plane_at, end, data = slice_header_end(rbsp, sps, pps,
                                                                                         nal_type(nal))
    w = Writer()
    continuation = first and plane > 0 and not flag_first
    if continuation:
        w.u(1, 0)
        w.copy(rbsp, 1, after_pps_id)
        if pps['dependent']:
            w.u(1, 0)
        w.u(ceil_log2(sps['ctbs']), 0)
    else:
        w.copy(rbsp, 0, after_address)
    if dependent:
        w.copy(rbsp, after_address, end)
    else:
        w.copy(rbsp, after_address, plane_at)
        w.u(2, plane)
        w.copy(rbsp, plane_at, end)
    w.align()
    # the last header byte holds the alignment bit, so the data escapes alike after either header and the entry
    # point offsets, which count escaped data bytes, still hold
    return nal[:2] + escape(w.bytes() + rbsp[data:])


def hash_sei(md5s):
    payload = bytes([0]) + b''.join(md5s)
    body = bytes([132, len(payload)]) + payload + b'\x80'
    return bytes([0x50, 0x01]) + escape(body)


def tool(directory, name):
    path = os.path.abspath(os.path.join(directory, name))
    return path + '.exe' if os.path.exists(path + '.exe') else path


def run(args, **kwargs):
    result = subprocess.run(args, capture_output=True, text=True, **kwargs)
    if result.returncode != 0:
        raise SystemExit('%s failed:\n%s%s' % (args[0], result.stdout, result.stderr))
    return result.stdout


def make(name, hm_bin, cfg, out_dir, work):
    config, frames, depth, interleave, flag_first, options = VECTORS[name]
    sample = 2 if depth > 8 else 1
    plane_size = WIDTH * HEIGHT * sample
    src = os.path.join(work, name + '-444.yuv')
    pix = 'yuv444p' if depth == 8 else 'yuv444p10le'
    run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i',
         'testsrc2=size=%dx%d:rate=25' % (WIDTH, HEIGHT), '-frames:v', str(frames), '-pix_fmt', pix, src])
    data = open(src, 'rb').read()
    streams = []
    decoded = []
    for k in range(3):
        mono = os.path.join(work, '%s-%d.yuv' % (name, k))
        with open(mono, 'wb') as f:
            for i in range(frames):
                f.write(data[(i * 3 + k) * plane_size:(i * 3 + k + 1) * plane_size])
        bit = os.path.join(out_dir, '%s-%d.bit' % (name, k))
        run([tool(hm_bin, 'TAppEncoder'), '-c', os.path.join(cfg, config), '-i', mono, '-b', bit,
             '-o', os.devnull, '-wdt', str(WIDTH), '-hgt', str(HEIGHT), '-fr', '25', '-f', str(frames),
             '--InputChromaFormat=400', '--ChromaFormatIDC=400', '--InputBitDepth=%d' % depth,
             '--InternalBitDepth=%d' % depth, '--OutputBitDepth=%d' % depth, '--SEIDecodedPictureHash=1',
             '--Profile=main-RExt', '--ConformanceWindowMode=0', '--QP=30'] + options)
        rec = os.path.join(work, '%s-%d-rec.yuv' % (name, k))
        log = run([tool(hm_bin, 'TAppDecoder'), '-b', bit, '-o', rec, '-d', str(depth)])
        if log.count('(OK)') != frames or 'ERROR' in log:
            raise SystemExit('HM did not verify every picture hash of %s plane %d:\n%s' % (name, k, log))
        streams.append(nal_units(open(bit, 'rb').read()))
        decoded.append(open(rec, 'rb').read())
    for k in range(3):
        assert len(decoded[k]) == frames * plane_size, 'HM wrote %d bytes' % len(decoded[k])
    # the three streams must share their parameter sets and the type and order of their NAL units
    params = [nal for nal in streams[0] if nal_type(nal) in (32, 33, 34)]
    for k in (1, 2):
        assert [nal for nal in streams[k] if nal_type(nal) in (32, 33, 34)] == params, 'parameter sets differ'
        assert [nal_type(n) for n in streams[k] if nal_type(n) < 32] == [nal_type(n) for n in streams[0]
                                                                          if nal_type(n) < 32], 'structure differs'
    sps_nal = next(n for n in params if nal_type(n) == 33)
    pps_nal = next(n for n in params if nal_type(n) == 34)
    sps = parse_sps(unescape(sps_nal[2:]))
    pps = parse_pps(unescape(pps_nal[2:]))
    pps_defaults(unescape(pps_nal[2:]))
    # split each stream into access units: parameter sets and prefix SEI, then the slices
    units = [[] for _ in range(3)]
    for k in range(3):
        current = None
        for nal in streams[k]:
            t = nal_type(nal)
            if t < 32:
                if (nal[2] & 0x80) and (current is None or current['slices']):
                    current = {'prefix': [], 'slices': []}
                    units[k].append(current)
                current['slices'].append(nal)
            elif t in (32, 33, 34, 35, 39):
                if current is None or current['slices']:
                    current = {'prefix': [], 'slices': []}
                    units[k].append(current)
                current['prefix'].append(nal)
    assert len(units[0]) == len(units[1]) == len(units[2]), 'access unit counts differ'
    # HM's output order is POC order; decoding order picture n maps to output index by the POC in the slice header
    out = bytearray()
    for unit_index in range(len(units[0])):
        for nal in units[0][unit_index]['prefix']:
            t = nal_type(nal)
            if t == 33:
                out += b'\x00\x00\x00\x01' + rewrite_sps(nal, sps)
            elif t in (32, 34):
                out += b'\x00\x00\x00\x01' + nal
        slices = [[rewrite_slice(nal, sps, pps, k, flag_first) for nal in units[k][unit_index]['slices']]
                  for k in range(3)]
        if interleave:
            order = []
            for i in range(max(len(s) for s in slices)):
                for k in range(3):
                    if i < len(slices[k]):
                        order.append(slices[k][i])
        else:
            order = slices[0] + slices[1] + slices[2]
        for nal in order:
            out += b'\x00\x00\x00\x01' + nal
        poc = unit_poc(units[0][unit_index]['slices'][0], sps, pps)
        out += b'\x00\x00\x00\x01' + hash_sei([hashlib.md5(planes(decoded, k, poc_index(name, poc), plane_size))
                                               .digest() for k in range(3)])
    stream = bytes(out)
    open(os.path.join(out_dir, name + '.bit'), 'wb').write(stream)
    expected = bytearray()
    md5s = []
    for i in range(frames):
        frame = b''.join(decoded[k][i * plane_size:(i + 1) * plane_size] for k in range(3))
        expected += frame
        md5s.append(hashlib.md5(frame).hexdigest())
    return '\t'.join([name, str(WIDTH), str(HEIGHT), str(frames), str(depth), hashlib.sha256(stream).hexdigest(),
                      hashlib.sha256(expected).hexdigest(), ','.join(md5s)])


POC_BASE = {}


def unit_poc(nal, sps, pps):
    rbsp = unescape(nal[2:])
    r = Reader(rbsp)
    r.u(1)
    t = nal_type(nal)
    if 16 <= t <= 23:
        r.u(1)
    r.ue()
    r.u(pps['extra_bits'])
    r.ue()
    if pps['output_flag']:
        r.u(1)
    if t in (19, 20):
        return 0
    return r.u(sps['log2_max_poc_lsb'])


def poc_index(name, poc):
    # every vector is a single coded video sequence starting at POC 0 whose POCs fit the LSBs: the output index is the POC
    return poc


def planes(decoded, k, index, plane_size):
    return decoded[k][index * plane_size:(index + 1) * plane_size]


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    hm_bin, cfg, out_dir = sys.argv[1:]
    os.makedirs(out_dir, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory() as work:
        for name in VECTORS:
            rows.append(make(name, hm_bin, cfg, out_dir, work))
    with open(os.path.join(out_dir, 'vectors.tsv'), 'w', newline='\n') as f:
        f.write('# name\twidth\theight\tframes\tbit depth\tstream sha256\tdecoded sha256\tframe MD5s\n')
        for row in rows:
            f.write(row + '\n')


if __name__ == '__main__':
    main()
