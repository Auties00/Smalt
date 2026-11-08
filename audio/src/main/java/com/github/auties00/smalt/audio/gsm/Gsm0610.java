/* Ports libgsm 1.0.22 (add.c, code.c, decode.c, long_term.c, lpc.c, preprocess.c, rpe.c, short_term.c, table.c).
   Copyright 1992, 1993, 1994 by Jutta Degener and Carsten Bormann, Technische Universitaet Berlin; libgsm licence.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.gsm;

import java.util.Arrays;

/**
 * The fixed-point RPE-LTP speech coder of ETSI GSM 06.10 (EN 300 961), encoder and decoder, on frames of 160
 * samples and their 76 parameters.
 *
 * <p>The parameters of a frame, in the order of {@link #PARAMETERS}: the eight coded log-area ratios
 * {@code LARc[0..7]}, then for each of the four sub-frames the LTP lag {@code Nc}, the LTP gain {@code bc}, the
 * RPE grid {@code Mc}, the block amplitude {@code xmaxc} and the 13 RPE pulses {@code xMc}. Samples are 16-bit with
 * the 13 significant bits of the Recommendation at the top.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results. Instances are not
 * thread-safe.
 */
final class Gsm0610 {
    /**
     * The samples of a frame.
     */
    static final int SAMPLES = 160;

    /**
     * The number of parameters of a frame.
     */
    static final int PARAMETERS = 76;

    /**
     * The bit width of each parameter of a frame.
     */
    static final int[] WIDTHS = new int[PARAMETERS];

    static {
        var lar = new int[]{6, 6, 5, 5, 4, 4, 3, 3};
        System.arraycopy(lar, 0, WIDTHS, 0, 8);
        for (var j = 0; j < 4; j++) {
            var base = 8 + 17 * j;
            WIDTHS[base] = 7;
            WIDTHS[base + 1] = 2;
            WIDTHS[base + 2] = 2;
            WIDTHS[base + 3] = 6;
            for (var i = 0; i < 13; i++) {
                WIDTHS[base + 4 + i] = 3;
            }
        }
    }

    /**
     * The decoder homing frame of EN 300 961 clause 4.2.
     */
    private static final int[] DECODER_HOMING_FRAME = new int[PARAMETERS];

    static {
        System.arraycopy(new int[]{9, 23, 15, 8, 7, 3, 3, 2}, 0, DECODER_HOMING_FRAME, 0, 8);
        for (var sub = 0; sub < 4; sub++) {
            var base = 8 + 17 * sub;
            DECODER_HOMING_FRAME[base] = 40;
            for (var i = 0; i < 13; i++) {
                DECODER_HOMING_FRAME[base + 4 + i] = 4;
            }
        }
        DECODER_HOMING_FRAME[8 + 17 * 3 + 4 + 4] = 3;
    }

    /**
     * The sample value of every sample of the encoder homing frame.
     */
    private static final int HOMING_SAMPLE = 8;

    private static final int MIN_WORD = -32768;
    private static final int MAX_WORD = 32767;
    private static final int[] QLB = {3277, 11469, 21299, 32767};
    private static final int[] DLB = {6554, 16384, 26214, 32767};
    private static final int[] NRFAC = {29128, 26215, 23832, 21846, 20165, 18725, 17476, 16384};
    private static final int[] FAC = {18431, 20479, 22527, 24575, 26623, 28671, 30719, 32767};
    private static final int[] LAR_B2 = {0, 0, 4096, -5120, 188, -3584, -682, -2288};
    private static final int[] LAR_MIC = {-32, -32, -16, -16, -8, -8, -4, -4};
    private static final int[] LAR_INVA = {13107, 13107, 13107, 13107, 19223, 17476, 31454, 29708};
    private static final int[] LAR_A = {20480, 20480, 20480, 20480, 13964, 15360, 8534, 9036};
    private static final int[] LAR_B = {0, 0, 2048, -2560, 94, -1792, -341, -1144};
    private static final int[] LAR_MAC = {31, 31, 15, 15, 7, 7, 3, 3};

    /**
     * The reconstructed short-term residual: {@code dp0[0..119]} the past 120 samples, then the current frame.
     */
    private final short[] dp0 = new short[280];
    private final short[] e = new short[50];
    private short z1;
    private long lZ2;
    private short mp;
    private final short[] u = new short[8];
    private final short[][] larpp = new short[2][8];
    private int j;
    private short nrp = 40;
    private final short[] v = new short[9];
    private short msr;
    private boolean home = true;

    private final short[] so = new short[SAMPLES];
    private final short[] wt = new short[SAMPLES];
    private final short[] erp = new short[40];
    private final short[] larp = new short[8];
    private final short[] work40 = new short[40];
    private final short[] xm = new short[13];
    private final short[] xmp = new short[13];
    private final long[] acf = new long[9];
    private final short[] lar = new short[8];
    private final short[] p = new short[9];
    private final short[] k = new short[9];
    private final short[] acf16 = new short[9];
    private final int[] out3 = new int[3];

    /**
     * Puts the coder in its home state (EN 300 961 clauses 4.5 and 4.6).
     */
    void reset() {
        Arrays.fill(dp0, (short) 0);
        Arrays.fill(e, (short) 0);
        z1 = 0;
        lZ2 = 0;
        mp = 0;
        Arrays.fill(u, (short) 0);
        Arrays.fill(larpp[0], (short) 0);
        Arrays.fill(larpp[1], (short) 0);
        j = 0;
        nrp = 40;
        Arrays.fill(v, (short) 0);
        msr = 0;
        home = true;
    }

    /**
     * Saturates a value to a 16-bit word.
     *
     * @param x the value
     * @return the word
     */
    private static short sat(long x) {
        return (short) (x < MIN_WORD ? MIN_WORD : x > MAX_WORD ? MAX_WORD : x);
    }

    /**
     * {@return the saturated sum of two words ({@code GSM_ADD})}
     *
     * @param a the first word
     * @param b the second word
     */
    private static short add(int a, int b) {
        return sat((long) a + b);
    }

    /**
     * {@return the saturated difference of two words ({@code GSM_SUB})}
     *
     * @param a the first word
     * @param b the second word
     */
    private static short sub(int a, int b) {
        return sat((long) a - b);
    }

    /**
     * {@return the Q15 product of two words, rounded, without the overflow guard ({@code GSM_MULT_R})}
     *
     * @param a the first word
     * @param b the second word
     */
    private static short multR(int a, int b) {
        return (short) (((long) a * b + 16384) >> 15);
    }

    /**
     * {@return the Q15 product of two words, truncated, without the overflow guard ({@code GSM_MULT})}
     *
     * @param a the first word
     * @param b the second word
     */
    private static short mult(int a, int b) {
        return (short) (((long) a * b) >> 15);
    }

    /**
     * {@return the Q15 product of two words, truncated, with {@code -1 * -1} saturated ({@code gsm_mult})}
     *
     * @param a the first word
     * @param b the second word
     */
    private static short multGuarded(int a, int b) {
        return a == MIN_WORD && b == MIN_WORD ? MAX_WORD : mult(a, b);
    }

    /**
     * {@return the saturated absolute value of a word ({@code GSM_ABS})}
     *
     * @param a the word
     */
    private static short abs(int a) {
        return (short) (a < 0 ? (a == MIN_WORD ? MAX_WORD : -a) : a);
    }

    /**
     * {@return the saturated sum of two 32-bit values ({@code GSM_L_ADD})}
     *
     * @param a the first value
     * @param b the second value
     */
    private static long lAdd(long a, long b) {
        var sum = a + b;
        return sum < Integer.MIN_VALUE ? Integer.MIN_VALUE : Math.min(sum, Integer.MAX_VALUE);
    }

    /**
     * Returns the left shift that normalizes a nonzero 32-bit value ({@code gsm_norm}).
     *
     * @param a the value, nonzero
     * @return the number of redundant sign bits
     */
    private static int norm(long a) {
        if (a < 0) {
            if (a <= -1073741824) {
                return 0;
            }
            a = ~a;
        }
        return Integer.numberOfLeadingZeros((int) a) - 1;
    }

    /**
     * Divides two words, {@code 0 <= num <= denum}, to a Q15 quotient ({@code gsm_div}).
     *
     * @param num the numerator
     * @param denum the denominator
     * @return the quotient
     */
    private static short div(int num, int denum) {
        if (num == 0) {
            return 0;
        }
        long lNum = num;
        var quotient = 0;
        for (var bit = 15; bit-- > 0; ) {
            quotient <<= 1;
            lNum <<= 1;
            if (lNum >= denum) {
                lNum -= denum;
                quotient++;
            }
        }
        return (short) quotient;
    }

    /**
     * {@return a word shifted right arithmetically, or left for a negative count ({@code gsm_asr})}
     *
     * @param a the word
     * @param n the count
     */
    private static short asr(int a, int n) {
        if (n >= 16) {
            return (short) (a < 0 ? -1 : 0);
        }
        if (n <= -16) {
            return 0;
        }
        return n < 0 ? (short) (a << -n) : (short) (a >> n);
    }

    /**
     * {@return a word shifted left, or right arithmetically for a negative count ({@code gsm_asl})}
     *
     * @param a the word
     * @param n the count
     */
    private static short asl(int a, int n) {
        if (n >= 16) {
            return 0;
        }
        if (n <= -16) {
            return (short) (a < 0 ? -1 : 0);
        }
        return n < 0 ? asr(a, -n) : (short) (a << n);
    }

    /**
     * Decodes the parameters of a frame into 160 samples ({@code Gsm_Decoder}), with the decoder homing of
     * EN 300 961 clause 4.4: a decoder homing frame returns the decoder to its home state, and one decoded in the home
     * state, where its log-area ratios and first sub-frame suffice to recognize it, outputs the encoder homing
     * frame.
     *
     * @param params the 76 parameters
     * @param samples receives the samples
     * @param offset the index of the first sample
     */
    void decode(int[] params, int[] samples, int offset) {
        // in the home state the log-area ratios and the first sub-frame of the homing frame suffice (clause 4.4,
        // note 2, and the HOMING01 sequence of clause 6.3.3.2)
        var checked = home ? 8 + 17 : PARAMETERS;
        var homing = Arrays.equals(params, 0, checked, DECODER_HOMING_FRAME, 0, checked);
        var wasHome = home;
        decodeFrame(params, samples, offset);
        if (homing) {
            if (wasHome) {
                Arrays.fill(samples, offset, offset + SAMPLES, HOMING_SAMPLE);
            }
            reset();
        } else {
            home = false;
        }
    }

    /**
     * Decodes the parameters of a frame into 160 samples.
     *
     * @param params the 76 parameters
     * @param samples receives the samples
     * @param offset the index of the first sample
     */
    private void decodeFrame(int[] params, int[] samples, int offset) {
        for (var sub = 0; sub < 4; sub++) {
            var base = 8 + 17 * sub;
            rpeDecoding(params[base + 3], params[base + 2], params, base + 4, erp);
            longTermSynthesis(params[base], params[base + 1]);
            System.arraycopy(dp0, 120, wt, sub * 40, 40);
        }
        shortTermSynthesis(params, wt, so);
        var m = msr;
        for (var i = 0; i < SAMPLES; i++) {
            var tmp = multR(m, 28180);
            m = add(so[i], tmp);
            samples[offset + i] = (short) (add(m, m) & 0xFFF8);
        }
        msr = m;
    }

    /**
     * Codes 160 samples into the parameters of a frame ({@code Gsm_Coder}), with the encoder homing of EN 300 961
     * clause 4.3: an encoder homing frame returns the encoder to its home state after it is coded.
     *
     * @param samples the 16-bit samples
     * @param offset the index of the first sample
     * @param params receives the 76 parameters
     */
    void encode(int[] samples, int offset, int[] params) {
        var homing = true;
        for (var i = 0; i < SAMPLES && homing; i++) {
            homing = samples[offset + i] == HOMING_SAMPLE;
        }
        encodeFrame(samples, offset, params);
        if (homing) {
            reset();
        }
    }

    /**
     * Codes 160 samples into the parameters of a frame.
     *
     * @param samples the 16-bit samples
     * @param offset the index of the first sample
     * @param params receives the 76 parameters
     */
    private void encodeFrame(int[] samples, int offset, int[] params) {
        preprocess(samples, offset);
        lpcAnalysis(params);
        shortTermAnalysis(params);
        for (var sub = 0; sub < 4; sub++) {
            var base = 8 + 17 * sub;
            var dp = 120 + 40 * sub;
            longTermPredictor(sub * 40, dp, params, base);
            rpeEncoding(params, base);
            for (var i = 0; i < 40; i++) {
                dp0[dp + i] = add(e[5 + i], dp0[dp + i]);
            }
        }
        System.arraycopy(dp0, 160, dp0, 0, 120);
    }

    /**
     * Downscales, offset-compensates and pre-emphasizes a frame into {@link #so} (4.2.1 to 4.2.3).
     *
     * @param samples the 16-bit samples
     * @param offset the index of the first sample
     */
    private void preprocess(int[] samples, int offset) {
        var z = z1;
        var lz2 = lZ2;
        var m = mp;
        for (var i = 0; i < SAMPLES; i++) {
            var input = (short) ((((short) samples[offset + i]) >> 3) << 2);
            var s1 = (short) (input - z);
            z = input;
            var ls2 = (long) s1 << 15;
            var msp = (short) (lz2 >> 15);
            var lsp = (short) (lz2 - ((long) msp << 15));
            ls2 += multR(lsp, 32735);
            var lTemp = (long) msp * 32735;
            lz2 = lAdd(lTemp, ls2);
            lTemp = lAdd(lz2, 16384);
            var mspe = multR(m, -28180);
            m = (short) (lTemp >> 15);
            so[i] = add(m, mspe);
        }
        z1 = z;
        lZ2 = lz2;
        mp = m;
    }

    /**
     * Computes the coded log-area ratios of {@link #so} into the parameters (4.2.4 to 4.2.7).
     *
     * @param params receives {@code LARc[0..7]}
     */
    private void lpcAnalysis(int[] params) {
        autocorrelation();
        reflectionCoefficients();
        for (var i = 0; i < 8; i++) {
            var temp = abs(lar[i]);
            if (temp < 22118) {
                temp >>= 1;
            } else if (temp < 31130) {
                temp -= 11059;
            } else {
                temp = (short) ((temp - 26112) << 2);
            }
            lar[i] = (short) (lar[i] < 0 ? -temp : temp);
        }
        for (var i = 0; i < 8; i++) {
            var temp = mult(LAR_A[i], lar[i]);
            temp = add(temp, LAR_B[i]);
            temp = add(temp, 256);
            temp = (short) (temp >> 9);
            params[i] = temp > LAR_MAC[i] ? LAR_MAC[i] - LAR_MIC[i] : temp < LAR_MIC[i] ? 0 : temp - LAR_MIC[i];
        }
    }

    /**
     * Computes the autocorrelation of {@link #so} into {@link #acf} (4.2.4).
     */
    private void autocorrelation() {
        var smax = 0;
        for (var i = 0; i < SAMPLES; i++) {
            var temp = abs(so[i]);
            if (temp > smax) {
                smax = temp;
            }
        }
        var scale = smax == 0 ? 0 : 4 - norm((long) smax << 16);
        if (scale > 0) {
            var factor = 16384 >> (scale - 1);
            for (var i = 0; i < SAMPLES; i++) {
                so[i] = multR(so[i], factor);
            }
        }
        for (var lag = 0; lag < 9; lag++) {
            var sum = 0L;
            for (var i = lag; i < SAMPLES; i++) {
                sum += (long) so[i] * so[i - lag];
            }
            acf[lag] = sum << 1;
        }
        if (scale > 0) {
            for (var i = 0; i < SAMPLES; i++) {
                so[i] = (short) (so[i] << scale);
            }
        }
    }

    /**
     * Computes the reflection coefficients of {@link #acf} into {@link #lar} by the Schur recursion (4.2.5).
     */
    private void reflectionCoefficients() {
        if (acf[0] == 0) {
            Arrays.fill(lar, (short) 0);
            return;
        }
        var shift = norm(acf[0]);
        for (var i = 0; i < 9; i++) {
            acf16[i] = (short) ((acf[i] << shift) >> 16);
        }
        for (var i = 1; i <= 7; i++) {
            k[i] = acf16[i];
        }
        System.arraycopy(acf16, 0, p, 0, 9);
        for (var n = 1; n <= 8; n++) {
            var temp = abs(p[1]);
            if (p[0] < temp) {
                for (var i = n; i <= 8; i++) {
                    lar[i - 1] = 0;
                }
                return;
            }
            var r = div(temp, p[0]);
            if (p[1] > 0) {
                r = (short) -r;
            }
            lar[n - 1] = r;
            if (n == 8) {
                return;
            }
            p[0] = add(p[0], multR(p[1], r));
            for (var m = 1; m <= 8 - n; m++) {
                var t = multR(k[m], r);
                p[m] = add(p[m + 1], t);
                t = multR(p[m + 1], r);
                k[m] = add(k[m], t);
            }
        }
    }

    /**
     * Decodes the coded log-area ratios into {@code larpp[j]} (4.2.8).
     *
     * @param params the parameters, {@code LARc[0..7]} first
     * @param target receives the decoded ratios
     */
    private static void decodeLars(int[] params, short[] target) {
        for (var i = 0; i < 8; i++) {
            var temp = (short) (add(params[i], LAR_MIC[i]) << 10);
            temp = sub(temp, LAR_B2[i]);
            temp = multR(LAR_INVA[i], temp);
            target[i] = add(temp, temp);
        }
    }

    /**
     * Interpolates the decoded ratios of the previous and current frame into {@link #larp} and converts them to
     * reflection coefficients (4.2.9).
     *
     * @param previous the previous frame's ratios
     * @param current the current frame's ratios
     * @param segment 0 to 3 for samples 0 to 12, 13 to 26, 27 to 39 and 40 to 159
     */
    private void interpolate(short[] previous, short[] current, int segment) {
        for (var i = 0; i < 8; i++) {
            larp[i] = switch (segment) {
                case 0 -> add(add(previous[i] >> 2, current[i] >> 2), previous[i] >> 1);
                case 1 -> add(previous[i] >> 1, current[i] >> 1);
                case 2 -> add(add(previous[i] >> 2, current[i] >> 2), current[i] >> 1);
                default -> current[i];
            };
        }
        for (var i = 0; i < 8; i++) {
            var value = larp[i];
            if (value < 0) {
                var temp = value == MIN_WORD ? MAX_WORD : -value;
                larp[i] = (short) -(temp < 11059 ? temp << 1 : temp < 20070 ? temp + 11059 : add(temp >> 2, 26112));
            } else {
                larp[i] = (short) (value < 11059 ? value << 1 : value < 20070 ? value + 11059
                        : add(value >> 2, 26112));
            }
        }
    }

    /**
     * Filters {@link #so} in place into the short-term residual (4.2.10).
     *
     * @param params the parameters, {@code LARc[0..7]} first
     */
    private void shortTermAnalysis(int[] params) {
        var current = larpp[j];
        j ^= 1;
        var previous = larpp[j];
        decodeLars(params, current);
        for (var segment = 0; segment < 4; segment++) {
            interpolate(previous, current, segment);
            var start = segment == 0 ? 0 : segment == 1 ? 13 : segment == 2 ? 27 : 40;
            var end = segment == 0 ? 13 : segment == 1 ? 27 : segment == 2 ? 40 : 160;
            for (var n = start; n < end; n++) {
                short di = so[n];
                var sav = di;
                for (var i = 0; i < 8; i++) {
                    var ui = u[i];
                    var rpi = larp[i];
                    u[i] = sav;
                    sav = add(ui, multR(rpi, di));
                    di = add(di, multR(rpi, ui));
                }
                so[n] = di;
            }
        }
    }

    /**
     * Filters the reconstructed residual into the synthesized signal (4.3.4).
     *
     * @param params the parameters, {@code LARc[0..7]} first
     * @param input the residual of the frame
     * @param output receives the signal
     */
    private void shortTermSynthesis(int[] params, short[] input, short[] output) {
        var current = larpp[j];
        j ^= 1;
        var previous = larpp[j];
        decodeLars(params, current);
        for (var segment = 0; segment < 4; segment++) {
            interpolate(previous, current, segment);
            var start = segment == 0 ? 0 : segment == 1 ? 13 : segment == 2 ? 27 : 40;
            var end = segment == 0 ? 13 : segment == 1 ? 27 : segment == 2 ? 40 : 160;
            for (var n = start; n < end; n++) {
                short sri = input[n];
                for (var i = 7; i >= 0; i--) {
                    int rrp = larp[i];
                    int vi = v[i];
                    var product = rrp == MIN_WORD && vi == MIN_WORD ? MAX_WORD : multR(rrp, vi);
                    sri = sub(sri, product);
                    product = rrp == MIN_WORD && sri == MIN_WORD ? MAX_WORD : multR(rrp, sri);
                    v[i + 1] = add(v[i], product);
                }
                output[n] = sri;
                v[0] = sri;
            }
        }
    }

    /**
     * Computes the LTP parameters of a sub-frame and its long-term residual into {@link #e} (4.2.11, 4.2.12).
     *
     * @param d the index of the sub-frame's short-term residual in {@link #so}
     * @param dp the index of the sub-frame in {@link #dp0}; the residual estimate is written there
     * @param params receives {@code Nc} and {@code bc}
     * @param base the index of the sub-frame's parameters
     */
    private void longTermPredictor(int d, int dp, int[] params, int base) {
        var dmax = 0;
        for (var i = 0; i < 40; i++) {
            var temp = abs(so[d + i]);
            if (temp > dmax) {
                dmax = temp;
            }
        }
        var temp = dmax == 0 ? 0 : norm((long) dmax << 16);
        var scal = temp > 6 ? 0 : 6 - temp;
        for (var i = 0; i < 40; i++) {
            work40[i] = (short) (so[d + i] >> scal);
        }
        var lMax = 0L;
        var nc = 40;
        for (var lambda = 40; lambda <= 120; lambda++) {
            var sum = 0L;
            for (var i = 0; i < 40; i++) {
                sum += (long) work40[i] * dp0[dp + i - lambda];
            }
            if (sum > lMax) {
                nc = lambda;
                lMax = sum;
            }
        }
        params[base] = nc;
        lMax <<= 1;
        lMax >>= 6 - scal;
        var lPower = 0L;
        for (var i = 0; i < 40; i++) {
            long value = dp0[dp + i - nc] >> 3;
            lPower += value * value;
        }
        lPower <<= 1;
        int bc;
        if (lMax <= 0) {
            bc = 0;
        } else if (lMax >= lPower) {
            bc = 3;
        } else {
            var shift = norm(lPower);
            var r = (short) ((lMax << shift) >> 16);
            var s = (short) ((lPower << shift) >> 16);
            bc = 0;
            while (bc <= 2 && r > multGuarded(s, DLB[bc])) {
                bc++;
            }
        }
        params[base + 1] = bc;
        var bp = QLB[bc];
        for (var i = 0; i < 40; i++) {
            var dpp = multR(bp, dp0[dp + i - nc]);
            dp0[dp + i] = dpp;
            e[5 + i] = sub(so[d + i], dpp);
        }
    }

    /**
     * Codes the long-term residual in {@link #e} into the RPE parameters and replaces it with its reconstruction
     * (4.2.13 to 4.2.17).
     *
     * @param params receives {@code Mc}, {@code xmaxc} and {@code xMc}
     * @param base the index of the sub-frame's parameters
     */
    private void rpeEncoding(int[] params, int base) {
        var x = work40;
        for (var n = 0; n < 40; n++) {
            long result = 4096;
            result += e[n] * -134L + e[n + 1] * -374L + e[n + 3] * 2054L + e[n + 4] * 5741L + e[n + 5] * 8192L
                    + e[n + 6] * 5741L + e[n + 7] * 2054L + e[n + 9] * -374L + e[n + 10] * -134L;
            result >>= 13;
            x[n] = (short) (result < MIN_WORD ? MIN_WORD : result > MAX_WORD ? MAX_WORD : result);
        }
        var mc = 0;
        var em = 0L;
        for (var m = 0; m < 4; m++) {
            var sum = 0L;
            for (var i = 0; i < 13; i++) {
                long value = x[m + 3 * i] >> 2;
                sum += value * value;
            }
            sum <<= 1;
            if (m == 0 || sum > em) {
                mc = m;
                em = sum;
            }
        }
        for (var i = 0; i < 13; i++) {
            xm[i] = x[mc + 3 * i];
        }
        params[base + 2] = mc;

        var xmax = 0;
        for (var i = 0; i < 13; i++) {
            var temp = abs(xm[i]);
            if (temp > xmax) {
                xmax = temp;
            }
        }
        var exp = 0;
        var temp = xmax >> 9;
        var itest = false;
        for (var i = 0; i <= 5; i++) {
            itest |= temp <= 0;
            temp >>= 1;
            if (!itest) {
                exp++;
            }
        }
        var xmaxc = add(xmax >> (exp + 5), exp << 3);
        params[base + 3] = xmaxc;
        expMant(xmaxc);
        exp = out3[0];
        var mant = out3[1];
        var shift = 6 - exp;
        var inverse = NRFAC[mant];
        for (var i = 0; i < 13; i++) {
            var value = (short) (xm[i] << shift);
            value = mult(value, inverse);
            params[base + 4 + i] = (value >> 12) + 4;
        }
        inverseQuantization(params, base + 4, mant, exp, xmp);
        positionGrid(mc, xmp, e, 5);
    }

    /**
     * Splits a coded block amplitude into its exponent and mantissa, left in {@code out3[0]} and {@code out3[1]}
     * (4.2.15).
     *
     * @param xmaxc the coded amplitude, from 0 to 63
     */
    private void expMant(int xmaxc) {
        var exp = 0;
        if (xmaxc > 15) {
            exp = (xmaxc >> 3) - 1;
        }
        var mant = xmaxc - (exp << 3);
        if (mant == 0) {
            exp = -4;
            mant = 7;
        } else {
            while (mant <= 7) {
                mant = mant << 1 | 1;
                exp--;
            }
            mant -= 8;
        }
        out3[0] = exp;
        out3[1] = mant;
    }

    /**
     * Decodes the 13 RPE pulses of a sub-frame (4.2.16).
     *
     * @param params the parameters
     * @param offset the index of the first pulse
     * @param mant the amplitude's mantissa
     * @param exp the amplitude's exponent
     * @param target receives the 13 decoded pulses
     */
    private static void inverseQuantization(int[] params, int offset, int mant, int exp, short[] target) {
        var temp1 = FAC[mant];
        var temp2 = sub(6, exp);
        var temp3 = asl(1, sub(temp2, 1));
        for (var i = 0; i < 13; i++) {
            var temp = (short) (((params[offset + i] << 1) - 7) << 12);
            temp = multR(temp1, temp);
            temp = add(temp, temp3);
            target[i] = asr(temp, temp2);
        }
    }

    /**
     * Upsamples the 13 decoded pulses of a sub-frame by 3 on the grid (4.2.17).
     *
     * @param mc the grid position, from 0 to 3
     * @param pulses the 13 decoded pulses
     * @param target receives the 40 samples
     * @param offset the index of the first sample
     */
    private static void positionGrid(int mc, short[] pulses, short[] target, int offset) {
        Arrays.fill(target, offset, offset + 40, (short) 0);
        for (var i = 0; i < 13; i++) {
            target[offset + mc + 3 * i] = pulses[i];
        }
    }

    /**
     * Decodes the RPE parameters of a sub-frame into its long-term residual (4.3.1).
     *
     * @param xmaxc the coded block amplitude
     * @param mc the grid position
     * @param params the parameters
     * @param offset the index of the first pulse
     * @param target receives the 40 samples
     */
    private void rpeDecoding(int xmaxc, int mc, int[] params, int offset, short[] target) {
        expMant(xmaxc);
        inverseQuantization(params, offset, out3[1], out3[0], xmp);
        positionGrid(mc, xmp, target, 0);
    }

    /**
     * Filters the long-term residual {@link #erp} into the reconstructed short-term residual at {@code dp0[120]}
     * and shifts the residual history (4.3.2).
     *
     * @param ncr the received LTP lag
     * @param bcr the received LTP gain, from 0 to 3
     */
    private void longTermSynthesis(int ncr, int bcr) {
        var nr = ncr < 40 || ncr > 120 ? nrp : (short) ncr;
        nrp = nr;
        var brp = QLB[bcr];
        for (var i = 0; i < 40; i++) {
            var drpp = multR(brp, dp0[120 + i - nr]);
            dp0[120 + i] = add(erp[i], drpp);
        }
        System.arraycopy(dp0, 40, dp0, 0, 120);
    }
}
