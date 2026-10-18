package com.github.auties00.smalt.video.av1.internal;

/**
 * Reads the MSB-first bit fields of AV1 OBU headers and payloads (AV1 specification section 4.10).
 *
 * <p>Reading past the end of the buffer never throws: the missing bits read as zero and
 * {@link #error()} reports the overrun, so a parser checks it once after a syntax structure.
 *
 * @implNote This implementation ports dav1d's {@code src/getbits.c}: a 64-bit cache refilled a byte
 * at a time, with the same overrun semantics.
 */
public final class BitReader {
    /**
     * The buffer being read.
     */
    private byte[] data;

    /**
     * The index of the first byte of the buffer.
     */
    private int start;

    /**
     * The index of the next byte to load into the cache.
     */
    private int pos;

    /**
     * The index one past the last readable byte.
     */
    private int end;

    /**
     * The cached bits, left-aligned.
     */
    private long state;

    /**
     * The number of valid bits in {@link #state}.
     */
    private int bitsLeft;

    /**
     * Whether a read ran past the end of the buffer.
     */
    private boolean error;

    /**
     * Creates a reader with no buffer; {@link #init(byte[], int, int)} must be called before reading.
     */
    public BitReader() {
    }

    /**
     * Starts reading a byte range.
     *
     * @param data the buffer
     * @param offset the index of the first byte
     * @param length the number of bytes
     */
    public void init(byte[] data, int offset, int length) {
        this.data = data;
        this.start = offset;
        this.pos = offset;
        this.end = offset + length;
        this.state = 0;
        this.bitsLeft = 0;
        this.error = false;
    }

    /**
     * Returns whether a read ran past the end of the buffer.
     *
     * @return {@code true} after an overrun
     */
    public boolean error() {
        return error;
    }


    /**
     * Returns the cached bits that were loaded but not consumed, left-aligned.
     *
     * @return the remaining cached bits; zero when every loaded bit was consumed or is zero
     */
    public long state() {
        return state;
    }

    /**
     * Returns the index of the next byte that would be loaded.
     *
     * @return the byte index
     */
    public int position() {
        return pos;
    }

    /**
     * Returns the index one past the last readable byte.
     *
     * @return the end index
     */
    public int end() {
        return end;
    }

    /**
     * Restricts the readable range to end at an index.
     *
     * @param end the new end index, not past the current one
     */
    public void setEnd(int end) {
        this.end = end;
    }

    /**
     * Moves the byte position; the cache must be empty (byte aligned).
     *
     * @param position the index of the next byte to read
     */
    public void seek(int position) {
        this.pos = position;
        this.state = 0;
        this.bitsLeft = 0;
    }

    /**
     * Returns the buffer being read.
     *
     * @return the buffer
     */
    public byte[] data() {
        return data;
    }

    /**
     * Returns the number of bits consumed since the start of the buffer.
     *
     * @return the bit position
     */
    public int bitPosition() {
        return (pos - start) * 8 - bitsLeft;
    }

    /**
     * Reads one bit.
     *
     * @return the bit, or zero past the end of the buffer
     */
    public int bit() {
        if (bitsLeft == 0) {
            if (pos >= end) {
                error = true;
            } else {
                var value = data[pos++] & 0xFF;
                bitsLeft = 7;
                state = (long) value << 57;
                return value >>> 7;
            }
        }
        var s = state;
        bitsLeft--;
        state = s << 1;
        return (int) (s >>> 63);
    }

    /**
     * Loads bytes into the cache until it holds at least {@code n} bits or the buffer ends.
     *
     * @param n the number of bits needed, from 1 to 32
     */
    private void refill(int n) {
        long value = 0;
        do {
            if (pos >= end) {
                error = true;
                if (value != 0) {
                    break;
                }
                return;
            }
            value = (value << 8) | (data[pos++] & 0xFF);
            bitsLeft += 8;
        } while (n > bitsLeft);
        state |= value << (64 - bitsLeft);
    }

    /**
     * Reads an unsigned value ({@code f(n)}).
     *
     * @param n the number of bits, from 1 to 32
     * @return the value; a 32-bit value is returned as its {@code int} bit pattern
     */
    public int bits(int n) {
        if (n > bitsLeft) {
            refill(n);
        }
        var s = state;
        bitsLeft -= n;
        state = s << n;
        return (int) (s >>> (64 - n));
    }

    /**
     * Reads an unsigned 32-bit value.
     *
     * @return the value, from 0 to 2^32 - 1
     */
    public long bits32() {
        return bits(32) & 0xFFFFFFFFL;
    }

    /**
     * Reads a two's complement signed value ({@code su(n)}).
     *
     * @param n the number of bits, from 1 to 32
     * @return the sign-extended value
     */
    public int sbits(int n) {
        if (n > bitsLeft) {
            refill(n);
        }
        var s = state;
        bitsLeft -= n;
        state = s << n;
        return (int) (s >> (64 - n));
    }

    /**
     * Reads a little-endian base-128 value ({@code leb128()}).
     *
     * @return the value, or zero with the error flag set when it does not fit in 32 bits
     */
    public long uleb128() {
        long value = 0;
        var shift = 0;
        int more;
        do {
            var v = bits(8);
            more = v & 0x80;
            value |= (long) (v & 0x7F) << shift;
            shift += 7;
        } while (more != 0 && shift < 56);
        if (value > 0xFFFFFFFFL || more != 0) {
            error = true;
            return 0;
        }
        return value;
    }

    /**
     * Reads a value uniformly distributed in {@code [0, max)} ({@code ns(n)}).
     *
     * @param max the number of values, at least 2
     * @return the value
     */
    public int uniform(int max) {
        var l = 32 - Integer.numberOfLeadingZeros(max);
        var m = (1 << l) - max;
        var v = bits(l - 1);
        return v < m ? v : (v << 1) - m + bit();
    }

    /**
     * Reads a variable-length unsigned value ({@code uvlc()}).
     *
     * @return the value, or {@code -1} (all ones) when it has 32 or more leading zeros
     */
    public int vlc() {
        if (bit() != 0) {
            return 0;
        }
        var nBits = 0;
        do {
            if (++nBits == 32) {
                return -1;
            }
        } while (bit() == 0);
        return ((1 << nBits) - 1) + bits(nBits);
    }

    /**
     * Reads a value coded relative to a reference with subexponential coding
     * ({@code decode_signed_subexp_with_ref}, AV1 specification section 5.9.27).
     *
     * @param ref the reference value
     * @param n the base-2 logarithm of half the value range
     * @return the value, in {@code [-(1 << n), 1 << n)}
     */
    public int subexp(int ref, int n) {
        return subexpUnsigned(ref + (1 << n), 2 << n) - (1 << n);
    }

    /**
     * Reads an unsigned value coded relative to a reference with subexponential coding.
     *
     * @param ref the reference value
     * @param n the number of values
     * @return the value, in {@code [0, n]}
     */
    private int subexpUnsigned(int ref, int n) {
        var v = 0;
        for (var i = 0; ; i++) {
            var b = i != 0 ? 3 + i - 1 : 3;
            if (n < v + 3 * (1 << b)) {
                v += uniform(n - v + 1);
                break;
            }
            if (bit() == 0) {
                v += bits(b);
                break;
            }
            v += 1 << b;
        }
        return ref * 2 <= n ? invRecenter(ref, v) : n - invRecenter(n - ref, v);
    }

    /**
     * Maps a recenterd value back around its reference ({@code inverse_recenter}).
     *
     * @param r the reference
     * @param v the recenterd value
     * @return the value
     */
    static int invRecenter(int r, int v) {
        if (v > (r << 1)) {
            return v;
        }
        if ((v & 1) == 0) {
            return (v >> 1) + r;
        }
        return r - ((v + 1) >> 1);
    }

    /**
     * Discards the bits up to the next byte boundary.
     */
    public void byteAlign() {
        bitsLeft = 0;
        state = 0;
    }
}
