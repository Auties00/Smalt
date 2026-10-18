package com.github.auties00.smalt.internal.plane;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Implements {@link Narrow} with the Vector API; the output equals {@link ScalarNarrow} byte for
 * byte.
 *
 * @implNote This implementation follows the kernel rules of
 * {@link com.github.auties00.smalt.internal.simd.Simd Simd}. It rounds as
 * {@code (v >>> shift) + ((v >>> (shift - 1)) & 1)}, which equals {@code (v + half) >> shift} for
 * every unsigned 16-bit {@code v} without overflowing a lane, and clamps with an unsigned minimum.
 * The samples at the end of a row that do not fill a vector use the scalar kernel.
 */
final class VectorNarrow extends Narrow {
    /**
     * The 16-lane short species of the source samples.
     */
    private static final VectorSpecies<Short> S256 = ShortVector.SPECIES_256;

    /**
     * The 16-lane byte species of the output samples.
     */
    private static final VectorSpecies<Byte> B128 = ByteVector.SPECIES_128;

    /**
     * The exact class of {@link #S256} vectors.
     */
    private static final Class<ShortVector> SHORT256 = vectorClass(S256);

    /**
     * The exact class of {@link #B128} vectors.
     */
    private static final Class<ByteVector> BYTE128 = vectorClass(B128);

    /**
     * Sixteen shorts equal to 255, the largest output sample.
     */
    private static final ShortVector MAX_SAMPLE = ShortVector.broadcast(S256, (short) 255);

    /**
     * Sixteen shorts equal to 1, the mask of the rounding bit.
     */
    private static final ShortVector ONE = ShortVector.broadcast(S256, (short) 1);

    /**
     * The kernels of the row ends.
     */
    private final ScalarNarrow scalar;

    /**
     * Constructs the kernels.
     */
    private VectorNarrow() {
        this.scalar = new ScalarNarrow();
    }

    /**
     * {@return the vector kernels}
     */
    static Narrow create() {
        return new VectorNarrow();
    }

    /**
     * Returns the exact class of the vectors of a species.
     *
     * @param species the species whose vectors are wanted
     * @param <E> the element type
     * @param <V> the vector type
     * @return the class
     */
    @SuppressWarnings("unchecked")
    private static <E, V> Class<V> vectorClass(VectorSpecies<E> species) {
        return (Class<V>) species.vectorType();
    }

    @Override
    public void narrow(short[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                       int width, int height, int shift) {
        var full = width & ~15;
        if (shift == 0) {
            copyClamped(src, srcOffset, srcStride, dst, dstOffset, dstStride, full, height);
        } else {
            round(src, srcOffset, srcStride, dst, dstOffset, dstStride, full, height, shift);
        }
        if (full < width) {
            scalar.narrow(src, srcOffset + full, srcStride, dst, dstOffset + full, dstStride, width - full, height,
                    shift);
        }
    }

    /**
     * Clamps rows of whole vectors to 8 bits.
     *
     * @param src the 16-bit samples
     * @param srcOffset the index of the first sample of the first row
     * @param srcStride the source row stride
     * @param dst the 8-bit samples
     * @param dstOffset the index of the first sample of the first row
     * @param dstStride the destination row stride
     * @param full the number of samples per row, a multiple of 16
     * @param height the number of rows
     */
    private static void copyClamped(short[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                    int dstStride, int full, int height) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < full; x += 16) {
                var value = SHORT256.cast(SHORT256.cast(ShortVector.fromArray(S256, src, s + x))
                        .lanewise(VectorOperators.UMIN, MAX_SAMPLE));
                BYTE128.cast(value.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d + x);
            }
        }
    }

    /**
     * Rounds rows of whole vectors to 8 bits.
     *
     * @param src the 16-bit samples
     * @param srcOffset the index of the first sample of the first row
     * @param srcStride the source row stride
     * @param dst the 8-bit samples
     * @param dstOffset the index of the first sample of the first row
     * @param dstStride the destination row stride
     * @param full the number of samples per row, a multiple of 16
     * @param height the number of rows
     * @param shift the bit depth minus 8, from 1 to 8
     */
    private static void round(short[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                              int full, int height, int shift) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < full; x += 16) {
                var sample = SHORT256.cast(ShortVector.fromArray(S256, src, s + x));
                var quotient = SHORT256.cast(sample.lanewise(VectorOperators.LSHR, shift));
                var carry = SHORT256.cast(SHORT256.cast(sample.lanewise(VectorOperators.LSHR, shift - 1))
                        .lanewise(VectorOperators.AND, ONE));
                var value = SHORT256.cast(SHORT256.cast(quotient.lanewise(VectorOperators.ADD, carry))
                        .lanewise(VectorOperators.UMIN, MAX_SAMPLE));
                BYTE128.cast(value.convertShape(VectorOperators.S2B, B128, 0)).intoArray(dst, d + x);
            }
        }
    }
}
