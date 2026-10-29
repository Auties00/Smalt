package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Lifts interleaved one-dimensional wavelet signals in place, several signals at a time: the
 * inverse and forward 5-3 and 9-7 transforms of ISO/IEC 15444-1 annex F.
 *
 * <p>A buffer holds {@code n = sn + dn} positions of {@link #LANES} signals each: position
 * {@code k} of signal {@code s} is at index {@code k * LANES + s}. In spatial order, the low-pass
 * coefficient {@code i} is at position {@code 2i + cas} and the high-pass coefficient {@code i}
 * at position {@code 2i + 1 - cas}, where {@code cas} is the parity of the signal's first
 * coordinate. Every lane is transformed with the same arithmetic, so the result of a lane does not
 * depend on the others.
 */
interface WaveletKernel {
    /**
     * The number of signals transformed together.
     */
    int LANES = 8;

    /**
     * The kernels used by the codec: the vector ones, written for 256-bit vectors, when
     * {@link Simd#WIDE}, the scalar ones otherwise, which are faster on narrower vectors.
     */
    WaveletKernel INSTANCE = Simd.WIDE ? new VectorWaveletKernel()
            : new ScalarWaveletKernel();

    /**
     * Applies the inverse reversible 5-3 transform.
     *
     * @param buffer the interleaved signals
     * @param sn the number of low-pass coefficients
     * @param dn the number of high-pass coefficients
     * @param cas the parity of the first coordinate, 0 or 1
     */
    void inverse53(int[] buffer, int sn, int dn, int cas);

    /**
     * Applies the inverse irreversible 9-7 transform, the low-pass coefficients scaled by
     * {@code K} and the high-pass ones by {@code 2 / K}.
     *
     * @param buffer the interleaved signals
     * @param sn the number of low-pass coefficients
     * @param dn the number of high-pass coefficients
     * @param cas the parity of the first coordinate, 0 or 1
     */
    void inverse97(float[] buffer, int sn, int dn, int cas);

    /**
     * Applies the forward reversible 5-3 transform.
     *
     * @param buffer the interleaved signals, in spatial order
     * @param sn the number of low-pass coefficients
     * @param dn the number of high-pass coefficients
     * @param cas the parity of the first coordinate, 0 or 1
     */
    void forward53(int[] buffer, int sn, int dn, int cas);

    /**
     * Applies the forward irreversible 9-7 transform, scaling the low-pass coefficients by
     * {@code 1 / K} and the high-pass ones by {@code K / 2}.
     *
     * @param buffer the interleaved signals, in spatial order
     * @param sn the number of low-pass coefficients
     * @param dn the number of high-pass coefficients
     * @param cas the parity of the first coordinate, 0 or 1
     */
    void forward97(float[] buffer, int sn, int dn, int cas);
}
