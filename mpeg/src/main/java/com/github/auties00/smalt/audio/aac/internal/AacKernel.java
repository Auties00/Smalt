package com.github.auties00.smalt.audio.aac.internal;

/**
 * Implements the hot DSP loops of the AAC decoder and encoder.
 *
 * <p>Two implementations exist: {@link ScalarAacKernel}, the reference, and a Vector API
 * implementation selected by {@link AacKernels#INSTANCE} when SIMD is enabled. Element-wise
 * kernels produce identical results in both, since the vector loops evaluate every expression
 * with the same operations in the same order and neither uses fused multiply-adds; the
 * reductions ({@link #distortion}, {@link #energy} and {@link #dot}) sum in a different order and
 * agree up to float rounding.
 *
 * @implSpec
 * Implementations must be stateless and thread-safe, must accept any array offsets that keep
 * the accessed ranges in bounds, and must not allocate per call.
 */
public interface AacKernel {
    /**
     * Applies one radix-2^2 decimation-in-frequency pass of a complex FFT to every block.
     *
     * <p>The pass combines two radix-2 stages: for blocks of {@code 4 * quarter} points and
     * {@code j < quarter}, with {@code x0..x3} the points {@code j + k * quarter} of a block and
     * {@code W = exp(-2 pi i / (4 * quarter))}, it computes {@code a = x0 + x2},
     * {@code b = x1 + x3}, {@code c = x0 - x2}, {@code d = -i (x1 - x3)} and stores
     * {@code a + b}, {@code (a - b) W^2j}, {@code (c + d) W^j} and {@code (c - d) W^3j} back at
     * the positions of {@code x0}, {@code x1}, {@code x2} and {@code x3}.
     *
     * @param re        the real parts, in place
     * @param im        the imaginary parts, in place
     * @param n         the transform length, a multiple of {@code 4 * quarter}
     * @param quarter   the quarter block length
     * @param twiddles  the twiddle table: at {@code offset} the real and imaginary parts of
     *                  {@code W^j}, then of {@code W^2j}, then of {@code W^3j}, each
     *                  {@code quarter} long
     * @param offset    the start of this pass's twiddles
     */
    void fftRadix4(float[] re, float[] im, int n, int quarter, float[] twiddles, int offset);

    /**
     * Applies one radix-2 decimation-in-frequency stage of a complex FFT to every block.
     *
     * <p>For blocks of {@code 2 * half} points and {@code j < half}, with {@code a} and
     * {@code c} the points {@code j} and {@code j + half} and
     * {@code W = exp(-2 pi i / (2 * half))}, it stores {@code a + c} and {@code (a - c) W^j}.
     *
     * @param re       the real parts, in place
     * @param im       the imaginary parts, in place
     * @param n        the transform length, a multiple of {@code 2 * half}
     * @param half     the half block length
     * @param twiddles the twiddle table: at {@code offset} the real then the imaginary parts
     *                 of {@code W^j}, each {@code half} long
     * @param offset   the start of this stage's twiddles
     */
    void fftRadix2(float[] re, float[] im, int n, int half, float[] twiddles, int offset);

    /**
     * Inverse-quantizes a run of spectral values:
     * {@code out[i] = sign(quant[i]) * |quant[i]|^(4/3) * gain}.
     *
     * @param quant the quantized values, each of magnitude below
     *              {@link Dequantizer#POW43_SIZE}; the implementation may overwrite them with
     *              their magnitudes
     * @param out   the spectral values
     * @param from  the first index
     * @param to    the index after the last
     * @param gain  the scalefactor gain
     */
    void dequantize(int[] quant, float[] out, int from, int to, float gain);

    /**
     * Unfolds, windows and overlap-adds the DCT-IV output of one long window.
     *
     * <p>With {@code m} the number of spectral lines, {@code h = m / 2} and {@code y} the
     * {@code 2m}-sample inverse MDCT output implied by the DCT-IV output {@code u} (so
     * {@code y[n] = u[h + n]}, {@code -u[m + h - 1 - n]}, {@code -u[m + h - 1 - n]} and
     * {@code -u[n - m - h]} over its four quarters), it writes
     * {@code out[outOffset + n] = overlap[n] + window[n] * y[n]} for {@code n < m} and then
     * replaces {@code overlap[n]} by {@code window[m + n] * y[m + n]}.
     *
     * @param u         the DCT-IV output, {@code m} values
     * @param m         the number of spectral lines, a multiple of 2
     * @param window    the composite window, {@code 2m} values
     * @param overlap   the second half of the previous windowed output, in place
     * @param out       the output samples
     * @param outOffset the index of the first output sample
     */
    void overlapLong(float[] u, int m, float[] window, float[] overlap, float[] out, int outOffset);

    /**
     * Computes the magnitudes of spectral values and their 3/4 powers:
     * {@code magnitude[i] = |x[i]|} and {@code power34[i] = |x[i]|^(3/4)}.
     *
     * @param x         the spectral values
     * @param magnitude the magnitudes
     * @param power34   the 3/4 powers
     * @param from      the first index
     * @param to        the index after the last
     */
    void powerLaw(float[] x, float[] magnitude, float[] power34, int from, int to);

    /**
     * Quantizes a run of 3/4-powered magnitudes:
     * {@code quant[i] = (int) (power34[i] * inverseStep + ROUNDING)} with
     * {@link Quantization#ROUNDING}, and returns the largest result.
     *
     * @param power34     the 3/4 powers of the magnitudes
     * @param quant       the quantized magnitudes
     * @param from        the first index
     * @param to          the index after the last
     * @param inverseStep the quantizer gain {@code 2^(-3/16 (sf - 100))}
     * @return the largest quantized magnitude, 0 for an empty run
     */
    int quantize(float[] power34, int[] quant, int from, int to, float inverseStep);

    /**
     * Returns the squared error of quantizing a run of magnitudes at a scalefactor:
     * the sum of {@code (magnitude[i] - q^(4/3) * step)^2} with
     * {@code q = (int) (power34[i] * inverseStep + ROUNDING)}, clamped below
     * {@link Dequantizer#POW43_SIZE}.
     *
     * @param magnitude   the magnitudes
     * @param power34     their 3/4 powers
     * @param scratch     a work array whose elements in {@code [from, to)} are overwritten
     * @param from        the first index
     * @param to          the index after the last
     * @param inverseStep the quantizer gain {@code 2^(-3/16 (sf - 100))}
     * @param step        the inverse quantizer gain {@code 2^((sf - 100) / 4)}
     * @return the distortion
     */
    float distortion(float[] magnitude, float[] power34, int[] scratch, int from, int to, float inverseStep, float step);

    /**
     * Returns the sum of squares of a run of values.
     *
     * @param x    the values
     * @param from the first index
     * @param to   the index after the last
     * @return the energy
     */
    float energy(float[] x, int from, int to);

    /**
     * Windows {@code 2m} input samples and folds them into the {@code m} DCT-IV inputs of a
     * forward MDCT.
     *
     * <p>With {@code h = m / 2} and {@code z[n] = x[offset + n] * window[n]}, it writes
     * {@code folded[k] = -z[3h - 1 - k] - z[3h + k]} for {@code k < h} and
     * {@code folded[k] = z[k - h] - z[3h - 1 - k]} for {@code h <= k < m}.
     *
     * @param x      the input samples
     * @param offset the index of the first of the {@code 2m} samples
     * @param window the window, {@code 2m} values
     * @param folded the folded values, {@code m} values
     * @param m      the number of spectral lines, a multiple of 2
     */
    void fold(float[] x, int offset, float[] window, float[] folded, int m);

    /**
     * Pairs and pre-twiddles the input of a DCT-IV of {@code size} values:
     * {@code re[k] = a c + b s} and {@code im[k] = b c - a s} for {@code k < size / 2}, with
     * {@code a = in[offset + 2k]}, {@code b = in[offset + size - 1 - 2k]},
     * {@code c = cos[k]} and {@code s = sin[k]}.
     *
     * @param in     the input
     * @param offset the index of the first input value
     * @param size   the transform length, a multiple of 2
     * @param cos    the twiddle cosines
     * @param sin    the twiddle sines
     * @param re     receives the real parts
     * @param im     receives the imaginary parts
     */
    void preTwiddle(float[] in, int offset, int size, float[] cos, float[] sin, float[] re, float[] im);

    /**
     * Converts a run of mid and side values to left and right in place:
     * {@code left[i] = m + s} and {@code right[i] = m - s}.
     *
     * @param left  the mid values, becoming left
     * @param right the side values, becoming right
     * @param from  the first index
     * @param to    the index after the last
     */
    void midSide(float[] left, float[] right, int from, int to);

    /**
     * Returns the dot product {@code sum a[aOffset + i] * b[bOffset + i]} for {@code i < length}.
     *
     * @param a       the first vector
     * @param aOffset the index of its first element
     * @param b       the second vector
     * @param bOffset the index of its first element
     * @param length  the number of elements
     * @return the dot product
     */
    float dot(float[] a, int aOffset, float[] b, int bOffset, int length);
}
