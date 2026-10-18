package com.github.auties00.smalt.internal.quantize;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link PaletteSearchKernel} with the Vector API; every result equals {@link ScalarPaletteSearchKernel}'s.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd}; it
 * folds two groups of eight entries per iteration into two running minimums of the keys, combined once at the end,
 * which is exact because the minimum does not depend on the comparison order. The class references
 * {@code jdk.incubator.vector} types and is loaded only when the Vector API is available.
 */
public final class VectorPaletteSearchKernel implements PaletteSearchKernel {
    /**
     * Whether the platform executes eight 32-bit lanes natively.
     */
    static final boolean WIDE = IntVector.SPECIES_PREFERRED.vectorBitSize() >= 256;

    /**
     * The species of eight 32-bit lanes.
     */
    private static final VectorSpecies<Integer> INTS = IntVector.SPECIES_256;

    /**
     * The exact class of the vectors of {@link #INTS}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(INTS);

    /**
     * The entry indices 0 to 255, loaded eight at a time as the low byte of the keys.
     */
    private static final int[] INDICES = indices();

    /**
     * The multiplier that shifts a distance left by 8 bits, in every lane.
     */
    private static final IntVector SHIFT = IntVector.broadcast(INTS, 256);

    /**
     * Zero in every lane.
     */
    private static final IntVector ZERO = IntVector.zero(INTS);

    /**
     * Constructs the kernel.
     */
    public VectorPaletteSearchKernel() {
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

    /**
     * Returns the indices 0 to 255.
     *
     * @return the array
     */
    private static int[] indices() {
        var indices = new int[256];
        for (var i = 0; i < indices.length; i++) {
            indices[i] = i;
        }
        return indices;
    }

    @Override
    public int nearest(int red, int green, int blue, int[] reds, int[] greens, int[] blues, int count) {
        var r = INT_VECTOR.cast(IntVector.broadcast(INTS, red));
        var g = INT_VECTOR.cast(IntVector.broadcast(INTS, green));
        var b = INT_VECTOR.cast(IntVector.broadcast(INTS, blue));
        var low = INT_VECTOR.cast(IntVector.broadcast(INTS, Integer.MAX_VALUE));
        var high = INT_VECTOR.cast(IntVector.broadcast(INTS, Integer.MAX_VALUE));
        for (var i = 0; i < count; i += 16) {
            var dr0 = INT_VECTOR.cast(IntVector.fromArray(INTS, reds, i)).lanewise(VectorOperators.SUB, r);
            var dg0 = INT_VECTOR.cast(IntVector.fromArray(INTS, greens, i)).lanewise(VectorOperators.SUB, g);
            var db0 = INT_VECTOR.cast(IntVector.fromArray(INTS, blues, i)).lanewise(VectorOperators.SUB, b);
            var distance0 = dr0.lanewise(VectorOperators.MUL, dr0)
                    .lanewise(VectorOperators.ADD, dg0.lanewise(VectorOperators.MUL, dg0))
                    .lanewise(VectorOperators.ADD, db0.lanewise(VectorOperators.MUL, db0));
            var key0 = distance0.lanewise(VectorOperators.MUL, SHIFT)
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(IntVector.fromArray(INTS, INDICES, i)));
            low = key0.lanewise(VectorOperators.MIN, low);
            var dr1 = INT_VECTOR.cast(IntVector.fromArray(INTS, reds, i + 8)).lanewise(VectorOperators.SUB, r);
            var dg1 = INT_VECTOR.cast(IntVector.fromArray(INTS, greens, i + 8)).lanewise(VectorOperators.SUB, g);
            var db1 = INT_VECTOR.cast(IntVector.fromArray(INTS, blues, i + 8)).lanewise(VectorOperators.SUB, b);
            var distance1 = dr1.lanewise(VectorOperators.MUL, dr1)
                    .lanewise(VectorOperators.ADD, dg1.lanewise(VectorOperators.MUL, dg1))
                    .lanewise(VectorOperators.ADD, db1.lanewise(VectorOperators.MUL, db1));
            var key1 = distance1.lanewise(VectorOperators.MUL, SHIFT)
                    .lanewise(VectorOperators.OR, INT_VECTOR.cast(IntVector.fromArray(INTS, INDICES, i + 8)));
            high = key1.lanewise(VectorOperators.MIN, high);
        }
        return INT_VECTOR.cast(low).lanewise(VectorOperators.MIN, high).reduceLanes(VectorOperators.MIN);
    }

    @Override
    public void bounds(int red, int green, int blue, int span, int[] reds, int[] greens, int[] blues, int count,
                       int[] near, int[] far) {
        var last = span - 1;
        var redLow = INT_VECTOR.cast(IntVector.broadcast(INTS, red));
        var greenLow = INT_VECTOR.cast(IntVector.broadcast(INTS, green));
        var blueLow = INT_VECTOR.cast(IntVector.broadcast(INTS, blue));
        var redHigh = INT_VECTOR.cast(IntVector.broadcast(INTS, red + last));
        var greenHigh = INT_VECTOR.cast(IntVector.broadcast(INTS, green + last));
        var blueHigh = INT_VECTOR.cast(IntVector.broadcast(INTS, blue + last));
        for (var i = 0; i < count; i += 8) {
            var r = INT_VECTOR.cast(IntVector.fromArray(INTS, reds, i));
            var g = INT_VECTOR.cast(IntVector.fromArray(INTS, greens, i));
            var b = INT_VECTOR.cast(IntVector.fromArray(INTS, blues, i));
            var nr = INT_VECTOR.cast(redLow).lanewise(VectorOperators.SUB, r)
                    .lanewise(VectorOperators.MAX, r.lanewise(VectorOperators.SUB, redHigh))
                    .lanewise(VectorOperators.MAX, ZERO);
            var ng = INT_VECTOR.cast(greenLow).lanewise(VectorOperators.SUB, g)
                    .lanewise(VectorOperators.MAX, g.lanewise(VectorOperators.SUB, greenHigh))
                    .lanewise(VectorOperators.MAX, ZERO);
            var nb = INT_VECTOR.cast(blueLow).lanewise(VectorOperators.SUB, b)
                    .lanewise(VectorOperators.MAX, b.lanewise(VectorOperators.SUB, blueHigh))
                    .lanewise(VectorOperators.MAX, ZERO);
            var fr = r.lanewise(VectorOperators.SUB, redLow).lanewise(VectorOperators.MAX,
                    INT_VECTOR.cast(redHigh).lanewise(VectorOperators.SUB, r));
            var fg = g.lanewise(VectorOperators.SUB, greenLow).lanewise(VectorOperators.MAX,
                    INT_VECTOR.cast(greenHigh).lanewise(VectorOperators.SUB, g));
            var fb = b.lanewise(VectorOperators.SUB, blueLow).lanewise(VectorOperators.MAX,
                    INT_VECTOR.cast(blueHigh).lanewise(VectorOperators.SUB, b));
            nr.lanewise(VectorOperators.MUL, nr)
                    .lanewise(VectorOperators.ADD, ng.lanewise(VectorOperators.MUL, ng))
                    .lanewise(VectorOperators.ADD, nb.lanewise(VectorOperators.MUL, nb))
                    .intoArray(near, i);
            fr.lanewise(VectorOperators.MUL, fr)
                    .lanewise(VectorOperators.ADD, fg.lanewise(VectorOperators.MUL, fg))
                    .lanewise(VectorOperators.ADD, fb.lanewise(VectorOperators.MUL, fb))
                    .intoArray(far, i);
        }
    }
}
