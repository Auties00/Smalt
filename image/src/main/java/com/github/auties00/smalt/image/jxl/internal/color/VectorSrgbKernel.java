/* Ports jxl-oxide 0.12.6 (crates/jxl-color) and libjxl (lib/jxl/cms). Copyright Wonwoo Choi and the jxl-oxide
   contributors, and the JPEG XL Project Authors; MIT License and BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.color;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link SrgbKernel} with the Vector API, eight samples at a time.
 *
 * <p>Each lane performs the operations of {@link ScalarSrgbKernel} in the same order, so the output
 * is bit-identical; the samples left over at the end of a range go through the scalar kernel.
 */
final class VectorSrgbKernel implements SrgbKernel {
    /**
     * The species: eight floats.
     */
    private static final VectorSpecies<Float> FLOATS = FloatVector.SPECIES_256;

    /**
     * The exact vector class of {@link #FLOATS}.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(FLOATS);

    /**
     * The number of lanes.
     */
    private static final int LANES = 8;

    /**
     * Zero in every lane.
     */
    private static final FloatVector ZERO = FloatVector.broadcast(FLOATS, 0.0f);

    /**
     * The end of the linear segment.
     */
    private static final FloatVector THRESHOLD = FloatVector.broadcast(FLOATS, 0.0031308f);

    /**
     * The slope of the linear segment.
     */
    private static final FloatVector SLOPE = FloatVector.broadcast(FLOATS, 12.92f);

    /**
     * The numerator coefficients, highest degree first.
     */
    private static final FloatVector P4 = FloatVector.broadcast(FLOATS, 7.352629620e-01f);

    /**
     * The numerator coefficient of degree 3.
     */
    private static final FloatVector P3 = FloatVector.broadcast(FLOATS, 1.474205315e+00f);

    /**
     * The numerator coefficient of degree 2.
     */
    private static final FloatVector P2 = FloatVector.broadcast(FLOATS, 3.903842876e-01f);

    /**
     * The numerator coefficient of degree 1.
     */
    private static final FloatVector P1 = FloatVector.broadcast(FLOATS, 5.287254571e-03f);

    /**
     * The numerator constant, subtracted.
     */
    private static final FloatVector P0 = FloatVector.broadcast(FLOATS, 5.135152395e-04f);

    /**
     * The denominator coefficient of degree 4.
     */
    private static final FloatVector Q4 = FloatVector.broadcast(FLOATS, 2.424867759e-02f);

    /**
     * The denominator coefficient of degree 3.
     */
    private static final FloatVector Q3 = FloatVector.broadcast(FLOATS, 9.258482155e-01f);

    /**
     * The denominator coefficient of degree 2.
     */
    private static final FloatVector Q2 = FloatVector.broadcast(FLOATS, 1.340816930e+00f);

    /**
     * The denominator coefficient of degree 1.
     */
    private static final FloatVector Q1 = FloatVector.broadcast(FLOATS, 3.036675394e-01f);

    /**
     * The denominator constant.
     */
    private static final FloatVector Q0 = FloatVector.broadcast(FLOATS, 1.004519624e-02f);

    /**
     * Creates the kernel.
     */
    VectorSrgbKernel() {
    }

    /**
     * Returns the vector class of a species.
     *
     * @param species the vector shape
     * @param <E> the lane type
     * @param <V> the vector class
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void encode(float[] plane, int from, int to) {
        var i = from;
        for (; i + LANES <= to; i += LANES) {
            var v = FLOAT_VECTOR.cast(FloatVector.fromArray(FLOATS, plane, i));
            var a = v.lanewise(VectorOperators.ABS);
            var x = a.lanewise(VectorOperators.SQRT);
            var yp = P4.lanewise(VectorOperators.MUL, x).lanewise(VectorOperators.ADD, P3);
            yp = yp.lanewise(VectorOperators.MUL, x).lanewise(VectorOperators.ADD, P2);
            yp = yp.lanewise(VectorOperators.MUL, x).lanewise(VectorOperators.ADD, P1);
            yp = yp.lanewise(VectorOperators.MUL, x).lanewise(VectorOperators.SUB, P0);
            var yq = Q4.lanewise(VectorOperators.MUL, x).lanewise(VectorOperators.ADD, Q3);
            yq = yq.lanewise(VectorOperators.MUL, x).lanewise(VectorOperators.ADD, Q2);
            yq = yq.lanewise(VectorOperators.MUL, x).lanewise(VectorOperators.ADD, Q1);
            yq = yq.lanewise(VectorOperators.MUL, x).lanewise(VectorOperators.ADD, Q0);
            var magnitude = yp.lanewise(VectorOperators.DIV, yq)
                    .blend(SLOPE.lanewise(VectorOperators.MUL, a), a.compare(VectorOperators.LE, THRESHOLD));
            magnitude.blend(magnitude.lanewise(VectorOperators.NEG), v.compare(VectorOperators.LT, ZERO))
                    .intoArray(plane, i);
        }
        for (; i < to; i++) {
            plane[i] = ScalarSrgbKernel.encode(plane[i]);
        }
    }
}
