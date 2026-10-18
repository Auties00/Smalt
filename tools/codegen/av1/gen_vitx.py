"""Translates the scalar 1-D inverse transforms of ScalarItxDsp into Vector API kernels.

Usage: gen_vitx.py <ScalarItxDsp.java> <head file> <output VectorItxDsp.java>
"""
import re
import sys



def write_java(path, text):
    """Writes a generated source, keeping the line endings of the file it replaces (LF for a new file)."""
    crlf = False
    try:
        with open(path, 'rb') as f:
            crlf = b'\r\n' in f.read()
    except FileNotFoundError:
        pass
    with open(path, 'w', encoding='utf-8', newline='') as f:
        f.write(text.replace('\n', '\r\n') if crlf else text)


def read_lf(path):
    with open(path, encoding='utf-8', newline='') as f:
        return f.read().replace('\r\n', '\n')


scalar_src = read_lf(sys.argv[1])
head = read_lf(sys.argv[2])
out_path = sys.argv[3]

def method_body(name):
    m = re.search(r"private static void %s\((.*?)\) \{" % name, scalar_src, re.S)
    start = m.end()
    depth = 1
    i = start
    while depth:
        if scalar_src[i] == '{':
            depth += 1
        elif scalar_src[i] == '}':
            depth -= 1
        i += 1
    return m.group(1), scalar_src[start:i - 1]

def strip_tx64(body):
    # keeps the else branch of "if (tx64) { ... } else { ... }"
    while True:
        k = body.find("if (tx64) {")
        if k < 0:
            break
        depth = 0
        i = body.index("{", k)
        j = i
        while True:
            if body[j] == '{':
                depth += 1
            elif body[j] == '}':
                depth -= 1
                if depth == 0:
                    break
            j += 1
        rest = body[j + 1:]
        m = re.match(r"\s*else\s*\{", rest)
        if not m:
            body = body[:k] + rest
            continue
        es = j + 1 + m.end()
        depth = 1
        e = es
        while depth:
            if body[e] == '{':
                depth += 1
            elif body[e] == '}':
                depth -= 1
            e += 1
        body = body[:k] + body[es:e - 1] + body[e:]
    body = body.replace(", tx64)", ")")
    return body

TOKEN = re.compile(r"\s*(?:(\d+)|([A-Za-z_]\w*)|(>>|<<|[-+*(),\[\]=]))")

def tokenize(text):
    pos = 0
    out = []
    text = text.strip()
    while pos < len(text):
        m = TOKEN.match(text, pos)
        if not m or m.end() == pos:
            raise SyntaxError("cannot tokenize: " + text[pos:pos + 30])
        if m.group(1):
            out.append(("num", int(m.group(1))))
        elif m.group(2):
            out.append(("id", m.group(2)))
        else:
            out.append(("op", m.group(3)))
        pos = m.end()
    return out

class Parser:
    def __init__(self, tokens):
        self.t = tokens
        self.i = 0

    def peek(self):
        return self.t[self.i] if self.i < len(self.t) else (None, None)

    def take(self):
        tok = self.t[self.i]
        self.i += 1
        return tok

    def expect(self, op):
        tok = self.take()
        if tok != ("op", op):
            raise SyntaxError("expected %s got %s" % (op, tok))

    PREC = {">>": 1, "<<": 1, "+": 2, "-": 2, "*": 3}

    def expr(self, minp=0):
        left = self.unary()
        while True:
            kind, val = self.peek()
            if kind != "op" or val not in self.PREC or self.PREC[val] < minp:
                return left
            self.take()
            right = self.expr(self.PREC[val] + 1)
            left = ("bin", val, left, right)

    def unary(self):
        kind, val = self.peek()
        if kind == "op" and val == "-":
            self.take()
            return ("neg", self.unary())
        return self.primary()

    def primary(self):
        kind, val = self.take()
        if kind == "num":
            return ("num", val)
        if kind == "op" and val == "(":
            e = self.expr()
            self.expect(")")
            return e
        if kind == "id":
            nk, nv = self.peek()
            if nk == "op" and nv == "(":
                self.take()
                args = []
                if self.peek() != ("op", ")"):
                    args.append(self.expr())
                    while self.peek() == ("op", ","):
                        self.take()
                        args.append(self.expr())
                self.expect(")")
                return ("call", val, args)
            if nk == "op" and nv == "[":
                self.take()
                idx = self.expr()
                self.expect("]")
                return ("index", val, idx)
            return ("id", val)
        raise SyntaxError("unexpected %s %s" % (kind, val))

SCALARS = {"o", "s", "io", "is", "oo", "os", "min", "max"}
ARRAYS = {"c", "in", "out"}
OPS = {"+": "ADD", "-": "SUB", "*": "MUL"}

class Gen:
    def __init__(self, lanes):
        self.lanes = lanes
        self.I = "I256" if lanes == 8 else "I128"
        self.IC = "INT256" if lanes == 8 else "INT128"
        self.vectors = set()
        self.consts = set()
        self.uses = set()

    def const(self, v):
        self.consts.add(v)
        return const_name(self.lanes, v)

    def ev(self, node):
        """returns (kind, code) with kind in const, scalar, vector; const code is the int value"""
        t = node[0]
        if t == "num":
            return ("const", node[1])
        if t == "id":
            name = node[1]
            if name in self.vectors:
                self.uses.add(name)
                return ("vector", name)
            if name in SCALARS:
                return ("scalar", name)
            raise NameError(name)
        if t == "index":
            kind, idx = self.ev(node[2])
            if kind == "vector":
                raise TypeError("vector index")
            idx = str(idx)
            return ("vector", "%s.cast(IntVector.fromArray(%s, %s, %s))" % (self.IC, self.I, node[1], idx))
        if t == "neg":
            kind, code = self.ev(node[1])
            if kind == "const":
                return ("const", -code)
            if kind == "scalar":
                return ("scalar", "-(%s)" % code)
            return ("vector", "%s.lanewise(VectorOperators.NEG)" % code)
        if t == "call":
            if node[1] != "clip":
                raise NameError(node[1])
            kind, code = self.ev(node[2][0])
            return ("vector", "%s.lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high)" % code)
        if t == "bin":
            op = node[1]
            lk, lc = self.ev(node[2])
            rk, rc = self.ev(node[3])
            if lk == "const" and rk == "const":
                return ("const", {"+": lc + rc, "-": lc - rc, "*": lc * rc, ">>": lc >> rc, "<<": lc << rc}[op])
            if lk != "vector" and rk != "vector":
                if op == "*" and ((lk == "const" and lc == 0) or (rk == "const" and rc == 0)):
                    return ("const", 0)
                if op == "*" and lk == "const" and lc == 1:
                    return (rk, rc)
                if op == "*" and rk == "const" and rc == 1:
                    return (lk, lc)
                if op in ("+", "-") and rk == "const" and rc == 0:
                    return (lk, lc)
                if op == "+" and lk == "const" and lc == 0:
                    return (rk, rc)
                left = "(%s)" % lc if lk == "scalar" and " " in str(lc) and op == "*" else str(lc)
                right = "(%s)" % rc if rk == "scalar" and " " in str(rc) and op != "+" else str(rc)
                return ("scalar", "%s %s %s" % (left, op, right))
            if op in (">>", "<<"):
                if rk != "const":
                    raise TypeError("vector shift by non-constant")
                return ("vector", "%s.lanewise(VectorOperators.%s, %d)" % (lc, "ASHR" if op == ">>" else "LSHL", rc))
            if lk == "vector" and rk == "vector":
                return ("vector", "%s.lanewise(VectorOperators.%s, %s)" % (lc, OPS[op], rc))
            if lk == "vector":
                if rk != "const":
                    raise TypeError("vector op scalar")
                return ("vector", "%s.lanewise(VectorOperators.%s, %s)" % (lc, OPS[op], self.const(rc)))
            if lk != "const":
                raise TypeError("scalar op vector")
            if op in ("+", "*"):
                return ("vector", "%s.lanewise(VectorOperators.%s, %s)" % (rc, OPS[op], self.const(lc)))
            return ("vector", "%s.lanewise(VectorOperators.SUB, %s)" % (self.const(lc), rc))
        raise TypeError(t)

def const_name(lanes, v):
    return "K%d_%s%d" % (lanes, "M" if v < 0 else "", abs(v))

def split_statements(body):
    stmts = []
    cur = ""
    depth = 0
    for ch in body:
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
        if ch == ";" and depth == 0:
            stmts.append(cur.strip())
            cur = ""
        else:
            cur += ch
    if cur.strip():
        stmts.append(cur.strip())
    return [s for s in stmts if s]

SPLITS = {
    "dct32": ([33, 57, 81, 105], "c", ["o + %d * s" % (2 * k + 1) for k in range(16)]),
    "dct16": (["int t0 = c[o + 0 * s]"], "c", ["o + %d * s" % (2 * k + 1) for k in range(8)]),
    "adst16": (["int t0a = clip(", "t8 = ((t8a", "t0 = clip(t0a", "out[oo + 0 * os] ="], "in", ["io + %d * is" % k for k in range(16)]),
}

def parse_ir(name, lanes):
    params, body = method_body(name)
    body = strip_tx64(body)
    params = params.replace(", boolean tx64", "")
    g = Gen(lanes)
    ir = []
    def ev(expr):
        g.uses = set()
        kind, code = g.ev(Parser(tokenize(expr)).expr())
        return kind, code, set(g.uses)
    for st in split_statements(body):
        st = re.sub(r"\s+", " ", st)
        if st.startswith("int ") and "=" not in st:
            continue
        if st.startswith("int "):
            decls = []
            depth = 0
            cur = ""
            for ch in st[4:]:
                if ch in "([":
                    depth += 1
                elif ch in ")]":
                    depth -= 1
                if ch == "," and depth == 0:
                    decls.append(cur)
                    cur = ""
                else:
                    cur += ch
            decls.append(cur)
            for k, d in enumerate(decls):
                var, expr = d.split("=", 1)
                var = var.strip()
                kind, code, uses = ev(expr)
                if kind != "vector":
                    raise TypeError("non-vector local " + var)
                ir.append({"kind": "assign", "var": var, "code": code, "uses": uses, "text": st if k == 0 else ""})
                g.vectors.add(var)
            continue
        m = re.match(r"^(\w+)\s*\[(.*)\]\s*=\s*(.*)$", st)
        if m:
            arr, idx, expr = m.groups()
            ik, ic, _ = ev(idx)
            kind, code, uses = ev(expr)
            ir.append({"kind": "store", "arr": arr, "idx": ic, "code": code, "uses": uses, "text": st})
            continue
        m = re.match(r"^(\w+)\s*=\s*(.*)$", st)
        if m:
            var, expr = m.groups()
            kind, code, uses = ev(expr)
            ir.append({"kind": "assign", "var": var, "code": code, "uses": uses, "text": st})
            g.vectors.add(var)
            continue
        m = re.match(r"^(dct\d+)\((.*)\)$", st)
        if m:
            callee, args = m.groups()
            ir.append({"kind": "call", "code": "v%s%d(%s)" % (callee, lanes, args), "uses": set(), "text": st})
            continue
        raise SyntaxError("unhandled statement in %s: %s" % (name, st))
    return params, ir, g

def live_at(ir, b):
    defined = set()
    for e in ir[:b]:
        if e["kind"] == "assign":
            defined.add(e["var"])
    live = []
    for v in sorted(defined, key=lambda v: next(i for i, e in enumerate(ir) if e["kind"] == "assign" and e["var"] == v)):
        for e in ir[b:]:
            if v in e["uses"]:
                live.append(v)
                break
            if e["kind"] == "assign" and e["var"] == v:
                break
    return live

def emit_chunk(g, ir, live_in, live_out, arr, slots, out_slots=None):
    if out_slots is None:
        out_slots = slots
    lines = ["        var low = IntVector.broadcast(%s, min);" % g.I,
             "        var high = IntVector.broadcast(%s, max);" % g.I]
    declared = set()
    for i, v in enumerate(live_in):
        lines.append("        var %s = %s.cast(IntVector.fromArray(%s, %s, %s));" % (v, g.IC, g.I, arr, slots[i]))
        declared.add(v)
    for e in ir:
        if e["kind"] == "assign":
            if e["var"] in declared:
                lines.append("        %s = %s;" % (e["var"], e["code"]))
            else:
                lines.append("        var %s = %s;" % (e["var"], e["code"]))
                declared.add(e["var"])
        elif e["kind"] == "store":
            lines.append("        %s.cast(%s).intoArray(%s, %s);" % (g.IC, e["code"], e["arr"], e["idx"]))
        else:
            lines.append("        %s;" % e["code"])
    for i, v in enumerate(live_out):
        lines.append("        %s.cast(%s).intoArray(%s, %s);" % (g.IC, v, arr, out_slots[i]))
    return "\n".join(lines)

def doc_block(summary, sig):
    doc = ["    /**"] + ["     * " + l for l in summary] + ["     *"]
    for p in [x.strip().split()[1] for x in sig.split(",")]:
        doc.append("     * @param %s %s" % (p, PARAM_DOC[p]))
    doc.append("     */")
    return "\n".join(doc)

def translate(name, lanes, all_consts):
    params, ir, g = parse_ir(name, lanes)
    all_consts |= {(lanes, v) for v in g.consts}
    sig = params
    summary = ["Runs {@code %s} of {@link ScalarItxDsp} on %d transforms at once, one per lane: element" % (name, lanes),
               "{@code k} of lane {@code j} is at index {@code o + k * s + j} (the inputs and outputs of an ADST",
               "at their own offsets and strides)."]
    if name not in SPLITS:
        return doc_block(summary, sig) + "\n    private static void v%s%d(%s) {\n%s\n    }" % (
            name, lanes, sig, emit_chunk(g, ir, [], [], None, []))
    markers, arr, slots = SPLITS[name]
    bounds = [0]
    for mk in markers:
        bounds.append(mk if isinstance(mk, int) else next(i for i, e in enumerate(ir) if e["text"].startswith(mk)))
    bounds.append(len(ir))
    final = None
    if name == "dct32":
        final = dct32_final(ir, bounds[-2], slots)
        bounds.pop()
    args = ", ".join(x.strip().split()[1] for x in sig.split(","))
    parts = []
    calls = []
    nparts = len(bounds) - 1 + (len(final) if final else 0)
    for k in range(len(bounds) - 1):
        b0, b1 = bounds[k], bounds[k + 1]
        live_in = live_at(ir, b0) if k > 0 else []
        live_out = live_at(ir, b1) if b1 < len(ir) else []
        out_slots = slots
        in_slots = slots
        if final and k == len(bounds) - 2:
            live_out = final_live(ir, b1)
            out_slots = final_slots(ir, b1, slots)
        if len(live_in) > len(slots) or len(live_out) > len(slots):
            raise ValueError("too many live values in %s part %d" % (name, k))
        body = emit_chunk(g, ir[b0:b1], live_in, live_out, arr, in_slots, out_slots)
        part_summary = ["Runs part %d of %d of {@link #v%s%d}, passing its live values to the next part in the" % (k + 1, nparts, name, lanes),
                        "array elements whose inputs it has consumed."]
        parts.append(doc_block(part_summary, sig) + "\n    private static void v%s%dp%d(%s) {\n%s\n    }" % (name, lanes, k, sig, body))
        calls.append("        v%s%dp%d(%s);" % (name, lanes, k, args))
    if final:
        for j, text in enumerate(final):
            k = len(bounds) - 1 + j
            part_summary = ["Runs part %d of %d of {@link #v%s%d}, the output butterflies of a subset of the pairs." % (k + 1, nparts, name, lanes),
                            "The pairs of the later part read only elements the earlier parts did not write."]
            parts.append(doc_block(part_summary, sig) + "\n    private static void v%s%dp%d(%s) {\n%s\n    }" % (name, lanes, k, sig, text))
            calls.append("        v%s%dp%d(%s);" % (name, lanes, k, args))
    main = doc_block(summary, sig) + "\n    private static void v%s%d(%s) {\n%s\n    }" % (name, lanes, sig, "\n".join(calls))
    return "\n\n".join([main] + parts)

# The output butterflies of dct32: out[k] = t[k] + x[k], out[31 - k] = t[k] - x[k], where t[k] is the even
# half's output k, left at element 2k, and x[k] the odd half's. Splitting the 16 pairs in two methods keeps
# each under the kernel limit; the first method's pairs write no element the second reads (searched offline).
DCT32_FIRST = [0, 1, 2, 4, 8, 15]
DCT32_SECOND = [k for k in range(16) if k not in DCT32_FIRST]

def dct32_pairs(ir, b):
    """returns the odd half's variable of each of the 16 pairs, from the final stores"""
    x = {}
    for e in ir[b:]:
        if e["kind"] != "store":
            continue
        m = re.match(r"^c\[o \+ (\d+) \* s\] = clip\((\w+) ([+-]) (\w+),", e["text"])
        k, t, op, xv = int(m.group(1)), m.group(2), m.group(3), m.group(4)
        if op == "+":
            assert t == "t%d" % k
            x[k] = xv
    assert sorted(x) == list(range(16))
    return x

def final_live(ir, b):
    x = dct32_pairs(ir, b)
    return [x[k] for k in range(16)]

def final_slots(ir, b, slots):
    """the element of each pair's odd half value: the second method's pairs get elements the first writes none of"""
    written = set(DCT32_FIRST) | {31 - k for k in DCT32_FIRST}
    free = [p for p in range(1, 32, 2) if p not in written]
    taken = [p for p in range(1, 32, 2) if p in written]
    assign = {}
    for k in DCT32_SECOND:
        assign[k] = free.pop(0)
    for k in DCT32_FIRST:
        assign[k] = taken.pop(0)
    return ["o + %d * s" % assign[k] for k in range(16)]

def dct32_final(ir, b, slots):
    x = dct32_pairs(ir, b)
    elems = final_slots(ir, b, slots)
    texts = []
    for pairs in (DCT32_FIRST, DCT32_SECOND):
        lines = ["        var low = IntVector.broadcast(I256, min);", "        var high = IntVector.broadcast(I256, max);"]
        for k in pairs:
            lines.append("        var t%d = INT256.cast(IntVector.fromArray(I256, c, o + %d * s));" % (k, 2 * k))
            lines.append("        var x%d = INT256.cast(IntVector.fromArray(I256, c, %s));" % (k, elems[k]))
        for k in pairs:
            lines.append("        INT256.cast(t%d.lanewise(VectorOperators.ADD, x%d).lanewise(VectorOperators.MAX, low)\n                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + %d * s);" % (k, k, k))
            lines.append("        INT256.cast(t%d.lanewise(VectorOperators.SUB, x%d).lanewise(VectorOperators.MAX, low)\n                .lanewise(VectorOperators.MIN, high)).intoArray(c, o + %d * s);" % (k, k, 31 - k))
        texts.append("\n".join(lines))
    return texts

PARAM_DOC = {
    "c": "the values, transformed in place",
    "o": "the index of the first value of the first lane",
    "s": "the distance between consecutive values of a lane",
    "min": "the lowest intermediate value",
    "max": "the highest intermediate value",
    "in": "the inputs",
    "io": "the index of the first input of the first lane",
    "is": "the distance between consecutive inputs of a lane",
    "out": "the outputs, which may be the inputs",
    "oo": "the index of the first output of the first lane",
    "os": "the distance between consecutive outputs of a lane, negative for a flipped ADST",
}

def identity(n, lanes):
    I = "I256" if lanes == 8 else "I128"
    IC = "INT256" if lanes == 8 else "INT128"
    consts = set()
    if n == 4:
        expr = "v.lanewise(VectorOperators.ADD, v.lanewise(VectorOperators.MUL, %s).lanewise(VectorOperators.ADD, %s)\n                    .lanewise(VectorOperators.ASHR, 12))" % (const_name(lanes, 1697), const_name(lanes, 2048))
        consts |= {1697, 2048}
    elif n == 8:
        expr = "v.lanewise(VectorOperators.LSHL, 1)"
    elif n == 16:
        expr = "v.lanewise(VectorOperators.LSHL, 1).lanewise(VectorOperators.ADD, v.lanewise(VectorOperators.MUL, %s)\n                    .lanewise(VectorOperators.ADD, %s).lanewise(VectorOperators.ASHR, 11))" % (const_name(lanes, 1697), const_name(lanes, 1024))
        consts |= {1697, 1024}
    else:
        expr = "v.lanewise(VectorOperators.LSHL, 2)"
    code = """    /**
     * Runs {@code identity%d} of {@link ScalarItxDsp} on %d transforms at once, one per lane.
     *
     * @param c the values, transformed in place
     * @param o the index of the first value of the first lane
     * @param s the distance between consecutive values of a lane
     */
    private static void videntity%d%d(int[] c, int o, int s) {
        for (var i = 0; i < %d; i++) {
            var v = %s.cast(IntVector.fromArray(%s, c, o + i * s));
            %s.cast(%s).intoArray(c, o + i * s);
        }
    }""" % (n, lanes, n, lanes, n, IC, I, IC, expr)
    return code, {(lanes, v) for v in consts}

all_consts = set()
kernels = []
for lanes in (8, 4):
    for name in ("dct4", "dct8", "dct16", "dct32", "adst4", "adst8", "adst16"):
        if name == "dct32" and lanes != 8:
            continue
        kernels.append(translate(name, lanes, all_consts))
    for n in (4, 8, 16, 32):
        code, cs = identity(n, lanes)
        kernels.append(code)
        all_consts |= cs

all_consts |= {(8, 181), (8, 128), (8, 8), (4, 181), (4, 128), (4, 8)}
const_lines = []
for lanes, v in sorted(all_consts):
    const_lines.append("    /**\n     * %s ints equal to {@code %d}.\n     */\n    private static final IntVector %s = IntVector.broadcast(%s, %d);"
                       % ("Eight" if lanes == 8 else "Four", v, const_name(lanes, v), "I256" if lanes == 8 else "I128", v))

text = head.replace("    // CONSTANTS\n", "\n\n".join(const_lines) + "\n")
text = text.rstrip()
assert text.endswith("}")
text = text[:-1].rstrip() + "\n\n" + "\n\n".join(kernels) + "\n}\n"
write_java(out_path, text)
print("constants:", len(all_consts), "kernels:", len(kernels))
