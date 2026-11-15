package com.github.auties00.smalt.internal.plane;

import com.github.auties00.smalt.internal.simd.Simd;
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
 * A vector holds 16 samples where {@link Simd#WIDE} holds and 8 otherwise; the samples at the end
 * of a row that do not fill a vector use the scalar kernel.
 */
final class VectorNarrow extends Narrow {
    /**
     * The short species of the source samples: 16 lanes where the platform has 256-bit vectors, 8
     * otherwise.
     */
    private static final VectorSpecies<Short> SHORTS = Simd.WIDE ? ShortVector.SPECIES_256 : ShortVector.SPECIES_128;

    /**
     * The byte species of the output samples, with as many lanes as {@link #SHORTS}.
     */
    private static final VectorSpecies<Byte> BYTES = Simd.WIDE ? ByteVector.SPECIES_128 : ByteVector.SPECIES_64;

    /**
     * The number of samples per vector.
     */
    private static final int LANES = SHORTS.length();

    /**
     * The exact class of {@link #SHORTS} vectors.
     */
    private static final Class<ShortVector> SHORT_VECTOR = vectorClass(SHORTS);

    /**
     * The exact class of {@link #BYTES} vectors.
     */
    private static final Class<ByteVector> BYTE_VECTOR = vectorClass(BYTES);

    /**
     * Shorts equal to 255, the largest output sample.
     */
    private static final ShortVector MAX_SAMPLE = ShortVector.broadcast(SHORTS, (short) 255);

    /**
     * Shorts equal to 1, the mask of the rounding bit.
     */
    private static final ShortVector ONE = ShortVector.broadcast(SHORTS, (short) 1);

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
        var full = width - width % LANES;
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
     * @param full the number of samples per row, a multiple of {@link #LANES}
     * @param height the number of rows
     */
    private static void copyClamped(short[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset,
                                    int dstStride, int full, int height) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < full; x += LANES) {
                var value = SHORT_VECTOR.cast(SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, src, s + x))
                        .lanewise(VectorOperators.UMIN, MAX_SAMPLE));
                BYTE_VECTOR.cast(value.convertShape(VectorOperators.S2B, BYTES, 0)).intoArray(dst, d + x);
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
     * @param full the number of samples per row, a multiple of {@link #LANES}
     * @param height the number of rows
     * @param shift the bit depth minus 8, from 1 to 8
     */
    private static void round(short[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                              int full, int height, int shift) {
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < full; x += LANES) {
                var sample = SHORT_VECTOR.cast(ShortVector.fromArray(SHORTS, src, s + x));
                var quotient = SHORT_VECTOR.cast(sample.lanewise(VectorOperators.LSHR, shift));
                var carry = SHORT_VECTOR.cast(SHORT_VECTOR.cast(sample.lanewise(VectorOperators.LSHR, shift - 1))
                        .lanewise(VectorOperators.AND, ONE));
                var value = SHORT_VECTOR.cast(SHORT_VECTOR.cast(quotient.lanewise(VectorOperators.ADD, carry))
                        .lanewise(VectorOperators.UMIN, MAX_SAMPLE));
                BYTE_VECTOR.cast(value.convertShape(VectorOperators.S2B, BYTES, 0)).intoArray(dst, d + x);
            }
        }
    }
}
