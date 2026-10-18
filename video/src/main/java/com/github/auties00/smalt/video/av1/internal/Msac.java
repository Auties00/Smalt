package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.internal.simd.Simd;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Decodes the symbols of an AV1 tile with the multi-symbol arithmetic decoder (AV1 specification
 * section 8.2), adapting the CDFs it is given.
 *
 * <p>CDFs are stored as {@link Cdf} describes: {@code n} entries of {@code 32768} minus the
 * cumulative probability followed by an adaptation counter. Reading past the end of the tile
 * data never throws: the decoder behaves as if the data continued with one bits, and
 * {@link #overread()} reports how far it went.
 *
 * @implNote This implementation ports dav1d's {@code src/msac.c} with a 64-bit window
 * ({@code EC_WIN_SIZE} 64), {@code EC_PROB_SHIFT} 6 and {@code EC_MIN_PROB} 4, the
 * constants of the specification's decoding process.
 */
public final class Msac {
    /**
     * Whether the CDF adaptation runs through {@link VectorCdfDsp}, which is loaded only when this
     * is {@code true}: the Vector API is enabled and the hardware has 256-bit vectors, since a
     * narrower machine would run the 16-lane operations on the library's allocating fallback, a
     * cost the per-symbol call cannot absorb.
     */
    static final boolean VECTORS = Simd.ENABLED && Simd.preferredBits() >= 256;

    /**
     * The number of bits the probabilities are reduced by before scaling the range.
     */
    private static final int EC_PROB_SHIFT = 6;

    /**
     * The minimum probability of a symbol, in range units.
     */
    private static final int EC_MIN_PROB = 4;

    /**
     * Reads eight bytes of the tile data as one big-endian word.
     */
    private static final VarHandle BIG_ENDIAN_LONGS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The tile data.
     */
    private byte[] buf;

    /**
     * The index of the next byte to load.
     */
    private int pos;

    /**
     * The index one past the last byte of the tile data.
     */
    private int end;

    /**
     * The decoder window, left-aligned; holds the complement of the coded bits.
     */
    private long dif;

    /**
     * The current range, from 32768 to 65535.
     */
    private int rng;

    /**
     * The number of bits of {@link #dif} beyond the 16 the range uses, minus 24.
     */
    private int cnt;

    /**
     * Whether CDFs adapt after each symbol.
     */
    private boolean allowUpdateCdf;

    /**
     * The scratch space {@link VectorCdfDsp#symbol} reads the bounds of the decoded symbol back
     * through.
     */
    private final char[] scratch = new char[17];

    /**
     * Creates a decoder; {@link #init(byte[], int, int, boolean)} must be called before decoding.
     */
    public Msac() {
    }

    /**
     * Starts decoding the data of one tile.
     *
     * @param data the buffer
     * @param offset the index of the first byte of the tile data
     * @param length the number of bytes of tile data
     * @param disableCdfUpdate whether CDF adaptation is disabled for the frame
     */
    public void init(byte[] data, int offset, int length, boolean disableCdfUpdate) {
        this.buf = data;
        this.pos = offset;
        this.end = offset + length;
        this.dif = 0;
        this.rng = 0x8000;
        this.cnt = -15;
        this.allowUpdateCdf = !disableCdfUpdate;
        refill();
    }

    /**
     * Returns whether the decoder has consumed more bits than the tile data holds, the condition
     * dav1d reports as a symbol decoder overread ({@code cnt <= -15}).
     *
     * @return {@code true} after an overread
     */
    public boolean overread() {
        return cnt <= -15;
    }

    /**
     * Returns the current range, for diagnostics.
     *
     * @return the range
     */
    public int range() {
        return rng;
    }

    /**
     * Loads bytes into the window.
     */
    private void refill() {
        var c = 64 - cnt - 24;
        var d = dif;
        if (pos + 8 <= end) {
            var n = (c >> 3) + 1;
            d |= (~(long) BIG_ENDIAN_LONGS.get(buf, pos) >>> (64 - 8 * n)) << (c & 7);
            pos += n;
            dif = d;
            cnt = 64 - (c - 8 * n) - 24;
            return;
        }
        do {
            if (pos >= end) {
                d |= ~(~0xFFL << c);
                break;
            }
            d |= (long) ((buf[pos++] & 0xFF) ^ 0xFF) << c;
            c -= 8;
        } while (c >= 0);
        dif = d;
        cnt = 64 - c - 24;
    }

    /**
     * Renormalizes the range after a symbol and refills the window when needed.
     *
     * @param newDif the new window
     * @param newRng the new range, below 65536
     */
    private void norm(long newDif, int newRng) {
        var d = Integer.numberOfLeadingZeros(newRng) - 16;
        var c = cnt;
        dif = newDif << d;
        rng = newRng << d;
        cnt = c - d;
        if (Integer.compareUnsigned(c, d) < 0) {
            refill();
        }
    }

    /**
     * Decodes one bit of probability one half.
     *
     * @return the bit
     */
    public int decodeBoolEqui() {
        var r = rng;
        var d = dif;
        var v = ((r >>> 8) << 7) + EC_MIN_PROB;
        var vw = (long) v << 48;
        var ret = Long.compareUnsigned(d, vw) >= 0 ? 1 : 0;
        d -= ret * vw;
        v += ret * (r - 2 * v);
        norm(d, v);
        return ret ^ 1;
    }

    /**
     * Decodes one bit.
     *
     * @param f the probability, in Q15, that the bit is zero, expressed as {@code 32768} minus
     * that probability as CDF entries store it
     * @return the bit
     */
    public int decodeBool(int f) {
        var r = rng;
        var d = dif;
        var v = (((r >>> 8) * (f >>> EC_PROB_SHIFT)) >>> (7 - EC_PROB_SHIFT)) + EC_MIN_PROB;
        var vw = (long) v << 48;
        var ret = Long.compareUnsigned(d, vw) >= 0 ? 1 : 0;
        d -= ret * vw;
        v += ret * (r - 2 * v);
        norm(d, v);
        return ret ^ 1;
    }

    /**
     * Decodes one bit with an adaptive binary CDF.
     *
     * @param cdf the context
     * @param off the offset of the CDF (one probability and its counter)
     * @return the bit
     */
    public int decodeBoolAdapt(char[] cdf, int off) {
        var bit = decodeBool(cdf[off]);
        if (allowUpdateCdf) {
            Cdf.adaptBool(cdf, off, bit);
        }
        return bit;
    }

    /**
     * Decodes a symbol with an adaptive CDF.
     *
     * @implNote This implementation searches and adapts a CDF of up to 16 entries in one vector
     * step ({@link VectorCdfDsp#symbol}) when {@link #VECTORS} holds, so no
     * data-dependent branch depends on the decoded symbol; otherwise it runs the loop of
     * dav1d's {@code msac_decode_symbol_adapt_c}.
     *
     * @param cdf the context
     * @param off the offset of the CDF
     * @param nSymbols the number of symbols minus one, from 1 to 15
     * @return the symbol, from 0 to {@code nSymbols}
     */
    public int decodeSymbolAdapt(char[] cdf, int off, int nSymbols) {
        var c = (int) (dif >>> 48);
        var r = rng >>> 8;
        if (VECTORS && nSymbols < 16 && off + 16 <= cdf.length) {
            var packed = VectorCdfDsp.symbol(cdf, off, nSymbols, c, r, rng, scratch, allowUpdateCdf);
            var upper = (int) (packed >>> 8) & 0xFFFFFF;
            var lower = (int) (packed >>> 32);
            norm(dif - ((long) lower << 48), upper - lower);
            return (int) packed & 0xFF;
        }
        int u;
        var v = rng;
        var val = -1;
        do {
            val++;
            u = v;
            v = (r * (cdf[off + val] >>> EC_PROB_SHIFT)) >>> (7 - EC_PROB_SHIFT);
            v += EC_MIN_PROB * (nSymbols - val);
        } while (c < v);
        norm(dif - ((long) v << 48), u - v);
        if (allowUpdateCdf) {
            Cdf.adapt(cdf, off, nSymbols, val);
        }
        return val;
    }

    /**
     * Decodes a symbol of a four-symbol adaptive CDF, the alphabet of every coefficient token. The
     * bounds of the three entries are computed together and the symbol is the number of them above
     * the coded value, without the data-dependent branches of the loop {@link #decodeSymbolAdapt}
     * runs for any alphabet.
     *
     * @param cdf the context
     * @param off the offset of the CDF
     * @return the symbol, from 0 to 3
     */
    public int decodeSymbol4(char[] cdf, int off) {
        var c = (int) (dif >>> 48);
        var r = rng >>> 8;
        var b0 = ((r * (cdf[off] >>> EC_PROB_SHIFT)) >>> (7 - EC_PROB_SHIFT)) + 3 * EC_MIN_PROB;
        var b1 = ((r * (cdf[off + 1] >>> EC_PROB_SHIFT)) >>> (7 - EC_PROB_SHIFT)) + 2 * EC_MIN_PROB;
        var b2 = ((r * (cdf[off + 2] >>> EC_PROB_SHIFT)) >>> (7 - EC_PROB_SHIFT)) + EC_MIN_PROB;
        var above0 = (c - b0) >> 31;
        var above1 = (c - b1) >> 31;
        var above2 = (c - b2) >> 31;
        var val = -(above0 + above1 + above2);
        var u = rng;
        u ^= (u ^ b0) & above0;
        u ^= (u ^ b1) & above1;
        u ^= (u ^ b2) & above2;
        var v = b0;
        v ^= (v ^ b1) & above0;
        v ^= (v ^ b2) & above1;
        v &= ~above2;
        norm(dif - ((long) v << 48), u - v);
        if (allowUpdateCdf) {
            Cdf.adapt(cdf, off, 3, val);
        }
        return val;
    }

    /**
     * Decodes a coefficient token above 2 ({@code coeff_br}), repeating the four-symbol CDF up to four
     * times.
     *
     * @param cdf the context
     * @param off the offset of the CDF
     * @return the token, from 3 to 15
     */
    public int decodeHiTok(char[] cdf, int off) {
        var tokBr = decodeSymbol4(cdf, off);
        var tok = 3 + tokBr;
        if (tokBr == 3) {
            tokBr = decodeSymbol4(cdf, off);
            tok = 6 + tokBr;
            if (tokBr == 3) {
                tokBr = decodeSymbol4(cdf, off);
                tok = 9 + tokBr;
                if (tokBr == 3) {
                    tok = 12 + decodeSymbol4(cdf, off);
                }
            }
        }
        return tok;
    }

    /**
     * Decodes an unsigned value of equiprobable bits, most significant first.
     *
     * @param n the number of bits
     * @return the value
     */
    public int decodeBools(int n) {
        var v = 0;
        while (n-- > 0) {
            v = (v << 1) | decodeBoolEqui();
        }
        return v;
    }

    /**
     * Decodes a value uniformly distributed in {@code [0, n)} ({@code NS(n)}).
     *
     * @param n the number of values, at least 2
     * @return the value
     */
    public int decodeUniform(int n) {
        var l = 32 - Integer.numberOfLeadingZeros(n);
        var m = (1 << l) - n;
        var v = decodeBools(l - 1);
        return v < m ? v : (v << 1) - m + decodeBoolEqui();
    }

    /**
     * Decodes a value coded relative to a reference with subexponential coding
     * ({@code decode_signed_subexp_with_ref_bool}, AV1 specification section 5.11.58).
     *
     * @param ref the reference value
     * @param n the number of values, with {@code n >> k == 8}
     * @param k the subexponent parameter
     * @return the value, from 0 to {@code n - 1}
     */
    public int decodeSubexp(int ref, int n, int k) {
        var a = 0;
        if (decodeBoolEqui() != 0) {
            if (decodeBoolEqui() != 0) {
                k += decodeBoolEqui() + 1;
            }
            a = 1 << k;
        }
        var v = decodeBools(k) + a;
        return ref * 2 <= n ? BitReader.invRecenter(ref, v) : n - 1 - BitReader.invRecenter(n - 1 - ref, v);
    }
}
