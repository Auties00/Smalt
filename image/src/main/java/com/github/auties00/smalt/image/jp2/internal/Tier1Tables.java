package com.github.auties00.smalt.image.jp2.internal;

/**
 * Holds the tables of the code-block coder (ISO/IEC 15444-1 annex C and D): the MQ coder's
 * probability state machine, the context of each neighborhood, and the distortion estimates the
 * encoder's rate control uses.
 *
 * <p>Contexts are numbered as in OpenJPEG: 0 to 8 zero coding, 9 to 13 sign coding, 14 to 16
 * magnitude refinement, 17 run-length (aggregation) and 18 uniform.
 *
 * @implNote This implementation follows OpenJPEG's {@code mqc.c} state table, where each state of
 * Table C.2 appears twice (most probable symbol 0, then 1), and computes the neighborhood tables
 * with the functions of OpenJPEG's {@code t1_generate_luts.c} at class initialization.
 */
final class Tier1Tables {
    /**
     * The first zero coding context.
     */
    static final int ZC = 0;

    /**
     * The first sign coding context.
     */
    static final int SC = 9;

    /**
     * The first magnitude refinement context.
     */
    static final int MAG = 14;

    /**
     * The run-length context.
     */
    static final int AGG = 17;

    /**
     * The uniform context.
     */
    static final int UNI = 18;

    /**
     * The number of contexts.
     */
    static final int CONTEXTS = 19;

    /**
     * The number of fractional bits of the encoder's distortion estimates.
     */
    static final int NMSEDEC_BITS = 7;

    /**
     * The number of fractional bits of the coefficients the encoder codes.
     */
    static final int NMSEDEC_FRACBITS = NMSEDEC_BITS - 1;

    /**
     * The probability estimate of each state (Table C.2, {@code Qe}).
     */
    static final int[] QE = new int[94];

    /**
     * The most probable symbol of each state.
     */
    static final int[] MPS = new int[94];

    /**
     * The state after coding the most probable symbol.
     */
    static final int[] NEXT_MPS = new int[94];

    /**
     * The state after coding the least probable symbol.
     */
    static final int[] NEXT_LPS = new int[94];

    /**
     * The zero coding context of each orientation and neighborhood: index
     * {@code (orientation << 9) | neighborhood significance bits}.
     */
    static final byte[] ZERO_CONTEXT = new byte[2048];

    /**
     * The sign coding context of each sign neighborhood index.
     */
    static final byte[] SIGN_CONTEXT = new byte[256];

    /**
     * The sign prediction bit of each sign neighborhood index.
     */
    static final byte[] SIGN_PREDICTION = new byte[256];

    /**
     * The distortion reduction of a significance decision, by the 7 bits below the bit-plane.
     */
    static final short[] NMSEDEC_SIG = new short[1 << NMSEDEC_BITS];

    /**
     * The distortion reduction of a significance decision at bit-plane 0.
     */
    static final short[] NMSEDEC_SIG0 = new short[1 << NMSEDEC_BITS];

    /**
     * The distortion reduction of a refinement decision, by the 7 bits below the bit-plane.
     */
    static final short[] NMSEDEC_REF = new short[1 << NMSEDEC_BITS];

    /**
     * The distortion reduction of a refinement decision at bit-plane 0.
     */
    static final short[] NMSEDEC_REF0 = new short[1 << NMSEDEC_BITS];

    /**
     * The probability estimates of the 47 states of Table C.2.
     */
    private static final int[] STATE_QE = {
            0x5601, 0x3401, 0x1801, 0x0ac1, 0x0521, 0x0221, 0x5601, 0x5401, 0x4801, 0x3801, 0x3001, 0x2401,
            0x1c01, 0x1601, 0x5601, 0x5401, 0x5101, 0x4801, 0x3801, 0x3401, 0x3001, 0x2801, 0x2401, 0x2201,
            0x1c01, 0x1801, 0x1601, 0x1401, 0x1201, 0x1101, 0x0ac1, 0x09c1, 0x08a1, 0x0521, 0x0441, 0x02a1,
            0x0221, 0x0141, 0x0111, 0x0085, 0x0049, 0x0025, 0x0015, 0x0009, 0x0005, 0x0001, 0x5601
    };

    /**
     * The next state after a most probable symbol, Table C.2 {@code NMPS}.
     */
    private static final int[] STATE_NMPS = {
            1, 2, 3, 4, 5, 38, 7, 8, 9, 10, 11, 12, 13, 29, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 45, 46
    };

    /**
     * The next state after a least probable symbol, Table C.2 {@code NLPS}.
     */
    private static final int[] STATE_NLPS = {
            1, 6, 9, 12, 29, 33, 6, 14, 14, 14, 17, 18, 20, 21, 14, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 23, 24, 25,
            26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 46
    };

    /**
     * Whether the most probable symbol switches after a least probable symbol, Table C.2
     * {@code SWITCH}.
     */
    private static final int[] STATE_SWITCH = {
            1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    static {
        for (var state = 0; state < 47; state++) {
            for (var mps = 0; mps < 2; mps++) {
                var index = 2 * state + mps;
                QE[index] = STATE_QE[state];
                MPS[index] = mps;
                NEXT_MPS[index] = 2 * STATE_NMPS[state] + mps;
                NEXT_LPS[index] = 2 * STATE_NLPS[state] + (STATE_SWITCH[state] == 1 ? 1 - mps : mps);
            }
        }
        for (var j = 0; j < 4; j++) {
            var orientation = j == 2 ? 1 : j == 1 ? 2 : j;
            for (var i = 0; i < 512; i++) {
                ZERO_CONTEXT[(orientation << 9) | i] = (byte) zeroContext(i, j);
            }
        }
        for (var i = 0; i < 256; i++) {
            SIGN_CONTEXT[i] = (byte) signContext(i);
            SIGN_PREDICTION[i] = (byte) signPrediction(i);
        }
        var scale = 1 << NMSEDEC_FRACBITS;
        for (var i = 0; i < (1 << NMSEDEC_BITS); i++) {
            var t = i / (double) scale;
            var u = t;
            var v = t - 1.5;
            NMSEDEC_SIG[i] = (short) Math.max(0, (int) (Math.floor((u * u - v * v) * scale + 0.5) / scale * 8192.0));
            NMSEDEC_SIG0[i] = (short) Math.max(0, (int) (Math.floor((u * u) * scale + 0.5) / scale * 8192.0));
            u = t - 1.0;
            v = (i & (1 << (NMSEDEC_BITS - 1))) != 0 ? t - 1.5 : t - 0.5;
            NMSEDEC_REF[i] = (short) Math.max(0, (int) (Math.floor((u * u - v * v) * scale + 0.5) / scale * 8192.0));
            NMSEDEC_REF0[i] = (short) Math.max(0, (int) (Math.floor((u * u) * scale + 0.5) / scale * 8192.0));
        }
    }

    /**
     * Prevents instantiation.
     */
    private Tier1Tables() {
        throw new AssertionError();
    }

    /**
     * Computes the zero coding context of a neighborhood (Table D.1).
     *
     * @param f           the significance bits of the 3x3 neighborhood, bit 4 being the sample
     * @param orientation the sub-band orientation as numbered by the generator (2 swaps the
     *                    horizontal and vertical neighbors)
     * @return the context
     */
    private static int zeroContext(int f, int orientation) {
        var h = ((f & (1 << 3)) != 0 ? 1 : 0) + ((f & (1 << 5)) != 0 ? 1 : 0);
        var v = ((f & (1 << 1)) != 0 ? 1 : 0) + ((f & (1 << 7)) != 0 ? 1 : 0);
        var d = ((f & 1) != 0 ? 1 : 0) + ((f & (1 << 2)) != 0 ? 1 : 0) + ((f & (1 << 8)) != 0 ? 1 : 0)
                + ((f & (1 << 6)) != 0 ? 1 : 0);
        int n;
        if (orientation == 3) {
            var hv = h + v;
            if (d == 0) {
                n = hv == 0 ? 0 : hv == 1 ? 1 : 2;
            } else if (d == 1) {
                n = hv == 0 ? 3 : hv == 1 ? 4 : 5;
            } else if (d == 2) {
                n = hv == 0 ? 6 : 7;
            } else {
                n = 8;
            }
        } else {
            if (orientation == 2) {
                var t = h;
                h = v;
                v = t;
            }
            if (h == 0) {
                if (v == 0) {
                    n = d == 0 ? 0 : d == 1 ? 1 : 2;
                } else {
                    n = v == 1 ? 3 : 4;
                }
            } else if (h == 1) {
                if (v == 0) {
                    n = d == 0 ? 5 : 6;
                } else {
                    n = 7;
                }
            } else {
                n = 8;
            }
        }
        return ZC + n;
    }

    /**
     * Computes the horizontal and vertical sign contributions of a sign neighborhood index
     * (Table D.2).
     *
     * @param f the index: bits 0/2/4/6 the signs of the west, east, north and south neighbors,
     *          bits 3/5/1/7 their significance
     * @return the horizontal contribution in the high half, the vertical one in the low half
     */
    private static int[] contributions(int f) {
        var eastPositive = (f & ((1 << 5) | (1 << 2))) == (1 << 5) ? 1 : 0;
        var westPositive = (f & ((1 << 3) | 1)) == (1 << 3) ? 1 : 0;
        var eastNegative = (f & ((1 << 5) | (1 << 2))) == ((1 << 5) | (1 << 2)) ? 1 : 0;
        var westNegative = (f & ((1 << 3) | 1)) == ((1 << 3) | 1) ? 1 : 0;
        var hc = Math.min(eastPositive + westPositive, 1) - Math.min(eastNegative + westNegative, 1);
        var northPositive = (f & ((1 << 1) | (1 << 4))) == (1 << 1) ? 1 : 0;
        var southPositive = (f & ((1 << 7) | (1 << 6))) == (1 << 7) ? 1 : 0;
        var northNegative = (f & ((1 << 1) | (1 << 4))) == ((1 << 1) | (1 << 4)) ? 1 : 0;
        var southNegative = (f & ((1 << 7) | (1 << 6))) == ((1 << 7) | (1 << 6)) ? 1 : 0;
        var vc = Math.min(northPositive + southPositive, 1) - Math.min(northNegative + southNegative, 1);
        return new int[]{hc, vc};
    }

    /**
     * Computes the sign coding context of a sign neighborhood index (Table D.3).
     *
     * @param f the sign neighborhood index
     * @return the context
     */
    private static int signContext(int f) {
        var c = contributions(f);
        var hc = c[0];
        var vc = c[1];
        if (hc < 0) {
            hc = -hc;
            vc = -vc;
        }
        int n;
        if (hc == 0) {
            n = vc == 0 ? 0 : 1;
        } else {
            n = vc == -1 ? 2 : vc == 0 ? 3 : 4;
        }
        return SC + n;
    }

    /**
     * Computes the sign prediction bit of a sign neighborhood index (Table D.3).
     *
     * @param f the sign neighborhood index
     * @return the bit the decoded sign is exclusive-ored with
     */
    private static int signPrediction(int f) {
        var c = contributions(f);
        var hc = c[0];
        var vc = c[1];
        if (hc == 0 && vc == 0) {
            return 0;
        }
        return (hc > 0 || (hc == 0 && vc > 0)) ? 0 : 1;
    }
}
