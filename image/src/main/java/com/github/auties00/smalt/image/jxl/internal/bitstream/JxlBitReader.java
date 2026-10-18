/* Ports jxl-oxide 0.12.6 (crates/jxl-bitstream). Copyright Wonwoo Choi and the jxl-oxide contributors; MIT License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.bitstream;

import com.github.auties00.smalt.MediaException;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Reads the least-significant-bit-first bitstream of a JPEG XL codestream, with the field types of
 * ISO/IEC 18181-1 section 4 ({@code U32}, {@code U64}, {@code F16}, {@code Bool}, {@code Enum},
 * {@code ZeroPadToByte}).
 *
 * <p>The reader covers one byte range of an array. Bits are buffered 64 at a time; a read that
 * needs bits past the end of the range raises {@link MediaException.Malformed}, so every truncated
 * structure is rejected. {@link #peek(int)} may look past the end (the missing bits read as zero)
 * because the entropy decoders peek a fixed number of bits before they know how many a code uses;
 * the following {@link #consume(int)} still fails when the code itself does not fit.
 *
 * <p>Every decoding thread uses its own reader. Instances are not thread-safe.
 *
 * @implNote This implementation refills by loading eight bytes at once with a little-endian view
 * and keeps at least 56 valid bits whenever eight bytes remain.
 */
public final class JxlBitReader {
    /**
     * The little-endian {@code long} view used to refill eight bytes at once.
     */
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The array holding the bitstream.
     */
    private final byte[] data;

    /**
     * The index one past the last byte of the range.
     */
    private final int end;

    /**
     * The index of the first byte of the range.
     */
    private final int start;

    /**
     * The index of the next byte to load into {@link #buffer}.
     */
    private int position;

    /**
     * The buffered bits, the next bit to read in the least significant position.
     */
    private long buffer;

    /**
     * The number of valid bits in {@link #buffer}.
     */
    private int bits;

    /**
     * Creates a reader over a byte range.
     *
     * @param data the array holding the bitstream
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws IndexOutOfBoundsException if the range is outside the array
     */
    public JxlBitReader(byte[] data, int offset, int length) {
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IndexOutOfBoundsException("range " + offset + "+" + length + " outside " + data.length);
        }
        this.data = data;
        this.start = offset;
        this.position = offset;
        this.end = offset + length;
    }

    /**
     * Loads bytes into the bit buffer until it holds at least 56 bits or the range is exhausted.
     */
    private void refill() {
        if (end - position >= 8) {
            var word = (long) LONG_LE.get(data, position);
            buffer |= word << bits;
            position += (63 - bits) >>> 3;
            bits |= 56;
            return;
        }
        while (bits <= 56 && position < end) {
            buffer |= (data[position++] & 0xFFL) << bits;
            bits += 8;
        }
    }

    /**
     * Returns the next bits without consuming them; bits past the end of the range read as zero.
     *
     * @param count the number of bits, from 0 to 32
     * @return the bits, the first one in the least significant position
     */
    public int peek(int count) {
        if (bits < count) {
            refill();
        }
        return (int) (buffer & ((1L << count) - 1));
    }

    /**
     * Consumes bits that {@link #peek(int)} returned.
     *
     * @param count the number of bits, from 0 to 32
     * @throws MediaException.Malformed if the range holds fewer bits
     */
    public void consume(int count) {
        if (bits < count) {
            refill();
            if (bits < count) {
                throw truncated();
            }
        }
        buffer >>>= count;
        bits -= count;
    }

    /**
     * Reads an unsigned integer of up to 32 bits.
     *
     * @param count the number of bits, from 0 to 32
     * @return the value; a 32-bit value may be negative as a Java {@code int}
     * @throws MediaException.Malformed if the range holds fewer bits
     */
    public int readBits(int count) {
        if (bits < count) {
            refill();
            if (bits < count) {
                throw truncated();
            }
        }
        var value = (int) (buffer & ((1L << count) - 1));
        buffer >>>= count;
        bits -= count;
        return value;
    }

    /**
     * Reads one bit as a boolean.
     *
     * @return whether the bit is set
     * @throws MediaException.Malformed if the range is exhausted
     */
    public boolean readBool() {
        return readBits(1) != 0;
    }

    /**
     * Reads a {@code U32} field: a two-bit selector picks one of four distributions, each a
     * constant plus a number of raw bits.
     *
     * @param c0 the constant of distribution 0
     * @param u0 the bit count of distribution 0
     * @param c1 the constant of distribution 1
     * @param u1 the bit count of distribution 1
     * @param c2 the constant of distribution 2
     * @param u2 the bit count of distribution 2
     * @param c3 the constant of distribution 3
     * @param u3 the bit count of distribution 3
     * @return the value, wrapping modulo {@code 2^32} like the specification's unsigned arithmetic
     * @throws MediaException.Malformed if the range holds too few bits
     */
    public int readU32(int c0, int u0, int c1, int u1, int c2, int u2, int c3, int u3) {
        return switch (readBits(2)) {
            case 0 -> c0 + readBits(u0);
            case 1 -> c1 + readBits(u1);
            case 2 -> c2 + readBits(u2);
            default -> c3 + readBits(u3);
        };
    }

    /**
     * Reads a {@code U64} field.
     *
     * @return the value as an unsigned 64-bit pattern
     * @throws MediaException.Malformed if the range holds too few bits
     */
    public long readU64() {
        var selector = readBits(2);
        return switch (selector) {
            case 0 -> 0;
            case 1 -> readBits(4) + 1L;
            case 2 -> readBits(8) + 17L;
            default -> {
                long value = readBits(12);
                var shift = 12;
                while (readBits(1) == 1) {
                    if (shift == 60) {
                        value |= (long) readBits(4) << shift;
                        break;
                    }
                    value |= (long) readBits(8) << shift;
                    shift += 8;
                }
                yield value;
            }
        };
    }

    /**
     * Reads an {@code F16} field, a binary16 floating-point value.
     *
     * @return the value as a {@code float}
     * @throws MediaException.Malformed if the range is exhausted or the value is infinite or NaN
     */
    public float readF16() {
        var v = readBits(16);
        var sign = (v & 0x8000) << 16;
        if ((v & 0x7FFF) == 0) {
            return Float.intBitsToFloat(sign);
        }
        var mantissa = v & 0x3FF;
        var exponent = (v >>> 10) & 0x1F;
        if (exponent == 0x1F) {
            throw new MediaException.Malformed("infinite or NaN F16 value in a JPEG XL header");
        }
        if (exponent == 0) {
            var value = (1.0f / 16384.0f) * (mantissa / 1024.0f);
            return sign != 0 ? -value : value;
        }
        return Float.intBitsToFloat((mantissa << 13) | ((exponent + 112) << 23) | sign);
    }

    /**
     * Reads an {@code Enum} field.
     *
     * @return the raw enumerator value, to be validated by the caller
     * @throws MediaException.Malformed if the range holds too few bits
     */
    public int readEnum() {
        return readU32(0, 0, 1, 0, 2, 4, 18, 6);
    }

    /**
     * Skips to the next byte boundary, requiring the skipped bits to be zero
     * ({@code ZeroPadToByte}).
     *
     * @throws MediaException.Malformed if a padding bit is set or the range is exhausted
     */
    public void zeroPadToByte() {
        var pad = (int) ((8 - (bitPosition() & 7)) & 7);
        if (pad != 0 && readBits(pad) != 0) {
            throw new MediaException.Malformed("non-zero padding bits in a JPEG XL codestream");
        }
    }

    /**
     * Skips bits.
     *
     * @param count the number of bits, non-negative
     * @throws MediaException.Malformed if the range holds fewer bits
     */
    public void skipBits(long count) {
        if (count <= bits) {
            buffer >>>= (int) count;
            bits -= (int) count;
            return;
        }
        var remaining = count - bits;
        buffer = 0;
        bits = 0;
        var bytes = remaining >>> 3;
        if (bytes > end - position) {
            position = end;
            throw truncated();
        }
        position += (int) bytes;
        var rest = (int) (remaining & 7);
        if (rest != 0) {
            readBits(rest);
        }
    }

    /**
     * Returns the number of bits read or skipped since the start of the range.
     *
     * @return the bit position
     */
    public long bitPosition() {
        return (long) (position - start) * 8 - bits;
    }

    /**
     * Returns the number of bits left in the range.
     *
     * @return the remaining bit count
     */
    public long remainingBits() {
        return (long) (end - position) * 8 + bits;
    }

    /**
     * Returns the error for a read past the end of the range.
     *
     * @return the exception to throw
     */
    private static MediaException.Malformed truncated() {
        return new MediaException.Malformed("truncated JPEG XL codestream");
    }
}
