/* Ports hvxcFltDec.c of the ISO/IEC 14496-3 reference software (MPEG-4 Audio, subpart 2, HVXC). Copyright Sony
 * Corporation; ISO/IEC 14496-3 reference software licence. Modified for Smalt. */
package com.github.auties00.smalt.audio.hvxc.internal;

import static com.github.auties00.smalt.audio.hvxc.internal.HvxcDsp.FRAME;
import static com.github.auties00.smalt.audio.hvxc.internal.HvxcDsp.ORDER;

/**
 * The postfilter and post-processing filters of the HVXC decoder (ISO/IEC 14496-3 Annex 2.B, informative): the
 * formant postfilter shared by the voiced and unvoiced synthesizers and the second and fourth order sections of the
 * band pass post-processing.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class HvxcFilters {
    /**
     * The 70 Hz high pass section of the post-processing ({@code hpf_dec_coef}), gain last.
     */
    static final double[] HIGH_PASS = {0.551543, 0.152100, 0.89, 0.198025, 1.226};

    /**
     * The fourth order high pass of the post-processing ({@code hpf4ji_coef}), two sections then the gain.
     */
    static final double[] HIGH_PASS_4 = {-1.998066423746901, 1.000000000000000, -1.962822436245804,
            0.9684991816600951, -1.999633313803449, 0.9999999999999999, -1.858097918647416, 0.8654599838007603, 1.0};

    /**
     * The 3.4 kHz low pass of the post-processing ({@code lpf3400_coef}), gain last.
     */
    static final double[] LOW_PASS = {-2. * 1. * StrictMath.cos((4.0 / 4.0) * Math.PI), 1.,
            -2. * 0.78 * StrictMath.cos((3.55 / 4.0) * Math.PI), 0.78 * 0.78, 0.768};

    /**
     * The 320 Hz high pass that follows the postfilter of unvoiced speech ({@code hpf_posfil_uv_coef}), gain last.
     */
    static final double[] UNVOICED_HIGH_PASS = {-1.999245, 0.9998, -1.656136, 0.710649, 0.83695};

    /**
     * The powers of 0.5 and 0.8 that widen the bandwidths of the postfilter numerator and denominator.
     */
    private static final float[] FAC05 = {1.0f, 0.5f, 0.25f, 0.125f, 0.0625f, 0.03125f, 0.015625f, 0.0078125f,
            0.00390625f, 0.001953125f, 0.0009765625f};

    private static final float[] FAC08 = {1.0f, 0.8f, 0.64f, 0.512f, 0.4096f, 0.32768f, 0.262144f, 0.2097152f,
            0.16777216f, 0.134217728f, 0.107374182f};

    /**
     * Prevents instantiation.
     */
    private HvxcFilters() {
    }

    /**
     * Copies the default bandwidth expansion powers.
     *
     * @param fac05 receives the numerator powers
     * @param fac08 receives the denominator powers
     */
    static void powers(float[] fac05, float[] fac08) {
        System.arraycopy(FAC05, 0, fac05, 0, ORDER + 1);
        System.arraycopy(FAC08, 0, fac08, 0, ORDER + 1);
    }

    /**
     * Filters one subframe through the postfilter of the current and, for the cross-fade, of the previous
     * subframe's coefficients.
     *
     * @param in the pre-emphasized input
     * @param inOffset the index of the first input sample
     * @param length the subframe length
     * @param offset the index of the subframe in the output arrays
     * @param fir the numerator coefficients of the subframe
     * @param iir the denominator coefficients of the subframe
     * @param mem1 the numerator state, updated
     * @param mem2 the denominator state, updated
     * @param firOld the numerator of the previous subframe, replaced by {@code fir}
     * @param iirOld the denominator of the previous subframe, replaced by {@code iir}
     * @param mem1Old work space of 11 values
     * @param mem2Old work space of 11 values
     * @param resi work space of a frame
     * @param pos receives the output with the current coefficients
     * @param rest receives the output with the previous coefficients
     */
    static void postFilterSubframe(float[] in, int inOffset, int length, int offset, float[] fir, float[] iir,
                                   float[] mem1, float[] mem2, float[] firOld, float[] iirOld, float[] mem1Old,
                                   float[] mem2Old, float[] resi, float[] pos, float[] rest) {
        System.arraycopy(mem1, 0, mem1Old, 0, ORDER + 1);
        for (var i = 0; i < length; i++) {
            mem1[0] = in[inOffset + i];
            mem1Old[0] = in[inOffset + i];
            var out = 0.0f;
            var outOld = 0.0f;
            for (var j = ORDER; j > 0; j--) {
                out += mem1[j] * fir[j];
                mem1[j] = mem1[j - 1];
                outOld += mem1Old[j] * firOld[j];
                mem1Old[j] = mem1Old[j - 1];
            }
            out += mem1[0];
            outOld += mem1Old[0];
            resi[offset + i] = out;
            rest[offset + i] = outOld;
        }
        System.arraycopy(mem2, 0, mem2Old, 0, ORDER + 1);
        for (var i = 0; i < length; i++) {
            var out2 = 0.0f;
            var out2Old = 0.0f;
            for (var j = ORDER; j > 0; j--) {
                out2 += mem2[j] * iir[j];
                out2Old += mem2Old[j] * iirOld[j];
                mem2[j] = mem2[j - 1];
                mem2Old[j] = mem2Old[j - 1];
            }
            out2 = resi[offset + i] - out2;
            out2Old = rest[offset + i] - out2Old;
            mem2[1] = out2;
            mem2Old[1] = out2Old;
            pos[offset + i] = out2;
            rest[offset + i] = out2Old;
        }
        System.arraycopy(fir, 0, firOld, 0, ORDER + 1);
        System.arraycopy(iir, 0, iirOld, 0, ORDER + 1);
    }

    /**
     * Restores the level of the postfiltered frame and cross-fades it in from the output of the previous
     * coefficients.
     *
     * @param syn the frame before the postfilter, replaced by the output
     * @param pos the postfiltered frame
     * @param rest the frame filtered with the previous coefficients
     * @param fade the length of the cross-fade
     * @param oldGain the gain of the previous frame
     * @return the gain of this frame
     */
    static float applyGain(float[] syn, float[] pos, float[] rest, int fade, float oldGain) {
        var sp = 0.0f;
        var op = 0.0f;
        for (var i = 0; i < FRAME; i++) {
            sp += syn[i] * syn[i];
            op += pos[i] * pos[i];
        }
        var gain = op >= 2560 ? (float) StrictMath.sqrt(sp / op) : 0.0f;
        for (var i = 0; i < FRAME; i++) {
            var window = i < fade ? (float) ((float) i / (double) fade) : 1.0f;
            syn[i] = (float) (gain * pos[i] * window + oldGain * rest[i] * (1.0 - window));
        }
        return gain;
    }

    /**
     * Runs a second order section in place ({@code iir2}).
     *
     * @param data the samples
     * @param state the section state, 4 values
     * @param coef the feedforward and feedback coefficients and the gain
     */
    static void iir2(float[] data, float[] state, double[] coef) {
        for (var i = 0; i < FRAME; i++) {
            var x = (float) (data[i] + state[0] * coef[0] + state[1] * coef[1]
                    - (state[2] * coef[2] + state[3] * coef[3]));
            state[1] = state[0];
            state[0] = data[i];
            state[3] = state[2];
            state[2] = x;
            data[i] = (float) (x * coef[4]);
        }
    }

    /**
     * Runs two cascaded second order sections in place ({@code iir4}).
     *
     * @param data the samples
     * @param state the section states, 8 values
     * @param coef the coefficients of both sections and the gain
     */
    static void iir4(float[] data, double[] state, double[] coef) {
        for (var i = 0; i < FRAME; i++) {
            var x = (float) (data[i] + state[0] * coef[0] + state[1] * coef[1]
                    - (state[2] * coef[2] + state[3] * coef[3]));
            state[1] = state[0];
            state[0] = data[i];
            state[3] = state[2];
            state[2] = x;
            data[i] = x;
            x = (float) (x + state[4] * coef[4] + state[5] * coef[5] - (state[6] * coef[6] + state[7] * coef[7]));
            state[5] = state[4];
            state[4] = data[i];
            state[7] = state[6];
            state[6] = x;
            data[i] = (float) (x * coef[8]);
        }
    }
}
