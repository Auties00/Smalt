package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.MediaException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Reads the most-significant-bit-first bit syntax of FLAC subframes, with a dedicated Rice block
 * decoder.
 *
 * <p>Bits are held left-aligned in a 64-bit cache refilled with one unaligned eight-byte load.
 * Reads past the end of the range yield zero bits and are detected afterwards through
 * {@link #overrun()}; the only read that could otherwise spin on those phantom zeros, the unary
 * prefix of a Rice code, fails immediately with {@link MediaException.Malformed} instead.
 *
 * @implNote This implementation keeps the refill trick of the module's shared MSB reader: a
 * refill ORs a whole eight-byte word shifted into place and accounts only for the whole bytes that
 * fit, so the bits of a partially loaded byte are re-ORed at the same position by the next refill
 * and never corrupt the cache. The Rice decoder copies the reader state into locals for its loop,
 * which keeps the cache in registers.
 */
final class FlacBitReader {
    /**
     * The big-endian {@code long} view used by refills.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The array being read.
     */
    private byte[] data;

    /**
     * The index one past the last byte of the range.
     */
    private int end;

    /**
     * The index of the first byte of the range.
     */
    private int start;

    /**
     * The index of the next byte to load into the cache.
     */
    private int next;

    /**
     * The buffered bits, left-aligned; bits after the first {@link #count} are ignored.
     */
    private long cache;

    /**
     * The number of valid bits in {@link #cache}.
     */
    private int count;

    /**
     * Constructs a reader over an empty range.
     */
    FlacBitReader() {
        this.data = new byte[0];
    }

    /**
     * Positions the reader at the start of a byte range.
     *
     * @param data   the array to read
     * @param offset the index of the first byte
     * @param length the number of bytes
     */
    void reset(byte[] data, int offset, int length) {
        this.data = data;
        this.start = offset;
        this.next = offset;
        this.end = offset + length;
        this.cache = 0;
        this.count = 0;
    }

    /**
     * Fills the cache with at least 56 valid bits, padding with zero bits past the end.
     */
    private void refill() {
        if (next + 8 <= end) {
            cache |= ((long) LONG_BE.get(data, next)) >>> count;
            var bytes = (63 - count) >>> 3;
            next += bytes;
            count += bytes << 3;
            return;
        }
        while (count <= 56) {
            if (next < end) {
                cache |= (data[next] & 0xFFL) << (56 - count);
            }
            next++;
            count += 8;
        }
    }

    /**
     * Reads an unsigned value.
     *
     * @param bits the number of bits, from 0 to 32
     * @return the value; a 32-bit read may be negative
     */
    int read(int bits) {
        if (count < bits) {
            refill();
        }
        var value = (int) ((cache >>> 1) >>> (63 - bits));
        cache <<= bits;
        count -= bits;
        return value;
    }

    /**
     * Reads a two's-complement signed value.
     *
     * @param bits the number of bits, from 0 to 32
     * @return the sign-extended value, zero for a zero-bit read
     */
    int readSigned(int bits) {
        if (bits == 0) {
            return 0;
        }
        var shift = 32 - bits;
        return (read(bits) << shift) >> shift;
    }

    /**
     * Reads a two's-complement signed value that may be wider than 32 bits.
     *
     * @param bits the number of bits, from 0 to 33
     * @return the sign-extended value
     */
    long readSignedLong(int bits) {
        if (bits <= 32) {
            return readSigned(bits);
        }
        var high = (long) readSigned(bits - 32);
        return high << 32 | (read(32) & 0xFFFF_FFFFL);
    }

    /**
     * Reads a unary code: the number of zero bits before the next one bit, which is consumed.
     *
     * @return the number of zero bits
     * @throws MediaException.Malformed if the range ends before a one bit
     */
    int readUnary() {
        var zeros = 0;
        while (true) {
            if (count == 0) {
                if (next >= end) {
                    throw new MediaException.Malformed("FLAC unary code runs past the end of the frame");
                }
                refill();
            }
            var leading = Long.numberOfLeadingZeros(cache);
            if (leading < count) {
                zeros += leading;
                cache = (cache << leading) << 1;
                count -= leading + 1;
                return zeros;
            }
            zeros += count;
            cache = 0;
            count = 0;
        }
    }

    /**
     * Decodes consecutive Rice-coded residuals with one parameter into {@code out}.
     *
     * <p>Each code is a unary quotient, a stop bit and {@code parameter} low bits; the folded
     * value {@code u} maps to the signed residual {@code (u >>> 1) ^ -(u & 1)}.
     *
     * @param out       the destination
     * @param from      the index of the first residual
     * @param to        the index one past the last residual
     * @param parameter the Rice parameter, from 0 to 30
     * @throws MediaException.Malformed if a unary code runs past the end of the range
     */
    void readRice(int[] out, int from, int to, int parameter) {
        var cache = this.cache;
        var count = this.count;
        var next = this.next;
        var data = this.data;
        var end = this.end;
        for (var i = from; i < to; i++) {
            if (count < 32 && next + 8 <= end) {
                cache |= ((long) LONG_BE.get(data, next)) >>> count;
                var bytes = (63 - count) >>> 3;
                next += bytes;
                count += bytes << 3;
            }
            var leading = Long.numberOfLeadingZeros(cache);
            if (leading + 1 + parameter <= count) {
                cache = (cache << leading) << 1;
                var low = (int) ((cache >>> 1) >>> (63 - parameter));
                cache <<= parameter;
                count -= leading + 1 + parameter;
                var folded = (leading << parameter) | low;
                out[i] = (folded >>> 1) ^ -(folded & 1);
                continue;
            }
            this.cache = cache;
            this.count = count;
            this.next = next;
            var quotient = readUnary();
            var low = read(parameter);
            var folded = (quotient << parameter) | low;
            out[i] = (folded >>> 1) ^ -(folded & 1);
            cache = this.cache;
            count = this.count;
            next = this.next;
        }
        this.cache = cache;
        this.count = count;
        this.next = next;
    }

    /**
     * Reads consecutive signed fixed-width values into {@code out}.
     *
     * @param out  the destination
     * @param from the index of the first value
     * @param to   the index one past the last value
     * @param bits the width of each value, from 0 to 32
     */
    void readSignedBlock(int[] out, int from, int to, int bits) {
        if (bits == 0) {
            for (var i = from; i < to; i++) {
                out[i] = 0;
            }
            return;
        }
        for (var i = from; i < to; i++) {
            out[i] = readSigned(bits);
        }
    }

    /**
     * Returns the number of bits consumed since {@link #reset(byte[], int, int)}.
     *
     * @return the bit position relative to the start of the range
     */
    long bitPosition() {
        return (long) (next - start) * 8 - count;
    }

    /**
     * Returns whether more bits were read than the range holds.
     *
     * @return {@code true} after an over-read
     */
    boolean overrun() {
        return (long) (end - next) * 8 + count < 0;
    }
}
