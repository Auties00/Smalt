"""Generates VectorCdefDsp.java: the hand-written parts in the header literal below, followed by the
generated CDEF filter kernels.

Usage: gen_vcdef.py <output VectorCdefDsp.java>
"""
import sys

out = sys.argv[1]


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


def kernel(kind, lanes):
    I = "I256" if lanes == 8 else "I128"
    S = "S128" if lanes == 8 else "S64"
    IC = "INT256" if lanes == 8 else "INT128"
    SC = "SHORT128" if lanes == 8 else "SHORT64"
    name = {"ps": "primarySecondary", "p": "primary", "s": "secondary", "sp": "secondaryPart",
            "pf": "primaryFinish"}[kind] + str(lanes)
    params = []
    if kind in ("ps", "p", "pf"):
        params += ["int priStrength", "int priShift", "int tap0", "int tap1", "int po0", "int po1"]
    if kind in ("ps", "s", "sp"):
        params += ["int secStrength", "int secShift", "int so0", "int so1", "int so2", "int so3"]
    doc_params = {
        "priStrength": "the primary strength",
        "priShift": "the primary damping shift",
        "tap0": "the weight of the nearer primary taps",
        "tap1": "the weight of the farther primary taps",
        "po0": "the offset of the nearer primary taps in the padded copy",
        "po1": "the offset of the farther primary taps in the padded copy",
        "secStrength": "the secondary strength",
        "secShift": "the secondary damping shift",
        "so0": "the offset of the nearer secondary taps of the first secondary direction",
        "so1": "the offset of the farther secondary taps of the first secondary direction",
        "so2": "the offset of the nearer secondary taps of the second secondary direction",
        "so3": "the offset of the farther secondary taps of the second secondary direction",
    }
    def c(name):
        return "%s.cast(%s)" % (IC, name)

    L = []
    L.append("    /**")
    if kind == "sp":
        L.append("     * Computes the secondary contributions of a block %d samples wide and the bounds of its" % lanes)
        L.append("     * secondary taps, storing them per row at {@link #PARTIAL} in the scratch buffer.")
    elif kind == "pf":
        L.append("     * Applies the primary and secondary CDEF filter to a block %d samples wide, adding the" % lanes)
        L.append("     * primary contributions to the partial results {@link #secondaryPart%d} stored." % lanes)
    else:
        title = {"ps": "primary and secondary", "p": "primary", "s": "secondary"}[kind]
        L.append("     * Applies the %s CDEF filter to a block %d samples wide, one row per iteration." % (title, lanes))
    L.append("     *")
    L.append("     * @param dst    the plane holding the block")
    L.append("     * @param dstOff the index of the block's top-left sample")
    L.append("     * @param stride the row stride")
    L.append("     * @param tmp    the padded block copy")
    L.append("     * @param h      the block height")
    for p in params:
        n = p.split()[1]
        L.append("     * @param %s %s" % (n, doc_params[n]))
    L.append("     */")
    sig = "    private static void %s(short[] dst, int dstOff, int stride, short[] tmp, int h, %s) {" % (name, ", ".join(params))
    L.append(sig)
    L.append("        var zero = IntVector.zero(%s);" % I)
    L.append("        var unsigned = IntVector.broadcast(%s, 0xFFFF);" % I)
    L.append("        var eight = IntVector.broadcast(%s, 8);" % I)
    if kind in ("ps", "p", "pf"):
        L.append("        var priThreshold = IntVector.broadcast(%s, priStrength);" % I)
        L.append("        var near = IntVector.broadcast(%s, tap0);" % I)
        L.append("        var far = IntVector.broadcast(%s, tap1);" % I)
    if kind in ("ps", "s", "sp"):
        L.append("        var secThreshold = IntVector.broadcast(%s, secStrength);" % I)
    L.append("        for (var y = 0; y < h; y++) {")
    L.append("            var row = dstOff + y * stride;")
    L.append("            var t = ScalarCdefDsp.TMP_ORIGIN + y * CdefDsp.TMP_STRIDE;")
    L.append("            var px = %s.cast(%s.cast(ShortVector.fromArray(%s, dst, row)).convertShape(VectorOperators.S2I, %s, 0));" % (IC, SC, S, I))
    taps = []
    if kind in ("ps", "p", "pf"):
        taps += [("p0", "t + po0", "pri"), ("p1", "t - po0", "pri"), ("p2", "t + po1", "pri"), ("p3", "t - po1", "pri")]
    if kind in ("ps", "s", "sp"):
        taps += [("s0", "t + so0", "sec"), ("s1", "t - so0", "sec"), ("s2", "t + so2", "sec"), ("s3", "t - so2", "sec"),
                 ("s4", "t + so1", "sec"), ("s5", "t - so1", "sec"), ("s6", "t + so3", "sec"), ("s7", "t - so3", "sec")]
    for (n, off, which) in taps:
        L.append("            var %s = %s.cast(%s.cast(ShortVector.fromArray(%s, tmp, %s)).convertShape(VectorOperators.S2I, %s, 0));" % (n, IC, SC, S, off, I))
    for (n, off, which) in taps:
        thr = "priThreshold" if which == "pri" else "secThreshold"
        sh = "priShift" if which == "pri" else "secShift"
        L.append("            var d%s = %s.lanewise(VectorOperators.SUB, px);" % (n, n))
        L.append("            var a%s = %s.cast(d%s.lanewise(VectorOperators.ABS));" % (n, IC, n))
        L.append("            var g%s = %s.cast(d%s).lanewise(VectorOperators.ASHR, 31);" % (n, IC, n))
        L.append("            var c%s = a%s.lanewise(VectorOperators.MIN, %s.lanewise(VectorOperators.SUB, a%s.lanewise(VectorOperators.ASHR, %s))" % (n, n, c(thr), n, sh))
        L.append("                    .lanewise(VectorOperators.MAX, %s)).lanewise(VectorOperators.XOR, g%s).lanewise(VectorOperators.SUB, g%s);" % (c("zero"), n, n))
    terms = []
    if kind in ("ps", "p", "pf"):
        terms.append("cp0.lanewise(VectorOperators.ADD, cp1).lanewise(VectorOperators.MUL, %s)" % c("near"))
        terms.append("cp2.lanewise(VectorOperators.ADD, cp3).lanewise(VectorOperators.MUL, %s)" % c("far"))
    if kind in ("ps", "s", "sp"):
        terms.append("cs0.lanewise(VectorOperators.ADD, cs1).lanewise(VectorOperators.ADD, cs2).lanewise(VectorOperators.ADD, cs3)\n                    .lanewise(VectorOperators.LSHL, 1)")
        terms.append("cs4.lanewise(VectorOperators.ADD, cs5).lanewise(VectorOperators.ADD, cs6).lanewise(VectorOperators.ADD, cs7)")
    L.append("            var sum = " + terms[0])
    for term in terms[1:]:
        L.append("                    .lanewise(VectorOperators.ADD, " + term + ")")
    L[-1] = L[-1] + ";"
    names = [n for (n, _, _) in taps]
    if kind == "sp":
        L.append("            var part = PARTIAL + y * 24;")
        L.append("            var low = px" + "".join("\n                    .lanewise(VectorOperators.MIN, %s.lanewise(VectorOperators.AND, %s))" % (n, c("unsigned")) for n in names) + ";")
        L.append("            var high = px" + "".join("\n                    .lanewise(VectorOperators.MAX, %s)" % n for n in names) + ";")
        L.append("            %s.cast(sum.convertShape(VectorOperators.I2S, %s, 0)).intoArray(tmp, part);" % (SC, S))
        L.append("            %s.cast(low.convertShape(VectorOperators.I2S, %s, 0)).intoArray(tmp, part + 8);" % (SC, S))
        L.append("            %s.cast(high.convertShape(VectorOperators.I2S, %s, 0)).intoArray(tmp, part + 16);" % (SC, S))
        L.append("        }")
        L.append("    }")
        return "\n".join(L)
    if kind == "pf":
        L.append("            var part = PARTIAL + y * 24;")
        L.append("            sum = sum.lanewise(VectorOperators.ADD, %s.cast(%s.cast(ShortVector.fromArray(%s, tmp, part))" % (IC, SC, S))
        L.append("                    .convertShape(VectorOperators.S2I, %s, 0)));" % I)
    L.append("            var sign = %s.cast(sum).lanewise(VectorOperators.ASHR, 31);" % IC)
    L.append("            var value = px.lanewise(VectorOperators.ADD, %s.cast(sum.lanewise(VectorOperators.ADD, sign)" % IC)
    L.append("                    .lanewise(VectorOperators.ADD, %s)).lanewise(VectorOperators.ASHR, 4));" % c("eight"))
    if kind == "pf":
        L.append("            var low = %s.cast(%s.cast(ShortVector.fromArray(%s, tmp, part + 8))" % (IC, SC, S))
        L.append("                    .convertShape(VectorOperators.ZERO_EXTEND_S2I, %s, 0))" % I)
        L.append("                    " + "".join(".lanewise(VectorOperators.MIN, %s.lanewise(VectorOperators.AND, %s))" % (n, c("unsigned")) for n in names) + ";")
        L.append("            var high = %s.cast(%s.cast(ShortVector.fromArray(%s, tmp, part + 16))" % (IC, SC, S))
        L.append("                    .convertShape(VectorOperators.S2I, %s, 0))" % I)
        L.append("                    " + "".join(".lanewise(VectorOperators.MAX, %s)" % n for n in names) + ";")
        L.append("            value = value.lanewise(VectorOperators.MAX, low).lanewise(VectorOperators.MIN, high);")
    L.append("            %s.cast(value.convertShape(VectorOperators.I2S, %s, 0)).intoArray(dst, row);" % (SC, S))
    L.append("        }")
    L.append("    }")
    return "\n".join(L)

kernels = []
for kind in ("sp", "pf", "p", "s"):
    for lanes in (8, 4):
        kernels.append(kernel(kind, lanes))

header = '''package com.github.auties00.smalt.video.av1.internal;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements the CDEF kernels with the Vector API, producing output identical to
 * {@link ScalarCdefDsp}.
 *
 * <p>The filter processes a row of the block per iteration, 8 or 4 samples in 32-bit lanes, reading
 * every tap of the row from the padded block copy that {@link ScalarCdefDsp#padding} builds. The
 * combined primary and secondary filter runs in two passes, the secondary taps first, keeping each
 * kernel small enough for the JIT compiler. 32-bit
 * lanes keep the difference between a tap and the sample exact for the missing-sample marker,
 * whose constrained contribution is then zero, and the clamping range ignores the marker as the
 * scalar filter does: the lower bound takes the minimum of the taps read as unsigned 16-bit values,
 * the upper bound the maximum of the signed values. The padded copy of a block whose four edges
 * have neighbors is built with vector row moves, other blocks use {@link ScalarCdefDsp#padding}.
 * The direction search accumulates the partial sums of every direction class in registers: each
 * row of the block, its reverse and its pair sums are rotated by the class's first bin of the row
 * and split by lane masks into the bins below 8 and from 8 on; the costs are then evaluated by
 * {@link ScalarCdefDsp}. A vector carried around a loop, such as an accumulator, is cast to its
 * exact class wherever it receives a call, since the compiler types it with its abstract class
 * there.
 *
 * <p>Every Vector API operation goes through the per-shape methods, no method takes or returns a
 * vector, and every loaded or converted vector, and every vector stored or shifted by a scalar, is
 * first cast through the {@code static final} class of its species; see {@link VectorMcDsp}.
 *
 * <p>Generated from {@code tools/codegen/av1}; edit the template.
 *
 * @implNote This implementation follows the structure of dav1d's {@code cdef_filter_8x8} and
 *           {@code cdef_filter_4x8} x86 kernels ({@code src/x86/cdef16_avx2.asm}), which also
 *           filter a block row by row from a padded copy.
 */
public final class VectorCdefDsp extends CdefDsp {
    /**
     * The 8-lane int species of 8-wide blocks.
     */
    private static final VectorSpecies<Integer> I256 = IntVector.SPECIES_256;

    /**
     * The 4-lane int species of 4-wide blocks.
     */
    private static final VectorSpecies<Integer> I128 = IntVector.SPECIES_128;

    /**
     * The 8-lane short species of 8-wide rows.
     */
    private static final VectorSpecies<Short> S128 = ShortVector.SPECIES_128;

    /**
     * The 4-lane short species of 4-wide rows.
     */
    private static final VectorSpecies<Short> S64 = ShortVector.SPECIES_64;

    /**
     * The exact class of {@link #I256} vectors.
     */
    private static final Class<IntVector> INT256 = vectorClass(I256);

    /**
     * The exact class of {@link #I128} vectors.
     */
    private static final Class<IntVector> INT128 = vectorClass(I128);

    /**
     * The exact class of {@link #S128} vectors.
     */
    private static final Class<ShortVector> SHORT128 = vectorClass(S128);

    /**
     * The exact class of {@link #S64} vectors.
     */
    private static final Class<ShortVector> SHORT64 = vectorClass(S64);

    /**
     * The offset in the scratch buffer of the per-row partial results of the combined filter: the
     * secondary contributions, the lower and the upper bound of the secondary taps, 8 entries each
     * per row.
     */
    private static final int PARTIAL = 144;

    /**
     * The masks selecting, for a rotation by {@code o} lanes, the lanes from {@code o} on (entries
     * {@code 8 * o} to {@code 8 * o + 7}) and the lanes below {@code o} (entries {@code 64 + 8 * o}
     * on): -1 for a selected lane, 0 otherwise.
     */
    private static final int[] LOW_LANES = lowLanes();

    /**
     * The shuffles rotating 8 lanes up by 0 to 7 lanes: lane {@code j} of rotation {@code o} takes
     * lane {@code (j - o) & 7}.
     */
    private static final VectorShuffle<Integer>[] ROTATE = rotations(false);

    /**
     * The shuffles reversing 8 lanes and rotating them up by 0 to 7 lanes: lane {@code j} of
     * rotation {@code o} takes lane {@code 7 - ((j - o) & 7)}.
     */
    private static final VectorShuffle<Integer>[] REVERSE_ROTATE = rotations(true);

    /**
     * Gathers the even lanes into the first four lanes.
     */
    private static final VectorShuffle<Integer> EVENS = VectorShuffle.fromValues(I256, 0, 2, 4, 6, 0, 2, 4, 6);

    /**
     * Gathers the odd lanes into the first four lanes.
     */
    private static final VectorShuffle<Integer> ODDS = VectorShuffle.fromValues(I256, 1, 3, 5, 7, 1, 3, 5, 7);

    /**
     * Reverses the first four lanes and keeps the others.
     */
    private static final VectorShuffle<Integer> REVERSE_PAIRS = VectorShuffle.fromValues(I256, 3, 2, 1, 0, 4, 5, 6,
            7);

    /**
     * Selects the last four lanes.
     */
    private static final VectorMask<Integer> UPPER_HALF = VectorMask.fromLong(I256, 0xF0L);

    /**
     * Builds {@link #LOW_LANES}.
     *
     * @return the masks
     */
    private static int[] lowLanes() {
        var masks = new int[128];
        for (var o = 0; o < 8; o++) {
            for (var j = 0; j < 8; j++) {
                masks[8 * o + j] = j >= o ? -1 : 0;
                masks[64 + 8 * o + j] = j >= o ? 0 : -1;
            }
        }
        return masks;
    }

    /**
     * Builds {@link #ROTATE} or {@link #REVERSE_ROTATE}.
     *
     * @param reverse whether the shuffles reverse the lanes before rotating them
     * @return the 8 shuffles
     */
    @SuppressWarnings("unchecked")
    private static VectorShuffle<Integer>[] rotations(boolean reverse) {
        var shuffles = (VectorShuffle<Integer>[]) new VectorShuffle<?>[8];
        for (var o = 0; o < 8; o++) {
            var indices = new int[8];
            for (var j = 0; j < 8; j++) {
                indices[j] = reverse ? 7 - ((j - o) & 7) : (j - o) & 7;
            }
            shuffles[o] = VectorShuffle.fromArray(I256, indices, 0);
        }
        return shuffles;
    }

    /**
     * Constructs the kernels.
     */
    private VectorCdefDsp() {
    }

    /**
     * Creates the kernels.
     *
     * @return the vector kernels
     */
    static CdefDsp create() {
        return new VectorCdefDsp();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vectors are asked for
     * @param <E>     the element type
     * @param <V>     the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    /**
     * Builds the padded copy of an 8-wide block whose four edges have neighbors, as
     * {@link ScalarCdefDsp#padding} does: the two rows above from {@code top}, every block row from
     * {@code left} and the plane, and the two rows below from {@code bottom}, each row moved with
     * two overlapping vectors.
     *
     * @param tmp       the padded copy, {@link CdefDsp#TMP_STRIDE} entries per row
     * @param src       the plane
     * @param srcOff    the index of the block's top-left sample
     * @param stride    the plane's row stride, also the row stride of {@code top} and {@code bottom}
     * @param left      the two samples left of every block row
     * @param leftOff   the index of the first row's left samples
     * @param top       the rows above the block
     * @param topOff    the index of the sample above the block's first column, two rows up
     * @param bottom    the rows below the block
     * @param bottomOff the index of the sample below the block's first column
     * @param h         the block height, 4 or 8
     */
    private static void padding8(short[] tmp, short[] src, int srcOff, int stride, short[] left, int leftOff,
                                 short[] top, int topOff, short[] bottom, int bottomOff, int h) {
        var origin = ScalarCdefDsp.TMP_ORIGIN;
        for (var y = 0; y < 2; y++) {
            var from = topOff + y * stride - 2;
            var to = origin + (y - 2) * TMP_STRIDE - 2;
            SHORT128.cast(ShortVector.fromArray(S128, top, from)).intoArray(tmp, to);
            SHORT64.cast(ShortVector.fromArray(S64, top, from + 8)).intoArray(tmp, to + 8);
        }
        for (var y = 0; y < h; y++) {
            var from = srcOff + y * stride;
            var to = origin + y * TMP_STRIDE;
            SHORT128.cast(ShortVector.fromArray(S128, src, from)).intoArray(tmp, to);
            SHORT64.cast(ShortVector.fromArray(S64, src, from + 6)).intoArray(tmp, to + 6);
            tmp[to - 2] = left[leftOff + 2 * y];
            tmp[to - 1] = left[leftOff + 2 * y + 1];
        }
        for (var y = 0; y < 2; y++) {
            var from = bottomOff + y * stride - 2;
            var to = origin + (h + y) * TMP_STRIDE - 2;
            SHORT128.cast(ShortVector.fromArray(S128, bottom, from)).intoArray(tmp, to);
            SHORT64.cast(ShortVector.fromArray(S64, bottom, from + 8)).intoArray(tmp, to + 8);
        }
    }

    /**
     * Builds the padded copy of a 4-wide block whose four edges have neighbors, as
     * {@link #padding8} does for 8-wide blocks.
     *
     * @param tmp       the padded copy, {@link CdefDsp#TMP_STRIDE} entries per row
     * @param src       the plane
     * @param srcOff    the index of the block's top-left sample
     * @param stride    the plane's row stride, also the row stride of {@code top} and {@code bottom}
     * @param left      the two samples left of every block row
     * @param leftOff   the index of the first row's left samples
     * @param top       the rows above the block
     * @param topOff    the index of the sample above the block's first column, two rows up
     * @param bottom    the rows below the block
     * @param bottomOff the index of the sample below the block's first column
     * @param h         the block height, 4 or 8
     */
    private static void padding4(short[] tmp, short[] src, int srcOff, int stride, short[] left, int leftOff,
                                 short[] top, int topOff, short[] bottom, int bottomOff, int h) {
        var origin = ScalarCdefDsp.TMP_ORIGIN;
        for (var y = 0; y < 2; y++) {
            SHORT128.cast(ShortVector.fromArray(S128, top, topOff + y * stride - 2))
                    .intoArray(tmp, origin + (y - 2) * TMP_STRIDE - 2);
        }
        for (var y = 0; y < h; y++) {
            var from = srcOff + y * stride;
            var to = origin + y * TMP_STRIDE;
            SHORT64.cast(ShortVector.fromArray(S64, src, from)).intoArray(tmp, to);
            SHORT64.cast(ShortVector.fromArray(S64, src, from + 2)).intoArray(tmp, to + 2);
            tmp[to - 2] = left[leftOff + 2 * y];
            tmp[to - 1] = left[leftOff + 2 * y + 1];
        }
        for (var y = 0; y < 2; y++) {
            SHORT128.cast(ShortVector.fromArray(S128, bottom, bottomOff + y * stride - 2))
                    .intoArray(tmp, origin + (h + y) * TMP_STRIDE - 2);
        }
    }

    @Override
    public int dir(short[] src, int srcOff, int stride, int[] variance, int bitdepthMax) {
        var shift = 32 - Integer.numberOfLeadingZeros(bitdepthMax) - 8;
        dirDiagonals(src, srcOff, stride, variance, shift);
        dirAlternates(src, srcOff, stride, variance, shift);
        return ScalarCdefDsp.finish(variance);
    }

    /**
     * Accumulates the two diagonal and the first two alternate partial sums of the direction
     * search, whose bins advance by one per row: each row of the block, scaled to 8 bits and
     * centered on 0, is rotated by its row index (reversed for the second class of each pair) and
     * split into the bins below and from 8 on.
     *
     * @param src      the plane
     * @param srcOff   the index of the block's top-left sample
     * @param stride   the plane's row stride
     * @param variance the direction search scratch, receiving the partial sums
     * @param shift    the bit depth minus 8
     */
    private static void dirDiagonals(short[] src, int srcOff, int stride, int[] variance, int shift) {
        var center = IntVector.broadcast(I256, 128);
        var zero = IntVector.zero(I256);
        var diagLow = IntVector.zero(I256);
        var diagHigh = IntVector.zero(I256);
        var antiLow = IntVector.zero(I256);
        var antiHigh = IntVector.zero(I256);
        var altLow = IntVector.zero(I256);
        var altHigh = IntVector.zero(I256);
        var revLow = IntVector.zero(I256);
        var revHigh = IntVector.zero(I256);
        for (var y = 0; y < 8; y++) {
            var px = INT256.cast(INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, srcOff + y * stride))
                    .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.ASHR, shift)
                    .lanewise(VectorOperators.SUB, INT256.cast(center)));
            var pairs = INT256.cast(INT256.cast(px.rearrange(EVENS)).lanewise(VectorOperators.ADD,
                    INT256.cast(px.rearrange(ODDS))).blend(INT256.cast(zero), UPPER_HALF));
            var low = INT256.cast(IntVector.fromArray(I256, LOW_LANES, 8 * y));
            var high = INT256.cast(IntVector.fromArray(I256, LOW_LANES, 64 + 8 * y));
            var rotate = ROTATE[y];
            var reverse = REVERSE_ROTATE[y];
            var diag = INT256.cast(px.rearrange(rotate));
            var anti = INT256.cast(px.rearrange(reverse));
            var alt = INT256.cast(pairs.rearrange(rotate));
            var rev = INT256.cast(INT256.cast(pairs.rearrange(REVERSE_PAIRS)).rearrange(rotate));
            diagLow = INT256.cast(diagLow).lanewise(VectorOperators.ADD, diag.lanewise(VectorOperators.AND, low));
            diagHigh = INT256.cast(diagHigh).lanewise(VectorOperators.ADD, diag.lanewise(VectorOperators.AND, high));
            antiLow = INT256.cast(antiLow).lanewise(VectorOperators.ADD, anti.lanewise(VectorOperators.AND, low));
            antiHigh = INT256.cast(antiHigh).lanewise(VectorOperators.ADD, anti.lanewise(VectorOperators.AND, high));
            altLow = INT256.cast(altLow).lanewise(VectorOperators.ADD, alt.lanewise(VectorOperators.AND, low));
            altHigh = INT256.cast(altHigh).lanewise(VectorOperators.ADD, alt.lanewise(VectorOperators.AND, high));
            revLow = INT256.cast(revLow).lanewise(VectorOperators.ADD, rev.lanewise(VectorOperators.AND, low));
            revHigh = INT256.cast(revHigh).lanewise(VectorOperators.ADD, rev.lanewise(VectorOperators.AND, high));
        }
        INT256.cast(diagLow).intoArray(variance, 1 + ScalarCdefDsp.DIAG);
        INT256.cast(diagHigh).intoArray(variance, 1 + ScalarCdefDsp.DIAG + 8);
        INT256.cast(antiLow).intoArray(variance, 1 + ScalarCdefDsp.DIAG + 15);
        INT256.cast(antiHigh).intoArray(variance, 1 + ScalarCdefDsp.DIAG + 23);
        INT256.cast(altLow).intoArray(variance, 1 + ScalarCdefDsp.ALT);
        INT256.cast(altHigh).intoArray(variance, 1 + ScalarCdefDsp.ALT + 8);
        INT256.cast(revLow).intoArray(variance, 1 + ScalarCdefDsp.ALT + 11);
        INT256.cast(revHigh).intoArray(variance, 1 + ScalarCdefDsp.ALT + 19);
    }

    /**
     * Accumulates the last two alternate partial sums of the direction search, whose bins move by
     * one every two rows, and the horizontal and vertical partial sums: each row is rotated by its
     * first bin and split into the bins below and from 8 on, its sum is a horizontal partial sum and
     * the column sums are the vertical ones. Runs after {@link #dirDiagonals}, whose last stores
     * spill into the bins this method stores.
     *
     * @param src      the plane
     * @param srcOff   the index of the block's top-left sample
     * @param stride   the plane's row stride
     * @param variance the direction search scratch, receiving the partial sums
     * @param shift    the bit depth minus 8
     */
    private static void dirAlternates(short[] src, int srcOff, int stride, int[] variance, int shift) {
        var center = IntVector.broadcast(I256, 128);
        var columns = IntVector.zero(I256);
        var downLow = IntVector.zero(I256);
        var downHigh = IntVector.zero(I256);
        var upLow = IntVector.zero(I256);
        var upHigh = IntVector.zero(I256);
        for (var y = 0; y < 8; y++) {
            var px = INT256.cast(INT256.cast(SHORT128.cast(ShortVector.fromArray(S128, src, srcOff + y * stride))
                    .convertShape(VectorOperators.S2I, I256, 0)).lanewise(VectorOperators.ASHR, shift)
                    .lanewise(VectorOperators.SUB, INT256.cast(center)));
            columns = INT256.cast(columns).lanewise(VectorOperators.ADD, px);
            variance[1 + ScalarCdefDsp.HV + y] = px.reduceLanes(VectorOperators.ADD);
            var down = 3 - (y >> 1);
            var up = y >> 1;
            var downRotated = INT256.cast(px.rearrange(ROTATE[down]));
            var upRotated = INT256.cast(px.rearrange(ROTATE[up]));
            var downLowLanes = INT256.cast(IntVector.fromArray(I256, LOW_LANES, 8 * down));
            var downHighLanes = INT256.cast(IntVector.fromArray(I256, LOW_LANES, 64 + 8 * down));
            var upLowLanes = INT256.cast(IntVector.fromArray(I256, LOW_LANES, 8 * up));
            var upHighLanes = INT256.cast(IntVector.fromArray(I256, LOW_LANES, 64 + 8 * up));
            downLow = INT256.cast(downLow).lanewise(VectorOperators.ADD,
                    downRotated.lanewise(VectorOperators.AND, downLowLanes));
            downHigh = INT256.cast(downHigh).lanewise(VectorOperators.ADD,
                    downRotated.lanewise(VectorOperators.AND, downHighLanes));
            upLow = INT256.cast(upLow).lanewise(VectorOperators.ADD,
                    upRotated.lanewise(VectorOperators.AND, upLowLanes));
            upHigh = INT256.cast(upHigh).lanewise(VectorOperators.ADD,
                    upRotated.lanewise(VectorOperators.AND, upHighLanes));
        }
        INT256.cast(columns).intoArray(variance, 1 + ScalarCdefDsp.HV + 8);
        INT256.cast(downLow).intoArray(variance, 1 + ScalarCdefDsp.ALT + 22);
        INT256.cast(downHigh).intoArray(variance, 1 + ScalarCdefDsp.ALT + 30);
        INT256.cast(upLow).intoArray(variance, 1 + ScalarCdefDsp.ALT + 33);
        INT256.cast(upHigh).intoArray(variance, 1 + ScalarCdefDsp.ALT + 41);
    }

    @Override
    public void filter(int layoutIndex, short[] dst, int dstOff, int stride, short[] left, int leftOff,
                       short[] top, int topOff, short[] bottom, int bottomOff, int priStrength,
                       int secStrength, int dir, int damping, int edges, short[] scratch, int bitdepthMax) {
        var w = layoutIndex == LAYOUT_8X8 ? 8 : 4;
        var h = layoutIndex == LAYOUT_4X4 ? 4 : 8;
        if (edges == (HAVE_LEFT | HAVE_RIGHT | HAVE_TOP | HAVE_BOTTOM)) {
            if (w == 8) {
                padding8(scratch, dst, dstOff, stride, left, leftOff, top, topOff, bottom, bottomOff, h);
            } else {
                padding4(scratch, dst, dstOff, stride, left, leftOff, top, topOff, bottom, bottomOff, h);
            }
        } else {
            ScalarCdefDsp.padding(scratch, dst, dstOff, stride, left, leftOff, top, topOff, bottom, bottomOff, w,
                    h, edges);
        }
        var dirs = (dir + 2) * 2;
        var dirsPlus = (dir + 4) * 2;
        var dirsMinus = dir * 2;
        if (priStrength != 0) {
            var bitdepthMin8 = 32 - Integer.numberOfLeadingZeros(bitdepthMax) - 8;
            var priTap = 4 - ((priStrength >> bitdepthMin8) & 1);
            var priShift = Math.max(0, damping - ScalarCdefDsp.ulog2(priStrength));
            var farTap = (priTap & 3) | 2;
            if (secStrength != 0) {
                var secShift = damping - ScalarCdefDsp.ulog2(secStrength);
                if (w == 8) {
                    secondaryPart8(dst, dstOff, stride, scratch, h, secStrength, secShift, DIRECTIONS[dirsPlus],
                            DIRECTIONS[dirsPlus + 1], DIRECTIONS[dirsMinus], DIRECTIONS[dirsMinus + 1]);
                    primaryFinish8(dst, dstOff, stride, scratch, h, priStrength, priShift, priTap, farTap,
                            DIRECTIONS[dirs], DIRECTIONS[dirs + 1]);
                } else {
                    secondaryPart4(dst, dstOff, stride, scratch, h, secStrength, secShift, DIRECTIONS[dirsPlus],
                            DIRECTIONS[dirsPlus + 1], DIRECTIONS[dirsMinus], DIRECTIONS[dirsMinus + 1]);
                    primaryFinish4(dst, dstOff, stride, scratch, h, priStrength, priShift, priTap, farTap,
                            DIRECTIONS[dirs], DIRECTIONS[dirs + 1]);
                }
            } else if (w == 8) {
                primary8(dst, dstOff, stride, scratch, h, priStrength, priShift, priTap, farTap, DIRECTIONS[dirs],
                        DIRECTIONS[dirs + 1]);
            } else {
                primary4(dst, dstOff, stride, scratch, h, priStrength, priShift, priTap, farTap, DIRECTIONS[dirs],
                        DIRECTIONS[dirs + 1]);
            }
        } else {
            var secShift = damping - ScalarCdefDsp.ulog2(secStrength);
            if (w == 8) {
                secondary8(dst, dstOff, stride, scratch, h, secStrength, secShift, DIRECTIONS[dirsPlus],
                        DIRECTIONS[dirsPlus + 1], DIRECTIONS[dirsMinus], DIRECTIONS[dirsMinus + 1]);
            } else {
                secondary4(dst, dstOff, stride, scratch, h, secStrength, secShift, DIRECTIONS[dirsPlus],
                        DIRECTIONS[dirsPlus + 1], DIRECTIONS[dirsMinus], DIRECTIONS[dirsMinus + 1]);
            }
        }
    }

'''
write_java(out, header + "\n\n".join(kernels) + "\n}\n")
