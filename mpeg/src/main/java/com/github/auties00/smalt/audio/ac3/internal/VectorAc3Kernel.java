package com.github.auties00.smalt.audio.ac3.internal;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShuffle;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link Ac3Kernel} with the Vector API.
 *
 * <p>Every loop processes whole vectors and finishes the remainder with the scalar reference, and
 * evaluates the same float operations in the same order as {@link ScalarAc3Kernel}, so the results
 * match it exactly.
 *
 * @implNote This implementation uses 256-bit vectors when the platform prefers vectors of at least
 * that width and 128-bit vectors otherwise. It calls only the per-shape {@code lanewise}
 * operations, casts every loaded, broadcast, rearranged or blended vector and every
 * {@code intoArray} receiver through the constant exact class of the species so that C2 can
 * intrinsify each operation while it parses the kernel, and keeps no vector, mask or shuffle
 * across a loop back edge: loop-invariant twiddles and gains are loaded inside the loop, which C2
 * hoists, following the rules the JIT lint enforces. Once compiled, every kernel allocates nothing
 * per call, with tiered compilation or C2 alone and with polluted type profiles.
 * The interleave spells each merge of two vectors as two single-vector rearrangements selected by
 * a blend, which moves the same lanes as a two-vector rearrangement without its shared dispatch;
 * the butterfly stages narrower than a vector run the scalar reference. A pair of butterfly stages
 * runs in one pass over the data, the two stages of each group of four values in registers, which
 * halves the loads and stores of the transform's wide stages.
 */
final class VectorAc3Kernel implements Ac3Kernel {
    /**
     * The float vector shape.
     */
    private static final VectorSpecies<Float> FLOATS = FloatVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? FloatVector.SPECIES_256 : FloatVector.SPECIES_128;

    /**
     * The number of lanes.
     */
    private static final int LANES = FLOATS.length();

    /**
     * The exact class of the vectors of {@link #FLOATS}.
     */
    @SuppressWarnings("unchecked")
    private static final Class<FloatVector> FLOAT_VECTOR = (Class<FloatVector>) FLOATS.vectorType();

    /**
     * Marks the odd lanes, which take their value from the reversed run of an interleave.
     */
    private static final VectorMask<Float> ODD_LANES = VectorMask.fromLong(FLOATS, LANES == 8 ? 0xAAL : 0xAL);

    /**
     * Moves lane {@code lane / 2} to each lane: the forward values of the lower output vector.
     */
    private static final VectorShuffle<Float> ZIP_LOW = VectorShuffle.fromOp(FLOATS, lane -> lane / 2);

    /**
     * Moves lane {@code LANES / 2 + lane / 2} to each lane: the forward values of the upper output
     * vector.
     */
    private static final VectorShuffle<Float> ZIP_HIGH = VectorShuffle.fromOp(FLOATS, lane -> LANES / 2 + lane / 2);

    /**
     * Moves lane {@code LANES - 1 - lane / 2} to each lane: the reversed values of the lower output
     * vector, from a run loaded in ascending order.
     */
    private static final VectorShuffle<Float> UNZIP_LOW = VectorShuffle.fromOp(FLOATS, lane -> LANES - 1 - lane / 2);

    /**
     * Moves lane {@code LANES / 2 - 1 - lane / 2} to each lane: the reversed values of the upper
     * output vector.
     */
    private static final VectorShuffle<Float> UNZIP_HIGH = VectorShuffle.fromOp(FLOATS,
            lane -> LANES / 2 - 1 - lane / 2);

    /**
     * Whether the first three FFT stages fit one vector: with 8 lanes, the butterflies of spans 1,
     * 2 and 4 pair lanes of the same vector.
     */
    private static final boolean IN_VECTOR_STAGES = LANES == 8;

    /**
     * Swaps adjacent lanes, for the in-vector stage of span 1.
     */
    private static final VectorShuffle<Float> SWAP_1 = VectorShuffle.fromOp(FLOATS, lane -> (lane ^ 1) & (LANES - 1));

    /**
     * Swaps lanes two apart, for the in-vector stage of span 2.
     */
    private static final VectorShuffle<Float> SWAP_2 = VectorShuffle.fromOp(FLOATS, lane -> (lane ^ 2) & (LANES - 1));

    /**
     * Swaps lanes four apart, for the in-vector stage of span 4.
     */
    private static final VectorShuffle<Float> SWAP_4 = VectorShuffle.fromOp(FLOATS, lane -> (lane ^ 4) & (LANES - 1));

    /**
     * Marks the lanes holding the second input of each butterfly of span 1.
     */
    private static final VectorMask<Float> SECOND_1 = seconds(1);

    /**
     * Marks the lanes holding the second input of each butterfly of span 2.
     */
    private static final VectorMask<Float> SECOND_2 = seconds(2);

    /**
     * Marks the lanes holding the second input of each butterfly of span 4.
     */
    private static final VectorMask<Float> SECOND_4 = seconds(4);

    /**
     * The scalar reference used for loop remainders.
     */
    private final ScalarAc3Kernel scalar = new ScalarAc3Kernel();

    /**
     * Creates the kernel.
     */
    VectorAc3Kernel() {
    }

    /**
     * Builds the mask of the second butterfly inputs of an in-vector stage.
     *
     * @param span the butterfly span: 1, 2 or 4
     * @return the mask of the lanes whose index has the span's bit set
     */
    private static VectorMask<Float> seconds(int span) {
        var bits = new boolean[LANES];
        for (var lane = 0; lane < LANES; lane++) {
            bits[lane] = (lane & span) != 0;
        }
        return VectorMask.fromArray(FLOATS, bits, 0);
    }

    @Override
    public void twiddle(float[] re, float[] im, float[] cos, float[] sin, int count) {
        var bound = FLOATS.loopBound(count);
        for (var i = 0; i < bound; i += LANES) {
            var r = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, i));
            var m = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, i));
            var c = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, cos, i));
            var s = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, sin, i));
            FLOAT_VECTOR.cast(r.lanewise(VectorOperators.MUL, c)
                            .lanewise(VectorOperators.SUB, m.lanewise(VectorOperators.MUL, s)))
                    .intoArray(re, i);
            FLOAT_VECTOR.cast(m.lanewise(VectorOperators.MUL, c)
                            .lanewise(VectorOperators.ADD, r.lanewise(VectorOperators.MUL, s)))
                    .intoArray(im, i);
        }
        for (var i = bound; i < count; i++) {
            var r = re[i];
            var m = im[i];
            re[i] = r * cos[i] - m * sin[i];
            im[i] = m * cos[i] + r * sin[i];
        }
    }

    @Override
    public void butterflies(float[] re, float[] im, float[] cos, float[] sin, int half, int length) {
        if (half < LANES) {
            scalar.butterflies(re, im, cos, sin, half, length);
            return;
        }
        for (var start = 0; start < length; start += 2 * half) {
            for (var k = 0; k < half; k += LANES) {
                var a = start + k;
                var b = a + half;
                var wr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, cos, k));
                var wi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, sin, k));
                var ra = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, a));
                var ia = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, a));
                var rb = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, b));
                var ib = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, b));
                var tr = rb.lanewise(VectorOperators.MUL, wr).lanewise(VectorOperators.SUB, ib.lanewise(VectorOperators.MUL, wi));
                var ti = ib.lanewise(VectorOperators.MUL, wr).lanewise(VectorOperators.ADD, rb.lanewise(VectorOperators.MUL, wi));
                FLOAT_VECTOR.cast(ra.lanewise(VectorOperators.SUB, tr)).intoArray(re, b);
                FLOAT_VECTOR.cast(ia.lanewise(VectorOperators.SUB, ti)).intoArray(im, b);
                FLOAT_VECTOR.cast(ra.lanewise(VectorOperators.ADD, tr)).intoArray(re, a);
                FLOAT_VECTOR.cast(ia.lanewise(VectorOperators.ADD, ti)).intoArray(im, a);
            }
        }
    }

    @Override
    public void butterflyPair(float[] re, float[] im, float[] cos1, float[] sin1, float[] cos2, float[] sin2,
                              int half, int length) {
        if (half < LANES) {
            scalar.butterflyPair(re, im, cos1, sin1, cos2, sin2, half, length);
            return;
        }
        for (var start = 0; start < length; start += 4 * half) {
            for (var k = 0; k < half; k += LANES) {
                var a = start + k;
                var b = a + half;
                var c = b + half;
                var d = c + half;
                var wr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, cos1, k));
                var wi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, sin1, k));
                var ra = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, a));
                var ia = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, a));
                var rb = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, b));
                var ib = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, b));
                var rc = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, c));
                var ic = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, c));
                var rd = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, d));
                var id = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, d));
                var tr = rb.lanewise(VectorOperators.MUL, wr).lanewise(VectorOperators.SUB, ib.lanewise(VectorOperators.MUL, wi));
                var ti = ib.lanewise(VectorOperators.MUL, wr).lanewise(VectorOperators.ADD, rb.lanewise(VectorOperators.MUL, wi));
                var ur = rd.lanewise(VectorOperators.MUL, wr).lanewise(VectorOperators.SUB, id.lanewise(VectorOperators.MUL, wi));
                var ui = id.lanewise(VectorOperators.MUL, wr).lanewise(VectorOperators.ADD, rd.lanewise(VectorOperators.MUL, wi));
                var ra1 = ra.lanewise(VectorOperators.ADD, tr);
                var ia1 = ia.lanewise(VectorOperators.ADD, ti);
                var rb1 = ra.lanewise(VectorOperators.SUB, tr);
                var ib1 = ia.lanewise(VectorOperators.SUB, ti);
                var rc1 = rc.lanewise(VectorOperators.ADD, ur);
                var ic1 = ic.lanewise(VectorOperators.ADD, ui);
                var rd1 = rc.lanewise(VectorOperators.SUB, ur);
                var id1 = ic.lanewise(VectorOperators.SUB, ui);
                var vr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, cos2, k));
                var vi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, sin2, k));
                var xr = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, cos2, k + half));
                var xi = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, sin2, k + half));
                var sr = rc1.lanewise(VectorOperators.MUL, vr).lanewise(VectorOperators.SUB, ic1.lanewise(VectorOperators.MUL, vi));
                var si = ic1.lanewise(VectorOperators.MUL, vr).lanewise(VectorOperators.ADD, rc1.lanewise(VectorOperators.MUL, vi));
                var pr = rd1.lanewise(VectorOperators.MUL, xr).lanewise(VectorOperators.SUB, id1.lanewise(VectorOperators.MUL, xi));
                var pi = id1.lanewise(VectorOperators.MUL, xr).lanewise(VectorOperators.ADD, rd1.lanewise(VectorOperators.MUL, xi));
                FLOAT_VECTOR.cast(ra1.lanewise(VectorOperators.SUB, sr)).intoArray(re, c);
                FLOAT_VECTOR.cast(ia1.lanewise(VectorOperators.SUB, si)).intoArray(im, c);
                FLOAT_VECTOR.cast(ra1.lanewise(VectorOperators.ADD, sr)).intoArray(re, a);
                FLOAT_VECTOR.cast(ia1.lanewise(VectorOperators.ADD, si)).intoArray(im, a);
                FLOAT_VECTOR.cast(rb1.lanewise(VectorOperators.SUB, pr)).intoArray(re, d);
                FLOAT_VECTOR.cast(ib1.lanewise(VectorOperators.SUB, pi)).intoArray(im, d);
                FLOAT_VECTOR.cast(rb1.lanewise(VectorOperators.ADD, pr)).intoArray(re, b);
                FLOAT_VECTOR.cast(ib1.lanewise(VectorOperators.ADD, pi)).intoArray(im, b);
            }
        }
    }

    @Override
    public void firstStages(float[] re, float[] im, float[] cos, float[] sin, int length) {
        if (!IN_VECTOR_STAGES) {
            scalar.firstStages(re, im, cos, sin, length);
            return;
        }
        for (var i = 0; i < length; i += LANES) {
            var swap1 = SWAP_1;
            var swap2 = SWAP_2;
            var swap4 = SWAP_4;
            var second1 = SECOND_1;
            var second2 = SECOND_2;
            var second4 = SECOND_4;
            var wr1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, cos, 0));
            var wi1 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, sin, 0));
            var wr2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, cos, 8));
            var wi2 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, sin, 8));
            var wr4 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, cos, 16));
            var wi4 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, sin, 16));
            var r0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, re, i));
            var i0 = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, im, i));
            var pr1 = FLOAT_VECTOR.cast(r0.rearrange(swap1));
            var pi1 = FLOAT_VECTOR.cast(i0.rearrange(swap1));
            var br1 = FLOAT_VECTOR.cast(pr1.blend(r0, second1));
            var bi1 = FLOAT_VECTOR.cast(pi1.blend(i0, second1));
            var ar1 = FLOAT_VECTOR.cast(r0.blend(pr1, second1));
            var ai1 = FLOAT_VECTOR.cast(i0.blend(pi1, second1));
            var tr1 = br1.lanewise(VectorOperators.MUL, wr1).lanewise(VectorOperators.SUB, bi1.lanewise(VectorOperators.MUL, wi1));
            var ti1 = bi1.lanewise(VectorOperators.MUL, wr1).lanewise(VectorOperators.ADD, br1.lanewise(VectorOperators.MUL, wi1));
            var r1 = FLOAT_VECTOR.cast(ar1.lanewise(VectorOperators.ADD, tr1).blend(ar1.lanewise(VectorOperators.SUB, tr1), second1));
            var i1 = FLOAT_VECTOR.cast(ai1.lanewise(VectorOperators.ADD, ti1).blend(ai1.lanewise(VectorOperators.SUB, ti1), second1));
            var pr2 = FLOAT_VECTOR.cast(r1.rearrange(swap2));
            var pi2 = FLOAT_VECTOR.cast(i1.rearrange(swap2));
            var br2 = FLOAT_VECTOR.cast(pr2.blend(r1, second2));
            var bi2 = FLOAT_VECTOR.cast(pi2.blend(i1, second2));
            var ar2 = FLOAT_VECTOR.cast(r1.blend(pr2, second2));
            var ai2 = FLOAT_VECTOR.cast(i1.blend(pi2, second2));
            var tr2 = br2.lanewise(VectorOperators.MUL, wr2).lanewise(VectorOperators.SUB, bi2.lanewise(VectorOperators.MUL, wi2));
            var ti2 = bi2.lanewise(VectorOperators.MUL, wr2).lanewise(VectorOperators.ADD, br2.lanewise(VectorOperators.MUL, wi2));
            var r2 = FLOAT_VECTOR.cast(ar2.lanewise(VectorOperators.ADD, tr2).blend(ar2.lanewise(VectorOperators.SUB, tr2), second2));
            var i2 = FLOAT_VECTOR.cast(ai2.lanewise(VectorOperators.ADD, ti2).blend(ai2.lanewise(VectorOperators.SUB, ti2), second2));
            var pr4 = FLOAT_VECTOR.cast(r2.rearrange(swap4));
            var pi4 = FLOAT_VECTOR.cast(i2.rearrange(swap4));
            var br4 = FLOAT_VECTOR.cast(pr4.blend(r2, second4));
            var bi4 = FLOAT_VECTOR.cast(pi4.blend(i2, second4));
            var ar4 = FLOAT_VECTOR.cast(r2.blend(pr4, second4));
            var ai4 = FLOAT_VECTOR.cast(i2.blend(pi4, second4));
            var tr4 = br4.lanewise(VectorOperators.MUL, wr4).lanewise(VectorOperators.SUB, bi4.lanewise(VectorOperators.MUL, wi4));
            var ti4 = bi4.lanewise(VectorOperators.MUL, wr4).lanewise(VectorOperators.ADD, br4.lanewise(VectorOperators.MUL, wi4));
            FLOAT_VECTOR.cast(ar4.lanewise(VectorOperators.ADD, tr4).blend(ar4.lanewise(VectorOperators.SUB, tr4), second4))
                    .intoArray(re, i);
            FLOAT_VECTOR.cast(ai4.lanewise(VectorOperators.ADD, ti4).blend(ai4.lanewise(VectorOperators.SUB, ti4), second4))
                    .intoArray(im, i);
        }
    }

    @Override
    public void interleave(float[] forward, int forwardOffset, float[] backward, int backwardEnd, float[] window,
                           int windowOffset, float[] out, int outOffset, int pairs) {
        var bound = FLOATS.loopBound(pairs);
        for (var n = 0; n < bound; n += LANES) {
            var ahead = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, forward, forwardOffset + n));
            var behind = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, backward, backwardEnd - n - LANES + 1));
            var low = FLOAT_VECTOR.cast(ahead.rearrange(ZIP_LOW).blend(behind.rearrange(UNZIP_LOW), ODD_LANES));
            var high = FLOAT_VECTOR.cast(ahead.rearrange(ZIP_HIGH).blend(behind.rearrange(UNZIP_HIGH), ODD_LANES));
            var lowWindow = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, windowOffset + 2 * n));
            var highWindow = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, window, windowOffset + 2 * n + LANES));
            FLOAT_VECTOR.cast(low.lanewise(VectorOperators.MUL, lowWindow)).intoArray(out, outOffset + 2 * n);
            FLOAT_VECTOR.cast(high.lanewise(VectorOperators.MUL, highWindow)).intoArray(out, outOffset + 2 * n + LANES);
        }
        for (var n = bound; n < pairs; n++) {
            out[outOffset + 2 * n] = forward[forwardOffset + n] * window[windowOffset + 2 * n];
            out[outOffset + 2 * n + 1] = backward[backwardEnd - n] * window[windowOffset + 2 * n + 1];
        }
    }

    @Override
    public void overlap(float[] block, float[] delay, float[] out, int offset, int half) {
        var bound = FLOATS.loopBound(half);
        for (var n = 0; n < bound; n += LANES) {
            var b = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, block, n));
            var d = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, delay, n));
            FLOAT_VECTOR.cast(b.lanewise(VectorOperators.ADD, d)).intoArray(out, offset + n);
            FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, block, half + n)).intoArray(delay, n);
        }
        for (var n = bound; n < half; n++) {
            out[offset + n] = block[n] + delay[n];
            delay[n] = block[half + n];
        }
    }

    @Override
    public void scale(float[] in, int inOffset, float gain, float[] out, int outOffset, int count) {
        var bound = FLOATS.loopBound(count);
        for (var i = 0; i < bound; i += LANES) {
            var factor = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, gain));
            FLOAT_VECTOR.cast(FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, inOffset + i))
                            .lanewise(VectorOperators.MUL, factor))
                    .intoArray(out, outOffset + i);
        }
        scalar.scale(in, inOffset + bound, gain, out, outOffset + bound, count - bound);
    }

    @Override
    public void accumulate(float[] in, int inOffset, float gain, float[] out, int outOffset, int count) {
        var bound = FLOATS.loopBound(count);
        for (var i = 0; i < bound; i += LANES) {
            var factor = FLOAT_VECTOR.cast(FloatVector.broadcast(FLOATS, gain));
            var sum = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, out, outOffset + i));
            var term = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, in, inOffset + i))
                    .lanewise(VectorOperators.MUL, factor);
            FLOAT_VECTOR.cast(sum.lanewise(VectorOperators.ADD, term)).intoArray(out, outOffset + i);
        }
        scalar.accumulate(in, inOffset + bound, gain, out, outOffset + bound, count - bound);
    }
}
