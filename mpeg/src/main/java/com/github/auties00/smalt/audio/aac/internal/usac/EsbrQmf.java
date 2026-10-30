/* Ports the ISO/IEC 23003-3:2019 reference software (ct_polyphase.c: interpolPrototype, InitSbrAnaFilterbank,
   CalculateSbrAnaFilterbank, InitSbrSynFilterbank, CalculateSbrSynFilterbank). Copyright Coding Technologies
   and ISO/IEC 2002; ISO/IEC reference software licence (use in products conforming to ISO/IEC 23003).
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

/**
 * Holds the complex QMF banks of enhanced spectral band replication: an analysis bank of 16, 24 or 32 subbands
 * at the core rate and a synthesis bank of 64 subbands at the output rate.
 *
 * <p>The banks themselves are stateless and shared by every channel; each channel keeps its own delay lines,
 * {@code float[10 * bands]} for the analysis and {@code double[1280]} for the synthesis. Instances are immutable.
 */
final class EsbrQmf {
    /**
     * The single precision value of pi the reference decoder builds its QMF banks with.
     */
    private static final float PI = 3.14159265358979f;

    /**
     * The number of analysis subbands.
     */
    final int bands;

    private final double[] analysisWindow;
    private final double[] analysisReal;
    private final double[] analysisImag;
    private final double[] synthesisReal;
    private final double[] synthesisImag;

    /**
     * Constructs the banks.
     *
     * @param bands the number of analysis subbands, 16, 24 or 32
     */
    EsbrQmf(int bands) {
        this.bands = bands;
        var length = 10 * bands;
        this.analysisWindow = new double[length];
        var gain = 2.0;
        if (bands == 24) {
            var interpolated = interpolate(EsbrTables.QMF_WINDOW, 640, 64, 24, 0);
            System.arraycopy(interpolated, 0, analysisWindow, 0, length);
            gain *= 4.0 / 3.0;
        } else {
            var stride = bands == 16 ? 4 : 2;
            if (bands == 16) {
                gain *= 2.0;
            }
            for (var i = 0; i < length; i++) {
                analysisWindow[i] = EsbrTables.QMF_WINDOW[stride * i];
            }
        }
        var twiddle = 0.5 * (bands / 32.0);
        this.analysisReal = new double[bands * 2 * bands];
        this.analysisImag = new double[bands * 2 * bands];
        for (var k = 0; k < bands; k++) {
            for (var l = 0; l < 2 * bands; l++) {
                var angle = (PI / (2 * bands)) * (k + 0.5) * (2 * l - twiddle);
                analysisReal[k * 2 * bands + l] = gain * StrictMath.cos(angle);
                analysisImag[k * 2 * bands + l] = gain * StrictMath.sin(angle);
            }
        }
        this.synthesisReal = new double[128 * 64];
        this.synthesisImag = new double[128 * 64];
        var synthesisGain = 1.0 / 64.0;
        for (var l = 0; l < 128; l++) {
            for (var k = 0; k < 64; k++) {
                var angle = PI / 128.0 * (k + 0.5) * (2 * l - 255);
                synthesisReal[l * 64 + k] = synthesisGain * StrictMath.cos(angle);
                synthesisImag[l * 64 + k] = synthesisGain * StrictMath.sin(angle);
            }
        }
    }

    /**
     * Linearly interpolates a prototype filter to another number of channels.
     *
     * @param prototype the original prototype
     * @param length the length of the original prototype
     * @param channels the number of channels of the original prototype
     * @param target the number of channels of the interpolated prototype
     * @param phase the phase offset
     * @return the interpolated prototype, {@code length * target / channels} taps
     */
    static double[] interpolate(double[] prototype, int length, int channels, int target, double phase) {
        var ratio = (double) channels / target;
        var size = (int) (length / ratio + 0.5f);
        var out = new double[size];
        for (var i = 0; i < size; i++) {
            var n = i * ratio + phase;
            var whole = (int) n;
            out[i] = (n - whole) * (prototype[whole + 1] - prototype[whole]) + prototype[whole];
        }
        return out;
    }

    /**
     * Splits one slot of time samples into the analysis subbands.
     *
     * @param state the channel's analysis delay line, {@code 10 * bands} samples
     * @param input the time samples
     * @param offset the index of the oldest of the {@code 10 * bands} samples the slot reads; the newest
     *               {@code bands} of them are the slot's new samples
     * @param real receives the real parts
     * @param imag receives the imaginary parts
     * @param scratch a scratch array of at least {@code 2 * bands} doubles
     */
    void analyze(float[] state, float[] input, int offset, float[] real, float[] imag, double[] scratch) {
        var stop = 10 * bands;
        System.arraycopy(state, 0, state, bands, stop - bands);
        for (var i = bands - 1; i >= 0; i--) {
            state[i] = input[offset + stop - 1 - i];
        }
        var width = 2 * bands;
        for (var i = 0; i < width; i++) {
            var accu = 0.0;
            for (var j = 0; j < 5; j++) {
                var index = i + j * width;
                accu = accu + state[index] * analysisWindow[index];
            }
            scratch[i] = accu;
        }
        for (var k = 0; k < bands; k++) {
            var re = 0.0;
            var im = 0.0;
            var row = k * width;
            for (var l = 0; l < width; l++) {
                re = re + scratch[l] * analysisReal[row + l];
                im = im + scratch[l] * analysisImag[row + l];
            }
            real[k] = (float) re;
            imag[k] = (float) im;
        }
    }

    /**
     * Merges one slot of 64 subbands into 64 time samples.
     *
     * @param state the channel's synthesis delay line, 1280 values
     * @param real the real parts
     * @param imag the imaginary parts
     * @param output receives the samples
     * @param offset the index of the first sample
     */
    void synthesize(double[] state, float[] real, float[] imag, float[] output, int offset) {
        System.arraycopy(state, 0, state, 128, 1280 - 128);
        for (var l = 0; l < 128; l++) {
            var accu = 0.0;
            var row = l * 64;
            for (var k = 0; k < 64; k++) {
                accu += real[k] * synthesisReal[row + k];
                accu -= imag[k] * synthesisImag[row + k];
            }
            state[l] = accu;
        }
        var window = EsbrTables.QMF_WINDOW;
        for (var i = 0; i < 64; i++) {
            var accu = 0.0;
            for (var j = 0; j < 10; j++) {
                var u = (j & 1) == 0 ? state[256 * (j >> 1) + i] : state[256 * (j >> 1) + 192 + i];
                accu = accu + u * window[64 * j + i];
            }
            output[offset + i] = (float) accu;
        }
    }
}
