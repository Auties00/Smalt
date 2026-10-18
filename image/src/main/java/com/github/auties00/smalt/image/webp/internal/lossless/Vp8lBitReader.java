package com.github.auties00.smalt.image.webp.internal.lossless;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Reads a VP8L bit stream least-significant bit first through a 64-bit window.
 *
 * <p>The window {@link #val} holds the next 64 bits of input; {@link #bitPos} counts the bits of
 * the window already consumed. {@link #fill()} refills the window 32 bits at a time once half of
 * it is consumed, so after a fill at least 32 bits are available to table lookups. Past the end
 * of the input the window yields zero bits; {@link #eos()} reports when more bits were consumed
 * than the input holds.
 *
 * @implNote This implementation follows libwebp's {@code VP8LBitReader} of
 * {@code src/utils/bit_reader_utils.c} (BSD-3), including its end-of-stream rule; its fields are
 * accessed directly by the decoder's inner loop so the JIT keeps them in registers.
 */
final class Vp8lBitReader {
    /**
     * Reads a little-endian {@code int} from a byte array at any offset.
     */
    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Reads a little-endian {@code long} from a byte array at any offset.
     */
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The input.
     */
    private final byte[] buf;

    /**
     * The index one past the last input byte.
     */
    private final int end;

    /**
     * The index of the next byte to load into the window.
     */
    int pos;

    /**
     * The bit window.
     */
    long val;

    /**
     * The number of consumed bits of the window.
     */
    int bitPos;

    /**
     * Constructs a reader, loading the first 8 bytes (zero-padded when the input is shorter).
     *
     * @param data   the array holding the input
     * @param offset the index of the first byte
     * @param length the number of bytes
     */
    Vp8lBitReader(byte[] data, int offset, int length) {
        if (length < 8) {
            var padded = new byte[8];
            System.arraycopy(data, offset, padded, 0, length);
            data = padded;
            offset = 0;
            length = 8;
        }
        this.buf = data;
        this.end = offset + length;
        var value = 0L;
        for (var i = 0; i < 8; i++) {
            value |= (data[offset + i] & 0xFFL) << (8 * i);
        }
        this.val = value;
        this.pos = offset + 8;
    }

    /**
     * Returns the next 32 bits without consuming them; valid after {@link #fill()}.
     *
     * @return the upcoming bits, least significant first
     */
    int prefetch() {
        return (int) (val >>> bitPos);
    }

    /**
     * Consumes bits already in the window.
     *
     * @param bits the number of bits
     */
    void consume(int bits) {
        bitPos += bits;
    }

    /**
     * Refills the window when half of it is consumed.
     */
    void fill() {
        if (bitPos >= 32) {
            doFill();
        }
    }

    /**
     * Refills the window with 32 bits, or byte by byte near the end of the input.
     */
    private void doFill() {
        if (pos + 8 < end) {
            val >>>= 32;
            bitPos -= 32;
            val |= ((int) INT_LE.get(buf, pos) & 0xFFFFFFFFL) << 32;
            pos += 4;
            return;
        }
        shiftBytes();
    }

    /**
     * Moves whole consumed bytes out of the window and new bytes in, as many as possible.
     *
     * <p>The result is the same as shifting one byte at a time while at least 8 bits are
     * consumed and input remains; bytes are loaded eight at a time when the array allows it,
     * and only the bytes before {@link #end} enter the window.
     */
    private void shiftBytes() {
        var bytes = Math.min(Math.min(bitPos >>> 3, 8), end - pos);
        if (bytes <= 0) {
            return;
        }
        if (pos + 8 <= buf.length) {
            var next = (long) LONG_LE.get(buf, pos);
            val = bytes == 8 ? next : (val >>> (bytes << 3)) | (next << (64 - (bytes << 3)));
        } else {
            for (var i = 0; i < bytes; i++) {
                val = (val >>> 8) | ((buf[pos + i] & 0xFFL) << 56);
            }
        }
        pos += bytes;
        bitPos -= bytes << 3;
    }

    /**
     * Reads an unsigned field.
     *
     * @param nBits the field width, from 0 to 24
     * @return the field value
     */
    int read(int nBits) {
        var value = (int) (val >>> bitPos) & ((1 << nBits) - 1);
        bitPos += nBits;
        shiftBytes();
        return value;
    }

    /**
     * Reads one bit.
     *
     * @return {@code 0} or {@code 1}
     */
    int readBit() {
        return read(1);
    }

    /**
     * Returns whether more bits were consumed than the input holds.
     *
     * @return whether the stream overran its end
     */
    boolean eos() {
        return pos == end && bitPos > 64;
    }
}
