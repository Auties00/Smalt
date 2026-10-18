"""Generates the sub-pixel motion compensation kernels of VectorVp9Dsp (see gen_vp9_dsp.py).

Each kernel filters a block 16 samples wide per 256-bit vector (or 8 or 4 samples in a 128-bit
vector) in one direction, two rows per iteration. Plain kernels read and write 8-bit samples; the
first pass of a two-dimensional filter writes its 8-bit results as 16-bit values to the scratch
array ("ToShorts"), and the second pass reads them from there ("FromShorts"), which spares the
conversions between the passes.
"""

import textwrap

VO = 'VectorOperators.'


def tap_load(arr, addr, wide, shorts):
    if shorts:
        if wide:
            return f'SHORT256.cast(BYTE256.cast(ByteVector.fromArray(B256, {arr}, {addr})).reinterpretShape(S256, 0))'
        return f'SHORT128.cast(BYTE128.cast(ByteVector.fromArray(B128, {arr}, {addr})).reinterpretShape(S128, 0))'
    if wide:
        return (f'SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, {arr}, {addr}))'
                f'.convertShape({VO}ZERO_EXTEND_B2S, S256, 0))')
    return (f'SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, {arr}, {addr}))'
            f'.convertShape({VO}ZERO_EXTEND_B2S, S128, 0))')


def tap_sum(arr, addrs, wide, shorts, prefix):
    bias = 'S256_BIAS' if wide else 'S128_BIAS'
    lines = []
    for t, addr in enumerate(addrs):
        lines.append(f'var {prefix}{t} = {tap_load(arr, addr, wide, shorts)}.lanewise({VO}MUL, k{t});')
    p = prefix
    lines.append(f'var {p}Sum = {p}0.lanewise({VO}ADD, {p}1).lanewise({VO}ADD, {p}2.lanewise({VO}ADD, {p}3))'
                 f'.lanewise({VO}ADD, {p}4.lanewise({VO}ADD, {p}5).lanewise({VO}ADD, {p}6.lanewise({VO}ADD, {p}7)))'
                 f'.lanewise({VO}ADD, {bias});')
    low = 'S256_128' if wide else 'S128_128'
    high = 'S256_383' if wide else 'S128_383'
    lines.append(f'var {prefix}Value = {prefix}Sum.lanewise({VO}LSHR, 7).lanewise({VO}MAX, {low})'
                 f'.lanewise({VO}MIN, {high}).lanewise({VO}SUB, {low});')
    return lines


def store(prefix, dst, d, width, shorts):
    wide = width >= 16
    if shorts:
        if wide:
            return [f'BYTE256.cast({prefix}Value.reinterpretShape(B256, 0)).intoArray({dst}, {d});']
        return [f'BYTE128.cast({prefix}Value.reinterpretShape(B128, 0)).intoArray({dst}, {d});']
    if wide:
        old = (f'SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, {dst}, {d}))'
               f'.convertShape({VO}ZERO_EXTEND_B2S, S256, 0))')
        avg = (f'BYTE128.cast({old}.lanewise({VO}ADD, {prefix}Value).lanewise({VO}ADD, S256_1)'
               f'.lanewise({VO}LSHR, 1).convertShape({VO}S2B, B128, 0)).intoArray({dst}, {d});')
        plain = f'BYTE128.cast({prefix}Value.convertShape({VO}S2B, B128, 0)).intoArray({dst}, {d});'
    else:
        old = (f'SHORT128.cast(BYTE64.cast(ByteVector.fromArray(B64, {dst}, {d}))'
               f'.convertShape({VO}ZERO_EXTEND_B2S, S128, 0))')
        avgv = (f'{old}.lanewise({VO}ADD, {prefix}Value).lanewise({VO}ADD, S128_1)'
                f'.lanewise({VO}LSHR, 1).convertShape({VO}S2B, B64, 0)')
        plainv = f'{prefix}Value.convertShape({VO}S2B, B64, 0)'
        if width == 8:
            avg = f'BYTE64.cast({avgv}).intoArray({dst}, {d});'
            plain = f'BYTE64.cast({plainv}).intoArray({dst}, {d});'
        else:
            avg = f'INT.set({dst}, {d}, INT64.cast(BYTE64.cast({avgv}).reinterpretShape(I64, 0)).lane(0));'
            plain = f'INT.set({dst}, {d}, INT64.cast(BYTE64.cast({plainv}).reinterpretShape(I64, 0)).lane(0));'
    return ['if (average) {', '    ' + avg, '} else {', '    ' + plain, '}']


def indent(lines, n):
    return [(' ' * n + l) if l else l for l in lines]


def kernel(direction, width, mode):
    """mode: 'plain', 'toShorts' (horizontal first pass) or 'fromShorts' (vertical second pass)."""
    wide = width >= 16
    looped = width > 16
    label = 'Wide' if looped else str(width)
    suffix = {'plain': '', 'toShorts': 'ToShorts', 'fromShorts': 'FromShorts'}[mode]
    name = direction + label + suffix
    shape = 'S256' if wide else 'S128'
    cls = 'SHORT256' if wide else 'SHORT128'
    doc_dir = 'horizontally' if direction == 'horizontal' else 'vertically'
    if looped:
        what = 'the samples of a row 16 at a time'
    elif width == 16:
        what = 'the 16 samples of a row at once'
    elif width == 8:
        what = 'the 8 samples of a row at once'
    else:
        what = 'the 4 samples of a row as the first half of an 8-lane vector'
    src_shorts = mode == 'fromShorts'
    dst_shorts = mode == 'toShorts'
    average = mode != 'toShorts'
    out = ['    /**',
           f'     * Filters a block {"at least 32" if looped else str(width)} samples wide {doc_dir} with one '
           f'eight-tap filter.',
           '     *',
           *['     * ' + line for line in textwrap.wrap(
               f'<p>Each sample is rounded and clamped to 8 bits. The kernel computes {what}, two rows per '
               'iteration; with an odd number of rows the last iteration computes the last row twice'
               + (', so averaging requires an even number.' if average else '.'), 113)]]
    if src_shorts:
        out += ['     * The source holds 8-bit samples as 16-bit values in native byte order, as',
                '     * {@link #horizontal' + label + 'ToShorts} writes them.']
    if dst_shorts:
        out += ['     * The destination receives the 8-bit results as 16-bit values in native byte order, the',
                '     * input of the vertical pass of a two-dimensional filter.']
    out.append('     *')
    out.append('     * @param src       the source ' + ('scratch array' if src_shorts else 'plane'))
    unit = ' (in bytes)' if src_shorts else ''
    if direction == 'horizontal':
        out.append('     * @param srcOff    the index of the source sample 3 columns left of the first output sample')
    else:
        out.append('     * @param srcOff    the index of the source sample 3 rows above the first output sample'
                   + unit)
    out.append('     * @param srcStride the source row stride' + unit)
    out.append('     * @param dst       the destination ' + ('scratch array' if dst_shorts else 'plane'))
    dunit = ' (in bytes)' if dst_shorts else ''
    out.append('     * @param dstOff    the index of the first destination sample' + dunit)
    out.append('     * @param dstStride the destination row stride' + dunit)
    out.append('     * @param kernels   the filter kernels')
    out.append('     * @param k         the index of the filter phase in {@code kernels}')
    if looped:
        out.append('     * @param w         the block width, a multiple of 16')
    out.append('     * @param h         the number of rows')
    if average:
        out.append('     * @param average   whether to average into the destination, rounding up')
    out.append('     */')
    params = 'byte[] src, int srcOff, int srcStride, byte[] dst, int dstOff, int dstStride,'
    tail = 'short[] kernels, int k' + (', int w' if looped else '') + ', int h' + (', boolean average' if average else '')
    out.append(f'    private static void {name}({params}')
    out.append(f'            {tail}) {{')
    body = []
    for t in range(8):
        body.append(f'var k{t} = {cls}.cast(ShortVector.broadcast({shape}, kernels[k + {t}]));')
    src_scale = '2 * ' if src_shorts else ''
    dst_scale = '2 * ' if dst_shorts else ''

    def row(s, d, prefix):
        addrs = []
        for t in range(8):
            if t == 0:
                addrs.append(s)
            elif direction == 'horizontal':
                addrs.append(f'{s} + {t}')
            else:
                addrs.append(f'{s} + {t} * srcStride')
        lines = tap_sum('src', addrs, wide, src_shorts, prefix)
        return lines + store(prefix, 'dst', d, width, dst_shorts)

    body.append('for (var y = 0; y < h; y += 2) {')
    body.append('    var s = srcOff + y * srcStride;')
    body.append('    var d = dstOff + y * dstStride;')
    body.append('    var next = Math.min(y + 1, h - 1);')
    body.append('    var s1 = srcOff + next * srcStride;')
    body.append('    var d1 = dstOff + next * dstStride;')
    if looped:
        body.append('    for (var x = 0; x < w; x += 16) {')
        body += indent(row(f's + {src_scale}x', f'd + {dst_scale}x', 'a'), 8)
        body += indent(row(f's1 + {src_scale}x', f'd1 + {dst_scale}x', 'b'), 8)
        body.append('    }')
    else:
        body += indent(row('s', 'd', 'a'), 4)
        body += indent(row('s1', 'd1', 'b'), 4)
    body.append('}')
    out += indent(body, 8)
    out.append('    }')
    return out


def fragment():
    out = []
    for direction, mode in (('horizontal', 'plain'), ('vertical', 'plain'), ('horizontal', 'toShorts'),
                            ('vertical', 'fromShorts')):
        for width in (32, 16, 8, 4):
            out += kernel(direction, width, mode)
            out.append('')
    return out
