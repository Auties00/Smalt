package com.github.auties00.smalt.image.raw.internal;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Interpolates Bayer rows with the Vector API: the vector twin of {@link ScalarBayerKernel}.
 *
 * <p>Each vector covers an even number of photosites starting at an even offset of the segment, so
 * its even lanes hold the segment's first color and its odd lanes the second; every interpolation
 * is computed for all lanes and the lanes are then chosen with a constant mask. The arithmetic of
 * each lane is the scalar kernel's, in the same order and single precision, so the results are
 * bit-identical.
 *
 * @implNote This implementation follows the rules of the module's JIT guard: loads are cast
 * through constant classes, operations use the per-shape {@code lanewise} and
 * {@code blend} methods with vector operands, and no vector crosses a loop iteration.
 */
final class VectorBayerKernel implements BayerKernel {
    /**
     * The shape of the vectors.
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
     * The odd lanes.
     */
    private static final VectorMask<Float> ODD = VectorMask.fromLong(F, 0xAAAAAAAAAAAAAAAAL);

    /**
     * The scalar twin, for the segment's tail.
     */
    private static final ScalarBayerKernel SCALAR = new ScalarBayerKernel();

    /**
     * Constructs the kernel.
     */
    private VectorBayerKernel() {
    }

    /**
     * Creates the kernel.
     *
     * @return the kernel
     */
    static BayerKernel create() {
        return new VectorBayerKernel();
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
    public void row(float[] data, int up, int mid, int down, int count, int pair, float[] r, float[] g, float[] b,
                    int out) {
        var bound = count - count % L;
        var rowRed = pair == RED_GREEN || pair == GREEN_RED;
        var rowColor = rowRed ? r : b;
        var otherColor = rowRed ? b : r;
        if (pair == GREEN_RED || pair == GREEN_BLUE) {
            greenFirst(data, up, mid, down, bound, g, rowColor, otherColor, out);
        } else {
            colorFirst(data, up, mid, down, bound, g, rowColor, otherColor, out);
        }
        if (bound < count) {
            SCALAR.row(data, up + bound, mid + bound, down + bound, count - bound, pair, r, g, b, out + bound);
        }
    }

    /**
     * Interpolates whole vectors of a segment that starts with a green photosite.
     *
     * @param data the image values
     * @param up the index of the photosite above the first one
     * @param mid the index of the first photosite
     * @param down the index of the photosite below the first one
     * @param bound the number of photosites, a multiple of the lane count
     * @param g receives the green values
     * @param rowColor receives the values of the row's other color
     * @param otherColor receives the values of the color of the rows above and below
     * @param out the index of the first value written
     */
    private static void greenFirst(float[] data, int up, int mid, int down, int bound, float[] g, float[] rowColor,
                                   float[] otherColor, int out) {
        var half = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 0.5f));
        var quarter = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 0.25f));
        for (var index = 0; index < bound; index += L) {
            var x = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, mid + index));
            var left = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, mid + index - 1));
            var right = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, mid + index + 1));
            var above = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, up + index));
            var below = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, down + index));
            var aboveLeft = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, up + index - 1));
            var aboveRight = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, up + index + 1));
            var belowLeft = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, down + index - 1));
            var belowRight = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, down + index + 1));
            var h = left.lanewise(VectorOperators.ADD, right).lanewise(VectorOperators.MUL, half);
            var v = above.lanewise(VectorOperators.ADD, below).lanewise(VectorOperators.MUL, half);
            var p = above.lanewise(VectorOperators.ADD, left).lanewise(VectorOperators.ADD, right)
                    .lanewise(VectorOperators.ADD, below).lanewise(VectorOperators.MUL, quarter);
            var c = aboveLeft.lanewise(VectorOperators.ADD, aboveRight).lanewise(VectorOperators.ADD, belowLeft)
                    .lanewise(VectorOperators.ADD, belowRight).lanewise(VectorOperators.MUL, quarter);
            FLOAT_VECTOR.cast(x.blend(p, ODD)).intoArray(g, out + index);
            FLOAT_VECTOR.cast(h.blend(x, ODD)).intoArray(rowColor, out + index);
            FLOAT_VECTOR.cast(v.blend(c, ODD)).intoArray(otherColor, out + index);
        }
    }

    /**
     * Interpolates whole vectors of a segment that starts with a red or blue photosite.
     *
     * @param data the image values
     * @param up the index of the photosite above the first one
     * @param mid the index of the first photosite
     * @param down the index of the photosite below the first one
     * @param bound the number of photosites, a multiple of the lane count
     * @param g receives the green values
     * @param rowColor receives the values of the row's other color
     * @param otherColor receives the values of the color of the rows above and below
     * @param out the index of the first value written
     */
    private static void colorFirst(float[] data, int up, int mid, int down, int bound, float[] g, float[] rowColor,
                                   float[] otherColor, int out) {
        var half = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 0.5f));
        var quarter = FLOAT_VECTOR.cast(FloatVector.broadcast(F, 0.25f));
        for (var index = 0; index < bound; index += L) {
            var x = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, mid + index));
            var left = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, mid + index - 1));
            var right = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, mid + index + 1));
            var above = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, up + index));
            var below = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, down + index));
            var aboveLeft = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, up + index - 1));
            var aboveRight = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, up + index + 1));
            var belowLeft = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, down + index - 1));
            var belowRight = FLOAT_VECTOR.cast(FloatVector.fromArray(F, data, down + index + 1));
            var h = left.lanewise(VectorOperators.ADD, right).lanewise(VectorOperators.MUL, half);
            var v = above.lanewise(VectorOperators.ADD, below).lanewise(VectorOperators.MUL, half);
            var p = above.lanewise(VectorOperators.ADD, left).lanewise(VectorOperators.ADD, right)
                    .lanewise(VectorOperators.ADD, below).lanewise(VectorOperators.MUL, quarter);
            var c = aboveLeft.lanewise(VectorOperators.ADD, aboveRight).lanewise(VectorOperators.ADD, belowLeft)
                    .lanewise(VectorOperators.ADD, belowRight).lanewise(VectorOperators.MUL, quarter);
            FLOAT_VECTOR.cast(p.blend(x, ODD)).intoArray(g, out + index);
            FLOAT_VECTOR.cast(x.blend(h, ODD)).intoArray(rowColor, out + index);
            FLOAT_VECTOR.cast(c.blend(v, ODD)).intoArray(otherColor, out + index);
        }
    }
}
