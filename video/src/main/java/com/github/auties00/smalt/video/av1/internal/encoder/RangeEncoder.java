package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Cdf;

import java.util.Arrays;

/**
 * Encodes the symbols of one AV1 tile with the multi-symbol arithmetic coder (AV1 specification
 * section 8.2, the inverse of the decoder's {@code Msac}).
 *
 * <p>Cumulative distributions use the decoder's layout: an inverse CDF ({@code 32768} minus the
 * cumulative probability) per symbol but the last, followed by the adaptation counter, so the
 * same {@code char[]} contexts drive both directions. Symbols are adapted after coding exactly as
 * the decoder adapts them, unless the frame disables CDF updates.
 *
 * The CDFs adapt through the decoder's {@link Cdf#adapt} and {@link Cdf#adaptBool}.
 *
 * @implNote This implementation ports libaom's {@code aom_dsp/entenc.c} ({@code od_ec_enc}: the
 * 64-bit low window, byte flushing with backwards carry propagation and the minimal termination of
 * {@code od_ec_enc_done}).
 */
final class RangeEncoder {
    /**
     * Whether symbols are only costed, without coding or adapting the CDFs.
     */
    private boolean counting;

    /**
     * The log symbols are recorded into instead of being coded, or {@code null}.
     */
    private SymbolLog log;

    /**
     * Whether recorded adaptive symbols also adapt their CDFs.
     */
    private boolean adaptRecorded;

    /**
     * Makes the coder record symbols into a log instead of coding them, or code them again.
     *
     * @param log the log, or {@code null} to code
     */
    void record(SymbolLog log) {
        record(log, false);
    }

    /**
     * Makes the coder record symbols into a log instead of coding them, or code them again,
     * optionally adapting the CDFs of the recorded symbols as coding them would.
     *
     * @param log the log, or {@code null} to code
     * @param adapt whether recorded adaptive symbols adapt their CDFs
     */
    void record(SymbolLog log, boolean adapt) {
        this.log = log;
        this.adaptRecorded = adapt;
    }

    /**
     * Records a literal of equiprobable bits whose value and width the log's replay supplies.
     *
     * @throws IllegalStateException if the coder is not recording
     */
    void encodeDeferred() {
        encodeDeferred(0);
    }

    /**
     * Records a literal of equiprobable bits whose value the log's replay looks up by a tag, and
     * whose width the replay supplies.
     *
     * @param tag the index of the literal's value in the replay's value table, below 2^28
     * @throws IllegalStateException if the coder is not recording
     */
    void encodeDeferred(int tag) {
        if (log == null) {
            throw new IllegalStateException("deferred literals need a log");
        }
        log.deferred(tag);
    }

    /**
     * Returns the log symbols are recorded into.
     *
     * @return the log, or {@code null} when symbols are coded
     */
    SymbolLog log() {
        return log;
    }

    /**
     * The cost of the symbols passed while counting, in {@code 1 / 512} bits.
     */
    private long cost;

    /**
     * Makes the coder cost symbols instead of coding them, from a zero cost; the CDFs are left
     * unchanged.
     */
    void startCounting() {
        counting = true;
        cost = 0;
    }

    /**
     * Returns the cost of the symbols passed since {@link #startCounting()}.
     *
     * @return the cost in {@code 1 / 512} bits
     */
    long cost() {
        return cost;
    }

    /**
     * The precision dropped from probabilities before they are scaled by the range.
     */
    private static final int EC_PROB_SHIFT = 6;

    /**
     * The minimum probability of every symbol, in units of the scaled range.
     */
    private static final int EC_MIN_PROB = 4;

    /**
     * The output buffer.
     */
    private byte[] buf;

    /**
     * The number of bytes flushed to {@link #buf}.
     */
    private int offs;

    /**
     * The low end of the coding interval, holding bits not yet flushed.
     */
    private long low;

    /**
     * The width of the coding interval, kept in {@code [32768, 65536)}.
     */
    private int rng;

    /**
     * The number of bits held in {@link #low} beyond a byte, offset by {@code -9}.
     */
    private int cnt;

    /**
     * Whether coded symbols adapt their CDFs.
     */
    private boolean allowUpdateCdf;

    /**
     * Creates an encoder with a default output capacity.
     */
    RangeEncoder() {
        this.buf = new byte[1 << 12];
        reset(false);
    }

    /**
     * Starts a new tile, discarding the previous output.
     *
     * @param disableCdfUpdate whether symbols keep their CDFs unchanged
     */
    void reset(boolean disableCdfUpdate) {
        offs = 0;
        low = 0;
        rng = 0x8000;
        cnt = -9;
        allowUpdateCdf = !disableCdfUpdate;
    }

    /**
     * Grows the output buffer to hold at least the given number of bytes.
     *
     * @param size the needed capacity
     */
    private void ensure(int size) {
        if (size > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(size, buf.length * 2));
        }
    }

    /**
     * Adds one to the flushed byte at an index and propagates the carry backwards.
     *
     * @param index the index of the last byte that receives the carry
     */
    private void propagateCarry(int index) {
        int sum;
        do {
            sum = (buf[index] & 0xFF) + 1;
            buf[index--] = (byte) sum;
        } while ((sum >> 8) != 0);
    }

    /**
     * Renormalizes the interval to {@code [32768, 65536)}, flushing whole bytes of the low end
     * when they can no longer change.
     *
     * @param newLow the new low end
     * @param newRng the new width
     */
    private void normalize(long newLow, int newRng) {
        var c = cnt;
        var d = 15 - (31 - Integer.numberOfLeadingZeros(newRng));
        var s = c + d;
        if (s >= 40) {
            ensure(offs + 8);
            var numBytesReady = (s >> 3) + 1;
            c += 24 - (numBytesReady << 3);
            var output = newLow >>> c;
            newLow &= (1L << c) - 1;
            var mask = 1L << (numBytesReady << 3);
            var carry = output & mask;
            output &= mask - 1;
            var o = offs;
            for (var i = numBytesReady - 1; i >= 0; i--) {
                buf[o++] = (byte) (output >>> (i << 3));
            }
            if (carry != 0) {
                propagateCarry(offs - 1);
            }
            offs = o;
            s = c + d - 24;
        }
        low = newLow << d;
        rng = newRng << d;
        cnt = s;
    }

    /**
     * Encodes a symbol of an adaptive alphabet and adapts its CDF.
     *
     * @param cdf the CDF contexts
     * @param off the offset of the symbol's CDF
     * @param val the symbol, from 0 to {@code n}
     * @param n the number of symbols minus one, from 1 to 15
     */
    void encodeSymbol(char[] cdf, int off, int val, int n) {
        if (log != null) {
            log.symbol(off, val, n);
            if (adaptRecorded && allowUpdateCdf) {
                Cdf.adapt(cdf, off, n, val);
            }
            return;
        }
        var r = rng;
        var l = low;
        var fl = val > 0 ? (int) cdf[off + val - 1] : 32768;
        var fh = val < n ? (int) cdf[off + val] : 0;
        if (counting) {
            cost += RdCost.costSymbol(fl - fh);
            return;
        }
        var r8 = r >>> 8;
        if (fl < 32768) {
            var u = ((r8 * (fl >>> EC_PROB_SHIFT)) >>> (7 - EC_PROB_SHIFT)) + EC_MIN_PROB * (n - val + 1);
            var v = ((r8 * (fh >>> EC_PROB_SHIFT)) >>> (7 - EC_PROB_SHIFT)) + EC_MIN_PROB * (n - val);
            l += r - u;
            r = u - v;
        } else {
            r -= ((r8 * (fh >>> EC_PROB_SHIFT)) >>> (7 - EC_PROB_SHIFT)) + EC_MIN_PROB * (n - val);
        }
        normalize(l, r);
        if (allowUpdateCdf) {
            Cdf.adapt(cdf, off, n, val);
        }
    }

    /**
     * Encodes a binary symbol with a probability the caller derived, without adaptation.
     *
     * @param bit the bit, 0 or 1
     * @param f the inverse probability of a zero, scaled by 32768
     */
    void encodeBoolFixed(int bit, int f) {
        if (log != null) {
            log.fixed(bit, f);
            return;
        }
        if (counting) {
            cost += RdCost.costSymbol(bit != 0 ? f : 32768 - f);
            return;
        }
        var r = rng;
        var l = low;
        var v = (((r >>> 8) * (f >>> EC_PROB_SHIFT)) >>> (7 - EC_PROB_SHIFT)) + EC_MIN_PROB;
        if (bit != 0) {
            l += r - v;
            r = v;
        } else {
            r -= v;
        }
        normalize(l, r);
    }

    /**
     * Encodes an adaptive binary symbol and adapts its CDF.
     *
     * @param cdf the CDF contexts
     * @param off the offset of the symbol's CDF
     * @param bit the bit, 0 or 1
     */
    void encodeBool(char[] cdf, int off, int bit) {
        if (log != null) {
            log.bool(off, bit);
            if (adaptRecorded && allowUpdateCdf) {
                Cdf.adaptBool(cdf, off, bit);
            }
            return;
        }
        encodeBoolFixed(bit, cdf[off]);
        if (allowUpdateCdf && !counting) {
            Cdf.adaptBool(cdf, off, bit);
        }
    }

    /**
     * Encodes an equiprobable bit.
     *
     * @param bit the bit, 0 or 1
     */
    void encodeBoolEqui(int bit) {
        encodeBoolFixed(bit, 16384);
    }

    /**
     * Encodes an unsigned literal of equiprobable bits, most significant bit first.
     *
     * @param value the value to code
     * @param n the number of bits
     */
    void encodeBits(int value, int n) {
        for (var bit = n - 1; bit >= 0; bit--) {
            encodeBoolFixed((value >>> bit) & 1, 16384);
        }
    }

    /**
     * Encodes a value with the Exp-Golomb code of the coefficient syntax ({@code write_golomb}).
     *
     * @param value the non-negative value
     */
    void encodeGolomb(int value) {
        var x = value + 1;
        var length = 32 - Integer.numberOfLeadingZeros(x);
        for (var i = 0; i < length - 1; i++) {
            encodeBoolFixed(0, 16384);
        }
        for (var i = length - 1; i >= 0; i--) {
            encodeBoolFixed((x >>> i) & 1, 16384);
        }
    }

    /**
     * Flushes the remaining bits with the minimal termination and returns the coded size.
     *
     * <p>The coded bytes stay in {@link #buffer()} until the next {@link #reset(boolean)}.
     *
     * @return the number of coded bytes
     */
    int finish() {
        var l = low;
        var c = cnt;
        var s = 10;
        var m = 0x3FFFL;
        var e = ((l + m) & ~m) | (m + 1);
        s += c;
        ensure(offs + Math.max((s + 7) >> 3, 0) + 1);
        if (s > 0) {
            var n = (1L << (c + 16)) - 1;
            do {
                var val = (int) (e >>> (c + 16)) & 0xFFFF;
                buf[offs] = (byte) val;
                if ((val & 0x100) != 0) {
                    propagateCarry(offs - 1);
                }
                offs++;
                e &= n;
                s -= 8;
                c -= 8;
                n >>>= 8;
            } while (s > 0);
        }
        return offs;
    }

    /**
     * {@return the output buffer, whose first bytes are the coded tile after {@link #finish()}}
     */
    byte[] buffer() {
        return buf;
    }
}
