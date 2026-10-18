package com.github.auties00.smalt.audio.aac.internal.sbr;

/**
 * Holds the hot element-wise loops of spectral band replication: the windowing and the batched
 * transforms of the QMF banks and the gain and noise application of the high frequency adjuster.
 *
 * <p>The QMF banks transform {@link #width()} time slots at once: every vector lane is one slot,
 * and a "row" is the value of one subband, sample or transform point across those slots. Rows of
 * the working buffers ({@code u}, {@code fr}, {@code fi}, the synthesis output) are
 * {@link #width()} floats long and addressed as {@code row * width()}; rows of the frame arrays
 * ({@code t}, {@code v}, the subband planes) have a stride given by the caller and are addressed
 * from a column, the first slot of the chunk.
 *
 * <p>Every kernel computes each output element with the same float operations in the same order
 * whatever the implementation, so the scalar and Vector API implementations return bit-identical
 * results.
 *
 * @implSpec Implementations evaluate each sum left to right exactly as the method descriptions
 * write it, with separate multiplications and additions (no fused multiply-add).
 */
interface SbrKernel {
    /**
     * Returns the number of slots a kernel transforms at once.
     *
     * @return the row length of the working buffers
     */
    int width();

    /**
     * Windows {@link #width()} consecutive analysis slots, in the reversed order of the standard's
     * {@code n}: for slot {@code l} and {@code m} below 64, {@code out[64 l + m]} is the sum, for
     * {@code j} from 0 to 4 in order, of {@code x[base + 32 l + m - 64 j] * w[64 j + m]}.
     *
     * @param x      the time samples, oldest first
     * @param base   the index of the first slot's first sample of the 64 latest
     * @param window the 320 window coefficients, reversed within each run of 64
     * @param out    receives the sums of the slots, 64 per slot
     */
    void analysisWindow(float[] x, int base, float[] window, float[] out);

    /**
     * Folds the windowed rows into the twiddled inputs of the two 16-point FFTs of the analysis
     * bank. With {@code u(n)} the row {@code 63 - n} of {@code u}, for {@code k} below 16, with
     * {@code a = u(2k) - u(63 - 2k)}, {@code b = u(31 - 2k) - u(32 + 2k)},
     * {@code a' = u(31 - 2k) + u(32 + 2k)} and {@code b' = u(2k) + u(63 - 2k)}, row {@code k} of
     * {@code fr}/{@code fi} is {@code (a c + b s, b c - a s)} and row {@code 16 + k} is
     * {@code (a' c + b' s, b' c - a' s)}.
     *
     * @param u   the 64 windowed rows, reversed
     * @param cos the 16 twiddle cosines
     * @param sin the 16 twiddle sines
     * @param fr  receives the real parts of the 32 rows
     * @param fi  receives the imaginary parts of the 32 rows
     */
    void foldRows(float[] u, float[] cos, float[] sin, float[] fr, float[] fi);

    /**
     * Runs one radix-2 decimation-in-frequency pass on a block of sixteen rows: for {@code j}
     * below 8, rows {@code base + j} and {@code base + j + 8} become their sum and their
     * difference times {@code exp(-i pi j / 8)}.
     *
     * @param fr   the real parts
     * @param fi   the imaginary parts
     * @param base the first row of the block
     * @param tw   the 8 cosines followed by the 8 sines of the twiddles
     */
    void radix2(float[] fr, float[] fi, int base, float[] tw);

    /**
     * Runs one radix-4 decimation-in-frequency pass on a block of thirty-two rows: for {@code j}
     * below 8, with {@code x_r} the row {@code base + j + 8 r}, the rows become
     * {@code y_m = (x0 + (-i)^m x1 + (-1)^m x2 + i^m x3) w^(m j)} for {@code m} from 0 to 3.
     *
     * @param fr   the real parts
     * @param fi   the imaginary parts
     * @param base the first row of the block
     * @param tw   the cosines of {@code w^j}, {@code w^(2j)} and {@code w^(3j)} then their sines,
     *             8 values each, with {@code w = exp(-2 pi i / 32)}
     */
    void radix4(float[] fr, float[] fi, int base, float[] tw);

    /**
     * Runs the last three decimation-in-frequency stages on {@code blocks} consecutive blocks of
     * eight rows, leaving each block's frequencies in bit-reversed order.
     *
     * @param fr     the real parts
     * @param fi     the imaginary parts
     * @param base   the first row
     * @param blocks the number of blocks
     */
    void tail8(float[] fr, float[] fi, int base, int blocks);

    /**
     * Untwiddles the two 16-point FFTs of the analysis bank into the 32 complex subbands. For
     * {@code j} below 16, {@code (zr, zi)} the output of frequency {@code j} (row
     * {@code positions[j]}) of a transform and {@code (c, s)} the twiddle of {@code j}, the
     * transform's outputs are {@code o[2j] = zr c + zi s} and {@code o[31 - 2j] = zr s - zi c}.
     * With {@code x} the first transform's output and {@code y} the second's, negated for odd
     * {@code k}, subband {@code k} is {@code (x r - y q, y r + x q)} for the rotation
     * {@code (r, q)} of {@code k}.
     *
     * @param fr        the real parts of the FFT outputs, the second transform from row 16
     * @param fi        the imaginary parts
     * @param positions the row of each frequency
     * @param cos       the 16 twiddle cosines
     * @param sin       the 16 twiddle sines
     * @param rotRe     the 32 rotation cosines
     * @param rotIm     the 32 rotation sines
     * @param re        receives the real parts of the 32 subbands
     * @param im        receives the imaginary parts
     */
    void analysisOutput(float[] fr, float[] fi, int[] positions, float[] cos, float[] sin, float[] rotRe, float[] rotIm,
                        float[] re, float[] im);

    /**
     * Folds the subbands of the synthesis bank into the twiddled inputs of its two
     * {@code bands / 2}-point FFTs: for {@code k} below {@code bands / 2}, with {@code a = re[2k]},
     * {@code b = re[bands - 1 - 2k]}, {@code a' = im[bands - 1 - 2k]} and {@code b' = im[2k]}, row
     * {@code k} of {@code fr}/{@code fi} is {@code (a c + b s, b c - a s)} and row
     * {@code bands / 2 + k} is {@code (a' c + b' s, b' c - a' s)}.
     *
     * @param re    the real parts of the subbands
     * @param im    the imaginary parts
     * @param bands the number of subbands, 64 or 32
     * @param cos   the {@code bands / 2} twiddle cosines
     * @param sin   the {@code bands / 2} twiddle sines
     * @param fr    receives the real parts of the {@code bands} rows
     * @param fi    receives the imaginary parts
     */
    void synthesisInput(float[] re, float[] im, int bands, float[] cos, float[] sin, float[] fr, float[] fi);

    /**
     * Untwiddles the two FFTs of the synthesis bank into the {@code 2 bands} rows of the vector
     * {@code V}. Untwiddled like {@link #analysisOutput}, the first transform gives {@code P} and
     * the second {@code Q}; for {@code m} below {@code bands / 2}, {@code V(2m) = Q(2m) - P(2m)},
     * {@code V(2m + 1) = -Q(2m + 1) - P(2m + 1)}, {@code V(2 bands - 1 - 2m) = P(2m) + Q(2m)} and
     * {@code V(2 bands - 2 - 2m) = P(2m + 1) - Q(2m + 1)}.
     *
     * @param fr        the real parts of the FFT outputs, the second transform from row {@code bands / 2}
     * @param fi        the imaginary parts
     * @param positions the row of each frequency
     * @param cos       the {@code bands / 2} twiddle cosines
     * @param sin       the {@code bands / 2} twiddle sines
     * @param bands     the number of subbands, 64 or 32
     * @param v         receives the rows of {@code V}
     */
    void synthesisOutput(float[] fr, float[] fi, int[] positions, float[] cos, float[] sin, int bands, float[] v);

    /**
     * Windows consecutive synthesis slots: for slot {@code s} below {@code slots}, {@code
     * out[offset + N s + j]} is the sum, for {@code i} from 0 to 4 and starting from zero, of
     * {@code v[at + 2 N s - 4 N i + j] * w[2 N i + j]} and then
     * {@code v[at + 2 N s - 4 N i - N + j] * w[2 N i + N + j]}, for {@code j} below {@code N},
     * where {@code N} is the number of subbands.
     *
     * @param v      the vectors {@code V} of the slots and the slots before them, {@code 2 N}
     *               values each, the newest last
     * @param at     the index of the first slot's {@code V(0)}
     * @param window the {@code 10 N} window coefficients
     * @param bands  the number of subbands {@code N}, 64 or 32
     * @param out    receives the samples
     * @param offset the index of the first sample
     * @param slots  the number of slots
     */
    void synthesisWindow(float[] v, int at, float[] window, int bands, float[] out, int offset, int slots);

    /**
     * Transposes a block of values: {@code dst[dstOffset + j dstStride + i] =
     * src[srcOffset + i srcStride + j]} for {@code i} below {@code rows} and {@code j} below
     * {@code cols}.
     *
     * @param src       the source
     * @param srcOffset the index of the block's first value in the source
     * @param srcStride the distance between the source rows
     * @param dst       the destination
     * @param dstOffset the index of the block's first value in the destination
     * @param dstStride the distance between the destination rows
     * @param rows      the number of source rows, a multiple of {@link #width()}
     * @param cols      the number of source columns, a multiple of {@link #width()}
     */
    void transpose(float[] src, int srcOffset, int srcStride, float[] dst, int dstOffset, int dstStride, int rows,
                   int cols);

    /**
     * Accumulates the covariance of the low band over a run of slots, for each subband from 1 to
     * {@code bands - 1}: with {@code (ar, ai)}, {@code (br, bi)} and {@code (cr, ci)} the samples of
     * the subband in slots {@code n + 2}, {@code n + 1} and {@code n}, summed in double precision
     * and in slot order over {@code n} below {@code count}, {@code out[b]} is the sum of
     * {@code ar br + ai bi}, {@code out[32 + b]} of {@code ai br - ar bi}, {@code out[64 + b]} of
     * {@code ar cr + ai ci}, {@code out[96 + b]} of {@code ai cr - ar ci}, {@code out[128 + b]} of
     * {@code br br + bi bi}, {@code out[160 + b]} of {@code br cr + bi ci}, {@code out[192 + b]} of
     * {@code bi cr - br ci} and {@code out[224 + b]} of {@code cr cr + ci ci}.
     *
     * @param re    the real parts of the low band, 32 subbands per slot
     * @param im    the imaginary parts
     * @param bands the first subband not summed
     * @param count the number of slots summed
     * @param out   receives the eight sums of each subband, 32 values per sum
     */
    void covariance(float[] re, float[] im, int bands, int count, double[] out);

    /**
     * Applies gains and noise to the high subbands of one slot: {@code re[offset + k] =
     * re[offset + k] * gain[k] + noise[k] * noiseRe[noiseOffset + k]}, and the same for the
     * imaginary parts with {@code noiseIm}, for {@code k} below {@code count}.
     *
     * @param re          the real parts, adjusted in place
     * @param im          the imaginary parts, adjusted in place
     * @param offset      the index of the first subband
     * @param gain        the gain of each subband
     * @param noise       the noise level of each subband
     * @param noiseRe     the real parts of the noise table
     * @param noiseIm     the imaginary parts of the noise table
     * @param noiseOffset the index of the first subband's noise sample
     * @param count       the number of subbands
     */
    void applyGains(float[] re, float[] im, int offset, float[] gain, float[] noise, float[] noiseRe, float[] noiseIm,
                    int noiseOffset, int count);
}
