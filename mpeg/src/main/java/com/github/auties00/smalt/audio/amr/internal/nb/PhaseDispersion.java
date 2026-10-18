/* Ports opencore-amr 0.1.6 (ph_disp.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MIN_32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.add;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lAdd;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;
import static com.github.auties00.smalt.audio.amr.internal.nb.Constants.L_SUBFR;

/**
 * Applies the adaptive phase dispersion of the AMR-NB decoder to the innovation of the low
 * rate modes and builds the excitation of a subframe ({@code ph_dispState}).
 *
 * <p>The sparse algebraic innovation of {@code MR475} to {@code MR67} and {@code MR795} is
 * circularly convolved with a dispersion impulse response chosen from the recent pitch gains, which
 * makes it sound less buzzy; an onset of the codebook gain switches the dispersion off.
 */
final class PhaseDispersion {
    /**
     * The number of pitch gains kept.
     */
    private static final int PHDGAINMEMSIZE = 5;

    /**
     * The pitch gain below which full dispersion applies, Q14.
     */
    private static final short PHDTHR1LTP = 9830;

    /**
     * The pitch gain from which no dispersion applies, Q14.
     */
    private static final short PHDTHR2LTP = 14746;

    /**
     * The codebook gain ratio that marks an onset, Q13.
     */
    private static final int ONFACTPLUS1 = 16384;

    /**
     * The number of subframes an onset lasts.
     */
    private static final short ONLENGTH = 2;

    /**
     * The last pitch gains, newest first.
     */
    private final short[] gainMem = new short[PHDGAINMEMSIZE];

    /**
     * The dispersion strength of the previous subframe: 0 full, 1 medium, 2 none.
     */
    private short prevState;

    /**
     * The codebook gain of the previous subframe.
     */
    private short prevCbGain;

    /**
     * Whether full dispersion is forced for this subframe.
     */
    private short lockFull;

    /**
     * The subframes left in the current onset.
     */
    private short onset;

    /**
     * The innovation before dispersion, scratch.
     */
    private final short[] innoSav = new short[L_SUBFR];

    /**
     * The positions of the nonzero innovation samples, scratch.
     */
    private final short[] psPoss = new short[L_SUBFR];

    /**
     * Creates the state, reset.
     */
    PhaseDispersion() {
        reset();
    }

    /**
     * Resets the state ({@code ph_disp_reset}).
     */
    void reset() {
        for (var i = 0; i < PHDGAINMEMSIZE; i++) {
            gainMem[i] = 0;
        }
        prevState = 0;
        prevCbGain = 0;
        lockFull = 0;
        onset = 0;
    }

    /**
     * Forces full dispersion for the next subframe ({@code ph_disp_lock}).
     */
    void lock() {
        lockFull = 1;
    }

    /**
     * Releases a forced full dispersion ({@code ph_disp_release}).
     */
    void release() {
        lockFull = 0;
    }

    /**
     * Disperses the innovation and builds the excitation {@code x = pitch_fac * x + cbGain * inno} ({@code ph_disp}).
     *
     * @param mode the codec mode, one of the {@link Mode} constants
     * @param x the adaptive codebook excitation, replaced by the total excitation
     * @param cbGain the codebook gain
     * @param ltpGain the pitch gain
     * @param inno the innovation, dispersed in place
     * @param pitchFac the pitch factor applied to {@code x}
     * @param tmpShift the left shift of the sum
     * @param overflow the flag raised on saturation
     */
    void disperse(int mode, short[] x, short cbGain, short ltpGain, short[] inno, short pitchFac, short tmpShift,
                  Flag overflow) {
        gainMem[4] = gainMem[3];
        gainMem[3] = gainMem[2];
        gainMem[2] = gainMem[1];
        gainMem[1] = gainMem[0];
        gainMem[0] = ltpGain;
        short impNr;
        if (ltpGain < PHDTHR2LTP) {
            impNr = ltpGain > PHDTHR1LTP ? (short) 1 : (short) 0;
        } else {
            impNr = 2;
        }
        var temp = (prevCbGain * ONFACTPLUS1) << 1;
        if (temp > 0x1fffffff) {
            overflow.value = true;
            temp = MAX_32;
        } else if (temp < -536870912) {
            overflow.value = true;
            temp = MIN_32;
        } else {
            temp <<= 2;
        }
        var tmp1 = round(temp, overflow);
        if (cbGain > tmp1) {
            onset = ONLENGTH;
        } else if (onset > 0) {
            onset -= 1;
        }
        if (onset == 0) {
            var i1 = 0;
            for (var i = 0; i < PHDGAINMEMSIZE; i++) {
                if (gainMem[i] < PHDTHR1LTP) {
                    i1 += 1;
                }
            }
            if (i1 > 2) {
                impNr = 0;
            }
        }
        if (impNr > prevState + 1 && onset == 0) {
            impNr -= 1;
        }
        if (impNr < 2 && onset > 0) {
            impNr += 1;
        }
        if (cbGain < 10) {
            impNr = 2;
        }
        if (lockFull == 1) {
            impNr = 0;
        }
        prevState = impNr;
        prevCbGain = cbGain;
        if (mode != Mode.MR122 && mode != Mode.MR102 && mode != Mode.MR74 && impNr < 2) {
            var nze = 0;
            for (var i = 0; i < L_SUBFR; i++) {
                if (inno[i] != 0) {
                    psPoss[nze] = (short) i;
                    nze += 1;
                }
                innoSav[i] = inno[i];
                inno[i] = 0;
            }
            short[] phImp;
            if (mode == Mode.MR795) {
                phImp = impNr == 0 ? CommonTables.PH_IMP_LOW_MR795 : CommonTables.PH_IMP_MID_MR795;
            } else {
                phImp = impNr == 0 ? CommonTables.PH_IMP_LOW : CommonTables.PH_IMP_MID;
            }
            for (var nPulse = 0; nPulse < nze; nPulse++) {
                var ppos = psPoss[nPulse];
                var cInnoSav = innoSav[ppos];
                var k = 0;
                for (var i = ppos; i < L_SUBFR; i++) {
                    var product = (short) ((cInnoSav * phImp[k++]) >> 15);
                    inno[i] = add(inno[i], product, overflow);
                }
                for (var i = 0; i < ppos; i++) {
                    var product = (short) ((cInnoSav * phImp[k++]) >> 15);
                    inno[i] = add(inno[i], product, overflow);
                }
            }
        }
        for (var i = 0; i < L_SUBFR; i++) {
            var sum = lMult(x[i], pitchFac, overflow);
            var temp2 = (inno[i] * cbGain) << 1;
            sum = lAdd(sum, temp2, overflow);
            sum = lShl(sum, tmpShift);
            x[i] = round(sum, overflow);
        }
    }
}
