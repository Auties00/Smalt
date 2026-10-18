"""Generates the loop filter kernels of VectorVp9Dsp (see gen_vp9_dsp.py).

The kernels read the samples at positions p7 to q7 across the edge from rows of 16 bytes
(two groups of 8 lines side by side) of an array, a plane or the transposed rows of the scratch
array, and write their results to rows of the loop filter scratch array: rows 16 to 18 hold the
thresholds, rows 19 to 22 the results of the 4-tap filter, rows 23 to 29 those of the 8-tap
filter and its flags, rows 30 to 43 those of the 16-tap filter.
"""

VO = 'VectorOperators.'
NAMES = ['p7', 'p6', 'p5', 'p4', 'p3', 'p2', 'p1', 'p0', 'q0', 'q1', 'q2', 'q3', 'q4', 'q5', 'q6', 'q7']


def load(row, name):
    return (f'var {name} = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, t, {row * 16}))'
            f'.convertShape({VO}ZERO_EXTEND_B2S, S256, 0));')


def store(row, value):
    return f'BYTE128.cast({value}.convertShape({VO}S2B, B128, 0)).intoArray(t, {row * 16});'


def absdiff(a, b):
    return f'{a}.lanewise({VO}SUB, {b}).lanewise({VO}ABS)'


def load_sample(position, name):
    k = position - 8
    addr = 'off' if k == 0 else (f'off - {-k} * step' if k < 0 else f'off + {k} * step')
    return (f'var {name} = SHORT256.cast(BYTE128.cast(ByteVector.fromArray(B128, a, {addr}))'
            f'.convertShape({VO}ZERO_EXTEND_B2S, S256, 0));')


def load_positions(first, last):
    return [load_sample(i, NAMES[i]) for i in range(first, last + 1)]


def thresholds():
    return [load(16, 'blimit'), load(17, 'limit'), load(18, 'thresh')]


def mask_and_filter4():
    return [
        f'var dp1p0 = {absdiff("p1", "p0")};',
        f'var dq1q0 = {absdiff("q1", "q0")};',
        f'var interior = {absdiff("p3", "p2")}.lanewise({VO}MAX, {absdiff("p2", "p1")})'
        f'.lanewise({VO}MAX, dp1p0).lanewise({VO}MAX, dq1q0)'
        f'.lanewise({VO}MAX, {absdiff("q2", "q1")}).lanewise({VO}MAX, {absdiff("q3", "q2")});',
        f'var dp0q0 = {absdiff("p0", "q0")};',
        f'var edge = dp0q0.lanewise({VO}ADD, dp0q0).lanewise({VO}ADD, {absdiff("p1", "q1")}.lanewise({VO}ASHR, 1));',
        f'var mask = interior.compare({VO}LE, limit).and(edge.compare({VO}LE, blimit));',
        f'var hev = dp1p0.lanewise({VO}MAX, dq1q0).compare({VO}GT, thresh);',
        f'var outer = S256_0.blend(p1.lanewise({VO}SUB, q1).lanewise({VO}MAX, S256_M128).lanewise({VO}MIN, S256_127), hev);',
        f'var delta = q0.lanewise({VO}SUB, p0);',
        f'var filter = S256_0.blend(outer.lanewise({VO}ADD, delta).lanewise({VO}ADD, delta).lanewise({VO}ADD, delta)'
        f'.lanewise({VO}MAX, S256_M128).lanewise({VO}MIN, S256_127), mask);',
        f'var filter1 = filter.lanewise({VO}ADD, S256_4).lanewise({VO}MIN, S256_127).lanewise({VO}ASHR, 3);',
        f'var filter2 = filter.lanewise({VO}ADD, S256_3).lanewise({VO}MIN, S256_127).lanewise({VO}ASHR, 3);',
        f'var adjust = S256_0.blend(filter1.lanewise({VO}ADD, S256_1).lanewise({VO}ASHR, 1), hev.not());',
        f'var f4q0 = q0.lanewise({VO}SUB, filter1).lanewise({VO}MAX, S256_0).lanewise({VO}MIN, S256_255);',
        f'var f4p0 = p0.lanewise({VO}ADD, filter2).lanewise({VO}MAX, S256_0).lanewise({VO}MIN, S256_255);',
        f'var f4q1 = q1.lanewise({VO}SUB, adjust).lanewise({VO}MAX, S256_0).lanewise({VO}MIN, S256_255);',
        f'var f4p1 = p1.lanewise({VO}ADD, adjust).lanewise({VO}MAX, S256_0).lanewise({VO}MIN, S256_255);',
    ]


def method(doc, name, body):
    lines = ['    /**'] + ['     * ' + d if d else '     *' for d in doc] + ['     *',
             '     * @param a    the array holding the samples',
             '     * @param off  the index of the first sample after the edge, on the first line',
             '     * @param step the distance between consecutive samples across the edge',
             '     * @param t    the loop filter scratch array', '     */',
             f'    private static void {name}(byte[] a, int off, int step, byte[] t) {{']
    lines += ['        ' + l for l in body]
    lines.append('    }')
    return lines


def filter4():
    body = load_positions(4, 11) + thresholds() + mask_and_filter4()
    body += [store(19, 'f4p1'), store(20, 'f4p0'), store(21, 'f4q0'), store(22, 'f4q1')]
    return method(['Applies the 4-tap loop filter ({@code filter4} under {@code filter_mask}) to 16 lines across an',
                   'edge in 16-bit lanes, writing the 4 positions around the edge to the result rows.'], 'filter4',
                  body)


def filter8():
    body = load_positions(4, 11) + thresholds() + mask_and_filter4()
    body.append(f'var flat = dp1p0.lanewise({VO}MAX, dq1q0).lanewise({VO}MAX, {absdiff("p2", "p0")})'
                f'.lanewise({VO}MAX, {absdiff("q2", "q0")}).lanewise({VO}MAX, {absdiff("p3", "p0")})'
                f'.lanewise({VO}MAX, {absdiff("q3", "q0")}).compare({VO}LE, S256_1).and(mask);')
    body.append(f'var t0 = p3.lanewise({VO}ADD, p3).lanewise({VO}ADD, p3).lanewise({VO}ADD, p2).lanewise({VO}ADD, p2)'
                f'.lanewise({VO}ADD, p1).lanewise({VO}ADD, p0).lanewise({VO}ADD, q0).lanewise({VO}ADD, S256_4);')
    body.append(f'var t1 = t0.lanewise({VO}SUB, p3).lanewise({VO}SUB, p2).lanewise({VO}ADD, p1).lanewise({VO}ADD, q1);')
    body.append(f'var t2 = t1.lanewise({VO}SUB, p3).lanewise({VO}SUB, p1).lanewise({VO}ADD, p0).lanewise({VO}ADD, q2);')
    body.append(f'var t3 = t2.lanewise({VO}SUB, p3).lanewise({VO}SUB, p0).lanewise({VO}ADD, q0).lanewise({VO}ADD, q3);')
    body.append(f'var t4 = t3.lanewise({VO}SUB, p2).lanewise({VO}SUB, q0).lanewise({VO}ADD, q1).lanewise({VO}ADD, q3);')
    body.append(f'var t5 = t4.lanewise({VO}SUB, p1).lanewise({VO}SUB, q1).lanewise({VO}ADD, q2).lanewise({VO}ADD, q3);')
    body.append(store(23, f'p2.blend(t0.lanewise({VO}ASHR, 3), flat)'))
    body.append(store(24, f'f4p1.blend(t1.lanewise({VO}ASHR, 3), flat)'))
    body.append(store(25, f'f4p0.blend(t2.lanewise({VO}ASHR, 3), flat)'))
    body.append(store(26, f'f4q0.blend(t3.lanewise({VO}ASHR, 3), flat)'))
    body.append(store(27, f'f4q1.blend(t4.lanewise({VO}ASHR, 3), flat)'))
    body.append(store(28, f'q2.blend(t5.lanewise({VO}ASHR, 3), flat)'))
    body.append(store(29, 'S256_0.blend(S256_M1, flat)'))
    return method(['Applies the 8-tap loop filter ({@code filter8}, falling back to {@code filter4} where the',
                   'samples are not flat) to 16 lines across an edge in 16-bit lanes.',
                   '',
                   '<p>The 6 filtered positions are written to the result rows, followed by a flag row that is',
                   '{@code -1} where the 8-tap filter applied and {@code 0} elsewhere.'], 'filter8', body)


def flat2_lines():
    return [f'var flat2 = {absdiff("p4", "p0")}.lanewise({VO}MAX, {absdiff("q4", "q0")})'
            f'.lanewise({VO}MAX, {absdiff("p5", "p0")}).lanewise({VO}MAX, {absdiff("q5", "q0")})'
            f'.lanewise({VO}MAX, {absdiff("p6", "p0")}).lanewise({VO}MAX, {absdiff("q6", "q0")})'
            f'.lanewise({VO}MAX, {absdiff("p7", "p0")}).lanewise({VO}MAX, {absdiff("q7", "q0")})'
            f'.compare({VO}LE, S256_1).and(flags.compare({VO}NE, S256_0));']


def filter16(side):
    """Outputs at positions 1..7 (side 'p') or 8..14 (side 'q')."""
    body = load_positions(0, 15)
    first = 1 if side == 'p' else 8
    results = {5: 23, 6: 24, 7: 25, 8: 26, 9: 27, 10: 28}
    for k in range(first, first + 7):
        if k in results:
            body.append(load(results[k], 'f8' + NAMES[k]))
    body.append(load(29, 'flags'))
    body += flat2_lines()
    if side == 'p':
        body.append(f'var w1 = p7.lanewise({VO}MUL, S256_7).lanewise({VO}ADD, p6).lanewise({VO}ADD, p5)'
                    f'.lanewise({VO}ADD, p4).lanewise({VO}ADD, p3).lanewise({VO}ADD, p2).lanewise({VO}ADD, p1)'
                    f'.lanewise({VO}ADD, p0).lanewise({VO}ADD, q0).lanewise({VO}ADD, S256_8);')
    else:
        body.append(f'var w8 = p6.lanewise({VO}ADD, p5).lanewise({VO}ADD, p4).lanewise({VO}ADD, p3)'
                    f'.lanewise({VO}ADD, p2).lanewise({VO}ADD, p1).lanewise({VO}ADD, p0).lanewise({VO}ADD, q0)'
                    f'.lanewise({VO}ADD, q1).lanewise({VO}ADD, q2).lanewise({VO}ADD, q3).lanewise({VO}ADD, q4)'
                    f'.lanewise({VO}ADD, q5).lanewise({VO}ADD, q6).lanewise({VO}ADD, q7).lanewise({VO}ADD, S256_8);')
    for k in range(first, first + 6):
        rem = NAMES[max(k - 7, 0)]
        add = NAMES[min(k + 8, 15)]
        body.append(f'var w{k + 1} = w{k}.lanewise({VO}SUB, {rem}).lanewise({VO}ADD, {add});')
    for k in range(first, first + 7):
        n = NAMES[k]
        base = ('f8' + n) if k in results else n
        value = f'{base}.blend(w{k}.lanewise({VO}ADD, {n}).lanewise({VO}ASHR, 4), flat2)'
        row = 30 + (k - 1)
        body.append(store(row, value))
    if side == 'p':
        doc = ['Computes the 16-tap loop filter ({@code filter16}) of the 7 positions before the edge, after',
               '{@link #filter8} has left its results and flags in the scratch rows.',
               '',
               '<p>Where the flag is set and the 7 samples on each side are flat, the 15-tap filter',
               'applies; elsewhere the 3 positions next to the edge take the results of {@link #filter8}',
               'and the outer ones keep their samples. The results go to the 7 rows after the flags.']
        name = 'filter16Before'
    else:
        doc = ['Computes the 16-tap loop filter ({@code filter16}) of the 7 positions after the edge, as',
               '{@link #filter16Before} does for the positions before it, into the 7 rows after its',
               'results.']
        name = 'filter16After'
    return method(doc, name, body)


def transpose_body():
    body = []
    masks = {8: 'L128_BYTES', 16: 'L128_SHORTS', 32: 'L128_INTS'}
    pairs = [(8, [(0, 1), (2, 3), (4, 5), (6, 7)]), (16, [(0, 2), (1, 3), (4, 6), (5, 7)]),
             (32, [(0, 4), (1, 5), (2, 6), (3, 7)])]
    for shift, ps in pairs:
        for a, b in ps:
            x = f'x{a}{b}'
            body.append(f'var {x} = LONG128.cast(r{a}.lanewise({VO}LSHR, {shift}).lanewise({VO}XOR, r{b})'
                        f'.lanewise({VO}AND, {masks[shift]}));')
            body.append(f'r{b} = r{b}.lanewise({VO}XOR, {x});')
            body.append(f'r{a} = r{a}.lanewise({VO}XOR, {x}.lanewise({VO}LSHL, {shift}));')
    return body


def transpose_in():
    out = ['    /**',
           '     * Transposes an 8-column window of 8 or 16 rows into 8 scratch rows.',
           '     *',
           '     * <p>Column {@code j} is stored as 16 bytes at {@code t[tOff + 16 * j]}: the first 8 rows,',
           '     * then the 8 rows {@code lower} entries further, which repeat the first 8 when',
           '     * {@code lower} is {@code 0}. The transposes run in registers on 64-bit lanes, one 8x8',
           '     * block per lane.',
           '     *',
           '     * @param s     the plane',
           '     * @param off   the index of the window\'s top-left sample',
           '     * @param pitch the row stride',
           '     * @param lower the distance from the first 8 rows to the second 8, or {@code 0}',
           '     * @param t     the scratch array',
           '     * @param tOff  the index of the first transposed column',
           '     */',
           '    private static void transposeIn(byte[] s, int off, int pitch, int lower, byte[] t, int tOff) {']
    body = []
    for i in range(8):
        a = 'off' if i == 0 else f'off + {i} * pitch'
        body.append(f'var r{i} = LONG128.cast(LONG128.cast(LongVector.broadcast(L128, (long) LONG.get(s, {a})))'
                    f'.withLane(1, (long) LONG.get(s, {a} + lower)));')
    body += transpose_body()
    for j in range(8):
        body.append(f'BYTE128.cast(r{j}.reinterpretShape(B128, 0)).intoArray(t, tOff + {16 * j});')
    out += ['        ' + l for l in body]
    out.append('    }')
    return out


def transpose_out():
    out = ['    /**',
           '     * Writes 8 scratch rows produced by {@link #transposeIn} back to the plane.',
           '     *',
           '     * @param t     the scratch array',
           '     * @param tOff  the index of the first transposed column',
           '     * @param s     the plane',
           '     * @param off   the index of the window\'s top-left sample',
           '     * @param pitch the row stride',
           '     * @param lower the distance from the first 8 rows to the second 8, or {@code 0} to write',
           '     *              the first 8 rows only',
           '     */',
           '    private static void transposeOut(byte[] t, int tOff, byte[] s, int off, int pitch, int lower) {']
    body = []
    for j in range(8):
        body.append(f'var r{j} = LONG128.cast(BYTE128.cast(ByteVector.fromArray(B128, t, tOff + {16 * j}))'
                    f'.reinterpretShape(L128, 0));')
    body += transpose_body()
    for i in range(8):
        a = 'off' if i == 0 else f'off + {i} * pitch'
        body.append(f'LONG.set(s, {a}, r{i}.lane(0));')
        body.append(f'LONG.set(s, {a} + lower, r{i}.lane(1));')
    out += ['        ' + l for l in body]
    out.append('    }')
    return out


def fragment():
    return (filter4() + [''] + filter8() + [''] + filter16('p') + [''] + filter16('q') + [''] + transpose_in() + ['']
            + transpose_out() + [''])
