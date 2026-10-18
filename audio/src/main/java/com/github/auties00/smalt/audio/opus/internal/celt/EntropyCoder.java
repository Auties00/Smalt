/* Ports libopus 1.5.2 (celt/entcode.h, celt/entcode.c, celt/mfrngcod.h). Copyright Xiph.Org Foundation, CSIRO and
   Jean-Marc Valin; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Holds the state shared by the Opus range decoder and range encoder.
 *
 * <p>The range coder of RFC 6716 section 4.1 codes symbols from the front of a buffer and raw
 * bits from its back. Both directions keep the same bookkeeping, so the bit accounting of
 * {@link #tell()} and {@link #tellFrac()} returns identical values in the encoder and the decoder
 * after the same symbols, which the codec relies on to make identical allocation decisions on
 * both sides. All 32-bit registers hold unsigned values stored in Java {@code int}s; every
 * comparison, division and right shift on them is unsigned.
 */
public abstract sealed class EntropyCoder permits RangeDecoder, RangeEncoder {
    /**
     * The number of bits output or consumed at a time.
     */
    public static final int EC_SYM_BITS = 8;

    /**
     * The total number of bits in each state register.
     */
    public static final int EC_CODE_BITS = 32;

    /**
     * The maximum symbol value.
     */
    public static final int EC_SYM_MAX = (1 << EC_SYM_BITS) - 1;

    /**
     * The shift that moves a symbol into the high-order position.
     */
    public static final int EC_CODE_SHIFT = EC_CODE_BITS - EC_SYM_BITS - 1;

    /**
     * The carry bit of the high-order range symbol, {@code 2^31} as an unsigned value.
     */
    public static final int EC_CODE_TOP = 1 << (EC_CODE_BITS - 1);

    /**
     * The low-order bit of the high-order range symbol.
     */
    public static final int EC_CODE_BOT = EC_CODE_TOP >>> EC_SYM_BITS;

    /**
     * The number of bits available for the last, partial symbol in the code field.
     */
    public static final int EC_CODE_EXTRA = (EC_CODE_BITS - 2) % EC_SYM_BITS + 1;

    /**
     * The size in bits of the raw-bit window.
     */
    public static final int EC_WINDOW_SIZE = 32;

    /**
     * The number of bits coded with the range coder by {@code ec_enc_uint}/{@code ec_dec_uint}
     * before switching to raw bits.
     */
    public static final int EC_UINT_BITS = 8;

    /**
     * The resolution of fractional bit counts: {@link #tellFrac()} counts in units of
     * {@code 1 / 2^BITRES} bits.
     */
    public static final int BITRES = 3;

    /**
     * The correction thresholds used by {@link #tellFrac()} to round the linear estimate.
     */
    private static final int[] TELL_FRAC_CORRECTION = {35733, 38967, 42495, 46340, 50535, 55109, 60097, 65535};

    /**
     * The buffer holding the coded data.
     */
    public byte[] buf;

    /**
     * The index in {@link #buf} of the first byte of the coded data.
     */
    public int base;

    /**
     * The size of the coded data in bytes.
     */
    public int storage;

    /**
     * The number of raw-bit bytes read from or written at the end of the buffer.
     */
    public int endOffs;

    /**
     * The raw bits buffered for reading or writing at the end, as an unsigned 32-bit window.
     */
    public int endWindow;

    /**
     * The number of valid bits in {@link #endWindow}.
     */
    public int nendBits;

    /**
     * The total number of whole bits read or written, excluding the partial bits still held by
     * the range coder.
     */
    public int nbitsTotal;

    /**
     * The offset of the next range-coder byte to read or write.
     */
    public int offs;

    /**
     * The number of values in the current range, unsigned.
     */
    public int rng;

    /**
     * In the decoder, the difference between the top of the current range and the input value,
     * minus one; in the encoder, the low end of the current range; unsigned.
     */
    public int val;

    /**
     * In the decoder, the normalization factor saved by {@code decode}; in the encoder, the
     * number of outstanding carry-propagating symbols; unsigned.
     */
    public int ext;

    /**
     * A buffered symbol awaiting carry propagation, or {@code -1} when there is none.
     */
    public int rem;

    /**
     * Nonzero if an error occurred: a read or write past the buffer, or an invalid value.
     */
    public int error;

    /**
     * Creates a coder with no buffer; subclasses initialize it before use.
     */
    EntropyCoder() {
    }

    /**
     * Returns the integer base-2 logarithm of an unsigned value plus one.
     *
     * <p>This is the number of significant bits of {@code v}: {@code 0} for {@code 0}, {@code 1}
     * for {@code 1}, and {@code 32} for any value with the top bit set.
     *
     * @param v the unsigned value
     * @return the number of significant bits
     */
    public static int ilog(int v) {
        return 32 - Integer.numberOfLeadingZeros(v);
    }

    /**
     * Returns the number of range-coder bytes read or written so far.
     *
     * @return the byte count
     */
    public final int rangeBytes() {
        return offs;
    }

    /**
     * Returns the error flag.
     *
     * @return nonzero if an error occurred
     */
    public final int getError() {
        return error;
    }

    /**
     * Returns the final range, which the encoder and a conforming decoder agree on after the
     * same packet.
     *
     * @return the unsigned 32-bit range
     */
    public final int finalRange() {
        return rng;
    }

    /**
     * Returns the number of bits used so far, rounded up.
     *
     * <p>The value is always slightly larger than the exact count: all rounding error is in the
     * positive direction, so a freshly initialized coder reports one bit.
     *
     * @return the bit count
     */
    public final int tell() {
        return nbitsTotal - ilog(rng);
    }

    /**
     * Returns the number of bits used so far in units of {@code 1 / 8} bit, rounded up.
     *
     * @return the count scaled by {@code 2^BITRES}
     */
    public final int tellFrac() {
        var nbits = nbitsTotal << BITRES;
        var l = ilog(rng);
        var r = rng >>> (l - 16);
        var b = (r >>> 12) - 8;
        if (Integer.compareUnsigned(r, TELL_FRAC_CORRECTION[b]) > 0) {
            b++;
        }
        l = (l << 3) + b;
        return nbits - l;
    }
}
