package com.github.auties00.smalt.image.color;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link MatShaperKernel} with the Vector API; the output equals {@link ScalarMatShaperKernel} sample for
 * sample.
 *
 * @implNote This implementation follows the kernel rules of {@link com.github.auties00.smalt.internal.simd.Simd}; it
 * runs the input table lookups and the output table lookups in scalar code, since they are gathers, and only the
 * fixed-point matrix on 32-bit lanes; the passes exchange values through the caller's scratch space.
 */
final class VectorMatShaperKernel implements MatShaperKernel {
    /**
     * The integer species: 256 bits when the hardware has them, else 128 bits.
     */
    private static final VectorSpecies<Integer> I = IntVector.SPECIES_PREFERRED.vectorBitSize() >= 256
            ? IntVector.SPECIES_256 : IntVector.SPECIES_128;

    /**
     * The number of pixels per vector.
     */
    private static final int L = I.length();

    /**
     * The exact class of the vectors of {@link #I}.
     */
    private static final Class<IntVector> INT_VECTOR = vectorClass(I);

    /**
     * The number of fraction bits of the fixed-point values.
     */
    private static final int SHIFT = 14;

    /**
     * Creates the kernel.
     */
    private VectorMatShaperKernel() {
    }

    /**
     * Creates the kernel.
     *
     * @return the vector kernel
     */
    static MatShaperKernel create() {
        return new VectorMatShaperKernel();
    }

    /**
     * Returns the exact vector class of a species.
     *
     * @param species the vector species whose class to return
     * @param <E> the lane type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count,
                      Tables tables, int[] scratch) {
        var red = tables.red();
        var green = tables.green();
        var blue = tables.blue();
        var s = srcOffset;
        for (var i = 0; i < count; i++) {
            scratch[i] = red[src[s] & 0xFF];
            scratch[BLOCK + i] = green[src[s + 1] & 0xFF];
            scratch[2 * BLOCK + i] = blue[src[s + 2] & 0xFF];
            s += srcStride;
        }
        var m = tables.matrix();
        var o = tables.offset();
        var m00 = INT_VECTOR.cast(IntVector.broadcast(I, m[0]));
        var m01 = INT_VECTOR.cast(IntVector.broadcast(I, m[1]));
        var m02 = INT_VECTOR.cast(IntVector.broadcast(I, m[2]));
        var m10 = INT_VECTOR.cast(IntVector.broadcast(I, m[3]));
        var m11 = INT_VECTOR.cast(IntVector.broadcast(I, m[4]));
        var m12 = INT_VECTOR.cast(IntVector.broadcast(I, m[5]));
        var m20 = INT_VECTOR.cast(IntVector.broadcast(I, m[6]));
        var m21 = INT_VECTOR.cast(IntVector.broadcast(I, m[7]));
        var m22 = INT_VECTOR.cast(IntVector.broadcast(I, m[8]));
        var k0 = INT_VECTOR.cast(IntVector.broadcast(I, o[0] + 0x2000));
        var k1 = INT_VECTOR.cast(IntVector.broadcast(I, o[1] + 0x2000));
        var k2 = INT_VECTOR.cast(IntVector.broadcast(I, o[2] + 0x2000));
        var zero = INT_VECTOR.cast(IntVector.zero(I));
        var limit = INT_VECTOR.cast(IntVector.broadcast(I, 16384));
        var bound = count - count % L;
        for (var j = 0; j < bound; j += L) {
            var r = INT_VECTOR.cast(IntVector.fromArray(I, scratch, j));
            var g = INT_VECTOR.cast(IntVector.fromArray(I, scratch, BLOCK + j));
            var b = INT_VECTOR.cast(IntVector.fromArray(I, scratch, 2 * BLOCK + j));
            var l0 = INT_VECTOR.cast(r.lanewise(VectorOperators.MUL, m00)
                            .lanewise(VectorOperators.ADD, g.lanewise(VectorOperators.MUL, m01))
                            .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, m02))
                            .lanewise(VectorOperators.ADD, k0))
                    .lanewise(VectorOperators.ASHR, SHIFT)
                    .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, limit);
            var l1 = INT_VECTOR.cast(r.lanewise(VectorOperators.MUL, m10)
                            .lanewise(VectorOperators.ADD, g.lanewise(VectorOperators.MUL, m11))
                            .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, m12))
                            .lanewise(VectorOperators.ADD, k1))
                    .lanewise(VectorOperators.ASHR, SHIFT)
                    .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, limit);
            var l2 = INT_VECTOR.cast(r.lanewise(VectorOperators.MUL, m20)
                            .lanewise(VectorOperators.ADD, g.lanewise(VectorOperators.MUL, m21))
                            .lanewise(VectorOperators.ADD, b.lanewise(VectorOperators.MUL, m22))
                            .lanewise(VectorOperators.ADD, k2))
                    .lanewise(VectorOperators.ASHR, SHIFT)
                    .lanewise(VectorOperators.MAX, zero).lanewise(VectorOperators.MIN, limit);
            INT_VECTOR.cast(l0).intoArray(scratch, 3 * BLOCK + j);
            INT_VECTOR.cast(l1).intoArray(scratch, 4 * BLOCK + j);
            INT_VECTOR.cast(l2).intoArray(scratch, 5 * BLOCK + j);
        }
        for (var j = bound; j < count; j++) {
            var r = scratch[j];
            var g = scratch[BLOCK + j];
            var b = scratch[2 * BLOCK + j];
            scratch[3 * BLOCK + j] = Math.min(Math.max((m[0] * r + m[1] * g + m[2] * b + o[0] + 0x2000) >> SHIFT, 0), 16384);
            scratch[4 * BLOCK + j] = Math.min(Math.max((m[3] * r + m[4] * g + m[5] * b + o[1] + 0x2000) >> SHIFT, 0), 16384);
            scratch[5 * BLOCK + j] = Math.min(Math.max((m[6] * r + m[7] * g + m[8] * b + o[2] + 0x2000) >> SHIFT, 0), 16384);
        }
        var redOut = tables.redOut();
        var greenOut = tables.greenOut();
        var blueOut = tables.blueOut();
        var d = dstOffset;
        for (var i = 0; i < count; i++) {
            dst[d] = redOut[scratch[3 * BLOCK + i]];
            dst[d + 1] = greenOut[scratch[4 * BLOCK + i]];
            dst[d + 2] = blueOut[scratch[5 * BLOCK + i]];
            d += dstStride;
        }
    }
}
