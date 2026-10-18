/* Ports libopus 1.5.2 (celt/entenc.c). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

import java.util.Arrays;

/**
 * Encodes symbols and raw bits into an Opus range-coded buffer (RFC 6716 section 5.1).
 *
 * <p>Range-coder bytes grow from the front of the buffer and raw bits from its back. Running out
 * of space never throws: it sets {@link #error}, exactly as the reference encoder does, and the
 * caller checks the flag after {@link #encDone()}.
 */
public final class RangeEncoder extends EntropyCoder {
    /**
     * Creates an encoder with no buffer; call {@link #init(byte[], int, int)} before use.
     */
    public RangeEncoder() {
    }

    /**
     * Creates an encoder writing at most {@code size} bytes into {@code data} starting at
     * {@code offset}.
     *
     * @param data   the output buffer
     * @param offset the index of the first output byte
     * @param size   the number of bytes available
     */
    public RangeEncoder(byte[] data, int offset, int size) {
        init(data, offset, size);
    }

    /**
     * Initializes the encoder for a new output buffer ({@code ec_enc_init}).
     *
     * @param data   the output buffer, or {@code null} when {@code size} is zero
     * @param offset the index of the first output byte
     * @param size   the number of bytes available
     */
    public void init(byte[] data, int offset, int size) {
        buf = data;
        base = offset;
        endOffs = 0;
        endWindow = 0;
        nendBits = 0;
        nbitsTotal = EC_CODE_BITS + 1;
        offs = 0;
        rng = EC_CODE_TOP;
        rem = -1;
        val = 0;
        ext = 0;
        storage = size;
        error = 0;
    }

    /**
     * Copies the complete state of another encoder into this one, sharing its buffer.
     *
     * <p>The CELT encoder saves and restores the coder around its trial encodings this way.
     *
     * @param other the encoder to copy
     */
    public void copyFrom(RangeEncoder other) {
        buf = other.buf;
        base = other.base;
        storage = other.storage;
        endOffs = other.endOffs;
        endWindow = other.endWindow;
        nendBits = other.nendBits;
        nbitsTotal = other.nbitsTotal;
        offs = other.offs;
        rng = other.rng;
        val = other.val;
        ext = other.ext;
        rem = other.rem;
        error = other.error;
    }

    /**
     * Writes a range-coder byte at the front.
     *
     * @param value the byte value
     * @return {@code 0} on success, {@code -1} when the buffer is full
     */
    private int writeByte(int value) {
        if (offs + endOffs >= storage) {
            return -1;
        }
        buf[base + offs++] = (byte) value;
        return 0;
    }

    /**
     * Writes a raw-bit byte at the back.
     *
     * @param value the byte value
     * @return {@code 0} on success, {@code -1} when the buffer is full
     */
    private int writeByteAtEnd(int value) {
        if (offs + endOffs >= storage) {
            return -1;
        }
        buf[base + storage - ++endOffs] = (byte) value;
        return 0;
    }

    /**
     * Outputs a symbol with a carry bit, buffering runs of {@code 0xFF} symbols until it is
     * known whether a carry propagates through them.
     *
     * @param c the symbol plus carry, in {@code [0, 511]}
     */
    private void carryOut(int c) {
        if (c != EC_SYM_MAX) {
            var carry = c >> EC_SYM_BITS;
            if (rem >= 0) {
                error |= writeByte(rem + carry);
            }
            if (ext != 0) {
                var sym = (EC_SYM_MAX + carry) & EC_SYM_MAX;
                do {
                    error |= writeByte(sym);
                } while (--ext != 0);
            }
            rem = c & EC_SYM_MAX;
        } else {
            ext++;
        }
    }

    /**
     * Renormalizes the range so that it lies entirely in the high-order symbol, outputting
     * bytes as needed.
     */
    private void normalize() {
        while (Integer.compareUnsigned(rng, EC_CODE_BOT) <= 0) {
            carryOut(val >>> EC_CODE_SHIFT);
            val = (val << EC_SYM_BITS) & (EC_CODE_TOP - 1);
            rng <<= EC_SYM_BITS;
            nbitsTotal += EC_SYM_BITS;
        }
    }

    /**
     * Encodes a symbol with frequency range {@code [fl, fh)} out of {@code ft}
     * ({@code ec_encode}).
     *
     * @param fl the low cumulative frequency
     * @param fh the high cumulative frequency, greater than {@code fl}
     * @param ft the total frequency, at most {@code 2^16}
     */
    public void encode(int fl, int fh, int ft) {
        var r = Integer.divideUnsigned(rng, ft);
        if (fl > 0) {
            val += rng - r * (ft - fl);
            rng = r * (fh - fl);
        } else {
            rng -= r * (ft - fh);
        }
        normalize();
    }

    /**
     * Encodes a symbol with frequency range {@code [fl, fh)} out of {@code 2^bits}
     * ({@code ec_encode_bin}).
     *
     * @param fl   the low cumulative frequency
     * @param fh   the high cumulative frequency, greater than {@code fl}
     * @param bits the base-2 logarithm of the total frequency
     */
    public void encodeBin(int fl, int fh, int bits) {
        var r = rng >>> bits;
        if (fl > 0) {
            val += rng - r * ((1 << bits) - fl);
            rng = r * (fh - fl);
        } else {
            rng -= r * ((1 << bits) - fh);
        }
        normalize();
    }

    /**
     * Encodes a bit whose probability of being one is {@code 1 / 2^logp}
     * ({@code ec_enc_bit_logp}).
     *
     * @param value the bit, zero or nonzero
     * @param logp  the base-2 logarithm of the inverse probability of a one
     */
    public void encBitLogp(int value, int logp) {
        var r = rng;
        var l = val;
        var s = r >>> logp;
        r -= s;
        if (value != 0) {
            val = l + r;
        }
        rng = value != 0 ? s : r;
        normalize();
    }

    /**
     * Encodes a symbol described by an 8-bit inverse cumulative distribution
     * ({@code ec_enc_icdf}).
     *
     * @param s    the symbol index
     * @param icdf the inverse CDF, unsigned byte values ending with zero
     * @param ftb  the base-2 logarithm of the total frequency
     */
    public void encIcdf(int s, short[] icdf, int ftb) {
        encIcdf(s, icdf, 0, ftb);
    }

    /**
     * Encodes a symbol described by an 8-bit inverse cumulative distribution that starts at
     * {@code offset} ({@code ec_enc_icdf}).
     *
     * @param s      the symbol index
     * @param icdf   the table holding the inverse CDF
     * @param offset the index of the first entry
     * @param ftb    the base-2 logarithm of the total frequency
     */
    public void encIcdf(int s, short[] icdf, int offset, int ftb) {
        var r = rng >>> ftb;
        if (s > 0) {
            val += rng - r * icdf[offset + s - 1];
            rng = r * (icdf[offset + s - 1] - icdf[offset + s]);
        } else {
            rng -= r * icdf[offset + s];
        }
        normalize();
    }

    /**
     * Encodes a uniformly distributed integer in {@code [0, ft)} ({@code ec_enc_uint}).
     *
     * @param fl the value to encode, unsigned
     * @param ft the number of possible values, unsigned and at least 2
     */
    public void encUint(int fl, int ft) {
        ft--;
        var ftb = ilog(ft);
        if (ftb > EC_UINT_BITS) {
            ftb -= EC_UINT_BITS;
            var ft1 = (ft >>> ftb) + 1;
            var fl1 = fl >>> ftb;
            encode(fl1, fl1 + 1, ft1);
            encBits(fl & (int) ((1L << ftb) - 1), ftb);
        } else {
            encode(fl, fl + 1, ft + 1);
        }
    }

    /**
     * Writes raw bits at the end of the buffer ({@code ec_enc_bits}).
     *
     * @param fl   the bits, unsigned
     * @param bits the number of bits, between 1 and 25
     */
    public void encBits(int fl, int bits) {
        var window = endWindow;
        var used = nendBits;
        if (used + bits > EC_WINDOW_SIZE) {
            do {
                error |= writeByteAtEnd(window & EC_SYM_MAX);
                window >>>= EC_SYM_BITS;
                used -= EC_SYM_BITS;
            } while (used >= EC_SYM_BITS);
        }
        window |= fl << used;
        used += bits;
        endWindow = window;
        nendBits = used;
        nbitsTotal += bits;
    }

    /**
     * Overwrites the first {@code nbits} bits written to the stream
     * ({@code ec_enc_patch_initial_bits}).
     *
     * <p>Sets {@link #error} when fewer than {@code nbits} bits have been encoded.
     *
     * @param value the replacement bits
     * @param nbits the number of bits to replace, at most 8
     */
    public void encPatchInitialBits(int value, int nbits) {
        var shift = EC_SYM_BITS - nbits;
        var mask = ((1 << nbits) - 1) << shift;
        if (offs > 0) {
            buf[base] = (byte) ((buf[base] & ~mask) | value << shift);
        } else if (rem >= 0) {
            rem = (rem & ~mask) | value << shift;
        } else if (Integer.compareUnsigned(rng, EC_CODE_TOP >>> nbits) <= 0) {
            val = (val & ~(mask << EC_CODE_SHIFT)) | value << (EC_CODE_SHIFT + shift);
        } else {
            error = -1;
        }
    }

    /**
     * Shrinks the output buffer to {@code size} bytes, moving the raw bits already written at the
     * end ({@code ec_enc_shrink}).
     *
     * @param size the new size, at least the number of bytes already written
     */
    public void encShrink(int size) {
        System.arraycopy(buf, base + storage - endOffs, buf, base + size - endOffs, endOffs);
        storage = size;
    }

    /**
     * Flushes the minimum number of bits that decode unambiguously, merges the raw bits, and
     * clears the unused middle of the buffer ({@code ec_enc_done}).
     */
    public void encDone() {
        var l = EC_CODE_BITS - ilog(rng);
        var msk = (EC_CODE_TOP - 1) >>> l;
        var end = (val + msk) & ~msk;
        if (Integer.compareUnsigned(end | msk, val + rng) >= 0) {
            l++;
            msk >>>= 1;
            end = (val + msk) & ~msk;
        }
        while (l > 0) {
            carryOut(end >>> EC_CODE_SHIFT);
            end = (end << EC_SYM_BITS) & (EC_CODE_TOP - 1);
            l -= EC_SYM_BITS;
        }
        if (rem >= 0 || ext != 0) {
            carryOut(0);
        }
        var window = endWindow;
        var used = nendBits;
        while (used >= EC_SYM_BITS) {
            error |= writeByteAtEnd(window & EC_SYM_MAX);
            window >>>= EC_SYM_BITS;
            used -= EC_SYM_BITS;
        }
        if (error == 0) {
            if (buf != null) {
                Arrays.fill(buf, base + offs, base + storage - endOffs, (byte) 0);
            }
            if (used > 0) {
                if (endOffs >= storage) {
                    error = -1;
                } else {
                    l = -l;
                    if (offs + endOffs >= storage && l < used) {
                        window &= (1 << l) - 1;
                        error = -1;
                    }
                    buf[base + storage - endOffs - 1] |= (byte) window;
                }
            }
        }
    }

    /**
     * Returns the output buffer.
     *
     * @return the buffer passed to {@link #init(byte[], int, int)}
     */
    public byte[] buffer() {
        return buf;
    }
}
