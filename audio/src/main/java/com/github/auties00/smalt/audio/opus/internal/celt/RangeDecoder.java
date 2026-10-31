/* Ports libopus 1.5.2 (celt/entdec.c). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Decodes symbols and raw bits from an Opus range-coded buffer (RFC 6716 section 4.1).
 *
 * <p>Reading past the end of the buffer never fails: missing range-coder bytes read as zero and
 * missing raw bits read as zero, exactly as the reference decoder behaves, so any byte sequence
 * decodes to some sequence of symbols. Every loop is bounded by the 32-bit state, so decoding
 * terminates on every input.
 *
 * <p>The decoder keeps {@code 1 <= rng <= 2^31} and {@code 0 <= val < 2^31}, and every
 * intermediate {@code r * icdf[k]} and {@code val / ext} stays below {@code rng}, so those
 * comparisons and divisions are exact with signed {@code int} arithmetic; only {@code rng}
 * itself, which can equal {@code 2^31}, needs unsigned treatment.
 */
public final class RangeDecoder extends EntropyCoder {
    /**
     * Creates a decoder with no buffer; call {@link #init(byte[], int, int)} before use.
     */
    public RangeDecoder() {
    }

    /**
     * Creates a decoder reading {@code length} bytes of {@code data} starting at {@code offset}.
     *
     * @param data   the buffer
     * @param offset the index of the first coded byte
     * @param length the number of coded bytes
     */
    public RangeDecoder(byte[] data, int offset, int length) {
        init(data, offset, length);
    }

    /**
     * Initializes the decoder for a new buffer ({@code ec_dec_init}).
     *
     * @param data   the buffer
     * @param offset the index of the first coded byte
     * @param length the number of coded bytes
     */
    public void init(byte[] data, int offset, int length) {
        buf = data;
        base = offset;
        storage = length;
        endOffs = 0;
        endWindow = 0;
        nendBits = 0;
        nbitsTotal = EC_CODE_BITS + 1 - ((EC_CODE_BITS - EC_CODE_EXTRA) / EC_SYM_BITS) * EC_SYM_BITS;
        offs = 0;
        rng = 1 << EC_CODE_EXTRA;
        rem = readByte();
        val = rng - 1 - (rem >> (EC_SYM_BITS - EC_CODE_EXTRA));
        error = 0;
        normalize();
    }

    /**
     * Reads the next range-coder byte from the front, or zero past the end.
     *
     * @return the unsigned byte
     */
    private int readByte() {
        return offs < storage ? buf[base + offs++] & 0xFF : 0;
    }

    /**
     * Reads the next raw-bit byte from the back, or zero past the front.
     *
     * @return the unsigned byte
     */
    private int readByteFromEnd() {
        return endOffs < storage ? buf[base + storage - ++endOffs] & 0xFF : 0;
    }

    /**
     * Renormalizes the range so that it lies entirely in the high-order symbol, reading input
     * bytes as needed.
     */
    private void normalize() {
        var rng = this.rng;
        // rng is at least 1 and at most 2^31, so rng - 1 compares correctly as a signed int.
        if (rng - 1 >= EC_CODE_BOT) {
            return;
        }
        var val = this.val;
        var rem = this.rem;
        var offs = this.offs;
        var nbits = this.nbitsTotal;
        var buf = this.buf;
        var base = this.base;
        var storage = this.storage;
        do {
            nbits += EC_SYM_BITS;
            rng <<= EC_SYM_BITS;
            var sym = rem;
            rem = offs < storage ? buf[base + offs++] & 0xFF : 0;
            sym = (sym << EC_SYM_BITS | rem) >> (EC_SYM_BITS - EC_CODE_EXTRA);
            val = ((val << EC_SYM_BITS) + (EC_SYM_MAX & ~sym)) & (EC_CODE_TOP - 1);
        } while (rng - 1 < EC_CODE_BOT);
        this.rng = rng;
        this.val = val;
        this.rem = rem;
        this.offs = offs;
        this.nbitsTotal = nbits;
    }

    /**
     * Computes the cumulative frequency of the next symbol ({@code ec_decode}).
     *
     * <p>The caller must follow with {@link #decUpdate(int, int, int)} using the frequency range
     * of the symbol that contains the returned value.
     *
     * @param ft the total frequency, positive and at most {@code 2^16}
     * @return the cumulative frequency, in {@code [0, ft)}
     */
    public int decode(int ft) {
        ext = Integer.divideUnsigned(rng, ft);
        var s = val / ext;
        return ft - Math.min(s + 1, ft);
    }

    /**
     * Computes the cumulative frequency of the next symbol for a power-of-two total
     * ({@code ec_decode_bin}).
     *
     * @param bits the base-2 logarithm of the total frequency
     * @return the cumulative frequency, in {@code [0, 2^bits)}
     */
    public int decodeBin(int bits) {
        ext = rng >>> bits;
        var s = val / ext;
        return (1 << bits) - Math.min(s + 1, 1 << bits);
    }

    /**
     * Advances past the symbol whose frequency range is {@code [fl, fh)} out of {@code ft}
     * ({@code ec_dec_update}).
     *
     * @param fl the low cumulative frequency
     * @param fh the high cumulative frequency
     * @param ft the total frequency
     */
    public void decUpdate(int fl, int fh, int ft) {
        var s = ext * (ft - fh);
        val -= s;
        rng = fl > 0 ? ext * (fh - fl) : rng - s;
        normalize();
    }

    /**
     * Decodes a bit whose probability of being one is {@code 1 / 2^logp}
     * ({@code ec_dec_bit_logp}).
     *
     * @param logp the base-2 logarithm of the inverse probability of a one
     * @return {@code 1} or {@code 0}
     */
    public int decBitLogp(int logp) {
        var r = rng;
        var d = val;
        var s = r >>> logp;
        var ret = d < s ? 1 : 0;
        if (ret == 0) {
            val = d - s;
        }
        rng = ret != 0 ? s : r - s;
        normalize();
        return ret;
    }

    /**
     * Decodes a symbol described by an 8-bit inverse cumulative distribution
     * ({@code ec_dec_icdf}).
     *
     * @param icdf the inverse CDF: {@code 2^ftb} minus the cumulative frequency of each symbol,
     *             ending with zero; values are unsigned bytes
     * @param ftb  the base-2 logarithm of the total frequency
     * @return the decoded symbol index
     */
    public int decIcdf(short[] icdf, int ftb) {
        return decIcdf(icdf, 0, ftb);
    }

    /**
     * Decodes a symbol described by an 8-bit inverse cumulative distribution that starts at
     * {@code offset} ({@code ec_dec_icdf}).
     *
     * @param icdf   the table holding the inverse CDF
     * @param offset the index of the first entry
     * @param ftb    the base-2 logarithm of the total frequency
     * @return the decoded symbol index
     */
    public int decIcdf(short[] icdf, int offset, int ftb) {
        var s = rng;
        var d = val;
        var r = s >>> ftb;
        var ret = -1;
        int t;
        do {
            t = s;
            s = r * icdf[offset + ++ret];
        } while (d < s);
        val = d - s;
        rng = t - s;
        normalize();
        return ret;
    }

    /**
     * Decodes a symbol described by a 16-bit inverse cumulative distribution ({@code ec_dec_icdf16}).
     *
     * @param icdf the inverse CDF: {@code 2^ftb} minus the cumulative frequency of each symbol, ending with zero
     * @param ftb  the base-2 logarithm of the total frequency, at most 15
     * @return the decoded symbol index
     */
    public int decIcdf16(int[] icdf, int ftb) {
        var s = rng;
        var d = val;
        var r = s >>> ftb;
        var ret = -1;
        int t;
        do {
            t = s;
            s = r * icdf[++ret];
        } while (d < s);
        val = d - s;
        rng = t - s;
        normalize();
        return ret;
    }

    /**
     * Decodes a uniformly distributed integer in {@code [0, ft)} ({@code ec_dec_uint}).
     *
     * <p>A decoded value out of range sets {@link #error} and returns {@code ft - 1}.
     *
     * @param ft the number of possible values, unsigned and at least 2
     * @return the decoded value, unsigned
     */
    public int decUint(int ft) {
        ft--;
        var ftb = ilog(ft);
        if (ftb > EC_UINT_BITS) {
            ftb -= EC_UINT_BITS;
            var ft1 = (ft >>> ftb) + 1;
            var s = decode(ft1);
            decUpdate(s, s + 1, ft1);
            var t = s << ftb | decBits(ftb);
            if (Integer.compareUnsigned(t, ft) <= 0) {
                return t;
            }
            error = 1;
            return ft;
        } else {
            ft++;
            var s = decode(ft);
            decUpdate(s, s + 1, ft);
            return s;
        }
    }

    /**
     * Reads raw bits from the end of the buffer ({@code ec_dec_bits}).
     *
     * @param bits the number of bits, between 0 and 25
     * @return the bits, unsigned
     */
    public int decBits(int bits) {
        var window = endWindow;
        var available = nendBits;
        if (available < bits) {
            do {
                window |= readByteFromEnd() << available;
                available += EC_SYM_BITS;
            } while (available <= EC_WINDOW_SIZE - EC_SYM_BITS);
        }
        var ret = window & (int) ((1L << bits) - 1);
        window >>>= bits;
        available -= bits;
        endWindow = window;
        nendBits = available;
        nbitsTotal += bits;
        return ret;
    }
}
