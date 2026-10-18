package com.github.auties00.smalt.audio.vorbis;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Reads a Vorbis packet least-significant bit first (Vorbis I section 2), with the
 * end-of-packet semantics of the specification.
 *
 * <p>Up to 64 upcoming bits are cached right-aligned. Reads past the end of the packet yield zero
 * bits and drive {@link #bitsLeft()} negative, which is how callers detect end-of-packet: a read
 * is valid when {@link #bitsLeft()} is still non-negative after it. The cache state is
 * package-private so the residue decoder can keep it in local variables across a run of codewords
 * and write it back afterwards.
 *
 * @implNote This implementation mirrors the core {@code LsbBitReader} refill (one unaligned
 * little-endian 64-bit load shifted above the valid bits), exposing its state for fused decoding
 * loops that the core reader's encapsulation does not allow.
 */
final class BitReader {
    /**
     * Reads a little-endian {@code long} from a byte array at any offset.
     */
    static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The packet bytes.
     */
    final byte[] data;

    /**
     * The index one past the last packet byte.
     */
    final int end;

    /**
     * The index of the next byte not yet counted into {@link #count}.
     */
    int next;

    /**
     * The upcoming bits, right-aligned: bit 0 is the next bit to read.
     */
    long cache;

    /**
     * The number of valid bits in {@link #cache}.
     */
    int count;

    /**
     * Constructs a reader over a whole array.
     *
     * @param data the packet
     * @throws NullPointerException if {@code data} is {@code null}
     */
    BitReader(byte[] data) {
        this(data, 0, data.length);
    }

    /**
     * Constructs a reader over {@code data[offset, offset + length)}.
     *
     * @param data   the packet array
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws NullPointerException      if {@code data} is {@code null}
     * @throws IndexOutOfBoundsException if the range is invalid
     */
    BitReader(byte[] data, int offset, int length) {
        this.data = Objects.requireNonNull(data, "data");
        Objects.checkFromIndexSize(offset, length, data.length);
        this.end = offset + length;
        this.next = offset;
    }

    /**
     * Tops the cache up to at least 57 valid bits, padding with zeros past the end of the packet.
     */
    void refill() {
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
     * Ensures at least {@code bits} upcoming bits are cached.
     *
     * @param bits the number of bits needed, from 0 to 56
     */
    void ensure(int bits) {
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
    int read(int bits) {
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
    int readBit() {
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
    int peek(int bits) {
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
    int peekCached(int bits) {
        return (int) (cache & ((1L << bits) - 1));
    }

    /**
     * Consumes bits already cached by {@link #ensure(int)} or {@link #peek(int)}.
     *
     * @param bits the number of bits to drop, at most the amount cached
     */
    void consume(int bits) {
        cache >>>= bits;
        count -= bits;
    }

    /**
     * Skips bits, up to the cache size at a time.
     *
     * @param bits the number of bits to skip; non-negative
     */
    void skip(int bits) {
        while (bits > 32) {
            read(32);
            bits -= 32;
        }
        read(bits);
    }

    /**
     * Returns the number of packet bits not yet read.
     *
     * @return the remaining bits, negative once reads ran past the end of the packet
     */
    long bitsLeft() {
        return (long) (end - next) * 8 + count;
    }

    /**
     * Returns whether any read ran past the end of the packet (end-of-packet).
     *
     * @return {@code true} once more bits were read than the packet holds
     */
    boolean overrun() {
        return bitsLeft() < 0;
    }
}
