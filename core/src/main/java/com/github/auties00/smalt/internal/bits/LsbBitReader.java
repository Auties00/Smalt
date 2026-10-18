package com.github.auties00.smalt.internal.bits;

import com.github.auties00.smalt.MediaException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Reads a bit stream least-significant bit first, as DEFLATE, WebP lossless (VP8L), GIF LZW and
 * Vorbis are coded.
 *
 * <p>The reader keeps up to 64 upcoming bits right-aligned in a cache and refills it eight bytes
 * at a time. Reading past the end of the input yields zero bits and drives {@link #bitsLeft()}
 * negative, so a decoder can finish a symbol and then reject the truncation with
 * {@link #checkNotOverrun()}.
 *
 * @implNote This implementation refills with one unaligned little-endian 64-bit load shifted
 * above the valid bits. The load also deposits the low bits of the next, not-yet-counted byte;
 * those bits equal the data the following refill ORs into the same position, so the overlap is
 * harmless and the refill needs no masking.
 */
public final class LsbBitReader {
    /**
     * Reads a little-endian {@code long} from a byte array at any offset.
     */
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The input array.
     */
    private final byte[] data;

    /**
     * The index of the first input byte.
     */
    private final int start;

    /**
     * The index one past the last input byte.
     */
    private final int end;

    /**
     * The index of the next byte not yet counted into {@link #count}.
     */
    private int next;

    /**
     * The upcoming bits, right-aligned: bit 0 is the next bit to read.
     */
    private long cache;

    /**
     * The number of valid bits in {@link #cache}.
     */
    private int count;

    /**
     * Constructs a reader over a whole array.
     *
     * @param data the input
     * @throws NullPointerException if {@code data} is {@code null}
     */
    public LsbBitReader(byte[] data) {
        this(data, 0, data.length);
    }

    /**
     * Constructs a reader over {@code data[offset, offset + length)}.
     *
     * @param data   the input array
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws NullPointerException      if {@code data} is {@code null}
     * @throws IndexOutOfBoundsException if the range is invalid
     */
    public LsbBitReader(byte[] data, int offset, int length) {
        this.data = Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        this.start = offset;
        this.end = offset + length;
        this.next = offset;
    }

    /**
     * Tops the cache up to at least 57 valid bits, padding with zeros past the end of the input.
     */
    private void refill() {
        if (next + 8 <= end) {
            cache |= ((long) LONG_LE.get(data, next)) << count;
            var bytes = (63 - count) >>> 3;
            next += bytes;
            count += bytes << 3;
            return;
        }
        while (count <= 56) {
            if (next < end) {
                cache |= (data[next] & 0xFFL) << count;
            }
            next++;
            count += 8;
        }
    }

    /**
     * Ensures at least {@code bits} upcoming bits are cached, for use before a run of
     * {@link #peekCached(int)} and {@link #consume(int)} calls.
     *
     * @param bits the number of bits needed, from 0 to 56
     */
    public void ensure(int bits) {
        if (count < bits) {
            refill();
        }
    }

    /**
     * Reads an unsigned field of up to 32 bits.
     *
     * @param bits the field width, from 0 to 32
     * @return the field value; a 32-bit field is returned as its two's-complement {@code int}
     */
    public int read(int bits) {
        if (count < bits) {
            refill();
        }
        var value = (int) (cache & ((1L << bits) - 1));
        cache >>>= bits;
        count -= bits;
        return value;
    }

    /**
     * Reads one bit.
     *
     * @return {@code 0} or {@code 1}
     */
    public int readBit() {
        if (count == 0) {
            refill();
        }
        var bit = (int) (cache & 1);
        cache >>>= 1;
        count--;
        return bit;
    }

    /**
     * Returns the next bits without consuming them.
     *
     * @param bits the number of bits to look at, from 0 to 32
     * @return the upcoming field value
     */
    public int peek(int bits) {
        if (count < bits) {
            refill();
        }
        return (int) (cache & ((1L << bits) - 1));
    }

    /**
     * Returns the next bits without consuming them or refilling, after {@link #ensure(int)}.
     *
     * @param bits the number of bits to look at, at most the amount ensured
     * @return the upcoming field value
     */
    public int peekCached(int bits) {
        return (int) (cache & ((1L << bits) - 1));
    }

    /**
     * Consumes bits already cached by {@link #ensure(int)}.
     *
     * @param bits the number of bits to drop, at most the amount ensured
     */
    public void consume(int bits) {
        cache >>>= bits;
        count -= bits;
    }

    /**
     * Skips bits.
     *
     * @param bits the number of bits to skip; non-negative, may exceed the cache size
     * @throws IllegalArgumentException if {@code bits} is negative
     */
    public void skip(long bits) {
        if (bits < 0) {
            throw new IllegalArgumentException("negative skip " + bits);
        }
        if (bits <= count) {
            cache = bits == 64 ? 0 : cache >>> bits;
            count -= (int) bits;
            return;
        }
        bits -= count;
        cache = 0;
        count = 0;
        var target = (long) next + (bits >>> 3);
        if (target > end) {
            next = end + 8;
            return;
        }
        next = (int) target;
        read((int) (bits & 7));
    }

    /**
     * Skips to the next byte boundary; does nothing when already aligned.
     */
    public void alignToByte() {
        var misaligned = count & 7;
        cache >>>= misaligned;
        count -= misaligned;
    }

    /**
     * Returns the number of bits read so far.
     *
     * @return the bit offset from the start of the input
     */
    public long bitPosition() {
        return (long) (next - start) * 8 - count;
    }

    /**
     * Returns the number of input bits not yet read.
     *
     * @return the remaining bits, negative once reads ran past the end of the input
     */
    public long bitsLeft() {
        return (long) (end - next) * 8 + count;
    }

    /**
     * Returns whether any read ran past the end of the input.
     *
     * @return {@code true} once more bits were read than the input holds
     */
    public boolean overrun() {
        return bitsLeft() < 0;
    }

    /**
     * Rejects the input if any read ran past its end.
     *
     * @throws MediaException.Malformed if more bits were read than the input holds
     */
    public void checkNotOverrun() {
        if (bitsLeft() < 0) {
            throw new MediaException.Malformed("bit stream truncated by " + -bitsLeft() + " bits");
        }
    }
}
