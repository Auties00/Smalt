package com.github.auties00.smalt.internal.bits;

import com.github.auties00.smalt.MediaException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Reads a bit stream most-significant bit first, as H.264, HEVC, AAC, MP3, FLAC and JPEG headers
 * are coded.
 *
 * <p>The reader keeps up to 64 upcoming bits left-aligned in a cache and refills it eight bytes
 * at a time, so a field read costs a shift and a mask in the common case. Reading past the end of
 * the input is not an error by itself: missing bits read as zero and {@link #bitsLeft()} goes
 * negative, which lets a decoder finish a syntax element and then reject the truncation with
 * {@link #checkNotOverrun()}.
 *
 * @implNote This implementation refills with one unaligned big-endian 64-bit load and ORs it
 * into the cache below the valid bits. The load also deposits the leading bits of the next,
 * not-yet-counted byte; those bits equal the data the following refill ORs into the same
 * position, so the overlap is harmless and the refill needs no masking.
 */
public final class MsbBitReader {
    /**
     * Reads a big-endian {@code long} from a byte array at any offset.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The input array.
     */
    private byte[] data;

    /**
     * The index of the first input byte.
     */
    private int start;

    /**
     * The index one past the last input byte.
     */
    private int end;

    /**
     * The index of the next byte not yet counted into {@link #count}.
     */
    private int next;

    /**
     * The upcoming bits, left-aligned: bit 63 is the next bit to read.
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
    public MsbBitReader(byte[] data) {
        this(data, 0, data.length);
    }

    /**
     * Constructs a reader over {@code data[offset, offset + length)}.
     *
     * @param data the input array
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws IndexOutOfBoundsException if the range is invalid
     */
    public MsbBitReader(byte[] data, int offset, int length) {
        this.data = Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        this.start = offset;
        this.end = offset + length;
        this.next = offset;
    }

    /**
     * Restarts the reader at the first bit of {@code data[offset, offset + length)}, as a new
     * reader over that range would.
     *
     * @param data the input array
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws IndexOutOfBoundsException if the range is invalid
     */
    public void reset(byte[] data, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, data.length);
        this.data = data;
        this.start = offset;
        this.end = offset + length;
        this.next = offset;
        this.cache = 0;
        this.count = 0;
    }

    /**
     * Moves the reader to a bit position from the start of the input, in either direction.
     *
     * <p>A position past the end of the input leaves the reader overrun, as reading up to it
     * would.
     *
     * @param bitPosition the position, as {@link #bitPosition()} reports it
     * @throws IllegalArgumentException if {@code bitPosition} is negative
     */
    public void seek(long bitPosition) {
        if (bitPosition < 0) {
            throw new IllegalArgumentException("negative bit position " + bitPosition);
        }
        next = start;
        cache = 0;
        count = 0;
        skip(bitPosition);
    }

    /**
     * Tops the cache up to at least 57 valid bits, padding with zeros past the end of the input.
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
     * Reads an unsigned field of up to 32 bits.
     *
     * @param bits the field width, from 0 to 32
     * @return the field value; a 32-bit field is returned as its two's-complement {@code int}
     */
    public int read(int bits) {
        if (count < bits) {
            refill();
        }
        var value = (int) ((cache >>> 1) >>> (63 - bits));
        cache <<= bits;
        count -= bits;
        return value;
    }

    /**
     * Reads an unsigned field of up to 64 bits.
     *
     * @param bits the field width, from 0 to 64
     * @return the field value; a 64-bit field is returned as its two's-complement {@code long}
     */
    public long readLong(int bits) {
        if (bits <= 32) {
            return read(bits) & 0xFFFFFFFFL;
        }
        var high = read(bits - 32) & 0xFFFFFFFFL;
        return (high << 32) | (read(32) & 0xFFFFFFFFL);
    }

    /**
     * Reads a signed two's-complement field of up to 32 bits.
     *
     * @param bits the field width, from 1 to 32
     * @return the sign-extended field value
     */
    public int readSigned(int bits) {
        var value = read(bits);
        var shift = 32 - bits;
        return (value << shift) >> shift;
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
        var bit = (int) (cache >>> 63);
        cache <<= 1;
        count--;
        return bit;
    }

    /**
     * Reads one bit as a flag.
     *
     * @return {@code true} when the bit is set
     */
    public boolean readFlag() {
        return readBit() != 0;
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
        return (int) ((cache >>> 1) >>> (63 - bits));
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
            cache = bits == 64 ? 0 : cache << bits;
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
     * Reads an unsigned Exp-Golomb code, {@code ue(v)} in the H.264 and HEVC syntax.
     *
     * @return the decoded value
     * @throws MediaException.Malformed if the code has more than 31 leading zeros
     */
    public int readUe() {
        if (count < 32) {
            refill();
        }
        var zeros = Long.numberOfLeadingZeros(cache);
        if (zeros <= 15) {
            var length = 2 * zeros + 1;
            if (length <= count) {
                var value = (int) (cache >>> (64 - length)) - 1;
                cache <<= length;
                count -= length;
                return value;
            }
        }
        zeros = 0;
        while (readBit() == 0) {
            if (++zeros > 31 || bitsLeft() < 0) {
                throw new MediaException.Malformed("invalid Exp-Golomb code");
            }
        }
        var value = (1L << zeros) - 1 + (readLong(zeros));
        if (value > Integer.MAX_VALUE) {
            throw new MediaException.Malformed("Exp-Golomb value " + value + " out of range");
        }
        return (int) value;
    }

    /**
     * Reads a signed Exp-Golomb code, {@code se(v)} in the H.264 and HEVC syntax.
     *
     * @return the decoded value
     * @throws MediaException.Malformed if the code has more than 31 leading zeros
     */
    public int readSe() {
        var code = readUe();
        return (code & 1) != 0 ? (code >>> 1) + 1 : -(code >>> 1);
    }

    /**
     * Skips to the next byte boundary; does nothing when already aligned.
     */
    public void alignToByte() {
        var misaligned = count & 7;
        cache <<= misaligned;
        count -= misaligned;
    }

    /**
     * Returns whether the reader sits on a byte boundary.
     *
     * @return {@code true} when the number of bits read is a multiple of eight
     */
    public boolean isByteAligned() {
        return (count & 7) == 0;
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
