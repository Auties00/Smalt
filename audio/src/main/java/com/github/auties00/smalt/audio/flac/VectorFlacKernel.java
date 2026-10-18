package com.github.auties00.smalt.audio.flac;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link FlacKernel} with the Vector API.
 *
 * <p>This class references {@code jdk.incubator.vector} types, so it is loaded only when
 * {@link com.github.auties00.smalt.internal.simd.Simd#ENABLED} is {@code true}. Integer
 * and float vectors share one shape, so a converted integer vector fills exactly one float vector.
 *
 * @implNote This implementation uses only the per-shape {@code lanewise} operations with vector
 * operands, and casts every loaded or converted vector that receives a call through the constant
 * exact class of its species, so that the compiler binds those calls without the type profiles
 * that every vector shape of the program shares.
 */
final class VectorFlacKernel implements FlacKernel {
    /**
     * The integer species, the platform's preferred shape.
     */
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_PREFERRED;

    /**
     * The float species with the same shape as {@link #INTS}.
     */
    private static final VectorSpecies<Float> FLOATS = VectorSpecies.of(float.class, INTS.vectorShape());

    /**
     * The exact class of the vectors of {@link #INTS}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(INTS);

    /**
     * The exact class of the vectors of {@link #FLOATS}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(FLOATS);

    /**
     * One in every lane.
     */
    private static final IntVector ONE = IntVector.broadcast(INTS, 1);

    /**
     * Constructs the kernel.
     */
    private VectorFlacKernel() {
    }

    /**
     * Creates the kernel.
     *
     * @return a new vector kernel
     */
    static FlacKernel create() {
        return new VectorFlacKernel();
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
    public void toFloat(int[] in, float[] out, int count, float scale) {
        var factor = FloatVector.broadcast(FLOATS, scale);
        var i = 0;
        var bound = INTS.loopBound(count);
        for (; i < bound; i += INTS.length()) {
            var converted = FLOAT_VECTOR.cast(INT_VECTOR.cast(IntVector.fromArray(INTS, in, i))
                    .convertShape(VectorOperators.I2F, FLOATS, 0));
            converted.lanewise(VectorOperators.MUL, factor).intoArray(out, i);
        }
        for (; i < count; i++) {
            out[i] = in[i] * scale;
        }
    }

    @Override
    public void leftSide(int[] left, int[] side, int count) {
        var i = 0;
        var bound = INTS.loopBound(count);
        for (; i < bound; i += INTS.length()) {
            INT_VECTOR.cast(IntVector.fromArray(INTS, left, i)).lanewise(VectorOperators.SUB, IntVector.fromArray(INTS, side, i))
                    .intoArray(side, i);
        }
        for (; i < count; i++) {
            side[i] = left[i] - side[i];
        }
    }

    @Override
    public void sideRight(int[] side, int[] right, int count) {
        var i = 0;
        var bound = INTS.loopBound(count);
        for (; i < bound; i += INTS.length()) {
            INT_VECTOR.cast(IntVector.fromArray(INTS, side, i)).lanewise(VectorOperators.ADD, IntVector.fromArray(INTS, right, i))
                    .intoArray(side, i);
        }
        for (; i < count; i++) {
            side[i] = side[i] + right[i];
        }
    }

    @Override
    public void midSide(int[] mid, int[] side, int count) {
        var i = 0;
        var bound = INTS.loopBound(count);
        for (; i < bound; i += INTS.length()) {
            var s = INT_VECTOR.cast(IntVector.fromArray(INTS, side, i));
            var m = INT_VECTOR.cast(IntVector.fromArray(INTS, mid, i)).lanewise(VectorOperators.LSHL, 1)
                    .lanewise(VectorOperators.OR, s.lanewise(VectorOperators.AND, ONE));
            m.lanewise(VectorOperators.ADD, s).lanewise(VectorOperators.ASHR, 1).intoArray(mid, i);
            m.lanewise(VectorOperators.SUB, s).lanewise(VectorOperators.ASHR, 1).intoArray(side, i);
        }
        for (; i < count; i++) {
            var s = side[i];
            var m = (mid[i] << 1) | (s & 1);
            mid[i] = (m + s) >> 1;
            side[i] = (m - s) >> 1;
        }
    }
}
