/* Ports opencore-amr 0.1.6 (c_g_aver.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.abs;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.divS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMsu;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.negate;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;

/**
 * Smooths the codebook gain of the AMR-NB decoder in stationary background noise
 * ({@code Cb_gain_averageState}).
 *
 * <p>When the spectrum is stationary (small distance between the current and the averaged
 * LSPs), the codebook gain of {@code MR475} to {@code MR67} and {@code MR102} is mixed with the
 * average of the last gains, which removes the swirling of coded noise.
 */
final class CodebookGainAverage {
    /**
     * The number of past codebook gains kept.
     */
    private static final int L_CBGAINHIST = 7;

    /**
     * The last codebook gains, oldest first.
     */
    private final short[] cbGainHistory = new short[L_CBGAINHIST];

    /**
     * The relative LSP distances of each LSP, scratch.
     */
    private final short[] tmp = new short[M];

    /**
     * The number of consecutive frames with a large spectral distance.
     */
    short hangVar;

    /**
     * The number of subframes since the spectral distance was last large.
     */
    short hangCount;

    /**
     * Creates the state, reset.
     */
    CodebookGainAverage() {
        reset();
    }

    /**
     * Resets the state ({@code Cb_gain_average_reset}).
     */
    void reset() {
        Arrays.fill(cbGainHistory, (short) 0);
        hangVar = 0;
        hangCount = 0;
    }

    /**
     * Returns the codebook gain mixed with its recent average ({@code Cb_gain_average}).
     *
     * @param mode the codec mode, one of the {@link Mode} constants
     * @param gainCode the decoded codebook gain
     * @param lsp the interpolated LSFs of the subframe
     * @param lspAver the averaged LSFs
     * @param bfi nonzero for a lost frame
     * @param prevBf nonzero when the previous frame was lost
     * @param pdfi nonzero for a potentially degraded frame
     * @param prevPdf nonzero when the previous frame was potentially degraded
     * @param inBackgroundNoise nonzero in background noise
     * @param voicedHangover the frames since the last voiced frame
     * @param overflow the flag raised on saturation
     * @return the mixed codebook gain
     */
    short average(int mode, short gainCode, short[] lsp, short[] lspAver, short bfi, short prevBf, short pdfi,
                  short prevPdf, short inBackgroundNoise, short voicedHangover, Flag overflow) {
        var cbGainMix = gainCode;
        System.arraycopy(cbGainHistory, 1, cbGainHistory, 0, L_CBGAINHIST - 1);
        cbGainHistory[L_CBGAINHIST - 1] = gainCode;
        short diff = 0;
        for (var i = 0; i < M; i++) {
            var tmp1 = abs(sub(lspAver[i], lsp[i], overflow));
            var shift1 = (short) (normS(tmp1) - 1);
            tmp1 = shl(tmp1, shift1);
            var shift2 = normS(lspAver[i]);
            var tmp2 = shl(lspAver[i], shift2);
            tmp[i] = divS(tmp1, tmp2);
            var shift = (short) (2 + shift1 - shift2);
            if (shift >= 0) {
                tmp[i] = shr(tmp[i], shift, overflow);
            } else {
                tmp[i] = shl(tmp[i], negate(shift));
            }
            diff = add(diff, tmp[i], overflow);
        }
        if (diff > 5325) {
            hangVar += 1;
        } else {
            hangVar = 0;
        }
        if (hangVar > 10) {
            hangCount = 0;
        }
        if (mode <= Mode.MR67 || mode == Mode.MR102) {
            short tmpDiff;
            if (((pdfi != 0 && prevPdf != 0) || bfi != 0 || prevBf != 0) && voicedHangover > 1
                    && inBackgroundNoise != 0 && (mode == Mode.MR475 || mode == Mode.MR515 || mode == Mode.MR59)) {
                tmpDiff = (short) (diff - 4506);
            } else {
                tmpDiff = (short) (diff - 3277);
            }
            var tmp1 = tmpDiff > 0 ? tmpDiff : (short) 0;
            short bgMix;
            if (2048 < tmp1) {
                bgMix = 8192;
            } else {
                bgMix = shl(tmp1, (short) 2);
            }
            if (hangCount < 40 || diff > 5325) {
                bgMix = 8192;
            }
            var sum = lMult((short) 6554, cbGainHistory[2], overflow);
            for (var i = 3; i < L_CBGAINHIST; i++) {
                sum = lMac(sum, (short) 6554, cbGainHistory[i], overflow);
            }
            var cbGainMean = round(sum, overflow);
            if ((bfi != 0 || prevBf != 0) && inBackgroundNoise != 0
                    && (mode == Mode.MR475 || mode == Mode.MR515 || mode == Mode.MR59)) {
                sum = lMult((short) 4681, cbGainHistory[0], overflow);
                for (var i = 1; i < L_CBGAINHIST; i++) {
                    sum = lMac(sum, (short) 4681, cbGainHistory[i], overflow);
                }
                cbGainMean = round(sum, overflow);
            }
            sum = lMult(bgMix, cbGainMix, overflow);
            sum = lMac(sum, (short) 8192, cbGainMean, overflow);
            sum = lMsu(sum, bgMix, cbGainMean, overflow);
            cbGainMix = round(lShl(sum, (short) 2), overflow);
        }
        hangCount += 1;
        return cbGainMix;
    }
}
