package com.github.auties00.smalt.audio.mp3;

/**
 * Splits one channel into 32 subbands with the polyphase analysis filterbank of ISO/IEC 11172-3
 * Annex C.1.3.
 *
 * <p>Every 32 input samples produce one sample per subband: the last 512 input samples are
 * weighted by the analysis window {@code C[i]} and folded into 64 partial sums {@code Y[i]}, and
 * the matrixing {@code S[k] = sum_i cos((2k + 1)(i - 16) pi / 64) Y[i]} gives the subband samples.
 * One call processes a granule of 576 input samples into 18 samples per subband.
 *
 * @implNote This implementation folds the 64-point matrixing into a 32-point one: the cosine is
 * even in {@code i - 16} and changes sign at {@code i - 16 = 32}, so
 * {@code S[k] = sum_{n=0}^{31} A[n] cos((2k + 1) n pi / 64)} with {@code A[0] = Y[16]},
 * {@code A[n] = Y[16 + n] + Y[16 - n]} for {@code n <= 16} and {@code A[n] = Y[16 + n] - Y[80 - n]}
 * above ({@code Y[48]} has a zero weight). The windowing and the matrixing run through a
 * {@link Mp3EncoderKernel}.
 */
final class Mp3AnalysisFilterbank {
    /**
     * The number of past input samples the window needs beyond the current granule.
     */
    private static final int HISTORY = 480;

    /**
     * The analysis window rearranged for {@link Mp3EncoderKernel#window}: entry {@code j * 64 + q}
     * holds {@code C[63 - q + 64 j]}.
     */
    static final float[] WINDOW = new float[512];

    /**
     * The folded matrixing table: entry {@code n * 32 + k} holds
     * {@code cos((2k + 1) n pi / 64)}.
     */
    static final float[] MATRIX = new float[32 * 32];

    static {
        for (var j = 0; j < 8; j++) {
            for (var q = 0; q < 64; q++) {
                WINDOW[j * 64 + q] = (float) (Mp3EncoderTables.ANALYSIS_WINDOW[63 - q + 64 * j] / 2097152.0);
            }
        }
        for (var n = 0; n < 32; n++) {
            for (var k = 0; k < 32; k++) {
                MATRIX[n * 32 + k] = (float) StrictMath.cos((2 * k + 1) * n * Math.PI / 64);
            }
        }
    }

    /**
     * The DSP kernel.
     */
    private final Mp3EncoderKernel kernel;

    /**
     * The input: {@value #HISTORY} samples of history followed by the current granule.
     */
    private final float[] buffer = new float[HISTORY + 576];

    /**
     * The window sums of one slot, in reverse order ({@code Y[63 - q]} at index {@code q}).
     */
    private final float[] sums = new float[64];

    /**
     * The folded matrixing input of one slot.
     */
    private final float[] folded = new float[32];

    /**
     * The subband samples of one slot.
     */
    private final float[] slot = new float[32];

    /**
     * Creates a filterbank with silent history.
     *
     * @param kernel the DSP kernel
     */
    Mp3AnalysisFilterbank(Mp3EncoderKernel kernel) {
        this.kernel = kernel;
    }

    /**
     * Filters one granule of input.
     *
     * @param input  the samples
     * @param offset the index of the granule's first sample
     * @param output receives the subband samples, subband {@code k}, time slot {@code t} at
     *               index {@code k * 18 + t}
     */
    void process(float[] input, int offset, float[] output) {
        var buf = buffer;
        System.arraycopy(buf, 576, buf, 0, HISTORY);
        System.arraycopy(input, offset, buf, HISTORY, 576);
        var y = sums;
        var a = folded;
        var s = slot;
        for (var t = 0; t < 18; t++) {
            kernel.window(buf, 32 * t, WINDOW, y);
            a[0] = y[47];
            for (var n = 1; n <= 16; n++) {
                a[n] = y[47 - n] + y[47 + n];
            }
            for (var n = 17; n < 32; n++) {
                a[n] = y[47 - n] - y[n - 17];
            }
            kernel.matrix(a, MATRIX, s);
            for (var k = 0; k < 32; k++) {
                output[k * 18 + t] = s[k];
            }
        }
    }

    /**
     * Forgets the input history.
     */
    void reset() {
        java.util.Arrays.fill(buffer, 0);
    }
}
