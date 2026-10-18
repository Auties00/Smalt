/* Ports libvpx (vpx_dsp/bitreader.c, bitreader.h). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Decodes the boolean arithmetic code of a VP9 compressed header or tile.
 *
 * <p>The decoder keeps up to 64 bits of the input in a window whose top byte is the arithmetic
 * decoder's value; {@link #count} holds the number of buffered bits beyond that byte. Reading
 * past the end of the input shifts in zero bits and marks the window with {@link #LOTS_OF_BITS},
 * so decoding never fails while it runs; {@link #hasError()} reports afterwards whether the
 * input was overrun.
 *
 * <p>The fields are package-private so that the coefficient token reader can keep them in local
 * variables across its inner loop, as libvpx's {@code decode_coefs} does; it calls
 * {@link #fill()} when {@link #count} becomes negative.
 *
 * @implNote This implementation follows libvpx's {@code vpx_dsp/bitreader.c} and
 * {@code bitreader.h}, with a 64-bit window. A branch compares the top byte of
 * the window with the split instead of the whole window with the shifted split, which
 * is equivalent because the shifted split has zero low bits.
 */
final class Vp9BoolDecoder {
    /**
     * The number of bits of the window.
     */
    static final int BD_VALUE_SIZE = 64;

    /**
     * The count added once the input is exhausted, so that decoding continues with zero bits.
     */
    static final int LOTS_OF_BITS = 0x40000000;

    /**
     * Reads eight bytes of a byte array as a big-endian {@code long}.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The input.
     */
    byte[] buffer;

    /**
     * The index of the next input byte to load into the window.
     */
    int position;

    /**
     * The index one past the last input byte.
     */
    int end;

    /**
     * The window, most significant bits first.
     */
    long value;

    /**
     * The number of buffered bits beyond the top byte of the window; negative when the window
     * must be refilled.
     */
    int count;

    /**
     * The current range, from 128 to 255 between symbols.
     */
    int range;

    /**
     * Creates an idle decoder.
     */
    public Vp9BoolDecoder() {
    }

    /**
     * Starts decoding {@code size} bytes of {@code data} from {@code offset}.
     *
     * @param data the input
     * @param offset the index of the first byte
     * @param size the number of bytes
     * @return {@code true} when the marker bit that starts every partition is set, which makes
     * the partition invalid
     */
    public boolean init(byte[] data, int offset, int size) {
        buffer = data;
        position = offset;
        end = offset + size;
        value = 0;
        count = -8;
        range = 255;
        fill();
        return readBool(128) != 0;
    }

    /**
     * Refills the window from the input, appending zero bits once the input is exhausted.
     */
    void fill() {
        var bytesLeft = end - position;
        var bitsLeft = (long) bytesLeft * 8;
        var shift = BD_VALUE_SIZE - 8 - (count + 8);
        if (bitsLeft > BD_VALUE_SIZE) {
            var bits = (shift & 0xFFFFFFF8) + 8;
            var bigEndian = (long) LONG_BE.get(buffer, position);
            var next = bigEndian >>> (BD_VALUE_SIZE - bits);
            count += bits;
            position += bits >> 3;
            value |= next << (shift & 7);
        } else {
            var bitsOver = (int) (shift + 8 - bitsLeft);
            var loopEnd = 0;
            if (bitsOver >= 0) {
                count += LOTS_OF_BITS;
                loopEnd = bitsOver;
            }
            if (bitsOver < 0 || bitsLeft != 0) {
                while (shift >= loopEnd) {
                    count += 8;
                    value |= (long) (buffer[position++] & 0xFF) << shift;
                    shift -= 8;
                }
            }
        }
    }

    /**
     * Decodes one boolean.
     *
     * @param probability the probability, out of 256, that the boolean is zero
     * @return {@code 0} or {@code 1}
     */
    public int readBool(int probability) {
        var split = (range * probability + (256 - probability)) >> 8;
        if (count < 0) {
            fill();
        }
        var window = value;
        int bit;
        int newRange;
        if ((int) (window >>> 56) >= split) {
            newRange = range - split;
            window -= (long) split << 56;
            bit = 1;
        } else {
            newRange = split;
            bit = 0;
        }
        var shift = Integer.numberOfLeadingZeros(newRange) - 24;
        range = newRange << shift;
        value = window << shift;
        count -= shift;
        return bit;
    }

    /**
     * Decodes one boolean with probability one half.
     *
     * @return {@code 0} or {@code 1}
     */
    public int readBit() {
        return readBool(128);
    }

    /**
     * Decodes an unsigned literal of {@code bits} equiprobable bits, most significant first.
     *
     * @param bits the number of bits, at most 30
     * @return the literal
     */
    public int readLiteral(int bits) {
        var literal = 0;
        for (var bit = bits - 1; bit >= 0; bit--) {
            literal |= readBool(128) << bit;
        }
        return literal;
    }

    /**
     * Decodes a symbol of a binary tree.
     *
     * <p>Node {@code i} of the tree is the pair {@code tree[i]}, {@code tree[i + 1]}; a
     * positive entry is the index of the next node and a non-positive one the negated symbol.
     * Node {@code i} is coded with probability {@code probabilities[offset + i / 2]}.
     *
     * @implNote This implementation keeps the window, range and bit count in local variables for
     * the whole walk and stores them back once, rather than once per node.
     * @param tree the tree in libvpx layout: child pairs, a leaf {@code -s} standing for symbol {@code s}
     * @param probabilities the node probabilities
     * @param offset the index of the first node's probability
     * @return the symbol
     */
    public int readTree(int[] tree, int[] probabilities, int offset) {
        var window = value;
        var currentRange = range;
        var bits = count;
        var node = 0;
        do {
            var probability = probabilities[offset + (node >> 1)];
            var split = (currentRange * probability + (256 - probability)) >> 8;
            if (bits < 0) {
                value = window;
                count = bits;
                fill();
                window = value;
                bits = count;
            }
            var bit = 0;
            if ((int) (window >>> 56) >= split) {
                currentRange -= split;
                window -= (long) split << 56;
                bit = 1;
            } else {
                currentRange = split;
            }
            var shift = Integer.numberOfLeadingZeros(currentRange) - 24;
            currentRange <<= shift;
            window <<= shift;
            bits -= shift;
            node = tree[node + bit];
        } while (node > 0);
        value = window;
        range = currentRange;
        count = bits;
        return -node;
    }

    /**
     * Returns whether the decoder read past the end of its input.
     *
     * @return {@code true} once more bits were decoded than the input held
     */
    public boolean hasError() {
        return count > BD_VALUE_SIZE && count < LOTS_OF_BITS;
    }

    /**
     * Returns the index one past the last input byte the decoder consumed, rewinding the bytes
     * that were loaded into the window but not used.
     *
     * @return the index of the first unread byte
     */
    public int findEnd() {
        while (count > 8 && count < BD_VALUE_SIZE) {
            count -= 8;
            position--;
        }
        return position;
    }
}
