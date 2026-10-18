/* Ports opencore-amr 0.1.6 (bgnscd.cpp, ex_ctrl.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.divS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAdd;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_FRAME;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_SUBFR;

/**
 * Detects stationary background noise in the decoded AMR-NB speech ({@code Bgn_scdState}) and
 * limits the excitation energy of noisy frames after losses.
 *
 * <p>The detector tracks the energy of the last 60 frames; the decoder uses its verdict, and the
 * hangover of voiced frames it counts, to smooth the concealment of the low rate modes in noise.
 */
final class BackgroundNoise {
    /**
     * The number of frame energies kept.
     */
    private static final int L_ENERGYHIST = 60;

    /**
     * The frame energy above which a frame is not background noise.
     */
    private static final short FRAMEENERGYLIMIT = 17578;

    /**
     * The frame energy below which a frame is silence rather than noise.
     */
    private static final short LOWERNOISELIMIT = 20;

    /**
     * The energy limit of the recent frames below which they count as noise.
     */
    private static final short UPPERNOISELIMIT = 1953;

    /**
     * The energies of the last frames, oldest first.
     */
    private final short[] frameEnergyHist = new short[L_ENERGYHIST];

    /**
     * The number of consecutive noise-like frames, at most 30.
     */
    private short bgHangover;

    /**
     * Creates the detector, reset.
     */
    BackgroundNoise() {
        reset();
    }

    /**
     * Resets the detector ({@code Bgn_scd_reset}).
     */
    void reset() {
        Arrays.fill(frameEnergyHist, (short) 0);
        bgHangover = 0;
    }

    /**
     * Classifies a decoded frame and updates the voiced hangover ({@code Bgn_scd}).
     *
     * @param ltpGainHist the pitch gains of the last 9 subframes
     * @param speech the decoded frame, 160 samples
     * @param speechOffset the index of the first sample
     * @param voicedHangover the frames since the last voiced frame
     * @param overflow the flag raised on saturation
     * @return whether the frame is background noise (upper 16 bits, 0 or 1) and the updated
     * voiced hangover (lower 16 bits)
     */
    int detect(short[] ltpGainHist, short[] speech, int speechOffset, short voicedHangover, Flag overflow) {
        var s = 0;
        for (var i = L_FRAME - 1; i >= 0; i--) {
            var temp = speech[speechOffset + i] * speech[speechOffset + i];
            temp = temp != 0x40000000 ? temp << 1 : MAX_32;
            s = lAdd(s, temp, overflow);
        }
        var currEnergy = s > 0x1fffffff ? MAX_16 : (short) (s >> 14);
        short frameEnergyMin = 32767;
        for (var i = L_ENERGYHIST - 1; i >= 0; i--) {
            if (frameEnergyHist[i] < frameEnergyMin) {
                frameEnergyMin = frameEnergyHist[i];
            }
        }
        var shifted = frameEnergyMin << 4;
        short noiseFloor;
        if (shifted != (short) shifted) {
            noiseFloor = shifted > 0 ? MAX_16 : BasicOps.MIN_16;
        } else {
            noiseFloor = (short) shifted;
        }
        var maxEnergy = frameEnergyHist[0];
        for (var i = L_ENERGYHIST - 5; i >= 1; i--) {
            if (maxEnergy < frameEnergyHist[i]) {
                maxEnergy = frameEnergyHist[i];
            }
        }
        var maxEnergyLastPart = frameEnergyHist[2 * L_ENERGYHIST / 3];
        for (var i = 2 * L_ENERGYHIST / 3 + 1; i < L_ENERGYHIST; i++) {
            if (maxEnergyLastPart < frameEnergyHist[i]) {
                maxEnergyLastPart = frameEnergyHist[i];
            }
        }
        if (maxEnergy > LOWERNOISELIMIT && currEnergy < FRAMEENERGYLIMIT && currEnergy > LOWERNOISELIMIT
                && (currEnergy < noiseFloor || maxEnergyLastPart < UPPERNOISELIMIT)) {
            bgHangover = (short) (bgHangover + 1 > 30 ? 30 : bgHangover + 1);
        } else {
            bgHangover = 0;
        }
        var inbgNoise = bgHangover > 1 ? 1 : 0;
        System.arraycopy(frameEnergyHist, 1, frameEnergyHist, 0, L_ENERGYHIST - 1);
        frameEnergyHist[L_ENERGYHIST - 1] = currEnergy;
        short ltpLimit;
        if (bgHangover > 15) {
            ltpLimit = 16383;
        } else if (bgHangover > 8) {
            ltpLimit = 15565;
        } else {
            ltpLimit = 13926;
        }
        var prevVoiced = FixedMath.gmedN(ltpGainHist, 4, 5) > ltpLimit;
        if (bgHangover > 20) {
            prevVoiced = FixedMath.gmedN(ltpGainHist, 0, 9) > ltpLimit;
        }
        if (prevVoiced) {
            voicedHangover = 0;
        } else {
            var temp = (short) (voicedHangover + 1);
            voicedHangover = temp > 10 ? 10 : temp;
        }
        return (inbgNoise << 16) | (voicedHangover & 0xffff);
    }

    /**
     * Limits the energy of the excitation of a noisy subframe after a loss to the median of the
     * recent energies ({@code Ex_ctrl}).
     *
     * @param excitation the 40 excitation samples, scaled in place
     * @param excEnergy the energy of the excitation
     * @param exEnergyHist the energies of the last 9 subframes
     * @param voicedHangover the frames since the last voiced frame
     * @param prevBfi whether the previous frame was lost
     * @param carefulFlag nonzero to limit the scaling to 1.5
     * @param overflow the flag raised on saturation
     */
    static void controlExcitation(short[] excitation, short excEnergy, short[] exEnergyHist, short voicedHangover,
                                  short prevBfi, short carefulFlag, Flag overflow) {
        var avgEnergy = FixedMath.gmedN(exEnergyHist, 0, 9);
        var prevEnergy = (short) ((exEnergyHist[7] + exEnergyHist[8]) >> 1);
        if (exEnergyHist[8] < prevEnergy) {
            prevEnergy = exEnergyHist[8];
        }
        if (excEnergy < avgEnergy && excEnergy > 5) {
            var testEnergy = shl(prevEnergy, (short) 2);
            if (voicedHangover < 7 || prevBfi != 0) {
                testEnergy = sub(testEnergy, prevEnergy, overflow);
            }
            if (avgEnergy > testEnergy) {
                avgEnergy = testEnergy;
            }
            var exp = normS(excEnergy);
            excEnergy = shl(excEnergy, exp);
            excEnergy = divS((short) 16383, excEnergy);
            var t0 = lMult(avgEnergy, excEnergy, overflow);
            t0 = lShr(t0, sub((short) 20, exp, overflow));
            if (t0 > 32767) {
                t0 = 32767;
            }
            var scaleFactor = (short) t0;
            if (carefulFlag != 0 && scaleFactor > 3072) {
                scaleFactor = 3072;
            }
            for (var i = 0; i < L_SUBFR; i++) {
                t0 = lMult(scaleFactor, excitation[i], overflow);
                t0 = lShr(t0, (short) 11);
                excitation[i] = (short) t0;
            }
        }
    }
}
