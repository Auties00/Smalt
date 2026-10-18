package com.github.auties00.smalt.image.raw.internal;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Applies color matrices with the Vector API: the vector twin of {@link ScalarColorKernel}.
 *
 * <p>Every lane runs the scalar kernel's arithmetic in the same order and single precision,
 * without fused multiply-add, so the results are bit-identical to it.
 *
 * @implNote This implementation follows the rules of the module's JIT guard: loads are cast
 * through constant classes, operations use the per-shape {@code lanewise} methods with
 * vector operands, and no vector crosses a loop iteration.
 */
final class VectorColorKernel implements ColorKernel {
    /**
     * The shape of the vectors: 256 bits where the platform prefers at least that, else 128.
     */
    private static final VectorSpecies<Float> F = FloatVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? FloatVector.SPECIES_256 : FloatVector.SPECIES_128;

    /**
     * The number of lanes.
     */
    private static final int L = F.length();

    /**
     * The concrete class of the vectors.
     */
    private static final Class<FloatVector> FLOAT_VECTOR = vectorClass(F);

    /**
     * Constructs the kernel.
     */
    private VectorColorKernel() {
    }

    /**
     * Creates the kernel.
     *
     * @return the kernel
     */
    static ColorKernel create() {
        return new VectorColorKernel();
    }

    /**
     * Returns the concrete vector class of a species.
     *
     * @param species the vector species whose class is returned
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void transform(float[] r, float[] g, float[] b, int count, float[] matrix, float scale) {
        var m0 = FLOAT_VECTOR.cast(FloatVector.broadcast(F, matrix[0]));
        var m1 = FLOAT_VECTOR.cast(FloatVector.broadcast(F, matrix[1]));
        var m2 = FLOAT_VECTOR.cast(FloatVector.broadcast(F, matrix[2]));
        var m3 = FLOAT_VECTOR.cast(FloatVector.broadcast(F, matrix[3]));
        var m4 = FLOAT_VECTOR.cast(FloatVector.broadcast(F, matrix[4]));
        var m5 = FLOAT_VECTOR.cast(FloatVector.broadcast(F, matrix[5]));
        var m6 = FLOAT_VECTOR.cast(FloatVector.broadcast(F, matrix[6]));
        var m7 = FLOAT_VECTOR.cast(FloatVector.broadcast(F, matrix[7]));
        var m8 = FLOAT_VECTOR.cast(FloatVector.broadcast(F, matrix[8]));
        var zero = FLOAT_VECTOR.cast(FloatVector.zero(F));
        var one = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 1f));
        var factor = FLOAT_VECTOR.cast(FloatVector.broadcast(F, scale));
        var bound = count - count % L;
        for (var index = 0; index < bound; index += L) {
            var red = FLOAT_VECTOR.cast(FloatVector.fromArray(F, r, index));
            var green = FLOAT_VECTOR.cast(FloatVector.fromArray(F, g, index));
            var blue = FLOAT_VECTOR.cast(FloatVector.fromArray(F, b, index));
            var x = FLOAT_VECTOR.cast(red.lanewise(VectorOperators.MUL, m0)
                    .lanewise(VectorOperators.ADD, green.lanewise(VectorOperators.MUL, m1))
                    .lanewise(VectorOperators.ADD, blue.lanewise(VectorOperators.MUL, m2))
                    .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, one)
                    .lanewise(VectorOperators.MUL, factor));
            var y = FLOAT_VECTOR.cast(red.lanewise(VectorOperators.MUL, m3)
                    .lanewise(VectorOperators.ADD, green.lanewise(VectorOperators.MUL, m4))
                    .lanewise(VectorOperators.ADD, blue.lanewise(VectorOperators.MUL, m5))
                    .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, one)
                    .lanewise(VectorOperators.MUL, factor));
            var z = FLOAT_VECTOR.cast(red.lanewise(VectorOperators.MUL, m6)
                    .lanewise(VectorOperators.ADD, green.lanewise(VectorOperators.MUL, m7))
                    .lanewise(VectorOperators.ADD, blue.lanewise(VectorOperators.MUL, m8))
                    .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, one)
                    .lanewise(VectorOperators.MUL, factor));
            x.intoArray(r, index);
            y.intoArray(g, index);
            z.intoArray(b, index);
        }
        for (var index = bound; index < count; index++) {
            var red = r[index];
            var green = g[index];
            var blue = b[index];
            var x = matrix[0] * red + matrix[1] * green + matrix[2] * blue;
            var y = matrix[3] * red + matrix[4] * green + matrix[5] * blue;
            var z = matrix[6] * red + matrix[7] * green + matrix[8] * blue;
            r[index] = Math.min(Math.max(x, 0f), 1f) * scale;
            g[index] = Math.min(Math.max(y, 0f), 1f) * scale;
            b[index] = Math.min(Math.max(z, 0f), 1f) * scale;
        }
    }

    @Override
    public void scale(float[] r, float[] g, float[] b, int count, float scale) {
        var zero = FLOAT_VECTOR.cast(FloatVector.zero(F));
        var one = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 1f));
        var factor = FLOAT_VECTOR.cast(FloatVector.broadcast(F, scale));
        var bound = count - count % L;
        for (var index = 0; index < bound; index += L) {
            var red = FLOAT_VECTOR.cast(FloatVector.fromArray(F, r, index));
            var green = FLOAT_VECTOR.cast(FloatVector.fromArray(F, g, index));
            var blue = FLOAT_VECTOR.cast(FloatVector.fromArray(F, b, index));
            FLOAT_VECTOR.cast(red.lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, one)
                    .lanewise(VectorOperators.MUL, factor)).intoArray(r, index);
            FLOAT_VECTOR.cast(green.lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, one)
                    .lanewise(VectorOperators.MUL, factor)).intoArray(g, index);
            FLOAT_VECTOR.cast(blue.lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, one)
                    .lanewise(VectorOperators.MUL, factor)).intoArray(b, index);
        }
        for (var index = bound; index < count; index++) {
            r[index] = Math.min(Math.max(r[index], 0f), 1f) * scale;
            g[index] = Math.min(Math.max(g[index], 0f), 1f) * scale;
            b[index] = Math.min(Math.max(b[index], 0f), 1f) * scale;
        }
    }
}
