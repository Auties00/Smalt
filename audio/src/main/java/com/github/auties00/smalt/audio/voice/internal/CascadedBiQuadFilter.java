/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/utility/cascaded_biquad_filter.cc). Copyright (c)
   2011, The WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal;

/**
 * Applies a cascade of second-order IIR sections in direct form 1.
 *
 * <p>Each section computes
 * {@code y[k] = b0 x[k] + b1 x[k-1] + b2 x[k-2] - a1 y[k-1] - a2 y[k-2]}, evaluated left to right
 * in single precision. Sections are given either as coefficients or as a complex zero, a complex
 * pole and a gain, from which the coefficients of the conjugate pair are derived.
 */
public final class CascadedBiQuadFilter {
    /**
     * The coefficients {@code b0, b1, b2, a1, a2} of every section, five per section.
     */
    private final float[] coefficients;

    /**
     * The state {@code x[k-1], x[k-2], y[k-1], y[k-2]} of every section, four per section.
     */
    private final float[] state;

    /**
     * The number of sections.
     */
    private final int sections;

    /**
     * Creates a cascade of identical sections given by their coefficients.
     *
     * @param b0 the coefficient of {@code x[k]}
     * @param b1 the coefficient of {@code x[k-1]}
     * @param b2 the coefficient of {@code x[k-2]}
     * @param a1 the coefficient of {@code y[k-1]}
     * @param a2 the coefficient of {@code y[k-2]}
     * @param sections the number of sections
     */
    public CascadedBiQuadFilter(float b0, float b1, float b2, float a1, float a2, int sections) {
        this.sections = sections;
        this.coefficients = new float[5 * sections];
        this.state = new float[4 * sections];
        for (var s = 0; s < sections; s++) {
            coefficients[5 * s] = b0;
            coefficients[5 * s + 1] = b1;
            coefficients[5 * s + 2] = b2;
            coefficients[5 * s + 3] = a1;
            coefficients[5 * s + 4] = a2;
        }
    }

    /**
     * Creates a cascade of sections given by zeros, poles and gains.
     *
     * <p>Each row of {@code params} is {@code {zeroRe, zeroIm, poleRe, poleIm, gain, mirror}};
     * a section has zeros at {@code zero} and its conjugate, or at {@code zeroRe} and
     * {@code -zeroRe} when {@code mirror} is non-zero, and poles at {@code pole} and its
     * conjugate.
     *
     * @param params the section parameters, one row per section
     * @return the filter
     */
    public static CascadedBiQuadFilter ofZerosAndPoles(float[][] params) {
        var filter = new CascadedBiQuadFilter(0, 0, 0, 0, 0, params.length);
        for (var s = 0; s < params.length; s++) {
            var p = params[s];
            var zr = p[0];
            var zi = p[1];
            var pr = p[2];
            var pi = p[3];
            var gain = p[4];
            var c = filter.coefficients;
            if (p[5] != 0) {
                c[5 * s] = gain * 1.f;
                c[5 * s + 1] = 0.f;
                c[5 * s + 2] = gain * -(zr * zr);
            } else {
                c[5 * s] = gain * 1.f;
                c[5 * s + 1] = gain * -2.f * zr;
                c[5 * s + 2] = gain * (zr * zr + zi * zi);
            }
            c[5 * s + 3] = -2.f * pr;
            c[5 * s + 4] = pr * pr + pi * pi;
        }
        return filter;
    }

    /**
     * Filters samples in place.
     *
     * @param y the samples; receives the filtered samples
     * @param offset the index of the first sample
     * @param length the number of samples
     */
    public void process(float[] y, int offset, int length) {
        for (var s = 0; s < sections; s++) {
            applySection(s, y, offset, y, offset, length);
        }
    }

    /**
     * Filters samples into another array.
     *
     * @param x the input samples
     * @param xOffset the index of the first input sample
     * @param y receives the filtered samples
     * @param yOffset the index of the first output sample
     * @param length the number of samples
     */
    public void process(float[] x, int xOffset, float[] y, int yOffset, int length) {
        if (sections == 0) {
            System.arraycopy(x, xOffset, y, yOffset, length);
            return;
        }
        applySection(0, x, xOffset, y, yOffset, length);
        for (var s = 1; s < sections; s++) {
            applySection(s, y, yOffset, y, yOffset, length);
        }
    }

    /**
     * Applies one section.
     *
     * @param s the section index
     * @param x the input samples
     * @param xOffset the index of the first input sample
     * @param y receives the output samples, possibly aliasing {@code x}
     * @param yOffset the index of the first output sample
     * @param length the number of samples
     */
    private void applySection(int s, float[] x, int xOffset, float[] y, int yOffset, int length) {
        var c = coefficients;
        var ca0 = c[5 * s + 3];
        var ca1 = c[5 * s + 4];
        var cb0 = c[5 * s];
        var cb1 = c[5 * s + 1];
        var cb2 = c[5 * s + 2];
        var st = state;
        var mx0 = st[4 * s];
        var mx1 = st[4 * s + 1];
        var my0 = st[4 * s + 2];
        var my1 = st[4 * s + 3];
        for (var k = 0; k < length; ++k) {
            var tmp = x[xOffset + k];
            var out = cb0 * tmp + cb1 * mx0 + cb2 * mx1 - ca0 * my0 - ca1 * my1;
            y[yOffset + k] = out;
            mx1 = mx0;
            mx0 = tmp;
            my1 = my0;
            my0 = out;
        }
        st[4 * s] = mx0;
        st[4 * s + 1] = mx1;
        st[4 * s + 2] = my0;
        st[4 * s + 3] = my1;
    }

    /**
     * Zeroes every state value whose magnitude is below a floor.
     *
     * <p>Fed with silence, a stable filter's state decays toward zero without ever reaching it
     * and ends up in subnormal numbers, whose arithmetic is about a hundred times slower on x86.
     * Zeroing the values that are far below any meaningful level keeps the state, and the output
     * for a silent input, exactly zero instead.
     *
     * @param floor the magnitude below which a state value is zeroed
     */
    public void flushStates(float floor) {
        var st = state;
        for (var i = 0; i < st.length; i++) {
            if (Math.abs(st[i]) < floor) {
                st[i] = 0.f;
            }
        }
    }

    /**
     * Clears the state of every section.
     */
    public void reset() {
        java.util.Arrays.fill(state, 0.f);
    }
}
