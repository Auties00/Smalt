package com.github.auties00.smalt.audio.mp3;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link Mp3EncoderKernel} with the Vector API; the results equal
 * {@link ScalarMp3EncoderKernel} element for element.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd}: independent outputs are spread over the
 * lanes, each computed as a multiplication then an addition (never fused), and the quantizer
 * converts with Java's truncating cast. Every loaded or converted vector, and every accumulator
 * that receives a call, is cast to the constant exact class of its species, so the JIT never
 * routes a call through a type profile or boxes a vector.
 */
final class VectorMp3EncoderKernel implements Mp3EncoderKernel {
    /**
     * The float vector shape.
     */
    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    /**
     * The int vector shape of the same bit size, which receives the quantized values.
     */
    private static final VectorSpecies<Integer> INT_SPECIES = IntVector.SPECIES_PREFERRED;

    /**
     * The number of lanes of {@link #SPECIES}.
     */
    private static final int LANES = SPECIES.length();

    /**
     * The exact class of the vectors of {@link #SPECIES}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(SPECIES);

    /**
     * The exact class of the vectors of {@link #INT_SPECIES}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(INT_SPECIES);

    /**
     * The quantizer's rounding offset in every lane.
     */
    private static final FloatVector ROUNDING = FloatVector.broadcast(SPECIES, ScalarMp3EncoderKernel.ROUNDING);

    /**
     * The scalar kernel that quantizes the values past the last whole vector.
     */
    private final ScalarMp3EncoderKernel scalar = new ScalarMp3EncoderKernel();

    /**
     * Creates the kernel.
     */
    private VectorMp3EncoderKernel() {
    }

    /**
     * Returns a new kernel.
     *
     * @return the kernel
     */
    static Mp3EncoderKernel create() {
        return new VectorMp3EncoderKernel();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the vector shape
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void window(float[] input, int start, float[] window, float[] sums) {
        for (var q = 0; q < 64; q += LANES) {
            var acc = FLOAT_VECTOR.cast(FloatVector.zero(SPECIES));
            for (var j = 0; j < 8; j++) {
                var w = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, window, 64 * j + q));
                var x = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, input, start + 448 - 64 * j + q));
                acc = FLOAT_VECTOR.cast(acc).lanewise(VectorOperators.ADD, w.lanewise(VectorOperators.MUL, x));
            }
            FLOAT_VECTOR.cast(acc).intoArray(sums, q);
        }
    }

    @Override
    public void matrix(float[] folded, float[] matrix, float[] output) {
        for (var k = 0; k < 32; k += LANES) {
            var acc = FLOAT_VECTOR.cast(FloatVector.zero(SPECIES));
            for (var n = 0; n < 32; n++) {
                var f = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, folded[n]));
                var m = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, matrix, 32 * n + k));
                acc = FLOAT_VECTOR.cast(acc).lanewise(VectorOperators.ADD, f.lanewise(VectorOperators.MUL, m));
            }
            FLOAT_VECTOR.cast(acc).intoArray(output, k);
        }
    }

    @Override
    public void mdct(float[] input, float[] table, float[] output) {
        var whole = 18 / LANES * LANES;
        for (var sb = 0; sb < 32; sb++) {
            var base = 36 * sb;
            for (var m = 0; m < whole; m += LANES) {
                var acc = FLOAT_VECTOR.cast(FloatVector.zero(SPECIES));
                for (var i = 0; i < 36; i++) {
                    var x = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, input[base + i]));
                    var c = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, table, 18 * i + m));
                    acc = FLOAT_VECTOR.cast(acc).lanewise(VectorOperators.ADD, x.lanewise(VectorOperators.MUL, c));
                }
                FLOAT_VECTOR.cast(acc).intoArray(output, 18 * sb + m);
            }
            for (var m = whole; m < 18; m++) {
                var acc = 0.0f;
                for (var i = 0; i < 36; i++) {
                    acc += input[base + i] * table[18 * i + m];
                }
                output[18 * sb + m] = acc;
            }
        }
    }

    @Override
    public int quantize(float[] pow34, int from, int to, float scale, int[] output) {
        var steps = FLOAT_VECTOR.cast(FloatVector.broadcast(SPECIES, scale));
        var top = INT_VECTOR.cast(IntVector.zero(INT_SPECIES));
        var i = from;
        for (; i + LANES <= to; i += LANES) {
            var x = FLOAT_VECTOR.cast(FloatVector.fromArray(SPECIES, pow34, i));
            var rounded = x.lanewise(VectorOperators.MUL, steps).lanewise(VectorOperators.ADD, ROUNDING);
            var q = INT_VECTOR.cast(rounded.convert(VectorOperators.F2I, 0));
            q.intoArray(output, i);
            top = INT_VECTOR.cast(top).lanewise(VectorOperators.MAX, q);
        }
        var max = INT_VECTOR.cast(top).reduceLanes(VectorOperators.MAX);
        return Math.max(max, scalar.quantize(pow34, i, to, scale, output));
    }
}
