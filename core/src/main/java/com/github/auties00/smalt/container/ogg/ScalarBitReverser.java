package com.github.auties00.smalt.container.ogg;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Implements {@link BitReverser} with plain loops; the reference for {@link VectorBitReverser}.
 *
 * @implNote This implementation reverses eight bytes at a time inside a {@code long} with three
 * mask-and-shift swaps (adjacent bits, bit pairs, nibbles), none of which moves a bit across a
 * byte boundary.
 */
final class ScalarBitReverser implements BitReverser {
    /**
     * Reads and writes eight bytes at once; the byte order is irrelevant to a per-byte transform.
     */
    private static final VarHandle LONGS = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Reverses the bits of every byte of a word.
     *
     * @param value eight bytes
     * @return the eight bytes, each with its bits reversed
     */
    static long reverseEach(long value) {
        value = ((value >>> 1) & 0x5555555555555555L) | ((value & 0x5555555555555555L) << 1);
        value = ((value >>> 2) & 0x3333333333333333L) | ((value & 0x3333333333333333L) << 2);
        return ((value >>> 4) & 0x0F0F0F0F0F0F0F0FL) | ((value & 0x0F0F0F0F0F0F0F0FL) << 4);
    }

    @Override
    public void reverse(byte[] source, int sourceOffset, byte[] target, int targetOffset, int length) {
        var index = 0;
        for (; index + 8 <= length; index += 8) {
            LONGS.set(target, targetOffset + index, reverseEach((long) LONGS.get(source, sourceOffset + index)));
        }
        for (; index < length; index++) {
            target[targetOffset + index] = (byte) reverseEach(source[sourceOffset + index] & 0xFFL);
        }
    }
}
