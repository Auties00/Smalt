package com.github.auties00.smalt.audio.amr.internal;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link AmrKernel} with the Vector API; the output equals {@link ScalarAmrKernel} value for value.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd}. It
 * uses 256-bit vectors when the platform prefers at least that width and 128-bit vectors otherwise, and finishes the
 * remainder of a run with the scalar loop.
 */
final class VectorAmrKernel implements AmrKernel {
    /**
     * The int vector shape.
     */
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? IntVector.SPECIES_256 : IntVector.SPECIES_128;

    /**
     * The short vector shape with as many lanes as {@link #INTS}.
     */
    private static final VectorSpecies<Short> SHORTS = INTS.vectorBitSize() == 256
            ? ShortVector.SPECIES_128 : ShortVector.SPECIES_64;

    /**
     * The number of lanes.
     */
    private static final int LANES = INTS.length();

    /**
     * The exact class of the vectors of {@link #INTS}.
     */
    @SuppressWarnings("unchecked")
    private static final Class<IntVector> INT_VECTOR = (Class<IntVector>) INTS.vectorType();

    /**
     * The exact class of the vectors of {@link #SHORTS}.
     */
    @SuppressWarnings("unchecked")
    private static final Class<ShortVector> SHORT_VECTOR = (Class<ShortVector>) SHORTS.vectorType();

    /**
     * The scalar reference used for loop remainders.
     */
    private final ScalarAmrKernel scalar = new ScalarAmrKernel();

    /**
     * Creates the kernel.
     */
    private VectorAmrKernel() {
    }

    /**
     * Returns a new kernel; {@link AmrKernels} calls it only when {@link com.github.auties00.smalt.internal.simd.Simd}
     * enables the Vector API.
     *
     * @return the kernel
     */
    static AmrKernel create() {
        return new VectorAmrKernel();
    }

    @Override
    public void fir(short[] x, int xOffset, int[] c, int taps, int count, int[] out, int outOffset) {
        var n = 0;
        for (; n + 4 * LANES <= count; n += 4 * LANES) {
            forward4(x, xOffset + n, c, taps, out, outOffset + n);
        }
        for (; n + 2 * LANES <= count; n += 2 * LANES) {
            forward2(x, xOffset + n, c, taps, out, outOffset + n);
        }
        for (; n + LANES <= count; n += LANES) {
            forward1(x, xOffset + n, c, taps, out, outOffset + n);
        }
        if (n < count) {
            scalar.fir(x, xOffset + n, c, taps, count - n, out, outOffset + n);
        }
    }

    /**
     * Computes 4 consecutive blocks of {@link #LANES} outputs of a forward filter in one pass over the taps.
     *
     * @param x the signal
     * @param at the index of the first sample of the first output
     * @param c the coefficients
     * @param taps the number of coefficients
     * @param out receives the outputs
     * @param index the index of the first output
     */
    private static void forward4(short[] x, int at, int[] c, int taps, int[] out, int index) {
        var sum0 = IntVector.zero(INTS);
        var sum1 = IntVector.zero(INTS);
        var sum2 = IntVector.zero(INTS);
        var sum3 = IntVector.zero(INTS);
        for (var i = 0; i < taps; i++) {
            var coefficient = INT_VECTOR.cast(IntVector.broadcast(INTS, c[i]));
            var sample0 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at + i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum0 = INT_VECTOR.cast(sum0).lanewise(VectorOperators.ADD,
                    sample0.lanewise(VectorOperators.MUL, coefficient));
            var sample1 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at + 1 * LANES + i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum1 = INT_VECTOR.cast(sum1).lanewise(VectorOperators.ADD,
                    sample1.lanewise(VectorOperators.MUL, coefficient));
            var sample2 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at + 2 * LANES + i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum2 = INT_VECTOR.cast(sum2).lanewise(VectorOperators.ADD,
                    sample2.lanewise(VectorOperators.MUL, coefficient));
            var sample3 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at + 3 * LANES + i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum3 = INT_VECTOR.cast(sum3).lanewise(VectorOperators.ADD,
                    sample3.lanewise(VectorOperators.MUL, coefficient));
        }
        INT_VECTOR.cast(sum0).intoArray(out, index);
        INT_VECTOR.cast(sum1).intoArray(out, index + 1 * LANES);
        INT_VECTOR.cast(sum2).intoArray(out, index + 2 * LANES);
        INT_VECTOR.cast(sum3).intoArray(out, index + 3 * LANES);
    }

    /**
     * Computes 2 consecutive blocks of {@link #LANES} outputs of a forward filter in one pass over the taps.
     *
     * @param x the signal
     * @param at the index of the first sample of the first output
     * @param c the coefficients
     * @param taps the number of coefficients
     * @param out receives the outputs
     * @param index the index of the first output
     */
    private static void forward2(short[] x, int at, int[] c, int taps, int[] out, int index) {
        var sum0 = IntVector.zero(INTS);
        var sum1 = IntVector.zero(INTS);
        for (var i = 0; i < taps; i++) {
            var coefficient = INT_VECTOR.cast(IntVector.broadcast(INTS, c[i]));
            var sample0 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at + i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum0 = INT_VECTOR.cast(sum0).lanewise(VectorOperators.ADD,
                    sample0.lanewise(VectorOperators.MUL, coefficient));
            var sample1 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at + 1 * LANES + i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum1 = INT_VECTOR.cast(sum1).lanewise(VectorOperators.ADD,
                    sample1.lanewise(VectorOperators.MUL, coefficient));
        }
        INT_VECTOR.cast(sum0).intoArray(out, index);
        INT_VECTOR.cast(sum1).intoArray(out, index + 1 * LANES);
    }

    /**
     * Computes 1 consecutive blocks of {@link #LANES} outputs of a forward filter in one pass over the taps.
     *
     * @param x the signal
     * @param at the index of the first sample of the first output
     * @param c the coefficients
     * @param taps the number of coefficients
     * @param out receives the outputs
     * @param index the index of the first output
     */
    private static void forward1(short[] x, int at, int[] c, int taps, int[] out, int index) {
        var sum0 = IntVector.zero(INTS);
        for (var i = 0; i < taps; i++) {
            var coefficient = INT_VECTOR.cast(IntVector.broadcast(INTS, c[i]));
            var sample0 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at + i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum0 = INT_VECTOR.cast(sum0).lanewise(VectorOperators.ADD,
                    sample0.lanewise(VectorOperators.MUL, coefficient));
        }
        INT_VECTOR.cast(sum0).intoArray(out, index);
    }

    @Override
    public void firReverse(short[] x, int xOffset, int[] c, int taps, int count, int[] out, int outOffset) {
        var n = 0;
        for (; n + 4 * LANES <= count; n += 4 * LANES) {
            backward4(x, xOffset + n, c, taps, out, outOffset + n);
        }
        for (; n + 2 * LANES <= count; n += 2 * LANES) {
            backward2(x, xOffset + n, c, taps, out, outOffset + n);
        }
        for (; n + LANES <= count; n += LANES) {
            backward1(x, xOffset + n, c, taps, out, outOffset + n);
        }
        if (n < count) {
            scalar.firReverse(x, xOffset + n, c, taps, count - n, out, outOffset + n);
        }
    }

    /**
     * Computes 4 consecutive blocks of {@link #LANES} outputs of a backward filter in one pass over the taps.
     *
     * @param x the signal
     * @param at the index of the newest sample of the first output
     * @param c the coefficients
     * @param taps the number of coefficients
     * @param out receives the outputs
     * @param index the index of the first output
     */
    private static void backward4(short[] x, int at, int[] c, int taps, int[] out, int index) {
        var sum0 = IntVector.zero(INTS);
        var sum1 = IntVector.zero(INTS);
        var sum2 = IntVector.zero(INTS);
        var sum3 = IntVector.zero(INTS);
        for (var i = 0; i < taps; i++) {
            var coefficient = INT_VECTOR.cast(IntVector.broadcast(INTS, c[i]));
            var sample0 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at - i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum0 = INT_VECTOR.cast(sum0).lanewise(VectorOperators.ADD,
                    sample0.lanewise(VectorOperators.MUL, coefficient));
            var sample1 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at + 1 * LANES - i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum1 = INT_VECTOR.cast(sum1).lanewise(VectorOperators.ADD,
                    sample1.lanewise(VectorOperators.MUL, coefficient));
            var sample2 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at + 2 * LANES - i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum2 = INT_VECTOR.cast(sum2).lanewise(VectorOperators.ADD,
                    sample2.lanewise(VectorOperators.MUL, coefficient));
            var sample3 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at + 3 * LANES - i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum3 = INT_VECTOR.cast(sum3).lanewise(VectorOperators.ADD,
                    sample3.lanewise(VectorOperators.MUL, coefficient));
        }
        INT_VECTOR.cast(sum0).intoArray(out, index);
        INT_VECTOR.cast(sum1).intoArray(out, index + 1 * LANES);
        INT_VECTOR.cast(sum2).intoArray(out, index + 2 * LANES);
        INT_VECTOR.cast(sum3).intoArray(out, index + 3 * LANES);
    }

    /**
     * Computes 2 consecutive blocks of {@link #LANES} outputs of a backward filter in one pass over the taps.
     *
     * @param x the signal
     * @param at the index of the newest sample of the first output
     * @param c the coefficients
     * @param taps the number of coefficients
     * @param out receives the outputs
     * @param index the index of the first output
     */
    private static void backward2(short[] x, int at, int[] c, int taps, int[] out, int index) {
        var sum0 = IntVector.zero(INTS);
        var sum1 = IntVector.zero(INTS);
        for (var i = 0; i < taps; i++) {
            var coefficient = INT_VECTOR.cast(IntVector.broadcast(INTS, c[i]));
            var sample0 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at - i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum0 = INT_VECTOR.cast(sum0).lanewise(VectorOperators.ADD,
                    sample0.lanewise(VectorOperators.MUL, coefficient));
            var sample1 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at + 1 * LANES - i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum1 = INT_VECTOR.cast(sum1).lanewise(VectorOperators.ADD,
                    sample1.lanewise(VectorOperators.MUL, coefficient));
        }
        INT_VECTOR.cast(sum0).intoArray(out, index);
        INT_VECTOR.cast(sum1).intoArray(out, index + 1 * LANES);
    }

    /**
     * Computes 1 consecutive blocks of {@link #LANES} outputs of a backward filter in one pass over the taps.
     *
     * @param x the signal
     * @param at the index of the newest sample of the first output
     * @param c the coefficients
     * @param taps the number of coefficients
     * @param out receives the outputs
     * @param index the index of the first output
     */
    private static void backward1(short[] x, int at, int[] c, int taps, int[] out, int index) {
        var sum0 = IntVector.zero(INTS);
        for (var i = 0; i < taps; i++) {
            var coefficient = INT_VECTOR.cast(IntVector.broadcast(INTS, c[i]));
            var sample0 = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, x, at - i))
                    .convertShape(VectorOperators.S2I, INTS, 0));
            sum0 = INT_VECTOR.cast(sum0).lanewise(VectorOperators.ADD,
                    sample0.lanewise(VectorOperators.MUL, coefficient));
        }
        INT_VECTOR.cast(sum0).intoArray(out, index);
    }

    @Override
    public void pairSearch(int kind, short[] block, int[] alp1, short[] psj, short[] ta, short[] tb, int[] out) {
        for (var group = 0; group < 8; group += LANES) {
            if (kind == 0) {
                firstStage(block, alp1, psj, ta, tb, out, group);
            } else {
                laterStage(block, alp1, psj, ta, tb, kind == 1 ? 12 : kind == 2 ? 11 : 10, kind == 3 ? 13 : 14, out, group);
            }
        }
    }

    /**
     * Scores the candidates of the columns {@code group} to {@code group + LANES - 1} for stage 0.
     *
     * @param block the correlations of the candidates
     * @param alp1 the energy of each row
     * @param psj the correlation of the first pulse of each row
     * @param ta the correlation of the second pulse of each column
     * @param tb the energy term of each column
     * @param out receives the result
     * @param group the first column
     */
    private static void firstStage(short[] block, int[] alp1, short[] psj, short[] ta, short[] tb, int[] out, int group) {
        var taV = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, ta, group))
                .convertShape(VectorOperators.S2I, INTS, 0));
        var tbV = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, tb, group))
                .convertShape(VectorOperators.S2I, INTS, 0));
        var sqk = IntVector.broadcast(INTS, -1);
        var alpk = IntVector.broadcast(INTS, 1);
        var jk = IntVector.zero(INTS);
        var bad = IntVector.zero(INTS);
        for (var j = 0; j < 8; j++) {
            var rr = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, block, 8 * j + group))
                .convertShape(VectorOperators.S2I, INTS, 0));
            var base = INT_VECTOR.cast(IntVector.broadcast(INTS, alp1[j]));
            var psJ = INT_VECTOR.cast(IntVector.broadcast(INTS, psj[j]));
            var sum = psJ.lanewise(VectorOperators.ADD, taV);
            var ps2 = INT_VECTOR.cast(sum.lanewise(VectorOperators.LSHL, 16)).lanewise(VectorOperators.ASHR, 16);
            var square = INT_VECTOR.cast(ps2.lanewise(VectorOperators.MUL, ps2)).lanewise(VectorOperators.ASHR, 15);
            var sq = INT_VECTOR.cast(square.lanewise(VectorOperators.LSHL, 16)).lanewise(VectorOperators.ASHR, 16);
            var raw = INT_VECTOR.cast(INT_VECTOR.cast(base.lanewise(VectorOperators.ADD, rr)).lanewise(VectorOperators.ASHR, 2)
                    .lanewise(VectorOperators.ADD, tbV)).lanewise(VectorOperators.ASHR, 1);
            var low = INT_VECTOR.cast(raw.lanewise(VectorOperators.SUB, IntVector.broadcast(INTS, 1))).lanewise(VectorOperators.LSHR, 31);
            var high = INT_VECTOR.cast(IntVector.broadcast(INTS, 32767).lanewise(VectorOperators.SUB, raw)).lanewise(VectorOperators.LSHR, 31);
            bad = INT_VECTOR.cast(bad).lanewise(VectorOperators.OR, low.lanewise(VectorOperators.OR, high));
            var left = sq.lanewise(VectorOperators.MUL, INT_VECTOR.cast(alpk));
            var right = INT_VECTOR.cast(sqk).lanewise(VectorOperators.MUL, raw);
            var better = left.compare(VectorOperators.GT, right);
            sqk = INT_VECTOR.cast(sqk).blend(sq, better);
            alpk = INT_VECTOR.cast(alpk).blend(raw, better);
            jk = INT_VECTOR.cast(jk).blend(IntVector.broadcast(INTS, j), better);
        }
        INT_VECTOR.cast(sqk).intoArray(out, group);
        INT_VECTOR.cast(alpk).intoArray(out, 8 + group);
        INT_VECTOR.cast(jk).intoArray(out, 16 + group);
        INT_VECTOR.cast(bad).intoArray(out, 24 + group);
    }

    /**
     * Scores the candidates of the columns {@code group} to {@code group + LANES - 1} for stages 1
     * to 3.
     *
     * @param block the correlations of the candidates
     * @param alp1 the energy of each row
     * @param psj the correlation of the first pulse of each row
     * @param ta the correlation of the second pulse of each column
     * @param tb the energy term of each column
     * @param rrShift the left shift of the correlations
     * @param tbShift the left shift of the energy terms
     * @param out receives the result
     * @param group the first column
     */
    private static void laterStage(short[] block, int[] alp1, short[] psj, short[] ta, short[] tb, int rrShift,
                                   int tbShift, int[] out, int group) {
        var taV = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, ta, group))
                .convertShape(VectorOperators.S2I, INTS, 0));
        var tbV = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, tb, group))
                .convertShape(VectorOperators.S2I, INTS, 0));
        var tail = INT_VECTOR.cast(tbV.lanewise(VectorOperators.LSHL, tbShift));
        var sqk = IntVector.broadcast(INTS, -1);
        var alpk = IntVector.broadcast(INTS, 1);
        var jk = IntVector.zero(INTS);
        var bad = IntVector.zero(INTS);
        for (var j = 0; j < 8; j++) {
            var rr = INT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, block, 8 * j + group))
                .convertShape(VectorOperators.S2I, INTS, 0));
            var base = INT_VECTOR.cast(IntVector.broadcast(INTS, alp1[j]));
            var psJ = INT_VECTOR.cast(IntVector.broadcast(INTS, psj[j]));
            var sum = psJ.lanewise(VectorOperators.ADD, taV);
            var ps2 = INT_VECTOR.cast(sum.lanewise(VectorOperators.LSHL, 16)).lanewise(VectorOperators.ASHR, 16);
            var square = INT_VECTOR.cast(ps2.lanewise(VectorOperators.MUL, ps2)).lanewise(VectorOperators.ASHR, 15);
            var sq = INT_VECTOR.cast(square.lanewise(VectorOperators.LSHL, 16)).lanewise(VectorOperators.ASHR, 16);
            var wide = INT_VECTOR.cast(base.lanewise(VectorOperators.ADD, INT_VECTOR.cast(rr.lanewise(VectorOperators.LSHL, rrShift))))
                    .lanewise(VectorOperators.ADD, tail);
            var alp = INT_VECTOR.cast(wide).lanewise(VectorOperators.ASHR, 16);
            var low = INT_VECTOR.cast(alp.lanewise(VectorOperators.SUB, IntVector.broadcast(INTS, 1))).lanewise(VectorOperators.LSHR, 31);
            bad = INT_VECTOR.cast(bad).lanewise(VectorOperators.OR, low);
            var left = sq.lanewise(VectorOperators.MUL, INT_VECTOR.cast(alpk));
            var right = INT_VECTOR.cast(sqk).lanewise(VectorOperators.MUL, alp);
            var better = left.compare(VectorOperators.GT, right);
            sqk = INT_VECTOR.cast(sqk).blend(sq, better);
            alpk = INT_VECTOR.cast(alpk).blend(alp, better);
            jk = INT_VECTOR.cast(jk).blend(IntVector.broadcast(INTS, j), better);
        }
        INT_VECTOR.cast(sqk).intoArray(out, group);
        INT_VECTOR.cast(alpk).intoArray(out, 8 + group);
        INT_VECTOR.cast(jk).intoArray(out, 16 + group);
        INT_VECTOR.cast(bad).intoArray(out, 24 + group);
    }
}
