/* Ports opencore-amr 0.1.6 (agc.cpp). Copyright PacketVideo; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MAX_32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MIN_16;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.MIN_32;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.divS;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMac;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lMult;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShl;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.lShr;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.normL;
import static com.github.auties00.smalt.audio.amr.internal.nb.BasicOps.round;

/**
 * Applies the automatic gain control of the AMR-NB decoder: it scales a filtered signal so that
 * its energy matches the energy of the signal before the filter ({@code agcState}).
 *
 * <p>The postfilter uses the smoothed, stateful control; the pitch enhancement of the excitation
 * uses the instantaneous one.
 */
final class Agc {
    /**
     * The gain of the last sample of the previous call, Q12.
     */
    private short pastGain;

    /**
     * Creates the control, reset.
     */
    Agc() {
        reset();
    }

    /**
     * Resets the control to unit gain ({@code agc_reset}).
     */
    void reset() {
        pastGain = 4096;
    }

    /**
     * Returns the energy of a signal scaled down by 4 before squaring ({@code energy_old}).
     *
     * @param in the signal
     * @param offset the index of the first sample
     * @param length the number of samples
     * @param overflow the flag raised on saturation
     * @return the energy
     */
    private static int energyOld(short[] in, int offset, int length, Flag overflow) {
        var s = 0;
        for (var i = 0; i < length; i++) {
            var temp = (short) (in[offset + i] >> 2);
            s = lMac(s, temp, temp, overflow);
        }
        return s;
    }

    /**
     * Returns the energy of a signal divided by 16, recomputed on the downscaled signal when
     * the plain sum saturates ({@code energy_new}).
     *
     * @param in the signal
     * @param offset the index of the first sample
     * @param length the number of samples
     * @param overflow the flag raised on saturation
     * @return the energy
     */
    private static int energyNew(short[] in, int offset, int length, Flag overflow) {
        var saved = overflow.value;
        var s = 0;
        for (var i = 0; i < length; i++) {
            s = lMac(s, in[offset + i], in[offset + i], overflow);
        }
        if (s != MAX_32) {
            return s >> 4;
        }
        overflow.value = saved;
        return energyOld(in, offset, length, overflow);
    }

    /**
     * Scales a signal to the energy of a reference, the gain moving smoothly from the previous
     * call's ({@code agc}).
     *
     * @param sigIn the reference signal
     * @param inOffset the index of its first sample
     * @param sigOut the signal scaled in place
     * @param outOffset the index of its first sample
     * @param agcFac the smoothing factor, Q15
     * @param length the number of samples
     * @param overflow the flag raised on saturation
     */
    void apply(short[] sigIn, int inOffset, short[] sigOut, int outOffset, short agcFac, int length, Flag overflow) {
        var s = energyNew(sigOut, outOffset, length, overflow);
        if (s == 0) {
            pastGain = 0;
            return;
        }
        var exp = (short) (normL(s) - 1);
        var temp32 = lShl(s, exp);
        var gainOut = round(temp32, overflow);
        s = energyNew(sigIn, inOffset, length, overflow);
        short g0;
        if (s == 0) {
            g0 = 0;
        } else {
            var i = normL(s);
            temp32 = s << i;
            var gainIn = round(temp32, overflow);
            exp = (short) (exp - i);
            var temp = divS(gainOut, gainIn);
            s = temp;
            s = s << 7;
            s = lShr(s, exp);
            s = FixedMath.invSqrt(s);
            temp32 = s << 9;
            var root = (short) ((temp32 + 0x00008000) >> 16);
            temp = (short) (32767 - agcFac);
            g0 = (short) ((root * temp) >> 15);
        }
        var gain = pastGain;
        for (var i = 0; i < length; i++) {
            gain = (short) ((gain * agcFac) >> 15);
            gain = (short) (gain + g0);
            var scaled = (sigOut[outOffset + i] * gain) << 1;
            sigOut[outOffset + i] = (short) (scaled >> 13);
        }
        pastGain = gain;
    }

    /**
     * Scales a signal to the energy of a reference with a constant gain ({@code agc2}).
     *
     * @param sigIn the reference signal
     * @param sigOut the signal scaled in place
     * @param length the number of samples
     * @param overflow the flag raised on saturation
     */
    static void applyInstant(short[] sigIn, short[] sigOut, int length, Flag overflow) {
        var s = energyNew(sigOut, 0, length, overflow);
        if (s == 0) {
            return;
        }
        var exp = (short) (normL(s) - 1);
        var temp32 = lShl(s, exp);
        var gainOut = round(temp32, overflow);
        s = energyNew(sigIn, 0, length, overflow);
        short g0;
        if (s == 0) {
            g0 = 0;
        } else {
            var i = normL(s);
            temp32 = lShl(s, i);
            var gainIn = round(temp32, overflow);
            exp = (short) (exp - i);
            var temp = divS(gainOut, gainIn);
            s = temp;
            if (s > 0x00FFFFFF) {
                s = MAX_32;
            } else if (s < -16777216) {
                s = MIN_32;
            } else {
                s = s << 7;
            }
            s = lShr(s, exp);
            s = FixedMath.invSqrt(s);
            if (s > 0x003FFFFF) {
                temp32 = MAX_32;
            } else if (s < -4194304) {
                temp32 = MIN_32;
            } else {
                temp32 = s << 9;
            }
            g0 = round(temp32, overflow);
        }
        for (var i = length - 1; i >= 0; i--) {
            var product = lMult(sigOut[i], g0, overflow);
            if (product > 0x0FFFFFFF) {
                sigOut[i] = MAX_16;
            } else if (product < -268435456) {
                sigOut[i] = MIN_16;
            } else {
                sigOut[i] = (short) (product >> 13);
            }
        }
    }
}
