/* Ports opencore-amr 0.1.6 (ec_gains.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.mult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.sub;

/**
 * Conceals the pitch and codebook gains of lost AMR-NB frames from the gains of the frames that
 * preceded them, and limits the first good gains after a loss.
 *
 * <p>The state holds the last five gains of each kind; the attenuation applied grows with the
 * decoder's error state (0 to 6).
 */
final class GainConcealment {
    /**
     * The pitch gain attenuation of each error state ({@code pdown}).
     */
    private static final short[] PDOWN = {32767, 32112, 32112, 26214, 9830, 6553, 6553};

    /**
     * The codebook gain attenuation of each error state ({@code cdown}).
     */
    private static final short[] CDOWN = {32767, 32112, 32112, 32112, 32112, 32112, 22937};

    /**
     * The last five pitch gains.
     */
    final short[] pbuf = new short[5];

    /**
     * The pitch gain of the previous subframe.
     */
    short pastGainPit;

    /**
     * The pitch gain of the last good subframe.
     */
    short prevGp;

    /**
     * The last five codebook gains.
     */
    final short[] gbuf = new short[5];

    /**
     * The codebook gain of the previous subframe.
     */
    short pastGainCode;

    /**
     * The codebook gain of the last good subframe.
     */
    short prevGc;

    /**
     * Creates the state, reset.
     */
    GainConcealment() {
        resetPitch();
        resetCode();
    }

    /**
     * Resets the pitch gain state ({@code ec_gain_pitch_reset}).
     */
    void resetPitch() {
        for (var i = 0; i < 5; i++) {
            pbuf[i] = 1640;
        }
        pastGainPit = 0;
        prevGp = 16384;
    }

    /**
     * Resets the codebook gain state ({@code ec_gain_code_reset}).
     */
    void resetCode() {
        for (var i = 0; i < 5; i++) {
            gbuf[i] = 1;
        }
        pastGainCode = 0;
        prevGc = 1;
    }

    /**
     * Returns the concealed pitch gain of a lost subframe ({@code ec_gain_pitch}).
     *
     * @param state the error state, 0 to 6
     * @param overflow the flag raised on saturation
     * @return the pitch gain
     */
    short pitch(short state, Flag overflow) {
        var tmp = FixedMath.gmedN(pbuf, 0, 5);
        if (sub(tmp, pastGainPit, overflow) > 0) {
            tmp = pastGainPit;
        }
        return mult(tmp, PDOWN[state], overflow);
    }

    /**
     * Records the pitch gain of a subframe, limiting the first good gain after a loss
     * ({@code ec_gain_pitch_update}).
     *
     * @param bfi whether the subframe is lost
     * @param prevBf whether the previous frame was lost
     * @param gainPitch the pitch gain
     * @param overflow the flag raised on saturation
     * @return the pitch gain, possibly limited
     */
    short pitchUpdate(short bfi, short prevBf, short gainPitch, Flag overflow) {
        if (bfi == 0) {
            if (prevBf != 0 && sub(gainPitch, prevGp, overflow) > 0) {
                gainPitch = prevGp;
            }
            prevGp = gainPitch;
        }
        pastGainPit = gainPitch;
        if (sub(pastGainPit, (short) 16384, overflow) > 0) {
            pastGainPit = 16384;
        }
        System.arraycopy(pbuf, 1, pbuf, 0, 4);
        pbuf[4] = pastGainPit;
        return gainPitch;
    }

    /**
     * Returns the concealed codebook gain of a lost subframe and updates the gain predictor with
     * its averaged energies ({@code ec_gain_code}).
     *
     * @param pred the gain predictor
     * @param state the error state, 0 to 6
     * @param overflow the flag raised on saturation
     * @return the codebook gain
     */
    short code(GcPred pred, short state, Flag overflow) {
        var tmp = FixedMath.gmedN(gbuf, 0, 5);
        if (sub(tmp, pastGainCode, overflow) > 0) {
            tmp = pastGainCode;
        }
        tmp = mult(tmp, CDOWN[state], overflow);
        var averages = pred.averageLimited(overflow);
        pred.update((short) (averages >> 16), (short) averages);
        return tmp;
    }

    /**
     * Records the codebook gain of a subframe, limiting the first good gain after a loss
     * ({@code ec_gain_code_update}).
     *
     * @param bfi whether the subframe is lost
     * @param prevBf whether the previous frame was lost
     * @param gainCode the codebook gain
     * @param overflow the flag raised on saturation
     * @return the codebook gain, possibly limited
     */
    short codeUpdate(short bfi, short prevBf, short gainCode, Flag overflow) {
        if (bfi == 0) {
            if (prevBf != 0 && sub(gainCode, prevGc, overflow) > 0) {
                gainCode = prevGc;
            }
            prevGc = gainCode;
        }
        pastGainCode = gainCode;
        System.arraycopy(gbuf, 1, gbuf, 0, 4);
        gbuf[4] = gainCode;
        return gainCode;
    }
}
