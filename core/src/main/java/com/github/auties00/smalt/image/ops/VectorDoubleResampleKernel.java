package com.github.auties00.smalt.image.ops;

import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

import java.util.Arrays;

/**
 * Implements {@link DoubleResampleKernel} with the Vector API; the output equals
 * {@link ScalarDoubleResampleKernel} value for value.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd}; every
 * tap is one multiply and one add of a whole vector by a broadcast weight, never fused, so the rounding matches the
 * scalar twin. The vertical pass runs across the values of a row with four accumulators per iteration, the horizontal
 * pass across the rows of a lane-interleaved block.
 */
final class VectorDoubleResampleKernel implements DoubleResampleKernel {
    /**
     * The widest double species: 256 bits when the hardware has them, else 128 bits.
     */
    private static final VectorSpecies<Double> D = DoubleVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? DoubleVector.SPECIES_256 : DoubleVector.SPECIES_128;

    /**
     * The number of lanes of {@link #D}.
     */
    private static final int L = D.length();

    /**
     * The exact class of the vectors of {@link #D}.
     */
    private static final Class<DoubleVector> DOUBLE_VECTOR = vectorClass(D);

    /**
     * The scalar kernel handling the values the vector loops do not cover.
     */
    private final ScalarDoubleResampleKernel scalar;

    /**
     * Constructs the kernel.
     */
    private VectorDoubleResampleKernel() {
        this.scalar = new ScalarDoubleResampleKernel();
    }

    /**
     * Creates the kernel.
     *
     * @return the vector kernel
     */
    static DoubleResampleKernel create() {
        return new VectorDoubleResampleKernel();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the vector species whose class to return
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public int lanes() {
        return L;
    }

    @Override
    public void vertical(double[] src, int[] offsets, double[] weights, int wOff, int taps, int length, double[] dst,
                         boolean accumulate) {
        if (!accumulate) {
            Arrays.fill(dst, 0, length, 0);
        }
        var x = 0;
        for (; x + 4 * L <= length; x += 4 * L) {
            var a0 = DOUBLE_VECTOR.cast(DoubleVector.fromArray(D, dst, x));
            var a1 = DOUBLE_VECTOR.cast(DoubleVector.fromArray(D, dst, x + L));
            var a2 = DOUBLE_VECTOR.cast(DoubleVector.fromArray(D, dst, x + 2 * L));
            var a3 = DOUBLE_VECTOR.cast(DoubleVector.fromArray(D, dst, x + 3 * L));
            for (var k = 0; k < taps; k++) {
                var weight = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D, weights[wOff + k]));
                var offset = offsets[k] + x;
                a0 = DOUBLE_VECTOR.cast(DOUBLE_VECTOR.cast(DoubleVector.fromArray(D, src, offset))
                        .lanewise(VectorOperators.MUL, weight)).lanewise(VectorOperators.ADD, DOUBLE_VECTOR.cast(a0));
                a1 = DOUBLE_VECTOR.cast(DOUBLE_VECTOR.cast(DoubleVector.fromArray(D, src, offset + L))
                        .lanewise(VectorOperators.MUL, weight)).lanewise(VectorOperators.ADD, DOUBLE_VECTOR.cast(a1));
                a2 = DOUBLE_VECTOR.cast(DOUBLE_VECTOR.cast(DoubleVector.fromArray(D, src, offset + 2 * L))
                        .lanewise(VectorOperators.MUL, weight)).lanewise(VectorOperators.ADD, DOUBLE_VECTOR.cast(a2));
                a3 = DOUBLE_VECTOR.cast(DOUBLE_VECTOR.cast(DoubleVector.fromArray(D, src, offset + 3 * L))
                        .lanewise(VectorOperators.MUL, weight)).lanewise(VectorOperators.ADD, DOUBLE_VECTOR.cast(a3));
            }
            DOUBLE_VECTOR.cast(a0).intoArray(dst, x);
            DOUBLE_VECTOR.cast(a1).intoArray(dst, x + L);
            DOUBLE_VECTOR.cast(a2).intoArray(dst, x + 2 * L);
            DOUBLE_VECTOR.cast(a3).intoArray(dst, x + 3 * L);
        }
        for (; x + L <= length; x += L) {
            var a0 = DOUBLE_VECTOR.cast(DoubleVector.fromArray(D, dst, x));
            for (var k = 0; k < taps; k++) {
                var weight = DOUBLE_VECTOR.cast(DoubleVector.broadcast(D, weights[wOff + k]));
                a0 = DOUBLE_VECTOR.cast(DOUBLE_VECTOR.cast(DoubleVector.fromArray(D, src, offsets[k] + x))
                        .lanewise(VectorOperators.MUL, weight)).lanewise(VectorOperators.ADD, DOUBLE_VECTOR.cast(a0));
            }
            DOUBLE_VECTOR.cast(a0).intoArray(dst, x);
        }
        scalar.verticalRange(src, offsets, weights, wOff, taps, dst, x, length);
    }

    @Override
    public void horizontal(double[] src, int lanes, int[] starts, int[] counts, double[] weights, int stride, int count,
                           double[] dst) {
        for (var i = 0; i < count; i++) {
            var base = starts[i] * L;
            var w = i * stride;
            var taps = counts[i];
            var acc = DOUBLE_VECTOR.cast(DoubleVector.zero(D));
            for (var k = 0; k < taps; k++) {
                acc = DOUBLE_VECTOR.cast(DOUBLE_VECTOR.cast(DoubleVector.fromArray(D, src, base + k * L))
                        .lanewise(VectorOperators.MUL, DOUBLE_VECTOR.cast(DoubleVector.broadcast(D, weights[w + k]))))
                        .lanewise(VectorOperators.ADD, DOUBLE_VECTOR.cast(acc));
            }
            DOUBLE_VECTOR.cast(acc).intoArray(dst, i * L);
        }
    }
}
