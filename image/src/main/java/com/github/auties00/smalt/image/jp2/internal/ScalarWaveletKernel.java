package com.github.auties00.smalt.image.jp2.internal;

/**
 * Lifts wavelet signals with scalar arithmetic: the reference the vector kernels must match.
 *
 * @implNote This implementation follows OpenJPEG's {@code dwt.c}: the 5-3 lifting of
 * {@code opj_dwt_decode_1_} and of the forward transform, and the 9-7 lifting steps of
 * {@code opj_v8dwt_decode} and {@code opj_dwt_encode_1_real} with OpenJPEG's constants (including
 * the inverse high-pass gain {@code 2 / K} rounded to 1.625732422) and the same order of
 * single-precision operations, so the coefficients are bit-identical to OpenJPEG's.
 */
final class ScalarWaveletKernel implements WaveletKernel {
    /**
     * The first lifting coefficient of the 9-7 wavelet.
     */
    static final float ALPHA = -1.586134342f;

    /**
     * The second lifting coefficient of the 9-7 wavelet.
     */
    static final float BETA = -0.052980118f;

    /**
     * The third lifting coefficient of the 9-7 wavelet.
     */
    static final float GAMMA = 0.882911075f;

    /**
     * The fourth lifting coefficient of the 9-7 wavelet.
     */
    static final float DELTA = 0.443506852f;

    /**
     * The low-pass gain of the 9-7 wavelet.
     */
    static final float K = 1.230174105f;

    /**
     * The inverse of {@link #K}.
     */
    static final float INVERSE_K = (float) (1.0 / 1.230174105);

    /**
     * The high-pass gain of the inverse 9-7 wavelet, {@code 2 / K} as OpenJPEG rounds it.
     */
    static final float TWO_INVERSE_K = 1.625732422f;

    /**
     * Constructs the kernels.
     */
    ScalarWaveletKernel() {
    }

    @Override
    public void inverse53(int[] buffer, int sn, int dn, int cas) {
        if (cas == 0) {
            if (dn <= 0 && sn <= 1) {
                return;
            }
            for (var i = 0; i < sn; i++) {
                var target = 2 * i * LANES;
                var left = (2 * Math.clamp(i - 1, 0, dn - 1) + 1) * LANES;
                var right = (2 * Math.min(i, dn - 1) + 1) * LANES;
                for (var lane = 0; lane < LANES; lane++) {
                    buffer[target + lane] -= (buffer[left + lane] + buffer[right + lane] + 2) >> 2;
                }
            }
            for (var i = 0; i < dn; i++) {
                var target = (2 * i + 1) * LANES;
                var left = 2 * Math.min(i, sn - 1) * LANES;
                var right = 2 * Math.min(i + 1, sn - 1) * LANES;
                for (var lane = 0; lane < LANES; lane++) {
                    buffer[target + lane] += (buffer[left + lane] + buffer[right + lane]) >> 1;
                }
            }
        } else {
            if (sn == 0 && dn == 1) {
                for (var lane = 0; lane < LANES; lane++) {
                    buffer[lane] /= 2;
                }
                return;
            }
            for (var i = 0; i < sn; i++) {
                var target = (2 * i + 1) * LANES;
                var left = 2 * Math.min(i, dn - 1) * LANES;
                var right = 2 * Math.min(i + 1, dn - 1) * LANES;
                for (var lane = 0; lane < LANES; lane++) {
                    buffer[target + lane] -= (buffer[left + lane] + buffer[right + lane] + 2) >> 2;
                }
            }
            for (var i = 0; i < dn; i++) {
                var target = 2 * i * LANES;
                var left = (2 * Math.clamp(i - 1, 0, sn - 1) + 1) * LANES;
                var right = (2 * Math.min(i, sn - 1) + 1) * LANES;
                for (var lane = 0; lane < LANES; lane++) {
                    buffer[target + lane] += (buffer[left + lane] + buffer[right + lane]) >> 1;
                }
            }
        }
    }

    @Override
    public void forward53(int[] buffer, int sn, int dn, int cas) {
        if (cas == 0) {
            if (sn + dn <= 1) {
                return;
            }
            for (var i = 0; i < dn; i++) {
                var target = (2 * i + 1) * LANES;
                var left = 2 * Math.min(i, sn - 1) * LANES;
                var right = 2 * Math.min(i + 1, sn - 1) * LANES;
                for (var lane = 0; lane < LANES; lane++) {
                    buffer[target + lane] -= (buffer[left + lane] + buffer[right + lane]) >> 1;
                }
            }
            for (var i = 0; i < sn; i++) {
                var target = 2 * i * LANES;
                var left = (2 * Math.clamp(i - 1, 0, dn - 1) + 1) * LANES;
                var right = (2 * Math.min(i, dn - 1) + 1) * LANES;
                for (var lane = 0; lane < LANES; lane++) {
                    buffer[target + lane] += (buffer[left + lane] + buffer[right + lane] + 2) >> 2;
                }
            }
        } else {
            if (sn + dn == 1) {
                for (var lane = 0; lane < LANES; lane++) {
                    buffer[lane] *= 2;
                }
                return;
            }
            for (var i = 0; i < dn; i++) {
                var target = 2 * i * LANES;
                var left = (2 * Math.clamp(i - 1, 0, sn - 1) + 1) * LANES;
                var right = (2 * Math.min(i, sn - 1) + 1) * LANES;
                for (var lane = 0; lane < LANES; lane++) {
                    buffer[target + lane] -= (buffer[left + lane] + buffer[right + lane]) >> 1;
                }
            }
            for (var i = 0; i < sn; i++) {
                var target = (2 * i + 1) * LANES;
                var left = 2 * Math.min(i, dn - 1) * LANES;
                var right = 2 * Math.min(i + 1, dn - 1) * LANES;
                for (var lane = 0; lane < LANES; lane++) {
                    buffer[target + lane] += (buffer[left + lane] + buffer[right + lane] + 2) >> 2;
                }
            }
        }
    }

    @Override
    public void inverse97(float[] buffer, int sn, int dn, int cas) {
        int a;
        int b;
        if (cas == 0) {
            if (dn <= 0 && sn <= 1) {
                return;
            }
            a = 0;
            b = 1;
        } else {
            if (sn <= 0 && dn <= 1) {
                return;
            }
            a = 1;
            b = 0;
        }
        scale(buffer, a, sn, K);
        scale(buffer, b, dn, TWO_INVERSE_K);
        lift(buffer, a, sn, Math.min(sn, dn - a), -DELTA);
        lift(buffer, b, dn, Math.min(dn, sn - b), -GAMMA);
        lift(buffer, a, sn, Math.min(sn, dn - a), -BETA);
        lift(buffer, b, dn, Math.min(dn, sn - b), -ALPHA);
    }

    @Override
    public void forward97(float[] buffer, int sn, int dn, int cas) {
        if (sn + dn <= 1) {
            return;
        }
        var a = cas == 0 ? 0 : 1;
        var b = 1 - a;
        lift(buffer, b, dn, Math.min(dn, sn - b), ALPHA);
        lift(buffer, a, sn, Math.min(sn, dn - a), BETA);
        lift(buffer, b, dn, Math.min(dn, sn - b), GAMMA);
        lift(buffer, a, sn, Math.min(sn, dn - a), DELTA);
        scale(buffer, a, sn, INVERSE_K);
        scale(buffer, b, dn, K);
    }

    /**
     * Multiplies every other position by a constant.
     *
     * @param buffer the interleaved signals
     * @param first  the first position
     * @param count  the number of positions
     * @param factor the constant
     */
    private static void scale(float[] buffer, int first, int count, float factor) {
        for (var i = 0; i < count; i++) {
            var target = (first + 2 * i) * LANES;
            for (var lane = 0; lane < LANES; lane++) {
                buffer[target + lane] = buffer[target + lane] * factor;
            }
        }
    }

    /**
     * Applies one lifting step to every other position: each target gains its two neighbors'
     * sum times a constant, a target without a right neighbor twice its left neighbor.
     *
     * @param buffer      the interleaved signals
     * @param first       the first target position
     * @param count       the number of targets
     * @param neighbored the number of leading targets with a right neighbor
     * @param factor      the lifting coefficient
     */
    private static void lift(float[] buffer, int first, int count, int neighbored, float factor) {
        var limit = Math.min(count, neighbored);
        for (var i = 0; i < limit; i++) {
            var position = first + 2 * i;
            var target = position * LANES;
            var left = (position == 0 ? 1 : position - 1) * LANES;
            var right = (position + 1) * LANES;
            for (var lane = 0; lane < LANES; lane++) {
                buffer[target + lane] = buffer[target + lane] + (buffer[left + lane] + buffer[right + lane]) * factor;
            }
        }
        if (neighbored < count) {
            var position = first + 2 * neighbored;
            var target = position * LANES;
            var left = (position == 0 ? 1 : position - 1) * LANES;
            var doubled = factor + factor;
            for (var lane = 0; lane < LANES; lane++) {
                buffer[target + lane] = buffer[target + lane] + buffer[left + lane] * doubled;
            }
        }
    }
}
