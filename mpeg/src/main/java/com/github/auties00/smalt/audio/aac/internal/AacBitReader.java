package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.MediaException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Reads the most-significant-bit-first bit stream of AAC configurations and raw data blocks.
 *
 * <p>The reader keeps up to 64 upcoming bits left-aligned in a cache. Besides checked reads, it
 * offers an unchecked fast path for the Huffman loops: {@link #ensure(int)} tops the cache up
 * once, after which {@link #peekFast(int)} and {@link #consumeFast(int)} work on the cache with
 * a shift each. As with the module's general reader, bits past the end of the input read as
 * zero and {@link #bitsLeft()} goes negative, so a decoder can finish a syntax element and then
 * reject the truncation.
 *
 * @implNote This implementation refills with one unaligned big-endian 64-bit load while eight
 * input bytes remain, keeping at least 56 valid bits, and byte by byte near the end.
 */
public final class AacBitReader {
    /**
     * Reads a big-endian {@code long} from a byte array at any offset.
     */
    static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The input array; the Huffman loops of this package read it directly.
     */
    byte[] data;

    /**
     * The index of the first input byte.
     */
    private int start;

    /**
     * The index one past the last input byte.
     */
    int end;

    /**
     * The index of the next byte not yet loaded into the cache; the Huffman loops of this
     * package hold it in a local variable while they run.
     */
    int next;

    /**
     * The upcoming bits, left-aligned: bit 63 is the next bit; the Huffman loops of this package
     * hold it in a local variable while they run.
     */
    long cache;

    /**
     * The number of valid bits in {@link #cache}; the Huffman loops of this package hold it in
     * a local variable while they run.
     */
    int count;

    /**
     * Creates a reader over a whole array.
     *
     * @param data the input
     * @throws NullPointerException if {@code data} is {@code null}
     */
    public AacBitReader(byte[] data) {
        this(data, 0, data.length);
    }

    /**
     * Creates a reader over {@code data[offset, offset + length)}.
     *
     * @param data   the input array
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws NullPointerException      if {@code data} is {@code null}
     * @throws IndexOutOfBoundsException if the range is invalid
     */
    public AacBitReader(byte[] data, int offset, int length) {
        this.data = Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        this.start = offset;
        this.end = offset + length;
        this.next = offset;
    }

    /**
     * Tops the cache up to at least 56 valid bits, padding with zeros past the end.
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
     * Guarantees that at least {@code bits} bits are cached for the fast path.
     *
     * @param bits the number of bits, at most 56
     */
    public void ensure(int bits) {
        if (count < bits) {
            refill();
        }
    }

    /**
     * Returns the next bits of the cache without consuming them; the caller must have ensured
     * them.
     *
     * @param bits the number of bits, from 1 to 32
     * @return the field value
     */
    public int peekFast(int bits) {
        return (int) (cache >>> (64 - bits));
    }

    /**
     * Consumes cached bits; the caller must have ensured them.
     *
     * @param bits the number of bits, from 0 to the number ensured
     */
    public void consumeFast(int bits) {
        cache <<= bits;
        count -= bits;
    }

    /**
     * Reads an unsigned field of up to 32 bits.
     *
     * @param bits the field width, from 0 to 32
     * @return the field value
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
     * Reads a signed two's-complement field of up to 32 bits.
     *
     * @param bits the field width, from 1 to 32
     * @return the sign-extended value
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
     * Skips bits.
     *
     * @param bits the number of bits; non-negative
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
     * Skips to the next byte boundary of the input.
     */
    public void alignToByte() {
        var misaligned = count & 7;
        cache <<= misaligned;
        count -= misaligned;
    }

    /**
     * Returns the number of input bits not yet read.
     *
     * @return the remaining bits, negative once reads ran past the end
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
            throw new MediaException.Malformed("AAC bit stream truncated by " + -bitsLeft() + " bits");
        }
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
     * Restarts the reader over {@code data[offset, offset + length)}.
     *
     * @param data the input array
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws IndexOutOfBoundsException if the range is outside {@code data}
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
     * Moves the reader to a bit position.
     *
     * @param position the number of bits from the start, at least 0; a position past the end makes
     * the reader report an overrun
     */
    public void seek(long position) {
        cache = 0;
        count = 0;
        var target = (long) start + (position >>> 3);
        if (target > end) {
            next = end + 8;
            return;
        }
        next = (int) target;
        read((int) (position & 7));
    }

    /**
     * Returns the bit at a position without moving the reader.
     *
     * @param position the number of bits from the start, at least 0
     * @return the bit, 0 past the end
     */
    public int bitAt(long position) {
        var index = start + (position >>> 3);
        return index < end ? data[(int) index] >>> (7 - (int) (position & 7)) & 1 : 0;
    }
}
