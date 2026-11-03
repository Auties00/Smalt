package com.github.auties00.smalt.audio.g722;

/**
 * One sub-band ADPCM coder of ITU-T G.722 (11/88) section 6: the lower sub-band's 6-bit coder or the higher
 * sub-band's 2-bit coder.
 *
 * <p>Variables keep the Recommendation's 16-bit fixed-point representations and its saturating arithmetic, so the
 * coder is bit-exact with the digital test sequences of its Appendix II.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results. Instances are not
 * thread-safe.
 */
final class G722Band {
    private static final int[] Q6 = {0, 35, 72, 110, 150, 190, 233, 276, 323, 370, 422, 473, 530, 587, 650, 714, 786,
            858, 940, 1023, 1121, 1219, 1339, 1458, 1612, 1765, 1980, 2195, 2557, 2919};
    private static final int[] QQ6 = {0, 17, 54, 91, 130, 170, 211, 254, 300, 347, 396, 447, 501, 558, 618, 682, 750,
            822, 899, 982, 1072, 1170, 1279, 1399, 1535, 1689, 1873, 2088, 2376, 2738, 3101};
    private static final int[] QQ5 = {0, 35, 110, 190, 276, 370, 473, 587, 714, 858, 1023, 1219, 1458, 1765, 2195,
            2919};
    private static final int[] QQ4 = {0, 150, 323, 530, 786, 1121, 1612, 2557};
    private static final int[] WL = {-60, -30, 58, 172, 334, 538, 1198, 3042};
    private static final int[] QQ2 = {0, 202, 926};
    private static final int[] WH = {0, -214, 798};
    private static final int[] ILB = {2048, 2093, 2139, 2186, 2233, 2282, 2332, 2383, 2435, 2489, 2543, 2599, 2656,
            2714, 2774, 2834, 2896, 2960, 3025, 3091, 3158, 3228, 3298, 3371, 3444, 3520, 3597, 3676, 3756, 3838, 3922,
            4008};

    private final boolean low;
    private int a1;
    private int a2;
    private final int[] b = new int[7];
    private final int[] d = new int[7];
    private int p1;
    private int p2;
    private int r1;
    private int r2;
    private int nb;
    private int det;
    private int sz;
    private int s;

    /**
     * Constructs a coder in the reset state.
     *
     * @param low whether the coder codes the lower sub-band
     */
    G722Band(boolean low) {
        this.low = low;
        reset();
    }

    /**
     * Puts the coder in the reset state of section 6.4.
     */
    void reset() {
        a1 = 0;
        a2 = 0;
        for (var i = 0; i < 7; i++) {
            b[i] = 0;
            d[i] = 0;
        }
        p1 = 0;
        p2 = 0;
        r1 = 0;
        r2 = 0;
        nb = 0;
        det = low ? 32 : 8;
        sz = 0;
        s = 0;
    }

    /**
     * Saturates a value to 16 bits.
     *
     * @param x the value
     * @return the value limited to -32768 to 32767
     */
    private static int sat(int x) {
        return x < -32768 ? -32768 : Math.min(x, 32767);
    }

    /**
     * Multiplies two Q15 values, truncating.
     *
     * @param a the first factor
     * @param b the second factor
     * @return the product
     */
    private static int mul(int a, int b) {
        return (a * b) >> 15;
    }

    /**
     * Encodes a lower sub-band sample (SUBTRA, QUANTL) and adapts the coder.
     *
     * @param xl the sample, from -16384 to 16383
     * @return the 6-bit code
     */
    int encodeLow(int xl) {
        var el = sat(xl - s);
        var negative = el >> 15 != 0;
        var wd = negative ? (32767 - el) & 32767 : el;
        var mil = 1;
        while (mil < 30 && wd >= mul(Q6[mil] << 3, det)) {
            mil++;
        }
        int il;
        if (!negative) {
            il = 62 - mil;
        } else {
            il = mil == 1 ? 63 : mil == 2 ? 62 : 34 - mil;
        }
        adaptLow(il);
        return il;
    }

    /**
     * Decodes a lower sub-band code (INVQBL) and adapts the coder from its top four bits.
     *
     * @param code the 6-bit code
     * @param mode 1, 2 or 3 for the 64, 56 and 48 kbit/s modes, which use 6, 5 and 4 bits of the code
     * @return the reconstructed sample, from -16384 to 16383
     */
    int decodeLow(int code, int mode) {
        int sign;
        int magnitude;
        switch (mode) {
            case 1 -> {
                if (code < 4) {
                    sign = 1;
                    magnitude = QQ6[1];
                } else if (code < 32) {
                    sign = 1;
                    magnitude = QQ6[34 - code];
                } else if (code >= 62) {
                    sign = 1;
                    magnitude = QQ6[64 - code];
                } else {
                    sign = 0;
                    magnitude = QQ6[62 - code];
                }
            }
            case 2 -> {
                var code5 = code >> 1;
                if (code5 < 2) {
                    sign = 1;
                    magnitude = QQ5[1];
                } else if (code5 < 16) {
                    sign = 1;
                    magnitude = QQ5[17 - code5];
                } else if (code5 == 31) {
                    sign = 1;
                    magnitude = QQ5[1];
                } else {
                    sign = 0;
                    magnitude = QQ5[31 - code5];
                }
            }
            default -> {
                var code4 = code >> 2;
                sign = code4 != 0 && code4 < 8 ? 1 : 0;
                magnitude = QQ4[code4 == 0 ? 0 : code4 < 8 ? 8 - code4 : 15 - code4];
            }
        }
        var wd1 = magnitude << 3;
        var dl = mul(det, sign != 0 ? -wd1 : wd1);
        var yl = sat(s + dl);
        var rl = Math.max(-16384, Math.min(16383, yl));
        adaptLow(code);
        return rl;
    }

    /**
     * Adapts the lower sub-band coder to a code through its 4-bit truncation (INVQAL, LOGSCL, SCALEL).
     *
     * @param code the 6-bit code
     */
    private void adaptLow(int code) {
        var code4 = code >> 2;
        var negative = code4 != 0 && code4 < 8;
        var il4 = code4 == 0 ? 0 : code4 < 8 ? 8 - code4 : 15 - code4;
        var wd1 = QQ4[il4] << 3;
        adapt(mul(det, negative ? -wd1 : wd1), il4);
    }

    /**
     * Encodes a higher sub-band sample (SUBTRA, QUANTH) and adapts the coder.
     *
     * @param xh the sample, from -16384 to 16383
     * @return the 2-bit code
     */
    int encodeHigh(int xh) {
        var eh = sat(xh - s);
        var negative = eh >> 15 != 0;
        var wd = negative ? (32767 - eh) & 32767 : eh;
        var large = wd >= mul(564 << 3, det);
        var ih = negative ? (large ? 0 : 1) : (large ? 2 : 3);
        decodeHigh(ih);
        return ih;
    }

    /**
     * Decodes a higher sub-band code (INVQAH) and adapts the coder.
     *
     * @param ih the 2-bit code
     * @return the reconstructed sample, from -16384 to 16383
     */
    int decodeHigh(int ih) {
        var negative = ih < 2;
        var ih2 = ih == 0 || ih == 2 ? 2 : 1;
        var wd1 = QQ2[ih2] << 3;
        var dh = mul(det, negative ? -wd1 : wd1);
        var yh = sat(s + dh);
        var rh = Math.max(-16384, Math.min(16383, yh));
        adapt(dh, ih2);
        return rh;
    }

    /**
     * Adapts the scale factor and the predictors to a quantized difference (LOGSCL/LOGSCH, SCALEL/SCALEH, RECONS,
     * PARREC, UPPOL1, UPPOL2, UPZERO, DELAYA, FILTEP, FILTEZ, PREDIC).
     *
     * @param dlt the quantized difference
     * @param codeScale the index of the scale factor multiplier
     */
    private void adapt(int dlt, int codeScale) {
        int nbp;
        int dep;
        if (low) {
            nbp = sat(mul(nb, 32512) + WL[codeScale]);
            nbp = nbp < 0 ? 0 : Math.min(nbp, 18432);
            dep = scale(nbp, 8);
        } else {
            nbp = sat(mul(nb, 32512) + WH[codeScale]);
            nbp = nbp < 0 ? 0 : Math.min(nbp, 22528);
            dep = scale(nbp, 10);
        }
        var plt = sat(dlt + sz);
        var rlt = sat(s + dlt);

        var wd1 = dlt == 0 ? 0 : 128;
        var sg0 = dlt >> 15;
        for (var i = 1; i < 7; i++) {
            var wd2 = sg0 == d[i] >> 15 ? wd1 : -wd1;
            b[i] = sat(wd2 + mul(b[i], 32640));
        }

        sg0 = plt >> 15;
        var sg1 = p1 >> 15;
        var sg2 = p2 >> 15;
        wd1 = sat(a1 + a1);
        wd1 = sat(wd1 + wd1);
        var wd2 = (sg0 == sg1 ? sat(-wd1) : wd1) >> 7;
        var wd3 = sg0 == sg2 ? 128 : -128;
        var apl2 = sat(sat(wd2 + wd3) + mul(a2, 32512));
        apl2 = Math.max(-12288, Math.min(12288, apl2));

        var apl1 = sat((sg0 == sg1 ? 192 : -192) + mul(a1, 32640));
        var limit = sat(15360 - apl2);
        apl1 = Math.max(-limit, Math.min(limit, apl1));

        nb = nbp;
        det = dep;
        System.arraycopy(d, 1, d, 2, 5);
        d[1] = dlt;
        a1 = apl1;
        a2 = apl2;
        p2 = p1;
        p1 = plt;
        r2 = r1;
        r1 = rlt;

        var zero = 0;
        for (var i = 6; i > 0; i--) {
            zero = sat(zero + mul(b[i], sat(d[i] + d[i])));
        }
        var pole = sat(mul(a1, sat(r1 + r1)) + mul(a2, sat(r2 + r2)));
        sz = zero;
        s = sat(pole + zero);
    }

    /**
     * Converts a logarithmic scale factor to the linear domain (SCALEL, SCALEH).
     *
     * @param nb the logarithmic scale factor
     * @param shiftBase 8 for the lower sub-band, 10 for the higher
     * @return the linear scale factor
     */
    private static int scale(int nb, int shiftBase) {
        var wd1 = (nb >> 6) & 31;
        var shift = shiftBase - (nb >> 11);
        var wd3 = shift >= 0 ? ILB[wd1] >> shift : ILB[wd1] << -shift;
        return wd3 << 2;
    }
}
