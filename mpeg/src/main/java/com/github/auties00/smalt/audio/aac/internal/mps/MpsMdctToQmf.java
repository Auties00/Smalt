/* Ports the ISO/IEC 23003-3:2019 reference software MPEG Surround decoder (sac_mdct2qmf.c and
   sac_mdct2qmf_wf_tables.c: the conversion of residual MDCT spectra into the QMF domain). Copyright Agere Systems,
   Coding Technologies, Fraunhofer IIS, Philips and ISO/IEC 2008; ISO/IEC reference software licence (use in products
   conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.mps;

import com.github.auties00.smalt.MediaException;

/**
 * Converts the MDCT spectrum of a residual frame into QMF slots, without an inverse MDCT (ISO/IEC 23003-1 subclause
 * 6.9.2).
 *
 * <p>The conversion folds the spectrum with frequency windows, transforms it with short complex modulations and
 * adds the result, weighted by time windows, into a QMF buffer of two spatial frames. Instances keep scratch arrays;
 * they are not thread-safe.
 */
final class MpsMdctToQmf {
    private static final float PI = 3.14159265358979f;
    private static final int FRAME = 1024;
    private static final int SHORT_FRAME = 128;

    private static final float[] WF_02 = {2.25159422791899e-001f, 9.75300216421083e-001f};
    private static final float[] WF_03 = {3.776593725453505e-001f, 9.260029738839094e-001f, 1.000000000000000e+000f};
    private static final float[] WF_04 = {6.96503775627941e-002f, 4.61853083731484e-001f, 8.86894602377496e-001f,
            9.98790120205249e-001f};
    private static final float[] WF_15 = {3.108607658174681e-002f, 7.684574106763581e-002f, 1.508354429213179e-001f,
            2.531644925199998e-001f, 3.776593725453505e-001f, 5.130597832310549e-001f, 6.458917069517359e-001f,
            7.638311369132910e-001f, 8.583559874082372e-001f, 9.260029738839094e-001f, 9.681920012517699e-001f,
            9.899965908597906e-001f, 9.983403183137536e-001f, 1.000033158322195e+000f, 1.000000000000000e+000f};
    private static final float[] WF_16 = {1.65475125062983e-002f, 4.65240767348341e-002f, 9.89602832916821e-002f,
            1.76902905856576e-001f, 2.78831786952801e-001f, 3.98430480799321e-001f, 5.25831178519259e-001f,
            6.49858917237363e-001f, 7.60468046976490e-001f, 8.50620915105811e-001f, 9.17198211446560e-001f,
            9.60923129495122e-001f, 9.85537849989136e-001f, 9.96542721419397e-001f, 9.99771769707441e-001f,
            1.00003374095677e+000f};
    private static final float[] WF_18 = {1.54272357075635e-002f, 4.01056583972472e-002f, 8.18912638533254e-002f,
            1.43547011618227e-001f, 2.25159422791899e-001f, 3.23727710751240e-001f, 4.33511567516425e-001f,
            5.47034164787458e-001f, 6.56436993346885e-001f, 7.54811649929518e-001f, 8.37198606574045e-001f,
            9.01092348426438e-001f, 9.46449282032434e-001f, 9.75300216421083e-001f, 9.91106019492545e-001f,
            9.97984869463190e-001f, 9.99917752053543e-001f, 1.00002790373369e+000f};
    private static final float[] WF_24 = {1.33344898119880e-002f, 2.90607489815381e-002f, 5.35733084899849e-002f,
            8.84809749081922e-002f, 1.34720976611448e-001f, 1.92339751279503e-001f, 2.60391996391985e-001f,
            3.36979154288995e-001f, 4.19421306618108e-001f, 5.04531772744345e-001f, 5.88947180873229e-001f,
            6.69460465973682e-001f, 7.43309991463507e-001f, 8.08391818789828e-001f, 8.63379473642823e-001f,
            9.07751738914918e-001f, 9.41740717799601e-001f, 9.66218394063355e-001f, 9.82540921463216e-001f,
            9.92367937267032e-001f, 9.97471588213559e-001f, 9.99548248354220e-001f, 1.00004547262230e+000f,
            1.00001684978847e+000f};
    private static final float[] WF_30 = {1.21701874987223e-002f, 2.35062123092812e-002f, 4.01056583972472e-002f,
            6.28961453327895e-002f, 9.25867277500073e-002f, 1.29578041287072e-001f, 1.73896248044280e-001f,
            2.25159422791899e-001f, 2.82580915231516e-001f, 3.45009538562499e-001f, 4.11001924411292e-001f,
            4.78918737883468e-001f, 5.47034164787458e-001f, 6.13647398093030e-001f, 6.77185703487687e-001f,
            7.36290721083768e-001f, 7.89882478441777e-001f, 8.37198606574045e-001f, 8.77808975766847e-001f,
            9.11608053596863e-001f, 9.38788574477624e-001f, 9.59800629511943e-001f, 9.75300216421083e-001f,
            9.86090888708778e-001f, 9.93061666985504e-001f, 9.97124011928831e-001f, 9.99150489219049e-001f,
            9.99917752053543e-001f, 1.00005651297348e+000f, 1.00001112602121e+000f};
    private static final float[] WF_32 = {1.18894978514784e-002f, 2.22355013318738e-002f, 3.71254015544489e-002f,
            5.73415208375520e-002f, 8.35091312497429e-002f, 1.16028814280589e-001f, 1.55023452985960e-001f,
            2.00305931728145e-001f, 2.51371327015577e-001f, 3.07414627534250e-001f, 3.67372216087139e-001f,
            4.29982868367448e-001f, 4.93862191842022e-001f, 5.57583444043476e-001f, 6.19757592535477e-001f,
            6.79106226817397e-001f, 7.34522308134036e-001f, 7.85115477922992e-001f, 8.30240452578559e-001f,
            8.69508657445507e-001f, 9.02784515399660e-001f, 9.30168618388376e-001f, 9.51970382536667e-001f,
            9.68672804668346e-001f, 9.80891731819072e-001f, 9.89331765860614e-001f, 9.94740668245914e-001f,
            9.97863972503918e-001f, 9.99401464920565e-001f, 9.99967216813160e-001f, 1.00005487312988e+000f,
            1.00000984349249e+000f};

    private final float[] frequencyWindow = new float[144];
    private final float[] timeWindow = new float[144];
    private final float[] main = new float[2 * FRAME];
    private final float[] slave = new float[2 * FRAME];
    private final float[] shortSpectrum = new float[3 * SHORT_FRAME];
    private final float[][] zReal;
    private final float[][] zImag;
    private final float[] twiddleReal;
    private final float[] twiddleImag;
    private final float[] fold = new float[64];
    private final int qmfBands;

    /**
     * Constructs a converter.
     *
     * @param qmfBands the number of QMF subbands
     */
    MpsMdctToQmf(int qmfBands) {
        this.qmfBands = qmfBands;
        this.zReal = new float[Math.max(qmfBands, 64)][64];
        this.zImag = new float[Math.max(qmfBands, 64)][64];
        this.twiddleReal = new float[qmfBands];
        this.twiddleImag = new float[qmfBands];
        for (var i = 0; i < qmfBands; i++) {
            var argument = (258.0f * i + 385.0f) * PI / 256.0f;
            twiddleReal[i] = (float) StrictMath.cos(argument);
            twiddleImag[i] = -(float) StrictMath.sin(argument);
        }
    }

    /**
     * {@return whether a QMF update length is one the conversion handles}
     *
     * @param update the number of QMF slots of one residual frame
     */
    static boolean supports(int update) {
        return update == 15 || update == 16 || update == 18 || update == 24 || update == 30 || update == 32;
    }

    /**
     * Adds the QMF slots of one residual frame ({@code SPACE_MDCT2QMF_Process}).
     *
     * @param update the number of QMF slots of the residual frame, 15, 16, 18, 24, 30 or 32
     * @param mdct the spectrum, 1024 lines and room for 1024 more; modified
     * @param qmfReal the real parts of the QMF buffer, {@code [slot][band]}, updated
     * @param qmfImag the imaginary parts of the QMF buffer, updated
     * @param windowSequence the AAC window sequence of the residual frame
     * @param offset the first slot of the residual frame in the buffer
     * @throws MediaException.Malformed if the update length or window sequence is invalid
     */
    void process(int update, float[] mdct, float[][] qmfReal, float[][] qmfImag, int windowSequence, int offset) {
        if (!supports(update)) {
            throw new MediaException.Malformed("MPEG Surround residual frames of " + update + " QMF slots");
        }
        var length = 2 * update;
        windows(update, windowSequence);
        if (windowSequence != 2) {
            var extra = update * qmfBands - FRAME;
            var mdctLength = FRAME;
            if (extra > 0) {
                for (var i = 0; i < extra; i++) {
                    mdct[FRAME + i] = 0.0f;
                }
            }
            mdctLength += extra;
            foldOut(mdct, mdctLength, frequencyWindow, 0, length);
            toQmf(mdctLength, timeWindow, 0, length);
            accumulate(qmfReal, qmfImag, offset, length);
            return;
        }
        int mdctLength;
        int qmfOffset;
        int qmfShift;
        int windowCount;
        var extra = 0;
        switch (update) {
            case 15 -> {
                length = 4;
                mdctLength = SHORT_FRAME;
                qmfOffset = 6;
                qmfShift = 2;
                windowCount = 7;
            }
            case 16, 32 -> {
                extra = (update - 16) * 8;
                mdctLength = SHORT_FRAME + extra;
                length = 2 * update / 8;
                qmfOffset = 7 * update / 16;
                qmfShift = update / 8;
                windowCount = 8;
            }
            case 18 -> {
                length = 4;
                mdctLength = SHORT_FRAME;
                qmfOffset = 8;
                qmfShift = 2;
                windowCount = 9;
            }
            case 24 -> {
                length = 4;
                mdctLength = SHORT_FRAME;
                qmfOffset = 11;
                qmfShift = 2;
                windowCount = 12;
            }
            default -> {
                length = 4;
                mdctLength = SHORT_FRAME;
                qmfOffset = 14;
                qmfShift = 2;
                windowCount = 15;
            }
        }
        var windowOffset = 0;
        var mdctOffset = 0;
        for (var k = 0; k < windowCount; k++) {
            if (update == 15 && k == windowCount - 1) {
                windowOffset = length;
                length = 6;
                mdctLength = 192;
                for (int i = 0, j = 0; i < mdctLength / 2; i++) {
                    shortSpectrum[j++] = mdct[mdctOffset + i];
                    shortSpectrum[j++] = mdct[SHORT_FRAME + mdctOffset + i];
                }
            } else {
                System.arraycopy(mdct, mdctOffset, shortSpectrum, 0, SHORT_FRAME);
                for (var i = 0; i < extra; i++) {
                    shortSpectrum[SHORT_FRAME + i] = 0.0f;
                }
            }
            foldOut(shortSpectrum, mdctLength, frequencyWindow, windowOffset, length);
            toQmf(mdctLength, timeWindow, windowOffset, length);
            accumulate(qmfReal, qmfImag, qmfOffset + offset, length);
            mdctOffset += SHORT_FRAME;
            qmfOffset += qmfShift;
        }
    }

    private void accumulate(float[][] qmfReal, float[][] qmfImag, int offset, int length) {
        for (var i = 0; i < qmfBands; i++) {
            var tr = twiddleReal[i];
            var ti = twiddleImag[i];
            var zr = zReal[i];
            var zi = zImag[i];
            for (var j = 0; j < length; j++) {
                qmfReal[j + offset][i] += tr * zr[j] - ti * zi[j];
                qmfImag[j + offset][i] += tr * zi[j] + ti * zr[j];
            }
        }
    }

    private void foldOut(float[] s, int lv, float[] w, int wOffset, int lw) {
        var m = lw / 2;
        var l = lv / m;
        var m2w = m / 2;
        var m2a = m - m2w;
        for (var i = m; i < lv; i += lw) {
            for (var n = i + lw; i < n; i++) {
                s[i] = -s[i];
            }
        }
        for (var i = 0; i < lv; i++) {
            main[i] = s[i];
            slave[i] = 0.0f;
        }
        var w1 = wOffset - m2a;
        var w2 = wOffset + m2w;
        var w3 = wOffset + m - m2a;
        var w4 = wOffset + m + m2w;
        var j = 0;
        var k = m;
        for (var n = 0; n < l - 1; n++) {
            var i = 0;
            for (; i < m2a; i++, j++, k++) {
                main[k] = w[w2 + i] * s[k];
                slave[j] = w[w4 + i] * s[k];
            }
            for (; i < m; i++, j++, k++) {
                main[j] = w[w3 + i] * s[j];
                slave[k] = w[w1 + i] * s[j];
            }
        }
        var i = 0;
        for (j = m - 1; i < m2a; i++, j--) {
            main[i] = w[w2 + i] * s[i];
            slave[j] = w[w4 + i] * s[i];
        }
        j = l * m - m2w;
        k = l * m - m2a - 1;
        for (; i < m; i++, j++, k--) {
            main[j] = w[w3 + i] * s[j];
            slave[k] = w[w1 + i] * s[j];
        }
    }

    private void toQmf(int lv, float[] w, int wOffset, int lw) {
        var m = lw / 2;
        var l = lv / m;
        for (var n = 0; n < l; n++) {
            var address = n * m;
            for (var i = 0; i < m; i++) {
                if ((n & 1) != 0) {
                    fold[i] = main[address + i];
                    fold[m + i] = slave[address + i];
                } else {
                    fold[m + i] = main[address + i];
                    fold[i] = slave[address + i];
                }
            }
            transform(fold, w, wOffset, m, n);
        }
    }

    private void transform(float[] x, float[] w, int wOffset, int length, int band) {
        var lw = 2 * length;
        var kMin = -(int) Math.floor(length / 2.0f);
        var gain = (float) Math.sqrt(1.0f / lw);
        var real = zReal[band];
        var imag = zImag[band];
        for (var k = 0; k < lw; k++) {
            var re = 0.0f;
            var im = 0.0f;
            for (var n = 0; n < lw; n++) {
                var argument = (k + kMin + 0.5f) * (n - length + 0.5f) * PI / length;
                re += x[n] * gain * (float) StrictMath.cos(argument);
                im += x[n] * gain * (float) StrictMath.sin(argument);
            }
            real[k] = re * w[wOffset + k];
            imag[k] = im * w[wOffset + k];
        }
    }

    private static void sine(int t, int p, int count, float[] out, int offset) {
        var odd = t % 2;
        for (var i = 0; i < count; i++) {
            out[offset + i] = (float) StrictMath.sin(PI / (2 * t) * (i + p + .5 * (odd + 1)));
        }
    }

    private static void fill(int count, float value, float[] out, int offset) {
        for (var i = 0; i < count; i++) {
            out[offset + i] = value;
        }
    }

    private static float[] frequencyTable(int length) {
        return switch (length) {
            case 2 -> WF_02;
            case 3 -> WF_03;
            case 4 -> WF_04;
            case 15 -> WF_15;
            case 16 -> WF_16;
            case 18 -> WF_18;
            case 24 -> WF_24;
            case 30 -> WF_30;
            case 32 -> WF_32;
            default -> throw new MediaException.Malformed("MPEG Surround residual window of " + length + " slots");
        };
    }

    private static void frequencyWindow(int length, float[] out, int offset) {
        var odd = length % 2;
        var table = frequencyTable(length);
        for (var i = 0; i < length - odd; i++) {
            out[offset + i] = table[i];
            out[offset + 2 * length - 1 - odd - i] = table[i];
        }
        if (odd == 1) {
            out[offset + length - 1] = table[length - 1];
            out[offset + 2 * length - 1] = 0.0f;
        }
    }

    private void windows(int update, int windowSequence) {
        int length;
        switch (windowSequence) {
            case 0 -> {
                sine(update, 0, 2 * update, timeWindow, 0);
                length = update;
            }
            case 1 -> {
                int constant1;
                int constant2;
                int right;
                switch (update) {
                    case 15 -> {
                        constant1 = 6;
                        constant2 = 7;
                        right = 2;
                    }
                    case 16, 32 -> {
                        constant1 = 7 * update / 16;
                        constant2 = constant1;
                        right = update / 8;
                    }
                    case 18 -> {
                        constant1 = 8;
                        constant2 = 8;
                        right = 2;
                    }
                    case 24 -> {
                        constant1 = 11;
                        constant2 = 11;
                        right = 2;
                    }
                    default -> {
                        constant1 = 14;
                        constant2 = 14;
                        right = 2;
                    }
                }
                sine(update, 0, update, timeWindow, 0);
                fill(constant1, 1.0f, timeWindow, update);
                sine(right, right, right, timeWindow, update + constant1);
                fill(constant2, 0.0f, timeWindow, update + constant1 + right);
                length = update;
            }
            case 2 -> {
                length = update == 16 || update == 32 ? update / 8 : 2;
                sine(length, 0, 2 * length, timeWindow, 0);
            }
            default -> {
                int constant1;
                int constant2;
                int left;
                switch (update) {
                    case 15 -> {
                        constant1 = 6;
                        constant2 = 7;
                        left = 2;
                    }
                    case 16, 32 -> {
                        constant1 = 7 * update / 16;
                        constant2 = constant1;
                        left = update / 8;
                    }
                    case 18 -> {
                        constant1 = 8;
                        constant2 = 8;
                        left = 2;
                    }
                    case 24 -> {
                        constant1 = 11;
                        constant2 = 11;
                        left = 2;
                    }
                    default -> {
                        constant1 = 14;
                        constant2 = 14;
                        left = 2;
                    }
                }
                fill(constant1, 0.0f, timeWindow, 0);
                sine(left, 0, left, timeWindow, constant1);
                fill(constant2, 1.0f, timeWindow, constant1 + left);
                sine(update, update, update, timeWindow, constant1 + left + constant2);
                length = update;
            }
        }
        frequencyWindow(length, frequencyWindow, 0);
        if (update == 15 && windowSequence == 2) {
            sine(length, 0, length, timeWindow, 2 * length);
            fill(1, 1.0f, timeWindow, 3 * length);
            sine(length, length, length, timeWindow, 3 * length + 1);
            fill(1, 0.0f, timeWindow, 4 * length + 1);
            frequencyWindow(3, frequencyWindow, 2 * length);
        }
    }
}
