/* Ports opencore-amr 0.1.6 (pstfilt.cpp, preemph.cpp, post_pro.cpp). Copyright PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.divS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAdd;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.AGC_FAC;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_FRAME;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_SUBFR;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MP1;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.MU;

/**
 * Applies the adaptive postfilter of the AMR-NB decoder (formant postfilter, tilt compensation
 * and gain control) and the output high-pass filter ({@code Post_FilterState} and
 * {@code Post_ProcessState}).
 */
final class PostFilter {

    /**
     * The scratch of the integer filters.
     */
    private final int[] work = new int[96];
    /**
     * The length of the truncated impulse response of the postfilter.
     */
    private static final int L_H = 22;

    /**
     * The numerator weighting factors of {@code MR122} and {@code MR102}.
     */
    private static final short[] GAMMA3_MR122 = {22938, 16057, 11240, 7868, 5508, 3856, 2699, 1889, 1322, 925};

    /**
     * The numerator weighting factors of the other modes.
     */
    private static final short[] GAMMA3 = {18022, 9912, 5451, 2998, 1649, 907, 499, 274, 151, 83};

    /**
     * The denominator weighting factors of {@code MR122} and {@code MR102}.
     */
    private static final short[] GAMMA4_MR122 = {24576, 18432, 13824, 10368, 7776, 5832, 4374, 3281, 2461, 1846};

    /**
     * The denominator weighting factors of the other modes.
     */
    private static final short[] GAMMA4 = {22938, 16057, 11240, 7868, 5508, 3856, 2699, 1889, 1322, 925};

    /**
     * The numerator of the 60 Hz high-pass filter, Q13.
     */
    private static final short[] HP_B = {7699, -15398, 7699};

    /**
     * The denominator of the 60 Hz high-pass filter, Q13.
     */
    private static final short[] HP_A = {8192, 15836, -7667};

    /**
     * The memory of the postfilter's synthesis filter.
     */
    private final short[] memSynPst = new short[M];

    /**
     * The residual of a subframe.
     */
    private final short[] res2 = new short[L_SUBFR];

    /**
     * The unfiltered synthesis with {@code M} samples of history.
     */
    private final short[] synthBuf = new short[L_FRAME + M];

    /**
     * The numerator filter of a subframe.
     */
    private final short[] ap3 = new short[MP1];

    /**
     * The denominator filter of a subframe.
     */
    private final short[] ap4 = new short[MP1];

    /**
     * The truncated impulse response of the postfilter.
     */
    private final short[] h = new short[L_H];

    /**
     * The gain control of the postfilter output.
     */
    private final Agc agc = new Agc();

    /**
     * The memory of the tilt compensation.
     */
    private short memPre;

    /**
     * The high-pass filter's output two samples back, high part.
     */
    private short y2Hi;

    /**
     * The high-pass filter's output two samples back, low part.
     */
    private short y2Lo;

    /**
     * The high-pass filter's previous output, high part.
     */
    private short y1Hi;

    /**
     * The high-pass filter's previous output, low part.
     */
    private short y1Lo;

    /**
     * The high-pass filter's previous input.
     */
    private short x0;

    /**
     * The high-pass filter's input two samples back.
     */
    private short x1;

    /**
     * Creates the filters, reset.
     */
    PostFilter() {
        resetPostFilter();
        resetHighPass();
    }

    /**
     * Resets the postfilter ({@code Post_Filter_reset}).
     */
    void resetPostFilter() {
        java.util.Arrays.fill(memSynPst, (short) 0);
        java.util.Arrays.fill(res2, (short) 0);
        java.util.Arrays.fill(synthBuf, (short) 0);
        agc.reset();
        memPre = 0;
    }

    /**
     * Resets the high-pass filter ({@code Post_Process_reset}).
     */
    void resetHighPass() {
        y2Hi = 0;
        y2Lo = 0;
        y1Hi = 0;
        y1Lo = 0;
        x0 = 0;
        x1 = 0;
    }

    /**
     * Postfilters a decoded frame in place ({@code Post_Filter}).
     *
     * @param mode the codec mode, one of the {@link Mode} constants
     * @param syn the 160 synthesized samples, filtered in place
     * @param az the 4 interpolated LP filters of the frame, 44 coefficients
     * @param overflow the flag raised on saturation
     */
    void filter(int mode, short[] syn, short[] az, Flag overflow) {
        System.arraycopy(syn, 0, synthBuf, M, L_FRAME);
        var azOffset = 0;
        var wide = mode == Mode.MR122 || mode == Mode.MR102;
        for (var iSubfr = 0; iSubfr < L_FRAME; iSubfr += L_SUBFR) {
            Filters.weightAi(az, azOffset, wide ? GAMMA3_MR122 : GAMMA3, 0, ap3, 0);
            Filters.weightAi(az, azOffset, wide ? GAMMA4_MR122 : GAMMA4, 0, ap4, 0);
            Filters.residu(ap3, 0, synthBuf, M + iSubfr, res2, 0, L_SUBFR, work);
            System.arraycopy(ap3, 0, h, 0, M + 1);
            for (var i = M + 1; i < L_H; i++) {
                h[i] = 0;
            }
            Filters.synFilt(ap4, 0, h, 0, h, 0, L_H, h, M + 1, false);
            var sum = 0;
            for (var i = L_H - 1; i >= 0; i--) {
                var product = h[i] * h[i];
                if (product != 0x40000000) {
                    product <<= 1;
                } else {
                    overflow.value = true;
                    break;
                }
                sum = lAdd(sum, product, overflow);
            }
            var temp1 = (short) (sum >> 16);
            sum = 0;
            for (var i = L_H - 2; i >= 0; i--) {
                var product = h[i] * h[i + 1];
                if (product != 0x40000000) {
                    product <<= 1;
                } else {
                    overflow.value = true;
                    break;
                }
                sum = lAdd(sum, product, overflow);
            }
            var temp2 = (short) (sum >> 16);
            if (temp2 <= 0) {
                temp2 = 0;
            } else {
                var scaled = (temp2 * MU) >> 15;
                if ((scaled & 0x00010000) != 0) {
                    scaled = scaled | 0xffff0000;
                }
                temp2 = (short) scaled;
                temp2 = divS(temp2, temp1);
            }
            preemphasis(res2, temp2, L_SUBFR, overflow);
            Filters.synFilt(ap4, 0, res2, 0, syn, iSubfr, L_SUBFR, memSynPst, 0, true);
            agc.apply(synthBuf, M + iSubfr, syn, iSubfr, AGC_FAC, L_SUBFR, overflow);
            azOffset += MP1;
        }
        System.arraycopy(synthBuf, L_FRAME, synthBuf, 0, M);
    }

    /**
     * Applies the tilt compensation filter {@code 1 - g z^-1} in place ({@code preemphasis}).
     *
     * @param signal the samples, filtered in place
     * @param g the filter coefficient
     * @param length the number of samples
     * @param overflow the flag raised on saturation
     */
    private void preemphasis(short[] signal, short g, int length, Flag overflow) {
        var p1 = length - 1;
        var p2 = p1 - 1;
        var temp = signal[p1];
        for (var i = 0; i <= length - 2; i++) {
            var temp2 = mult(g, signal[p2--], overflow);
            signal[p1] = sub(signal[p1], temp2, overflow);
            p1--;
        }
        var temp2 = mult(g, memPre, overflow);
        signal[p1] = sub(signal[p1], temp2, overflow);
        memPre = temp;
    }

    /**
     * Applies the output high-pass filter, cut-off 60 Hz, and the factor 2 up-scaling in place
     * ({@code Post_Process}).
     *
     * @param signal the samples, filtered in place
     * @param length the number of samples
     * @param overflow the flag raised on saturation
     */
    void highPass(short[] signal, int length, Flag overflow) {
        for (var i = 0; i < length; i++) {
            var x2 = x1;
            x1 = x0;
            x0 = signal[i];
            var sum = y1Hi * HP_A[1];
            sum += (y1Lo * HP_A[1]) >> 15;
            sum += y2Hi * HP_A[2];
            sum += (y2Lo * HP_A[2]) >> 15;
            sum += x0 * HP_B[0];
            sum += x1 * HP_B[1];
            sum += x2 * HP_B[2];
            sum = lShl(sum, (short) 3);
            signal[i] = round(lShl(sum, (short) 1), overflow);
            y2Hi = y1Hi;
            y2Lo = y1Lo;
            y1Hi = (short) (sum >> 16);
            y1Lo = (short) ((sum >> 1) - (y1Hi << 15));
        }
    }
}
