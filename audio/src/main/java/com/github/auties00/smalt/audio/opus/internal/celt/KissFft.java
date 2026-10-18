/* Ports KISS FFT as modified in libopus 1.5.2 (celt/kiss_fft.c). Copyright Mark Borgerding and Xiph.Org Foundation;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Computes the mixed-radix (2, 3, 4, 5) complex FFTs of the CELT mode.
 *
 * <p>Complex arrays are interleaved: point {@code k} has its real part at float index
 * {@code 2k} and its imaginary part at {@code 2k + 1}. The forward transform scales by
 * {@code 1 / nfft}; the in-place core ({@link #opusFftImpl}) expects its input already permuted
 * into bit-reversed order and does not scale.
 *
 * @implNote This implementation supports the non-custom factorizations only: the radix-2 butterfly assumes {@code m ==
 * 4}.
 */
public final class KissFft {
    private KissFft() {
        throw new AssertionError();
    }

    /**
     * Computes the scaled forward FFT of {@code fin} into {@code fout} ({@code opus_fft_c}).
     *
     * @param st   the FFT configuration
     * @param fin  the input, {@code nfft} interleaved complex points
     * @param fout receives the output; must not be {@code fin}
     */
    public static void opusFft(KissFftState st, float[] fin, float[] fout) {
        opusFft(st, fin, fout, new float[2 * st.nfft]);
    }

    /**
     * Computes the scaled forward FFT of {@code fin} into {@code fout} with caller-provided
     * scratch space ({@code opus_fft_c}).
     *
     * <p>The butterflies run on split real and imaginary arrays through {@link CeltKernels}; the
     * result is bit-identical to the interleaved reference.
     *
     * @param st      the FFT configuration
     * @param fin     the input, {@code nfft} interleaved complex points
     * @param fout    receives the output; may be {@code fin}
     * @param scratch scratch of at least {@code 2 * nfft} floats
     */
    public static void opusFft(KissFftState st, float[] fin, float[] fout, float[] scratch) {
        var n = st.nfft;
        var scale = st.scale;
        var bitrev = st.bitrev;
        for (var i = 0; i < n; i++) {
            var rev = bitrev[i];
            scratch[rev] = scale * fin[2 * i];
            scratch[n + rev] = scale * fin[2 * i + 1];
        }
        CeltKernels.INSTANCE.fftSoa(st, scratch, 0, n);
        for (var i = 0; i < n; i++) {
            fout[2 * i] = scratch[i];
            fout[2 * i + 1] = scratch[n + i];
        }
    }

    /**
     * Runs the butterfly stages of the FFT in place on bit-reversed input
     * ({@code opus_fft_impl}).
     *
     * @param st      the FFT configuration
     * @param fout    the data, interleaved complex
     * @param foutOff the float index of the first point
     */
    public static void opusFftImpl(KissFftState st, float[] fout, int foutOff) {
        var factors = st.factors;
        var fstride = st.fstride;
        var shift = st.shift > 0 ? st.shift : 0;
        var L = st.stages;
        int m = factors[2 * L - 1];
        for (var i = L - 1; i >= 0; i--) {
            int m2;
            if (i != 0) {
                m2 = factors[2 * i - 1];
            } else {
                m2 = 1;
            }
            switch (factors[2 * i]) {
                case 2 -> kfBfly2(fout, foutOff, fstride[i]);
                case 4 -> kfBfly4(fout, foutOff, fstride[i] << shift, st, m, fstride[i], m2);
                case 3 -> kfBfly3(fout, foutOff, fstride[i] << shift, st, m, fstride[i], m2);
                case 5 -> kfBfly5(fout, foutOff, fstride[i] << shift, st, m, fstride[i], m2);
                default -> {
                }
            }
            m = m2;
        }
    }

    /**
     * Runs a radix-2 stage that follows a radix-4 stage, so {@code m == 4} ({@code kf_bfly2}).
     *
     * @param f    the data
     * @param base the float index of the first point
     * @param N    the number of butterfly groups
     */
    private static void kfBfly2(float[] f, int base, int N) {
        final var tw = 0.7071067812f;
        var fo = base;
        for (var i = 0; i < N; i++) {
            var fo2 = fo + 8;
            float tr, ti;

            tr = f[fo2];
            ti = f[fo2 + 1];
            f[fo2] = f[fo] - tr;
            f[fo2 + 1] = f[fo + 1] - ti;
            f[fo] = f[fo] + tr;
            f[fo + 1] = f[fo + 1] + ti;

            tr = (f[fo2 + 2] + f[fo2 + 3]) * tw;
            ti = (f[fo2 + 3] - f[fo2 + 2]) * tw;
            f[fo2 + 2] = f[fo + 2] - tr;
            f[fo2 + 3] = f[fo + 3] - ti;
            f[fo + 2] = f[fo + 2] + tr;
            f[fo + 3] = f[fo + 3] + ti;

            tr = f[fo2 + 5];
            ti = -f[fo2 + 4];
            f[fo2 + 4] = f[fo + 4] - tr;
            f[fo2 + 5] = f[fo + 5] - ti;
            f[fo + 4] = f[fo + 4] + tr;
            f[fo + 5] = f[fo + 5] + ti;

            tr = (f[fo2 + 7] - f[fo2 + 6]) * tw;
            ti = -(f[fo2 + 7] + f[fo2 + 6]) * tw;
            f[fo2 + 6] = f[fo + 6] - tr;
            f[fo2 + 7] = f[fo + 7] - ti;
            f[fo + 6] = f[fo + 6] + tr;
            f[fo + 7] = f[fo + 7] + ti;

            fo += 16;
        }
    }

    /**
     * Runs a radix-4 stage ({@code kf_bfly4}).
     *
     * @param f       the data
     * @param base    the float index of the first point
     * @param fstride the twiddle stride in complex points
     * @param st      the FFT configuration
     * @param m       the length of each sub-transform
     * @param N       the number of butterfly groups
     * @param mm      the distance in complex points between groups
     */
    private static void kfBfly4(float[] f, int base, int fstride, KissFftState st, int m, int N, int mm) {
        if (m == 1) {
            var fo = base;
            for (var i = 0; i < N; i++) {
                var s0r = f[fo] - f[fo + 4];
                var s0i = f[fo + 1] - f[fo + 5];
                f[fo] = f[fo] + f[fo + 4];
                f[fo + 1] = f[fo + 1] + f[fo + 5];
                var s1r = f[fo + 2] + f[fo + 6];
                var s1i = f[fo + 3] + f[fo + 7];
                f[fo + 4] = f[fo] - s1r;
                f[fo + 5] = f[fo + 1] - s1i;
                f[fo] = f[fo] + s1r;
                f[fo + 1] = f[fo + 1] + s1i;
                s1r = f[fo + 2] - f[fo + 6];
                s1i = f[fo + 3] - f[fo + 7];
                f[fo + 2] = s0r + s1i;
                f[fo + 3] = s0i - s1r;
                f[fo + 6] = s0r - s1i;
                f[fo + 7] = s0i + s1r;
                fo += 8;
            }
        } else {
            var tw = st.twiddles;
            var m2 = 2 * m;
            var m3 = 3 * m;
            for (var i = 0; i < N; i++) {
                var fo = base + 2 * i * mm;
                var tw1 = 0;
                var tw2 = 0;
                var tw3 = 0;
                for (var j = 0; j < m; j++) {
                    var a1r = f[fo + 2 * m];
                    var a1i = f[fo + 2 * m + 1];
                    var a2r = f[fo + 2 * m2];
                    var a2i = f[fo + 2 * m2 + 1];
                    var a3r = f[fo + 2 * m3];
                    var a3i = f[fo + 2 * m3 + 1];
                    var b1r = tw[2 * tw1];
                    var b1i = tw[2 * tw1 + 1];
                    var b2r = tw[2 * tw2];
                    var b2i = tw[2 * tw2 + 1];
                    var b3r = tw[2 * tw3];
                    var b3i = tw[2 * tw3 + 1];
                    var s0r = a1r * b1r - a1i * b1i;
                    var s0i = a1r * b1i + a1i * b1r;
                    var s1r = a2r * b2r - a2i * b2i;
                    var s1i = a2r * b2i + a2i * b2r;
                    var s2r = a3r * b3r - a3i * b3i;
                    var s2i = a3r * b3i + a3i * b3r;
                    var s5r = f[fo] - s1r;
                    var s5i = f[fo + 1] - s1i;
                    f[fo] = f[fo] + s1r;
                    f[fo + 1] = f[fo + 1] + s1i;
                    var s3r = s0r + s2r;
                    var s3i = s0i + s2i;
                    var s4r = s0r - s2r;
                    var s4i = s0i - s2i;
                    f[fo + 2 * m2] = f[fo] - s3r;
                    f[fo + 2 * m2 + 1] = f[fo + 1] - s3i;
                    tw1 += fstride;
                    tw2 += fstride * 2;
                    tw3 += fstride * 3;
                    f[fo] = f[fo] + s3r;
                    f[fo + 1] = f[fo + 1] + s3i;
                    f[fo + 2 * m] = s5r + s4i;
                    f[fo + 2 * m + 1] = s5i - s4r;
                    f[fo + 2 * m3] = s5r - s4i;
                    f[fo + 2 * m3 + 1] = s5i + s4r;
                    fo += 2;
                }
            }
        }
    }

    /**
     * Runs a radix-3 stage ({@code kf_bfly3}).
     *
     * @param f       the data
     * @param base    the float index of the first point
     * @param fstride the twiddle stride in complex points
     * @param st      the FFT configuration
     * @param m       the length of each sub-transform
     * @param N       the number of butterfly groups
     * @param mm      the distance in complex points between groups
     */
    private static void kfBfly3(float[] f, int base, int fstride, KissFftState st, int m, int N, int mm) {
        var tw = st.twiddles;
        var m2 = 2 * m;
        var epi3i = tw[2 * (fstride * m) + 1];
        for (var i = 0; i < N; i++) {
            var fo = base + 2 * i * mm;
            var tw1 = 0;
            var tw2 = 0;
            var k = m;
            do {
                var a1r = f[fo + 2 * m];
                var a1i = f[fo + 2 * m + 1];
                var a2r = f[fo + 2 * m2];
                var a2i = f[fo + 2 * m2 + 1];
                var b1r = tw[2 * tw1];
                var b1i = tw[2 * tw1 + 1];
                var b2r = tw[2 * tw2];
                var b2i = tw[2 * tw2 + 1];
                var s1r = a1r * b1r - a1i * b1i;
                var s1i = a1r * b1i + a1i * b1r;
                var s2r = a2r * b2r - a2i * b2i;
                var s2i = a2r * b2i + a2i * b2r;
                var s3r = s1r + s2r;
                var s3i = s1i + s2i;
                var s0r = s1r - s2r;
                var s0i = s1i - s2i;
                tw1 += fstride;
                tw2 += fstride * 2;
                var fmr = f[fo] - s3r * .5f;
                var fmi = f[fo + 1] - s3i * .5f;
                s0r *= epi3i;
                s0i *= epi3i;
                f[fo] = f[fo] + s3r;
                f[fo + 1] = f[fo + 1] + s3i;
                f[fo + 2 * m2] = fmr + s0i;
                f[fo + 2 * m2 + 1] = fmi - s0r;
                f[fo + 2 * m] = fmr - s0i;
                f[fo + 2 * m + 1] = fmi + s0r;
                fo += 2;
            } while (--k != 0);
        }
    }

    /**
     * Runs a radix-5 stage ({@code kf_bfly5}).
     *
     * @param f       the data
     * @param base    the float index of the first point
     * @param fstride the twiddle stride in complex points
     * @param st      the FFT configuration
     * @param m       the length of each sub-transform
     * @param N       the number of butterfly groups
     * @param mm      the distance in complex points between groups
     */
    private static void kfBfly5(float[] f, int base, int fstride, KissFftState st, int m, int N, int mm) {
        var tw = st.twiddles;
        var yar = tw[2 * (fstride * m)];
        var yai = tw[2 * (fstride * m) + 1];
        var ybr = tw[2 * (fstride * 2 * m)];
        var ybi = tw[2 * (fstride * 2 * m) + 1];
        for (var i = 0; i < N; i++) {
            var f0 = base + 2 * i * mm;
            var f1 = f0 + 2 * m;
            var f2 = f0 + 4 * m;
            var f3 = f0 + 6 * m;
            var f4 = f0 + 8 * m;
            for (var u = 0; u < m; ++u) {
                var s0r = f[f0];
                var s0i = f[f0 + 1];
                var t1 = 2 * (u * fstride);
                var t2 = 2 * (2 * u * fstride);
                var t3 = 2 * (3 * u * fstride);
                var t4 = 2 * (4 * u * fstride);
                var s1r = f[f1] * tw[t1] - f[f1 + 1] * tw[t1 + 1];
                var s1i = f[f1] * tw[t1 + 1] + f[f1 + 1] * tw[t1];
                var s2r = f[f2] * tw[t2] - f[f2 + 1] * tw[t2 + 1];
                var s2i = f[f2] * tw[t2 + 1] + f[f2 + 1] * tw[t2];
                var s3r = f[f3] * tw[t3] - f[f3 + 1] * tw[t3 + 1];
                var s3i = f[f3] * tw[t3 + 1] + f[f3 + 1] * tw[t3];
                var s4r = f[f4] * tw[t4] - f[f4 + 1] * tw[t4 + 1];
                var s4i = f[f4] * tw[t4 + 1] + f[f4 + 1] * tw[t4];
                var s7r = s1r + s4r;
                var s7i = s1i + s4i;
                var s10r = s1r - s4r;
                var s10i = s1i - s4i;
                var s8r = s2r + s3r;
                var s8i = s2i + s3i;
                var s9r = s2r - s3r;
                var s9i = s2i - s3i;
                f[f0] = f[f0] + (s7r + s8r);
                f[f0 + 1] = f[f0 + 1] + (s7i + s8i);
                var s5r = s0r + (s7r * yar + s8r * ybr);
                var s5i = s0i + (s7i * yar + s8i * ybr);
                var s6r = s10i * yai + s9i * ybi;
                var s6i = -(s10r * yai + s9r * ybi);
                f[f1] = s5r - s6r;
                f[f1 + 1] = s5i - s6i;
                f[f4] = s5r + s6r;
                f[f4 + 1] = s5i + s6i;
                var s11r = s0r + (s7r * ybr + s8r * yar);
                var s11i = s0i + (s7i * ybr + s8i * yar);
                var s12r = s9i * yai - s10i * ybi;
                var s12i = s10r * ybi - s9r * yai;
                f[f2] = s11r + s12r;
                f[f2 + 1] = s11i + s12i;
                f[f3] = s11r - s12r;
                f[f3 + 1] = s11i - s12i;
                f0 += 2;
                f1 += 2;
                f2 += 2;
                f3 += 2;
                f4 += 2;
            }
        }
    }
}
