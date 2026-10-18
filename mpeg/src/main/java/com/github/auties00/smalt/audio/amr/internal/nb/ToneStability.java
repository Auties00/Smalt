/* Ports opencore-amr 0.1.6 (ton_stab.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.shr;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.GP_CLIP;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.M;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.N_FRAME;

/**
 * Detects the resonances that make the AMR-NB encoder's long-term predictor unstable and limits
 * the pitch gain while they last ({@code ton_stab}).
 */
final class ToneStability {
    /**
     * The number of consecutive frames with closely spaced LSPs.
     */
    private short count;

    /**
     * The pitch gain history, Q11.
     */
    private final short[] gp = new short[N_FRAME];

    /**
     * Creates the detector in its reset state.
     */
    ToneStability() {
        reset();
    }

    /**
     * Resets the detector.
     */
    void reset() {
        count = 0;
        java.util.Arrays.fill(gp, (short) 0);
    }

    /**
     * Checks whether the LSPs of the previous frame have been closely spaced for 12 frames
     * ({@code check_lsp}).
     *
     * @param lsp the previous unquantized LSPs
     * @return whether the pitch gain should be checked for clipping
     */
    boolean checkLsp(short[] lsp) {
        var distMin1 = MAX_16;
        for (var i = 3; i < M - 2; i++) {
            var dist = (short) (lsp[i] - lsp[i + 1]);
            if (dist < distMin1) {
                distMin1 = dist;
            }
        }
        var distMin2 = MAX_16;
        for (var i = 1; i < 3; i++) {
            var dist = (short) (lsp[i] - lsp[i + 1]);
            if (dist < distMin2) {
                distMin2 = dist;
            }
        }
        short distTh;
        if (lsp[1] > 32000) {
            distTh = 600;
        } else if (lsp[1] > 30500) {
            distTh = 800;
        } else {
            distTh = 1100;
        }
        if (distMin1 < 1500 || distMin2 < distTh) {
            count++;
        } else {
            count = 0;
        }
        if (count >= 12) {
            count = 12;
            return true;
        }
        return false;
    }

    /**
     * Checks whether the pitch gain, summed with the recent history, exceeds the clipping
     * threshold ({@code check_gp_clipping}).
     *
     * @param gPitch the pitch gain, Q14
     * @param overflow the saturation flag
     * @return whether the gain should be clipped
     */
    boolean checkGpClipping(short gPitch, Flag overflow) {
        var sum = shr(gPitch, (short) 3, overflow);
        for (var i = 0; i < N_FRAME; i++) {
            sum = add(sum, gp[i], overflow);
        }
        return sum > GP_CLIP;
    }

    /**
     * Shifts a quantized pitch gain into the history ({@code update_gp_clipping}).
     *
     * @param gPitch the pitch gain, Q14
     */
    void updateGpClipping(short gPitch) {
        System.arraycopy(gp, 1, gp, 0, N_FRAME - 1);
        gp[N_FRAME - 1] = (short) (gPitch >> 3);
    }
}
