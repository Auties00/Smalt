package com.github.auties00.smalt.video.intra.internal;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import static com.github.auties00.smalt.video.intra.internal.ScalarBlockKernel.A1;
import static com.github.auties00.smalt.video.intra.internal.ScalarBlockKernel.A2;
import static com.github.auties00.smalt.video.intra.internal.ScalarBlockKernel.A3;
import static com.github.auties00.smalt.video.intra.internal.ScalarBlockKernel.A4;
import static com.github.auties00.smalt.video.intra.internal.ScalarBlockKernel.A5;
import static com.github.auties00.smalt.video.intra.internal.ScalarBlockKernel.A6;
import static com.github.auties00.smalt.video.intra.internal.ScalarBlockKernel.A7;

/**
 * Implements {@link BlockKernel} with the Vector API, one block per 32-bit lane of a 256-bit
 * vector.
 *
 * <p>Each 8-point transform loads the eight values of one line of every block as eight vectors,
 * computes the even-odd decomposition lane-wise with the operations of
 * {@link ScalarBlockKernel#inverse1} or {@link ScalarBlockKernel#forward1} in the same order, and
 * stores the results in place, so the results equal the scalar kernel's bit for bit.
 *
 * <p>Every operation is invoked through the per-operation methods ({@code lanewise} with an
 * operator, {@code compare}, {@code blend}) with vector operands, no vector is passed to or
 * returned from a helper, and every vector that a load, a broadcast or a conversion produces is
 * cast to the exact class of its species before it is used as the receiver of a call, so that the
 * compiler binds the calls without consulting the receiver-type profiles every vector shape of the
 * program shares.
 *
 * <p>This class references {@code jdk.incubator.vector} types and is only loaded through
 * {@link VectorBlockKernels} when the Vector API is available.
 */
public final class VectorBlockKernel implements BlockKernel {
    /**
     * Eight 32-bit float lanes, one per block.
     */
    private static final VectorSpecies<Float> F = FloatVector.SPECIES_256;

    /**
     * Eight 32-bit integer lanes, one per block.
     */
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_256;

    /**
     * The exact class of the vectors of {@link #F}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(F);

    /**
     * The exact class of the vectors of {@link #I}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(I);

    /**
     * {@link ScalarBlockKernel#A1} in every lane.
     */
    private static final FloatVector V1 = FloatVector.broadcast(F, A1);

    /**
     * {@link ScalarBlockKernel#A2} in every lane.
     */
    private static final FloatVector V2 = FloatVector.broadcast(F, A2);

    /**
     * {@link ScalarBlockKernel#A3} in every lane.
     */
    private static final FloatVector V3 = FloatVector.broadcast(F, A3);

    /**
     * {@link ScalarBlockKernel#A4} in every lane.
     */
    private static final FloatVector V4 = FloatVector.broadcast(F, A4);

    /**
     * {@link ScalarBlockKernel#A5} in every lane.
     */
    private static final FloatVector V5 = FloatVector.broadcast(F, A5);

    /**
     * {@link ScalarBlockKernel#A6} in every lane.
     */
    private static final FloatVector V6 = FloatVector.broadcast(F, A6);

    /**
     * {@link ScalarBlockKernel#A7} in every lane.
     */
    private static final FloatVector V7 = FloatVector.broadcast(F, A7);

    /**
     * The species of eight 16-bit samples, the row of a block.
     */
    private static final VectorSpecies<Short> S = ShortVector.SPECIES_128;

    /**
     * The concrete class of the vectors of {@link #S}.
     */
    private static final Class<ShortVector> SHORT_VECTOR = vectorClass(S);

    /**
     * The index map that gathers one column of eight rows stored one after the other.
     */
    private static final int[] ROW_GATHER = {0, 8, 16, 24, 32, 40, 48, 56};

    /**
     * Zero in every float lane.
     */
    private static final FloatVector ZERO = FloatVector.zero(F);

    /**
     * Zero in every integer lane.
     */
    private static final IntVector ZERO_INT = IntVector.zero(I);

    /**
     * Constructs the kernel.
     */
    VectorBlockKernel() {
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the vector species of the lanes
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void inverse(float[] batch) {
        for (var row = 0; row < 8; row++) {
            inverseLine(batch, row * 64, LANES);
        }
        for (var column = 0; column < 8; column++) {
            inverseLine(batch, column * LANES, 64);
        }
    }

    @Override
    public void forward(float[] batch) {
        for (var row = 0; row < 8; row++) {
            forwardLine(batch, row * 64, LANES);
        }
        for (var column = 0; column < 8; column++) {
            forwardLine(batch, column * LANES, 64);
        }
    }

    @Override
    public void toSamples(float[] batch, float scale, float offset, float limit, int[] out) {
        var scaleVector = FLOAT_VECTOR.cast(FloatVector.broadcast(F, scale));
        var offsetVector = FLOAT_VECTOR.cast(FloatVector.broadcast(F, offset));
        var limitVector = FLOAT_VECTOR.cast(FloatVector.broadcast(F, limit));
        for (var i = 0; i < BATCH_LENGTH; i += LANES) {
            var value = FLOAT_VECTOR.cast(FloatVector.fromArray(F, batch, i))
                    .lanewise(VectorOperators.MUL, scaleVector)
                    .lanewise(VectorOperators.ADD, offsetVector)
                    .lanewise(VectorOperators.MAX, ZERO)
                    .lanewise(VectorOperators.MIN, limitVector);
            INT_VECTOR.cast(value.convertShape(VectorOperators.F2I, I, 0)).intoArray(out, i);
        }
    }

    @Override
    public void quantize(float[] batch, float[] weights, float rounding, int[] out) {
        var roundingVector = FLOAT_VECTOR.cast(FloatVector.broadcast(F, rounding));
        for (var i = 0; i < BATCH_LENGTH; i += LANES) {
            var coefficient = FLOAT_VECTOR.cast(FloatVector.fromArray(F, batch, i));
            var weight = FLOAT_VECTOR.cast(FloatVector.fromArray(F, weights, i));
            var magnitude = coefficient.lanewise(VectorOperators.ABS)
                    .lanewise(VectorOperators.MUL, weight)
                    .lanewise(VectorOperators.ADD, roundingVector);
            var level = INT_VECTOR.cast(magnitude.convertShape(VectorOperators.F2I, I, 0));
            var negative = coefficient.compare(VectorOperators.LT, ZERO).cast(I);
            INT_VECTOR.cast(level.blend(level.lanewise(VectorOperators.NEG), negative)).intoArray(out, i);
        }
    }

    @Override
    public void loadBlocks(short[][] sources, int[] offsets, int[] strides, int lanes, float subtract, float scale,
                           float[] batch, float[] scratch) {
        var subtractVector = FLOAT_VECTOR.cast(FloatVector.broadcast(F, subtract));
        var scaleVector = FLOAT_VECTOR.cast(FloatVector.broadcast(F, scale));
        for (var row = 0; row < 8; row++) {
            for (var lane = 0; lane < LANES; lane++) {
                if (lane < lanes) {
                    var shorts = SHORT_VECTOR.cast(ShortVector.fromArray(S, sources[lane], offsets[lane] + row * strides[lane]));
                    var ints = INT_VECTOR.cast(shorts.convertShape(VectorOperators.ZERO_EXTEND_S2I, I, 0));
                    var floats = FLOAT_VECTOR.cast(ints.convert(VectorOperators.I2F, 0));
                    FLOAT_VECTOR.cast(floats.lanewise(VectorOperators.SUB, subtractVector)
                            .lanewise(VectorOperators.MUL, scaleVector)).intoArray(scratch, lane * LANES);
                } else {
                    ZERO.intoArray(scratch, lane * LANES);
                }
            }
            for (var column = 0; column < 8; column++) {
                FLOAT_VECTOR.cast(FloatVector.fromArray(F, scratch, column, ROW_GATHER, 0))
                        .intoArray(batch, (row * 8 + column) * LANES);
            }
        }
    }

    @Override
    public void quantizeMasked(float[] batch, float[] weights, float rounding, int limit, int[] out, int[] masks) {
        var roundingVector = FLOAT_VECTOR.cast(FloatVector.broadcast(F, rounding));
        var limitVector = INT_VECTOR.cast(IntVector.broadcast(I, limit));
        for (var position = 0; position < 64; position++) {
            var i = position * LANES;
            var coefficient = FLOAT_VECTOR.cast(FloatVector.fromArray(F, batch, i));
            var weight = FLOAT_VECTOR.cast(FloatVector.fromArray(F, weights, i));
            var magnitude = coefficient.lanewise(VectorOperators.ABS)
                    .lanewise(VectorOperators.MUL, weight)
                    .lanewise(VectorOperators.ADD, roundingVector);
            var level = INT_VECTOR.cast(INT_VECTOR.cast(magnitude.convertShape(VectorOperators.F2I, I, 0))
                    .lanewise(VectorOperators.MIN, limitVector));
            level.intoArray(out, i);
            masks[position] = (int) level.compare(VectorOperators.NE, ZERO_INT).toLong();
        }
    }

    /**
     * Computes the 8-point inverse DCT of one line of every block of a batch in place.
     *
     * @param a the batch
     * @param base the index of the line's first value in lane 0
     * @param step the distance between the line's values
     */
    private static void inverseLine(float[] a, int base, int step) {
        var x0 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base));
        var x1 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + step));
        var x2 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + 2 * step));
        var x3 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + 3 * step));
        var x4 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + 4 * step));
        var x5 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + 5 * step));
        var x6 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + 6 * step));
        var x7 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + 7 * step));
        var e0p = x0.lanewise(VectorOperators.ADD, x4).lanewise(VectorOperators.MUL, V4);
        var e1p = x0.lanewise(VectorOperators.SUB, x4).lanewise(VectorOperators.MUL, V4);
        var t2 = x2.lanewise(VectorOperators.MUL, V2).lanewise(VectorOperators.ADD, x6.lanewise(VectorOperators.MUL, V6));
        var t3 = x2.lanewise(VectorOperators.MUL, V6).lanewise(VectorOperators.SUB, x6.lanewise(VectorOperators.MUL, V2));
        var e0 = e0p.lanewise(VectorOperators.ADD, t2);
        var e3 = e0p.lanewise(VectorOperators.SUB, t2);
        var e1 = e1p.lanewise(VectorOperators.ADD, t3);
        var e2 = e1p.lanewise(VectorOperators.SUB, t3);
        var o0 = x1.lanewise(VectorOperators.MUL, V1)
                .lanewise(VectorOperators.ADD, x3.lanewise(VectorOperators.MUL, V3))
                .lanewise(VectorOperators.ADD, x5.lanewise(VectorOperators.MUL, V5))
                .lanewise(VectorOperators.ADD, x7.lanewise(VectorOperators.MUL, V7));
        var o1 = x1.lanewise(VectorOperators.MUL, V3)
                .lanewise(VectorOperators.SUB, x3.lanewise(VectorOperators.MUL, V7))
                .lanewise(VectorOperators.SUB, x5.lanewise(VectorOperators.MUL, V1))
                .lanewise(VectorOperators.SUB, x7.lanewise(VectorOperators.MUL, V5));
        var o2 = x1.lanewise(VectorOperators.MUL, V5)
                .lanewise(VectorOperators.SUB, x3.lanewise(VectorOperators.MUL, V1))
                .lanewise(VectorOperators.ADD, x5.lanewise(VectorOperators.MUL, V7))
                .lanewise(VectorOperators.ADD, x7.lanewise(VectorOperators.MUL, V3));
        var o3 = x1.lanewise(VectorOperators.MUL, V7)
                .lanewise(VectorOperators.SUB, x3.lanewise(VectorOperators.MUL, V5))
                .lanewise(VectorOperators.ADD, x5.lanewise(VectorOperators.MUL, V3))
                .lanewise(VectorOperators.SUB, x7.lanewise(VectorOperators.MUL, V1));
        FLOAT_VECTOR.cast(e0.lanewise(VectorOperators.ADD, o0)).intoArray(a, base);
        FLOAT_VECTOR.cast(e0.lanewise(VectorOperators.SUB, o0)).intoArray(a, base + 7 * step);
        FLOAT_VECTOR.cast(e1.lanewise(VectorOperators.ADD, o1)).intoArray(a, base + step);
        FLOAT_VECTOR.cast(e1.lanewise(VectorOperators.SUB, o1)).intoArray(a, base + 6 * step);
        FLOAT_VECTOR.cast(e2.lanewise(VectorOperators.ADD, o2)).intoArray(a, base + 2 * step);
        FLOAT_VECTOR.cast(e2.lanewise(VectorOperators.SUB, o2)).intoArray(a, base + 5 * step);
        FLOAT_VECTOR.cast(e3.lanewise(VectorOperators.ADD, o3)).intoArray(a, base + 3 * step);
        FLOAT_VECTOR.cast(e3.lanewise(VectorOperators.SUB, o3)).intoArray(a, base + 4 * step);
    }

    /**
     * Computes the 8-point forward DCT of one line of every block of a batch in place.
     *
     * @param a the batch
     * @param base the index of the line's first value in lane 0
     * @param step the distance between the line's values
     */
    private static void forwardLine(float[] a, int base, int step) {
        var y0 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base));
        var y1 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + step));
        var y2 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + 2 * step));
        var y3 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + 3 * step));
        var y4 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + 4 * step));
        var y5 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + 5 * step));
        var y6 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + 6 * step));
        var y7 = FLOAT_VECTOR.cast(FloatVector.fromArray(F, a, base + 7 * step));
        var s0 = y0.lanewise(VectorOperators.ADD, y7);
        var s1 = y1.lanewise(VectorOperators.ADD, y6);
        var s2 = y2.lanewise(VectorOperators.ADD, y5);
        var s3 = y3.lanewise(VectorOperators.ADD, y4);
        var d0 = y0.lanewise(VectorOperators.SUB, y7);
        var d1 = y1.lanewise(VectorOperators.SUB, y6);
        var d2 = y2.lanewise(VectorOperators.SUB, y5);
        var d3 = y3.lanewise(VectorOperators.SUB, y4);
        var p = s0.lanewise(VectorOperators.ADD, s3);
        var q = s1.lanewise(VectorOperators.ADD, s2);
        var m = s0.lanewise(VectorOperators.SUB, s3);
        var n = s1.lanewise(VectorOperators.SUB, s2);
        FLOAT_VECTOR.cast(p.lanewise(VectorOperators.ADD, q).lanewise(VectorOperators.MUL, V4)).intoArray(a, base);
        FLOAT_VECTOR.cast(p.lanewise(VectorOperators.SUB, q).lanewise(VectorOperators.MUL, V4)).intoArray(a, base + 4 * step);
        FLOAT_VECTOR.cast(m.lanewise(VectorOperators.MUL, V2)
                .lanewise(VectorOperators.ADD, n.lanewise(VectorOperators.MUL, V6))).intoArray(a, base + 2 * step);
        FLOAT_VECTOR.cast(m.lanewise(VectorOperators.MUL, V6)
                .lanewise(VectorOperators.SUB, n.lanewise(VectorOperators.MUL, V2))).intoArray(a, base + 6 * step);
        FLOAT_VECTOR.cast(d0.lanewise(VectorOperators.MUL, V1)
                .lanewise(VectorOperators.ADD, d1.lanewise(VectorOperators.MUL, V3))
                .lanewise(VectorOperators.ADD, d2.lanewise(VectorOperators.MUL, V5))
                .lanewise(VectorOperators.ADD, d3.lanewise(VectorOperators.MUL, V7))).intoArray(a, base + step);
        FLOAT_VECTOR.cast(d0.lanewise(VectorOperators.MUL, V3)
                .lanewise(VectorOperators.SUB, d1.lanewise(VectorOperators.MUL, V7))
                .lanewise(VectorOperators.SUB, d2.lanewise(VectorOperators.MUL, V1))
                .lanewise(VectorOperators.SUB, d3.lanewise(VectorOperators.MUL, V5))).intoArray(a, base + 3 * step);
        FLOAT_VECTOR.cast(d0.lanewise(VectorOperators.MUL, V5)
                .lanewise(VectorOperators.SUB, d1.lanewise(VectorOperators.MUL, V1))
                .lanewise(VectorOperators.ADD, d2.lanewise(VectorOperators.MUL, V7))
                .lanewise(VectorOperators.ADD, d3.lanewise(VectorOperators.MUL, V3))).intoArray(a, base + 5 * step);
        FLOAT_VECTOR.cast(d0.lanewise(VectorOperators.MUL, V7)
                .lanewise(VectorOperators.SUB, d1.lanewise(VectorOperators.MUL, V5))
                .lanewise(VectorOperators.ADD, d2.lanewise(VectorOperators.MUL, V3))
                .lanewise(VectorOperators.SUB, d3.lanewise(VectorOperators.MUL, V1))).intoArray(a, base + 7 * step);
    }
}
