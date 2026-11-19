"""Generates VectorLoopFilterDsp.java: the hand-written parts of VectorLoopFilterDsp.java.head followed by the
generated loop filter kernels.

Usage: gen_vlf.py <output VectorLoopFilterDsp.java> <VectorLoopFilterDsp.java.head>
"""
import sys

out = sys.argv[1]
head = sys.argv[2]


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


ROWS = {
    "p6": -7, "p5": -6, "p4": -5, "p3": -4, "p2": -3, "p1": -2, "p0": -1,
    "q0": 0, "q1": 1, "q2": 2, "q3": 3, "q4": 4, "q5": 5, "q6": 6,
}

def rows_for(max_wd):
    if max_wd == 4:
        return ["p1", "p0", "q0", "q1"]
    if max_wd == 6:
        return ["p2", "p1", "p0", "q0", "q1", "q2"]
    if max_wd == 8:
        return ["p3", "p2", "p1", "p0", "q0", "q1", "q2", "q3"]
    return ["p6", "p5", "p4", "p3", "p2", "p1", "p0", "q0", "q1", "q2", "q3", "q4", "q5", "q6"]

def off_expr(r):
    m = ROWS[r]
    if m == 0:
        return "off"
    if m == 1:
        return "off + stride"
    if m == -1:
        return "off - stride"
    return "off %s %d * stride" % ("+" if m > 0 else "-", abs(m))

def lane_param(L, name, args):
    L.append("        var %s = SHORT256.cast(ShortVector.broadcast(S256, (short) %s)).blend(ShortVector.broadcast(S256, (short) %s), FROM_4)"
             % (name, args[0], args[1]))
    L.append("                .blend(ShortVector.broadcast(S256, (short) %s), FROM_8).blend(ShortVector.broadcast(S256, (short) %s), FROM_12);"
             % (args[2], args[3]))

def abs_diff(a, b):
    return "%s.lanewise(VectorOperators.SUB, %s).lanewise(VectorOperators.ABS)" % (a, b)

def add(*terms):
    return terms[0] + "".join(".lanewise(VectorOperators.ADD, %s)" % t for t in terms[1:])

def twice(x):
    return "%s.lanewise(VectorOperators.LSHL, 1)" % x

PDOC = {
    "dst": "the plane", "off": "the index of the first sample below the edge in the first segment",
    "stride": "the plane's row stride", "flat": "the flatness threshold, {@code 1 << (bitDepth - 8)}",
    "diffMin": "the smallest filter difference", "diffMax": "the largest filter difference",
    "max": "the largest sample value",
    "scratch": "the decisions of the group, written by the decision kernel: the category of each lane "
               "(0 unfiltered, 1 narrow, 2 flat, 3 flat over 16 samples) at indices 0 to 15 and its high edge "
               "variance flag at indices 16 to 31",
}
for k in range(4):
    PDOC["e%d" % k] = "the edge limit of segment %d" % k
    PDOC["i%d" % k] = "the interior limit of segment %d" % k
    PDOC["h%d" % k] = "the high edge variance threshold of segment %d" % k
    PDOC["w%d" % k] = "the filter width of segment %d, or 0 when it is not filtered" % k

def header(L, name, params, doc, returns=None):
    L.append("    /**")
    text = " ".join(doc).split(" ")
    line = "     *"
    for word in text:
        if len(line) + 1 + len(word) > 108:
            L.append(line)
            line = "     *"
        line += " " + word
    L.append(line)
    L.append("     *")
    for p_ in params:
        n = p_.split()[1]
        words = ("@param %s %s" % (n, PDOC[n])).split(" ")
        line = "     *"
        for word in words:
            if len(line) + 1 + len(word) > 108:
                L.append(line)
                line = "     *        " + " " * len(n)
            line += " " + word
        L.append(line)
    if returns:
        words = ("@return " + returns).split(" ")
        line = "     *"
        for word in words:
            if len(line) + 1 + len(word) > 108:
                L.append(line)
                line = "     *        "
            line += " " + word
        L.append(line)
    L.append("     */")
    L.append("    private static %s %s(%s) {" % ("int" if returns else "void", name, ", ".join(params)))

def decisions(L, max_wd):
    L.append("        var fm = %s.compare(VectorOperators.LE, i)" % abs_diff("p1", "p0"))
    L.append("                .and(%s.compare(VectorOperators.LE, i))" % abs_diff("q1", "q0"))
    L.append("                .and(%s.lanewise(VectorOperators.ADD, %s)" % (abs_diff("p0", "q0"), abs_diff("p0", "q0")))
    L.append("                        .lanewise(VectorOperators.ADD, SHORT256.cast(%s).lanewise(VectorOperators.ASHR, 1))" % abs_diff("p1", "q1"))
    L.append("                        .compare(VectorOperators.LE, e))")
    L.append("                .and(w.compare(VectorOperators.GT, ZERO));")
    if max_wd > 4:
        L.append("        fm = fm.and(%s.compare(VectorOperators.LE, i)" % abs_diff("p2", "p1"))
        L.append("                .and(%s.compare(VectorOperators.LE, i))" % abs_diff("q2", "q1"))
        L.append("                .or(w.compare(VectorOperators.LE, FOUR)));")
    if max_wd > 6:
        L.append("        fm = fm.and(%s.compare(VectorOperators.LE, i)" % abs_diff("p3", "p2"))
        L.append("                .and(%s.compare(VectorOperators.LE, i))" % abs_diff("q3", "q2"))
        L.append("                .or(w.compare(VectorOperators.LE, SIX)));")
    L.append("        var hev = %s.compare(VectorOperators.GT, h)" % abs_diff("p1", "p0"))
    L.append("                .or(%s.compare(VectorOperators.GT, h));" % abs_diff("q1", "q0"))
    if max_wd > 4:
        L.append("        var flatIn = %s.compare(VectorOperators.LE, f)" % abs_diff("p2", "p0"))
        L.append("                .and(%s.compare(VectorOperators.LE, f))" % abs_diff("p1", "p0"))
        L.append("                .and(%s.compare(VectorOperators.LE, f))" % abs_diff("q1", "q0"))
        L.append("                .and(%s.compare(VectorOperators.LE, f))" % abs_diff("q2", "q0"))
        L.append("                .and(w.compare(VectorOperators.GE, SIX)).and(fm);")
        if max_wd > 6:
            L.append("        flatIn = flatIn.and(%s.compare(VectorOperators.LE, f)" % abs_diff("p3", "p0"))
            L.append("                .and(%s.compare(VectorOperators.LE, f))" % abs_diff("q3", "q0"))
            L.append("                .or(w.compare(VectorOperators.LE, SIX)));")
    if max_wd == 16:
        L.append("        var flat16 = %s.compare(VectorOperators.LE, f)" % abs_diff("p6", "p0"))
        L.append("                .and(%s.compare(VectorOperators.LE, f))" % abs_diff("p5", "p0"))
        L.append("                .and(%s.compare(VectorOperators.LE, f))" % abs_diff("p4", "p0"))
        L.append("                .and(%s.compare(VectorOperators.LE, f))" % abs_diff("q4", "q0"))
        L.append("                .and(%s.compare(VectorOperators.LE, f))" % abs_diff("q5", "q0"))
        L.append("                .and(%s.compare(VectorOperators.LE, f))" % abs_diff("q6", "q0"))
        L.append("                .and(w.compare(VectorOperators.EQ, SIXTEEN)).and(flatIn);")

def narrow_math(L, lanes_expr, hev_expr):
    L.append("        var low = ShortVector.broadcast(S256, (short) diffMin);")
    L.append("        var high = ShortVector.broadcast(S256, (short) diffMax);")
    L.append("        var top = ShortVector.broadcast(S256, (short) max);")
    L.append("        var lanes = %s;" % lanes_expr)
    L.append("        var strong = %s;" % hev_expr)
    L.append("        var outer = ZERO.blend(p1.lanewise(VectorOperators.SUB, q1).lanewise(VectorOperators.MAX, low)")
    L.append("                .lanewise(VectorOperators.MIN, high), strong);")
    L.append("        var base = q0.lanewise(VectorOperators.SUB, p0).lanewise(VectorOperators.MUL, THREE)")
    L.append("                .lanewise(VectorOperators.ADD, outer).lanewise(VectorOperators.MAX, low)")
    L.append("                .lanewise(VectorOperators.MIN, high);")
    L.append("        var f1 = SHORT256.cast(base.lanewise(VectorOperators.ADD, FOUR).lanewise(VectorOperators.MIN, high))")
    L.append("                .lanewise(VectorOperators.ASHR, 3);")
    L.append("        var f2 = SHORT256.cast(base.lanewise(VectorOperators.ADD, THREE).lanewise(VectorOperators.MIN, high))")
    L.append("                .lanewise(VectorOperators.ASHR, 3);")
    L.append("        var half = SHORT256.cast(f1.lanewise(VectorOperators.ADD, ONE)).lanewise(VectorOperators.ASHR, 1);")
    L.append("        var outerLanes = lanes.andNot(strong);")
    L.append("        p0.blend(p0.lanewise(VectorOperators.ADD, f2).lanewise(VectorOperators.MAX, ZERO)")
    L.append("                .lanewise(VectorOperators.MIN, top), lanes).intoArray(dst, off - stride);")
    L.append("        q0.blend(q0.lanewise(VectorOperators.SUB, f1).lanewise(VectorOperators.MAX, ZERO)")
    L.append("                .lanewise(VectorOperators.MIN, top), lanes).intoArray(dst, off);")
    L.append("        p1.blend(p1.lanewise(VectorOperators.ADD, half).lanewise(VectorOperators.MAX, ZERO)")
    L.append("                .lanewise(VectorOperators.MIN, top), outerLanes).intoArray(dst, off - 2 * stride);")
    L.append("        q1.blend(q1.lanewise(VectorOperators.SUB, half).lanewise(VectorOperators.MAX, ZERO)")
    L.append("                .lanewise(VectorOperators.MIN, top), outerLanes).intoArray(dst, off + stride);")

def load_rows(L, rows):
    for r in rows:
        L.append("        var %s = SHORT256.cast(ShortVector.fromArray(S256, dst, %s));" % (r, off_expr(r)))

def k_narrow4():
    L = []
    params = ["short[] dst", "int off", "int stride", "int e0", "int e1", "int e2", "int e3", "int i0", "int i1",
              "int i2", "int i3", "int h0", "int h1", "int h2", "int h3", "int w0", "int w1", "int w2", "int w3",
              "int diffMin", "int diffMax", "int max"]
    header(L, "narrow4", params, ["Decides and applies the narrow filter for four 4-sample segments of a row edge none of",
                                  "which is wider than 4."])
    lane_param(L, "e", ["e0", "e1", "e2", "e3"])
    lane_param(L, "i", ["i0", "i1", "i2", "i3"])
    lane_param(L, "h", ["h0", "h1", "h2", "h3"])
    lane_param(L, "w", ["w0", "w1", "w2", "w3"])
    load_rows(L, rows_for(4))
    decisions(L, 4)
    narrow_math(L, "fm", "hev")
    L.append("    }")
    return "\n".join(L)

def k_decide(max_wd):
    L = []
    params = ["short[] dst", "int off", "int stride", "int e0", "int e1", "int e2", "int e3", "int i0", "int i1",
              "int i2", "int i3", "int h0", "int h1", "int h2", "int h3", "int w0", "int w1", "int w2", "int w3",
              "int flat", "short[] scratch"]
    width_text = {6: "a chroma row edge at most 6 wide", 8: "a luma row edge at most 8 wide",
                  16: "a luma row edge with a 16-wide segment"}[max_wd]
    header(L, "decide%d" % max_wd, params,
           ["Takes the filter decisions of four 4-sample segments of %s from the unfiltered" % width_text,
            "samples and stores them in the scratch buffer."],
           "the categories present: bit 0 for narrow lanes, bit 1 for flat lanes, bit 2 for lanes flat over 16 samples")
    lane_param(L, "e", ["e0", "e1", "e2", "e3"])
    lane_param(L, "i", ["i0", "i1", "i2", "i3"])
    lane_param(L, "h", ["h0", "h1", "h2", "h3"])
    lane_param(L, "w", ["w0", "w1", "w2", "w3"])
    L.append("        var f = ShortVector.broadcast(S256, (short) flat);")
    load_rows(L, rows_for(max_wd))
    decisions(L, max_wd)
    L.append("        var narrow = fm.andNot(flatIn);")
    cat = "ZERO.blend(ONE, narrow).blend(TWO, flatIn)"
    if max_wd == 16:
        cat += ".blend(THREE, flat16)"
    L.append("        %s.intoArray(scratch, 0);" % cat)
    L.append("        ZERO.blend(ONE, hev).intoArray(scratch, 16);")
    if max_wd == 16:
        L.append("        return (narrow.anyTrue() ? 1 : 0) | (flatIn.andNot(flat16).anyTrue() ? 2 : 0) | (flat16.anyTrue() ? 4 : 0);")
    else:
        L.append("        return (narrow.anyTrue() ? 1 : 0) | (flatIn.anyTrue() ? 2 : 0);")
    L.append("    }")
    return "\n".join(L)

def k_narrow_apply():
    L = []
    params = ["short[] dst", "int off", "int stride", "int diffMin", "int diffMax", "int max", "short[] scratch"]
    header(L, "narrowApply", params, ["Applies the narrow filter to the lanes the decision kernel classified as narrow."])
    load_rows(L, rows_for(4))
    L.append("        var category = SHORT256.cast(ShortVector.fromArray(S256, scratch, 0));")
    L.append("        var variance = SHORT256.cast(ShortVector.fromArray(S256, scratch, 16));")
    narrow_math(L, "category.compare(VectorOperators.EQ, ONE)", "variance.compare(VectorOperators.EQ, ONE)")
    L.append("    }")
    return "\n".join(L)

def k_flat(kind):
    L = []
    params = ["short[] dst", "int off", "int stride", "short[] scratch"]
    if kind == 6:
        header(L, "flat6Apply", params, ["Applies the 6-wide filter to the lanes the decision kernel classified as flat."])
        load_rows(L, rows_for(6))
        L.append("        var lanes = SHORT256.cast(ShortVector.fromArray(S256, scratch, 0)).compare(VectorOperators.EQ, TWO);")
        outs = [
            ("p1", add("p2", twice("p2"), twice("p1"), twice("p0"), "q0", "FOUR")),
            ("p0", add("p2", twice("p1"), twice("p0"), twice("q0"), "q1", "FOUR")),
            ("q0", add("p1", twice("p0"), twice("q0"), twice("q1"), "q2", "FOUR")),
            ("q1", add("p0", twice("q0"), twice("q1"), twice("q2"), "q2", "FOUR")),
        ]
    elif kind == 8:
        header(L, "flat8Apply", params, ["Applies the 8-wide filter to the lanes the decision kernel classified as flat."])
        load_rows(L, rows_for(8))
        L.append("        var lanes = SHORT256.cast(ShortVector.fromArray(S256, scratch, 0)).compare(VectorOperators.EQ, TWO);")
        outs = [
            ("p2", add("p3", "p3", "p3", twice("p2"), "p1", "p0", "q0", "FOUR")),
            ("p1", add("p3", "p3", "p2", twice("p1"), "p0", "q0", "q1", "FOUR")),
            ("p0", add("p3", "p2", "p1", twice("p0"), "q0", "q1", "q2", "FOUR")),
            ("q0", add("p2", "p1", "p0", twice("q0"), "q1", "q2", "q3", "FOUR")),
            ("q1", add("p1", "p0", "q0", twice("q1"), "q2", "q3", "q3", "FOUR")),
            ("q2", add("p0", "q0", "q1", twice("q2"), "q3", "q3", "q3", "FOUR")),
        ]
    if kind in (6, 8):
        for (r, expr) in outs:
            L.append("        var n%s = SHORT256.cast(%s).lanewise(VectorOperators.ASHR, 3);" % (r, expr))
        for (r, _) in outs:
            L.append("        %s.blend(n%s, lanes).intoArray(dst, %s);" % (r, r, off_expr(r)))
        L.append("    }")
        return "\n".join(L)
    header(L, "flat16Apply", params, ["Applies the 16-wide filter to the lanes the decision kernel classified as flat over 16",
                                      "samples, updating a running sum by two taps in and two out per output."])
    load_rows(L, rows_for(16))
    L.append("        var lanes = SHORT256.cast(ShortVector.fromArray(S256, scratch, 0)).compare(VectorOperators.EQ, THREE);")
    L.append("        var s0 = p6.lanewise(VectorOperators.MUL, SEVEN).lanewise(VectorOperators.ADD, %s)" % twice("p5"))
    L.append("                .lanewise(VectorOperators.ADD, %s).lanewise(VectorOperators.ADD, p3)" % twice("p4"))
    L.append("                .lanewise(VectorOperators.ADD, p2).lanewise(VectorOperators.ADD, p1)")
    L.append("                .lanewise(VectorOperators.ADD, p0).lanewise(VectorOperators.ADD, q0)")
    L.append("                .lanewise(VectorOperators.ADD, EIGHT);")
    steps = [
        ("p5", None),
        ("p4", ("p6", "p6", "p3", "q1")),
        ("p3", ("p6", "p5", "p2", "q2")),
        ("p2", ("p6", "p4", "p1", "q3")),
        ("p1", ("p6", "p3", "p0", "q4")),
        ("p0", ("p6", "p2", "q0", "q5")),
        ("q0", ("p6", "p1", "q1", "q6")),
        ("q1", ("p5", "p0", "q2", "q6")),
        ("q2", ("p4", "q0", "q3", "q6")),
        ("q3", ("p3", "q1", "q4", "q6")),
        ("q4", ("p2", "q2", "q5", "q6")),
        ("q5", ("p1", "q3", "q6", "q6")),
    ]
    prev = "s0"
    for k, (r, delta) in enumerate(steps):
        cur = "s%d" % k
        if delta is not None:
            a, b, c, d = delta
            L.append("        var %s = %s.lanewise(VectorOperators.SUB, %s).lanewise(VectorOperators.SUB, %s)" % (cur, prev, a, b))
            L.append("                .lanewise(VectorOperators.ADD, %s).lanewise(VectorOperators.ADD, %s);" % (c, d))
        L.append("        %s.blend(SHORT256.cast(%s).lanewise(VectorOperators.ASHR, 4), lanes).intoArray(dst, %s);" % (r, cur, off_expr(r)))
        prev = cur
    L.append("    }")
    return "\n".join(L)

kernels = [k_narrow4(), k_decide(6), k_decide(8), k_decide(16), k_narrow_apply(), k_flat(6), k_flat(8), k_flat(16)]
with open(head, encoding='utf-8', newline='') as f:
    head_text = f.read().replace('\r\n', '\n')
write_java(out, head_text + "\n\n".join(kernels) + "\n}\n")
