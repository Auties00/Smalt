package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Implements the element-wise DSP loops of the AC-3 decoder and encoder: the complex twiddle
 * products and the radix-2 butterfly stages of the transforms, the windowed interleaving of the
 * inverse transform's output, the overlap-add of the synthesis filter bank, and the gains and
 * weighted sums of the downmix.
 *
 * <p>Two implementations exist: {@link ScalarAc3Kernel}, the reference, and a Vector API
 * implementation selected by {@link Ac3Kernels#INSTANCE} when SIMD is enabled. Every kernel is
 * element-wise and evaluates the same float operations in the same order in both, without fused
 * multiply-adds, so their results are identical.
 *
 * @implSpec
 * Implementations must be stateless and thread-safe, must accept any array offsets that keep the
 * accessed ranges in bounds, must not allocate per call, and must produce results identical to
 * {@link ScalarAc3Kernel}.
 */
public interface Ac3Kernel {
    /**
     * Multiplies complex values by complex factors in place: for {@code i < count},
     * {@code (re[i], im[i])} becomes {@code (re[i] * cos[i] - im[i] * sin[i],
     * im[i] * cos[i] + re[i] * sin[i])}.
     *
     * @param re the real parts, in place
     * @param im the imaginary parts, in place
     * @param cos the real parts of the factors
     * @param sin the imaginary parts of the factors
     * @param count the number of values
     */
    void twiddle(float[] re, float[] im, float[] cos, float[] sin, int count);

    /**
     * Runs one radix-2 decimation-in-time stage of an in-place complex FFT: for every group start
     * {@code s}, a multiple of {@code 2 * half} below {@code length}, and every {@code k < half},
     * with {@code a = s + k} and {@code b = a + half}, computes
     * {@code tr = re[b] * cos[k] - im[b] * sin[k]} and {@code ti = im[b] * cos[k] + re[b] * sin[k]},
     * then stores {@code re[a] - tr} and {@code im[a] - ti} at {@code b} and {@code re[a] + tr} and
     * {@code im[a] + ti} at {@code a}.
     *
     * @param re the real parts, in place
     * @param im the imaginary parts, in place
     * @param cos the real parts of the stage's twiddle factors, {@code half} of them
     * @param sin the imaginary parts of the stage's twiddle factors
     * @param half the butterfly span, a power of two
     * @param length the transform length, a multiple of {@code 2 * half}
     */
    void butterflies(float[] re, float[] im, float[] cos, float[] sin, int half, int length);

    /**
     * Runs two consecutive radix-2 decimation-in-time stages of an in-place complex FFT, of spans
     * {@code half} and {@code 2 * half}: the same results as
     * {@link #butterflies(float[], float[], float[], float[], int, int)} with {@code cos1},
     * {@code sin1} and {@code half}, then with {@code cos2}, {@code sin2} and {@code 2 * half}.
     *
     * @param re the real parts, in place
     * @param im the imaginary parts, in place
     * @param cos1 the real parts of the first stage's twiddle factors, {@code half} of them
     * @param sin1 the imaginary parts of the first stage's twiddle factors
     * @param cos2 the real parts of the second stage's twiddle factors, {@code 2 * half} of them
     * @param sin2 the imaginary parts of the second stage's twiddle factors
     * @param half the span of the first stage, a power of two
     * @param length the transform length, a multiple of {@code 4 * half}
     */
    void butterflyPair(float[] re, float[] im, float[] cos1, float[] sin1, float[] cos2, float[] sin2, int half,
                       int length);

    /**
     * Runs the first three radix-2 stages, of spans 1, 2 and 4, of an in-place complex FFT whose
     * input is already in bit-reversed order: the stage of span {@code h} runs
     * {@link #butterflies(float[], float[], float[], float[], int, int)} with the twiddle factors
     * {@code cos[8s + k]} and {@code sin[8s + k]} for {@code k < h}, where {@code s} is 0, 1 and 2
     * for the spans 1, 2 and 4.
     *
     * @implSpec The tables hold each stage's factors repeated over 8 entries: {@code cos[8s + j]}
     *           equals {@code cos[8s + j % h]}, so that a vector implementation can load the
     *           factors of 8 consecutive values at once.
     *
     * @param re the real parts, in place
     * @param im the imaginary parts, in place
     * @param cos the real parts of the twiddle factors of the three stages, 24 values
     * @param sin the imaginary parts of the twiddle factors of the three stages, 24 values
     * @param length the transform length, a multiple of 8
     */
    void firstStages(float[] re, float[] im, float[] cos, float[] sin, int length);

    /**
     * Interleaves a forward and a reversed run of values, each multiplied by a window: for
     * {@code n < pairs}, stores {@code forward[forwardOffset + n] * window[windowOffset + 2n]} at
     * {@code out[outOffset + 2n]} and {@code backward[backwardEnd - n] * window[windowOffset + 2n + 1]}
     * at {@code out[outOffset + 2n + 1]}.
     *
     * @param forward the run read forward
     * @param forwardOffset the index of the first forward value
     * @param backward the run read backward
     * @param backwardEnd the index of the first backward value, the highest one read
     * @param window the window, in output order
     * @param windowOffset the index of the window value of the first output
     * @param out receives the {@code 2 * pairs} products
     * @param outOffset the index of the first output
     * @param pairs the number of output pairs
     */
    void interleave(float[] forward, int forwardOffset, float[] backward, int backwardEnd, float[] window,
                    int windowOffset, float[] out, int outOffset, int pairs);

    /**
     * Overlaps a windowed block with the delayed half of the previous one: for {@code n < half},
     * stores {@code block[n] + delay[n]} at {@code out[offset + n]} and then {@code block[half + n]}
     * at {@code delay[n]}.
     *
     * @param block the windowed block of {@code 2 * half} samples
     * @param delay the delayed half, updated in place
     * @param out receives the output samples
     * @param offset the index of the first output sample
     * @param half the number of output samples
     */
    void overlap(float[] block, float[] delay, float[] out, int offset, int half);

    /**
     * Scales a run of samples: stores {@code in[inOffset + i] * gain} at {@code out[outOffset + i]}
     * for {@code i < count}; {@code in} and {@code out} may be the same run.
     *
     * @param in the samples
     * @param inOffset the index of the first sample
     * @param gain the linear factor every sample is multiplied by
     * @param out receives the scaled samples
     * @param outOffset the index of the first scaled sample
     * @param count the number of samples
     */
    void scale(float[] in, int inOffset, float gain, float[] out, int outOffset, int count);

    /**
     * Adds a scaled run of samples: stores {@code out[outOffset + i] + in[inOffset + i] * gain} at
     * {@code out[outOffset + i]} for {@code i < count}.
     *
     * @param in the samples
     * @param inOffset the index of the first sample
     * @param gain the linear factor every sample is multiplied by
     * @param out the accumulated samples, updated in place
     * @param outOffset the index of the first accumulated sample
     * @param count the number of samples
     */
    void accumulate(float[] in, int inOffset, float gain, float[] out, int outOffset, int count);
}
