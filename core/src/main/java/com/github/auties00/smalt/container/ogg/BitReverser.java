package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Reverses the order of the bits inside every byte of a range, the transform that turns the
 * most-significant-bit-first Ogg CRC into the least-significant-bit-first CRC-32 of
 * {@link java.util.zip.CRC32}.
 *
 * <p>Two implementations exist: {@link ScalarBitReverser}, the reference, and
 * {@link VectorBitReverser}, which uses the Vector API. Both produce identical output.
 */
interface BitReverser {
    /**
     * The implementation selected for this JVM.
     */
    BitReverser INSTANCE = Simd.ENABLED ? VectorBitReverser.create() : new ScalarBitReverser();

    /**
     * Writes the bit-reversed bytes of {@code source[sourceOffset, sourceOffset + length)} to
     * {@code target[targetOffset, targetOffset + length)}.
     *
     * @param source       the input bytes
     * @param sourceOffset the index of the first input byte
     * @param target       the output array, distinct from {@code source}
     * @param targetOffset the index of the first output byte
     * @param length       the number of bytes
     */
    void reverse(byte[] source, int sourceOffset, byte[] target, int targetOffset, int length);
}
