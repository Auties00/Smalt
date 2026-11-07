package com.github.auties00.smalt.audio.aac.internal.sbr;

/**
 * Splits a channel of time samples into 64 complex QMF subbands, one slot per 64 samples, with the analysis
 * counterpart of the 64-band synthesis filterbank of ISO/IEC 14496-3, as the encoder of spectral band
 * replication analyzes its full-rate input.
 *
 * <p>For each slot the 640 latest samples {@code x(n)}, newest first, are windowed into
 * {@code u(n) = sum over j of x(n + 128 j) c(n + 128 j)} for {@code n} below 128, and the subbands are
 * {@code W(k) = sum u(n) exp(i pi (k + 1/2)(2 n - 1) / 128)}. Samples are scaled from the module's
 * {@code [-1, 1]} range to the 16-bit range the standard's energies refer to, so that the 64-band synthesis
 * reconstructs the input and the lowest 32 subbands equal those the 32-band analysis bank of a decoder finds in
 * the input downsampled by two. Instances are not thread-safe.
 */
final class EncoderQmfAnalysis {
    /**
     * The window {@code 32768 c(n)}.
     */
    private static final float[] WINDOW = new float[640];

    /**
     * The modulation cosines {@code cos(pi (k + 1/2)(2 n - 1) / 128)} at {@code 128 k + n}.
     */
    private static final float[] COS = new float[64 * 128];

    /**
     * The modulation sines {@code sin(pi (k + 1/2)(2 n - 1) / 128)} at {@code 128 k + n}.
     */
    private static final float[] SIN = new float[64 * 128];

    static {
        for (var n = 0; n < 640; n++) {
            WINDOW[n] = (float) (32768 * SbrTables.QMF_WINDOW[n]);
        }
        for (var k = 0; k < 64; k++) {
            for (var n = 0; n < 128; n++) {
                var angle = Math.PI * (k + 0.5) * (2 * n - 1) / 128;
                COS[128 * k + n] = (float) StrictMath.cos(angle);
                SIN[128 * k + n] = (float) StrictMath.sin(angle);
            }
        }
    }

    /**
     * The latest 640 samples, oldest first.
     */
    private final float[] history = new float[640];

    /**
     * The windowed sums {@code u(n)}.
     */
    private final float[] sums = new float[128];

    /**
     * Creates a bank with a silent history.
     */
    EncoderQmfAnalysis() {
    }

    /**
     * Analyzes one slot of 64 new samples.
     *
     * @param input the samples, in {@code [-1, 1]}
     * @param offset the index of the first new sample
     * @param re the destination real parts, 64 subbands
     * @param im the destination imaginary parts, 64 subbands
     * @param out the index of subband 0 in {@code re} and {@code im}
     */
    void analyze(float[] input, int offset, float[] re, float[] im, int out) {
        System.arraycopy(history, 64, history, 0, 576);
        System.arraycopy(input, offset, history, 576, 64);
        for (var n = 0; n < 128; n++) {
            var sum = 0.0f;
            for (var j = 0; j < 5; j++) {
                var index = n + 128 * j;
                sum += history[639 - index] * WINDOW[index];
            }
            sums[n] = sum;
        }
        for (var k = 0; k < 64; k++) {
            var row = 128 * k;
            var real = 0.0f;
            var imaginary = 0.0f;
            for (var n = 0; n < 128; n++) {
                var u = sums[n];
                real += u * COS[row + n];
                imaginary += u * SIN[row + n];
            }
            re[out + k] = real;
            im[out + k] = imaginary;
        }
    }

    /**
     * Clears the history.
     */
    void reset() {
        java.util.Arrays.fill(history, 0);
    }
}
