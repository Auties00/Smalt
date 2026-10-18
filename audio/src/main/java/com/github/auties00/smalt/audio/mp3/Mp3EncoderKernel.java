package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Runs the hot loops of the MP3 encoder: the analysis filterbank windowing and matrixing, the
 * forward MDCT and the quantization of a spectrum.
 *
 * <p>Implementations must produce results bit-identical to {@link ScalarMp3EncoderKernel}, computing
 * every output element with the same operations in the same order, and keep no state from one call
 * to the next.
 */
interface Mp3EncoderKernel {
    /**
     * Returns a new kernel for one encoder: {@link VectorMp3EncoderKernel} when {@link Simd#ENABLED},
     * {@link ScalarMp3EncoderKernel} otherwise.
     *
     * @return the kernel
     */
    static Mp3EncoderKernel create() {
        return Simd.ENABLED ? VectorMp3EncoderKernel.create() : new ScalarMp3EncoderKernel();
    }

    /**
     * Computes the 64 windowed partial sums of one filterbank slot, in reverse order.
     *
     * <p>Element {@code q} of {@code sums} is
     * {@code sum_{j=0}^{7} window[64 j + q] * input[start + 448 - 64 j + q]}, accumulated from
     * {@code j = 0} up.
     *
     * @param input the input history and granule
     * @param start the offset of the slot, a multiple of 32
     * @param window the rearranged analysis window of {@link Mp3AnalysisFilterbank#WINDOW}
     * @param sums receives 64 sums
     */
    void window(float[] input, int start, float[] window, float[] sums);

    /**
     * Computes the 32 subband samples of one slot from the folded window sums.
     *
     * <p>Element {@code k} of {@code output} is {@code sum_{n=0}^{31} folded[n] * matrix[32 n + k]},
     * accumulated from {@code n = 0} up.
     *
     * @param folded the 32 folded sums
     * @param matrix the folded matrixing table of {@link Mp3AnalysisFilterbank#MATRIX}
     * @param output receives 32 subband samples
     */
    void matrix(float[] folded, float[] matrix, float[] output);

    /**
     * Applies an 18-output forward MDCT to the 36 windowed samples of every subband.
     *
     * <p>Element {@code 18 sb + m} of {@code output} is
     * {@code sum_{i=0}^{35} input[36 sb + i] * table[18 i + m]}, accumulated from {@code i = 0} up,
     * for the 32 subbands {@code sb}.
     *
     * @param input 32 blocks of 36 windowed samples
     * @param table the 36 by 18 cosine table, row by row
     * @param output receives 32 blocks of 18 coefficients
     */
    void mdct(float[] input, float[] table, float[] output);

    /**
     * Quantizes a run of magnitudes raised to the power 3/4 and returns the largest result.
     *
     * <p>Element {@code i} of {@code output} becomes {@code (int) (pow34[i] * scale + 0.4054f)}.
     *
     * @param pow34 the magnitudes raised to the power 3/4
     * @param from the first index
     * @param to the end index
     * @param scale the quantizer step raised to the power -3/4
     * @param output receives the quantized magnitudes
     * @return the largest quantized magnitude of the run, {@code 0} for an empty run
     */
    int quantize(float[] pow34, int from, int to, float scale, int[] output);
}
