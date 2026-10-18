"""Generates the inverse transforms of VectorVp9Dsp from the scalar 1-D transforms of ScalarVp9Dsp.

Each 1-D transform of ScalarVp9Dsp (a straight-line sequence of int statements ported from libvpx's
inv_txfm.c) becomes vector code in which every scalar variable is a vector of 8 (4 for the 4-point
transforms) independent transforms, the rows of a group of 8 rows in the row pass and the columns of
a group of 8 columns in the column pass; both passes run the same methods, which read their inputs
from an array and write their outputs with a stride. The (short) truncations of the scalar code are
dropped: a conformant stream keeps every intermediate value within 16 bits, so they never change a
value.

Transforms too long for one method are split into stages whose estimated bytecode stays under
BUDGET (the Vector API rules of the module cap a kernel at 2000 bytes); each variable that crosses a
stage boundary lives in a home slot of the scratch array between the stage that defines it and the
stages that use it.
"""
import re


VO = 'VectorOperators.'
BUDGET = 1800


def extract(src, name):
    m = re.search(r'static void ' + name + r'\(int\[\] in, int inOff, int\[\] out, int outOff\) \{', src)
    i = m.end()
    depth = 1
    start = i
    while depth:
        if src[i] == '{':
            depth += 1
        elif src[i] == '}':
            depth -= 1
        i += 1
    return src[start:i - 1]


def split_top(text):
    parts, depth, cur = [], 0, ''
    for ch in text:
        if ch in '([':
            depth += 1
        if ch in ')]':
            depth -= 1
        if ch == ',' and depth == 0:
            parts.append(cur)
            cur = ''
        else:
            cur += ch
    parts.append(cur)
    return [p.strip() for p in parts]


def statements(body):
    body = re.sub(r'if \(.*?\) \{.*?return;\s*\}', '', body, flags=re.S)
    result = []
    for s in body.split(';'):
        s = ' '.join(s.split())
        if not s:
            continue
        m = re.match(r'^int (.*)$', s)
        if m:
            for d in split_top(m.group(1)):
                name, init = [x.strip() for x in d.split('=', 1)]
                if init != '0':
                    result.append((name, init))
            continue
        lhs, rhs = s.split('=', 1)
        result.append((lhs.strip(), rhs.strip()))
    return result


TOKEN = re.compile(r'\s*(>>|[A-Za-z_][A-Za-z0-9_]*|\d+|[()\[\]+\-*])')


def tokenize(text):
    pos, out = 0, []
    while pos < len(text):
        m = TOKEN.match(text, pos)
        if not m:
            raise SystemExit('bad token at ' + text[pos:])
        out.append(m.group(1))
        pos = m.end()
    return out


class Parser:
    def __init__(self, text):
        self.t = tokenize(text)
        self.i = 0

    def peek(self):
        return self.t[self.i] if self.i < len(self.t) else None

    def take(self, expected=None):
        tok = self.t[self.i]
        if expected and tok != expected:
            raise SystemExit(f'expected {expected} got {tok} in {self.t}')
        self.i += 1
        return tok

    def expr(self):
        node = self.add()
        while self.peek() == '>>':
            self.take()
            node = ('shr', node, int(self.take()))
        return node

    def add(self):
        node = self.mul()
        while self.peek() in ('+', '-'):
            op = self.take()
            node = ('add' if op == '+' else 'sub', node, self.mul())
        return node

    def mul(self):
        node = self.unary()
        while self.peek() == '*':
            self.take()
            node = ('mul', node, self.unary())
        return node

    def unary(self):
        if self.peek() == '-':
            self.take()
            return ('neg', self.unary())
        if self.peek() == '(' and self.t[self.i + 1] in ('short', 'int') and self.t[self.i + 2] == ')':
            self.i += 3
            return self.unary()
        return self.primary()

    def primary(self):
        tok = self.take()
        if tok == '(':
            node = self.expr()
            self.take(')')
            return node
        if tok.isdigit():
            return ('num', int(tok))
        if self.peek() == '[':
            self.take('[')
            self.take()
            self.take('+')
            k = int(self.take())
            self.take(']')
            return ('in', k) if tok == 'in' else ('outref', k)
        return ('var', tok)


def parse_target(lhs):
    m = re.match(r'^out\[outOff \+ (\d+)\]$', lhs)
    return ('out', int(m.group(1))) if m else ('var', lhs)


class Emitter:
    def __init__(self, consts, lanes):
        self.consts = consts
        self.lanes = lanes
        self.used = set()

    def const_name(self, value):
        prefix = 'I256' if self.lanes == 8 else 'I128'
        name = f'{prefix}_{"M" if value < 0 else ""}{abs(value)}'
        self.used.add((name, value))
        return name

    def is_const(self, node):
        return (node[0] == 'var' and node[1] in self.consts) or (node[0] == 'neg' and self.is_const(node[1])) \
            or node[0] == 'num'

    def const_value(self, node):
        if node[0] == 'num':
            return node[1]
        if node[0] == 'neg':
            return -self.const_value(node[1])
        return self.consts[node[1]]

    def emit(self, node, names):
        kind = node[0]
        if kind == 'var':
            if node[1] in self.consts:
                raise SystemExit('bare constant ' + node[1])
            return names(node[1])
        if kind == 'in':
            return names(('in', node[1]))
        if kind == 'neg':
            return f'{self.emit(node[1], names)}.lanewise({VO}NEG)'
        if kind == 'shr':
            return f'{self.emit(node[1], names)}.lanewise({VO}ASHR, {node[2]})'
        if kind == 'mul':
            a, b = node[1], node[2]
            if self.is_const(a):
                a, b = b, a
            if not self.is_const(b):
                raise SystemExit('vector product')
            return f'{self.emit(a, names)}.lanewise({VO}MUL, {self.const_name(self.const_value(b))})'
        if kind in ('add', 'sub'):
            a, b = node[1], node[2]
            op = 'ADD' if kind == 'add' else 'SUB'
            if self.is_const(b):
                return f'{self.emit(a, names)}.lanewise({VO}{op}, {self.const_name(self.const_value(b))})'
            if self.is_const(a):
                if kind == 'sub':
                    raise SystemExit('constant minus vector')
                return f'{self.emit(b, names)}.lanewise({VO}ADD, {self.const_name(self.const_value(a))})'
            return f'{self.emit(a, names)}.lanewise({VO}{op}, {self.emit(b, names)})'
        raise SystemExit('cannot emit ' + str(node))


def ops(node):
    if node[0] in ('var', 'in', 'num', 'outref'):
        return 0
    return 1 + sum(ops(child) for child in node[1:] if isinstance(child, tuple))


def refs(node, acc):
    if node[0] == 'var' and not node[1].startswith(('COSPI_', 'SINPI_')):
        acc.add(node[1])
    elif node[0] == 'in':
        acc.add(('in', node[1]))
    else:
        for child in node[1:]:
            if isinstance(child, tuple):
                refs(child, acc)
    return acc


def program(src, name, consts):
    stmts = []
    for lhs, rhs in statements(extract(src, name)):
        target = parse_target(lhs)
        node = Parser(rhs).expr()
        stmts.append((target, node))
    return stmts


def partition(stmts, mode, lanes):
    """Splits statements into the fewest stages whose estimated bytecode fits the budget, with
    the cost spread evenly across them."""
    def greedy(limit):
        stages, start, n = [], 0, len(stmts)
        while start < n:
            end = start
            while end < n:
                if stage_cost(stmts, start, end + 1, mode) > limit and end > start:
                    break
                end += 1
            stages.append((start, end))
            start = end
        return stages
    stages = greedy(BUDGET)
    count = len(stages)
    limit = BUDGET
    while True:
        smaller = greedy(limit - 25)
        if len(smaller) > count:
            return stages
        stages = smaller
        limit -= 25


def live_in(stmts, a, b):
    """Variables read in [a, b) whose value comes from before a."""
    defined = set()
    result = []
    for i in range(a, b):
        reads, target = refs(stmts[i][1], set()), stmts[i][0]
        for r in sorted(reads, key=str):
            if r not in defined and r not in result:
                result.append(r)
        if target[0] == 'var':
            defined.add(target[1])
    return result


def live_out(stmts, a, b):
    """Variables defined in [a, b) and read after b before being redefined."""
    defined = []
    for i in range(a, b):
        target = stmts[i][0]
        if target[0] == 'var' and target[1] not in defined:
            defined.append(target[1])
    result = []
    for v in defined:
        for j in range(b, len(stmts)):
            if v in refs(stmts[j][1], set()):
                result.append(v)
                break
            if stmts[j][0] == ('var', v):
                break
    return result


def stage_cost(stmts, a, b, mode):
    cost = 0
    for i in range(a, b):
        cost += 10 * ops(stmts[i][1]) + 3
        if stmts[i][0][0] == 'out':
            cost += 30
    for v in live_in(stmts, a, b):
        cost += 27 if isinstance(v, tuple) else 21
    cost += 18 * len(live_out(stmts, a, b))
    return cost + 40


def java_name(v):
    if isinstance(v, tuple):
        return f'in{v[1]}'
    return v


def generate(src, consts, name, mode, size, lanes, method, slot_base_name):
    """Returns (methods, stage names, max slots) for one 1-D transform in one pass."""
    stmts = program(src, name, consts)
    stages = partition(stmts, mode, lanes)
    emitter = Emitter(consts, lanes)
    species = 'I256' if lanes == 8 else 'I128'
    cls = 'INT256' if lanes == 8 else 'INT128'
    slots = {}
    out_methods = []
    names = []
    for index, (a, b) in enumerate(stages):
        stage_name = method if len(stages) == 1 else f'{method}Stage{index}'
        names.append(stage_name)
        body = []
        for v in live_in(stmts, a, b):
            if isinstance(v, tuple):
                k = v[1]
                body.append(f'var {java_name(v)} = {cls}.cast(IntVector.fromArray({species}, in, '
                            f'inBase + {k * lanes}));')
            else:
                slot = slots[v]
                body.append(f'var {v} = {cls}.cast(IntVector.fromArray({species}, s, {slot_base_name} + {slot * lanes}));')
        declared = set(java_name(v) for v in live_in(stmts, a, b))
        for i in range(a, b):
            target, node = stmts[i]
            expr = emitter.emit(node, java_name)
            if node[0] in ('var', 'in'):
                expr = f'{cls}.cast({expr})'
            if target[0] == 'var':
                v = target[1]
                if v in declared:
                    body.append(f'{v} = {expr};')
                else:
                    body.append(f'var {v} = {expr};')
                    declared.add(v)
            else:
                k = target[1]
                body.append(f'{cls}.cast({expr}).intoArray(s, tOff + {k} * tStride);' if k else
                            f'{cls}.cast({expr}).intoArray(s, tOff);')
        for v in live_out(stmts, a, b):
            if v not in slots:
                slots[v] = len(slots)
            body.append(f'{cls}.cast({v}).intoArray(s, {slot_base_name} + {slots[v] * lanes});')
        out_methods.append((stage_name, body, index, len(stages)))
    return out_methods, names, len(slots), emitter.used


def method_text(stage_name, body, index, count, mode, kind, size, lanes):
    what = {'idct': 'inverse DCT', 'iadst': 'inverse ADST'}[kind]
    part = '' if count == 1 else f', stage {index + 1} of {count}'
    doc = [f'     * Computes the {size}-point {what} of {lanes} rows or {lanes} columns of a block at once, one per',
           f'     * lane{part}.',
           '     *',
           f'     * <p>Element {{@code k}} of the {lanes} transforms is read at {{@code inOff + {lanes} * k}}, and output',
           '     * {@code k} is written at {@code tOff + k * tStride}.']
    if count > 1:
        doc.append('     *')
        doc.append('     * <p>Values that later stages read are left in the stage slots of the scratch array.')
    pdoc = ['     * @param in      the array holding the input elements',
            '     * @param inOff   the index of the first input element',
            '     * @param s       the scratch array receiving the outputs and holding the stage slots',
            '     * @param tOff    the index in {@code s} of the first output',
            '     * @param tStride the distance in {@code s} between consecutive outputs']
    lines = ['    /**'] + doc + ['     *'] + pdoc + ['     */']
    lines.append(f'    private static void {stage_name}(int[] in, int inOff, int[] s, int tOff, int tStride) {{')
    lines.append('        var inBase = inOff;')
    if any('slotBase' in l for l in body):
        lines.append('        var slotBase = SLOTS;')
    lines += ['        ' + l for l in body]
    lines.append('    }')
    return lines


def sequence_text(name, stages, mode, kind, size, lanes):
    what = {'idct': 'inverse DCT', 'iadst': 'inverse ADST'}[kind]
    lines = ['    /**',
             f'     * Computes the {size}-point {what} of {lanes} rows or {lanes} columns of a block at once, running its',
             '     * stages in order.',
             '     *',
             '     * @param in      the array holding the input elements',
             '     * @param inOff   the index of the first input element',
             '     * @param s       the scratch array receiving the outputs and holding the stage slots',
             '     * @param tOff    the index in {@code s} of the first output',
             '     * @param tStride the distance in {@code s} between consecutive outputs',
             '     */',
             f'    private static void {name}(int[] in, int inOff, int[] s, int tOff, int tStride) {{']
    lines += [f'        {stage}(in, inOff, s, tOff, tStride);' for stage in stages]
    lines.append('    }')
    return lines


def fragment(scalar_source):
    """Returns (lines, constants) for the scalar source text: the Java methods of every transform
    and the (name, value) pairs of the vector constants they use."""
    consts = {}
    for m in re.finditer(r'static final int ((?:COSPI|SINPI)_\w+) = (-?\d+);', scalar_source):
        consts[m.group(1)] = int(m.group(2))
    out = []
    used_consts = set()
    for kind, size in (('idct', 4), ('iadst', 4), ('idct', 8), ('iadst', 8), ('idct', 16), ('iadst', 16),
                       ('idct', 32)):
        lanes = 4 if size == 4 else 8
        method = f'{kind}{size}'
        methods, names, slots, used = generate(scalar_source, consts, method, 'both', size, lanes, method,
                                               'slotBase')
        if slots * lanes > 66 * 8:
            raise SystemExit(f'{method} needs {slots} slots, more than SLOT_GROUP holds')
        used_consts |= used
        for stage_name, body, index, count in methods:
            out += method_text(stage_name, body, index, count, 'both', kind, size, lanes)
            out.append('')
        if len(names) > 1:
            out += sequence_text(method, names, 'both', kind, size, lanes)
            out.append('')
    return out, sorted(used_consts)
