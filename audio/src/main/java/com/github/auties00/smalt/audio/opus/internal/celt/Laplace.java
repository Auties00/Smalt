/* Ports libopus 1.5.2 (celt/laplace.c). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Codes the Laplace-distributed coarse energy residuals of CELT (RFC 6716 section 4.3.2.1).
 *
 * @implNote This implementation omits the DRED-only {@code _p0} variants.
 */
public final class Laplace {
    /**
     * The base-2 logarithm of the minimum probability of an energy delta, out of 32768.
     */
    private static final int LAPLACE_LOG_MINP = 0;

    /**
     * The minimum probability of an energy delta, out of 32768.
     */
    private static final int LAPLACE_MINP = 1 << LAPLACE_LOG_MINP;

    /**
     * The minimum number of guaranteed representable energy deltas in one direction.
     */
    private static final int LAPLACE_NMIN = 16;

    private Laplace() {
        throw new AssertionError();
    }

    /**
     * Returns the frequency of the value one, given the frequency of zero and the decay
     * ({@code ec_laplace_get_freq1}).
     *
     * @param fs0   the frequency of zero
     * @param decay the decay, positive and at most 11456
     * @return the frequency of plus or minus one
     */
    private static int getFreq1(int fs0, int decay) {
        var ft = 32768 - LAPLACE_MINP * (2 * LAPLACE_NMIN) - fs0;
        return ft * (16384 - decay) >> 15;
    }

    /**
     * Encodes a Laplace-distributed value ({@code ec_laplace_encode}).
     *
     * <p>Values beyond the representable range are clamped; the value actually coded is returned.
     *
     * @param enc   the range encoder
     * @param value the value to encode
     * @param fs    the probability of zero, multiplied by 32768
     * @param decay the probability of plus or minus one, multiplied by 16384
     * @return the value coded
     */
    public static int laplaceEncode(RangeEncoder enc, int value, int fs, int decay) {
        var fl = 0;
        var val = value;
        if (val != 0) {
            var s = -(val < 0 ? 1 : 0);
            val = (val + s) ^ s;
            fl = fs;
            fs = getFreq1(fs, decay);
            int i;
            for (i = 1; fs > 0 && i < val; i++) {
                fs *= 2;
                fl += fs + 2 * LAPLACE_MINP;
                fs = (fs * decay) >> 15;
            }
            if (fs == 0) {
                var ndiMax = (32768 - fl + LAPLACE_MINP - 1) >> LAPLACE_LOG_MINP;
                ndiMax = (ndiMax - s) >> 1;
                var di = Math.min(val - i, ndiMax - 1);
                fl += (2 * di + 1 + s) * LAPLACE_MINP;
                fs = Math.min(LAPLACE_MINP, 32768 - fl);
                value = (i + di + s) ^ s;
            } else {
                fs += LAPLACE_MINP;
                fl += fs & ~s;
            }
        }
        enc.encodeBin(fl, fl + fs, 15);
        return value;
    }

    /**
     * Decodes a Laplace-distributed value ({@code ec_laplace_decode}).
     *
     * @param dec   the range decoder
     * @param fs    the probability of zero, multiplied by 32768
     * @param decay the probability of plus or minus one, multiplied by 16384
     * @return the decoded value
     */
    public static int laplaceDecode(RangeDecoder dec, int fs, int decay) {
        var val = 0;
        var fm = dec.decodeBin(15);
        var fl = 0;
        if (fm >= fs) {
            val++;
            fl = fs;
            fs = getFreq1(fs, decay) + LAPLACE_MINP;
            while (fs > LAPLACE_MINP && fm >= fl + 2 * fs) {
                fs *= 2;
                fl += fs;
                fs = ((fs - 2 * LAPLACE_MINP) * decay) >> 15;
                fs += LAPLACE_MINP;
                val++;
            }
            if (fs <= LAPLACE_MINP) {
                var di = (fm - fl) >> (LAPLACE_LOG_MINP + 1);
                val += di;
                fl += 2 * di * LAPLACE_MINP;
            }
            if (fm < fl + fs) {
                val = -val;
            } else {
                fl += fs;
            }
        }
        dec.decUpdate(fl, Math.min(fl + fs, 32768), 32768);
        return val;
    }
}
