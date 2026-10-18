/* Ports KISS FFT as modified in libopus 1.5.2 (celt/kiss_fft.c). Copyright Mark Borgerding and Xiph.Org Foundation;
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Runs the butterfly stages of {@link KissFft} on split real and imaginary arrays.
 *
 * <p>Every butterfly performs exactly the operations of the interleaved reference in the same
 * order, so the results are bit-identical; only the memory layout differs. The split layout lets
 * the vector kernels process consecutive butterflies of a stage with contiguous loads. Each stage
 * method accepts the first butterfly index {@code jFrom} so that a vector implementation can
 * finish the tail of a stage with this scalar code.
 *
 * @implNote This implementation restates the butterflies and {@code opus_fft_impl} of the reference for a split layout.
 */
final class FftSoa {
    private FftSoa() {
        throw new AssertionError();
    }

    /**
     * Runs all stages of the FFT in place on bit-reversed split data with the scalar butterflies.
     *
     * @param st the FFT configuration
     * @param a  the array holding both halves
     * @param re the index of the first real part
     * @param im the index of the first imaginary part
     */
    static void impl(KissFftState st, float[] a, int re, int im) {
        var factors = st.factors;
        var fstride = st.fstride;
        var shift = st.shift > 0 ? st.shift : 0;
        var L = st.stages;
        int m = factors[2 * L - 1];
        for (var i = L - 1; i >= 0; i--) {
            var m2 = i != 0 ? factors[2 * i - 1] : 1;
            switch (factors[2 * i]) {
                case 2 -> bfly2(a, re, im, fstride[i]);
                case 4 -> {
                    if (m == 1) {
                        bfly4m1(a, re, im, fstride[i]);
                    } else {
                        bfly4(a, re, im, st.stageTwiddles[i], m, fstride[i], m2, 0);
                    }
                }
                case 3 -> bfly3(a, re, im, st.stageTwiddles[i], st.stageConstants[i], m, fstride[i], m2, 0);
                case 5 -> bfly5(a, re, im, st.stageTwiddles[i], st.stageConstants[i], m, fstride[i], m2, 0);
                default -> {
                }
            }
            m = m2;
        }
    }

    /**
     * Runs a radix-2 stage following a radix-4 stage ({@code m == 4}).
     *
     * @param a  the data
     * @param re the index of the first real part
     * @param im the index of the first imaginary part
     * @param N  the number of butterfly groups
     */
    static void bfly2(float[] a, int re, int im, int N) {
        final var tw = 0.7071067812f;
        for (var i = 0; i < N; i++) {
            var r = re + 8 * i;
            var q = im + 8 * i;
            float tr, ti;
            tr = a[r + 4];
            ti = a[q + 4];
            a[r + 4] = a[r] - tr;
            a[q + 4] = a[q] - ti;
            a[r] = a[r] + tr;
            a[q] = a[q] + ti;

            tr = (a[r + 5] + a[q + 5]) * tw;
            ti = (a[q + 5] - a[r + 5]) * tw;
            a[r + 5] = a[r + 1] - tr;
            a[q + 5] = a[q + 1] - ti;
            a[r + 1] = a[r + 1] + tr;
            a[q + 1] = a[q + 1] + ti;

            tr = a[q + 6];
            ti = -a[r + 6];
            a[r + 6] = a[r + 2] - tr;
            a[q + 6] = a[q + 2] - ti;
            a[r + 2] = a[r + 2] + tr;
            a[q + 2] = a[q + 2] + ti;

            tr = (a[q + 7] - a[r + 7]) * tw;
            ti = -(a[q + 7] + a[r + 7]) * tw;
            a[r + 7] = a[r + 3] - tr;
            a[q + 7] = a[q + 3] - ti;
            a[r + 3] = a[r + 3] + tr;
            a[q + 3] = a[q + 3] + ti;
        }
    }

    /**
     * Runs a radix-4 stage whose twiddles are all one ({@code m == 1}).
     *
     * @param a  the data
     * @param re the index of the first real part
     * @param im the index of the first imaginary part
     * @param N  the number of butterfly groups
     */
    static void bfly4m1(float[] a, int re, int im, int N) {
        for (var i = 0; i < N; i++) {
            var r = re + 4 * i;
            var q = im + 4 * i;
            var s0r = a[r] - a[r + 2];
            var s0i = a[q] - a[q + 2];
            a[r] = a[r] + a[r + 2];
            a[q] = a[q] + a[q + 2];
            var s1r = a[r + 1] + a[r + 3];
            var s1i = a[q + 1] + a[q + 3];
            a[r + 2] = a[r] - s1r;
            a[q + 2] = a[q] - s1i;
            a[r] = a[r] + s1r;
            a[q] = a[q] + s1i;
            s1r = a[r + 1] - a[r + 3];
            s1i = a[q + 1] - a[q + 3];
            a[r + 1] = s0r + s1i;
            a[q + 1] = s0i - s1r;
            a[r + 3] = s0r - s1i;
            a[q + 3] = s0i + s1r;
        }
    }

    /**
     * Runs the butterflies {@code jFrom} to {@code m - 1} of every group of a radix-4 stage.
     *
     * @param a     the data
     * @param re    the index of the first real part
     * @param im    the index of the first imaginary part
     * @param tw    the stage twiddles: real then imaginary parts of the three twiddle sequences
     * @param m     the length of each sub-transform
     * @param N     the number of butterfly groups
     * @param mm    the distance between groups
     * @param jFrom the first butterfly processed in each group
     */
    static void bfly4(float[] a, int re, int im, float[] tw, int m, int N, int mm, int jFrom) {
        for (var i = 0; i < N; i++) {
            var r = re + i * mm;
            var q = im + i * mm;
            for (var j = jFrom; j < m; j++) {
                var a1r = a[r + j + m];
                var a1i = a[q + j + m];
                var a2r = a[r + j + 2 * m];
                var a2i = a[q + j + 2 * m];
                var a3r = a[r + j + 3 * m];
                var a3i = a[q + j + 3 * m];
                var b1r = tw[j];
                var b1i = tw[m + j];
                var b2r = tw[2 * m + j];
                var b2i = tw[3 * m + j];
                var b3r = tw[4 * m + j];
                var b3i = tw[5 * m + j];
                var s0r = a1r * b1r - a1i * b1i;
                var s0i = a1r * b1i + a1i * b1r;
                var s1r = a2r * b2r - a2i * b2i;
                var s1i = a2r * b2i + a2i * b2r;
                var s2r = a3r * b3r - a3i * b3i;
                var s2i = a3r * b3i + a3i * b3r;
                var f0r = a[r + j];
                var f0i = a[q + j];
                var s5r = f0r - s1r;
                var s5i = f0i - s1i;
                f0r = f0r + s1r;
                f0i = f0i + s1i;
                var s3r = s0r + s2r;
                var s3i = s0i + s2i;
                var s4r = s0r - s2r;
                var s4i = s0i - s2i;
                a[r + j + 2 * m] = f0r - s3r;
                a[q + j + 2 * m] = f0i - s3i;
                a[r + j] = f0r + s3r;
                a[q + j] = f0i + s3i;
                a[r + j + m] = s5r + s4i;
                a[q + j + m] = s5i - s4r;
                a[r + j + 3 * m] = s5r - s4i;
                a[q + j + 3 * m] = s5i + s4r;
            }
        }
    }

    /**
     * Runs the butterflies {@code jFrom} to {@code m - 1} of every group of a radix-3 stage.
     *
     * @param a         the data
     * @param re        the index of the first real part
     * @param im        the index of the first imaginary part
     * @param tw        the stage twiddles: real then imaginary parts of the two twiddle sequences
     * @param constants the stage constants: the imaginary part of {@code exp(-2 pi i / 3)}
     * @param m         the length of each sub-transform
     * @param N         the number of butterfly groups
     * @param mm        the distance between groups
     * @param jFrom     the first butterfly processed in each group
     */
    static void bfly3(float[] a, int re, int im, float[] tw, float[] constants, int m, int N, int mm, int jFrom) {
        var epi3i = constants[0];
        for (var i = 0; i < N; i++) {
            var r = re + i * mm;
            var q = im + i * mm;
            for (var j = jFrom; j < m; j++) {
                var a1r = a[r + j + m];
                var a1i = a[q + j + m];
                var a2r = a[r + j + 2 * m];
                var a2i = a[q + j + 2 * m];
                var b1r = tw[j];
                var b1i = tw[m + j];
                var b2r = tw[2 * m + j];
                var b2i = tw[3 * m + j];
                var s1r = a1r * b1r - a1i * b1i;
                var s1i = a1r * b1i + a1i * b1r;
                var s2r = a2r * b2r - a2i * b2i;
                var s2i = a2r * b2i + a2i * b2r;
                var s3r = s1r + s2r;
                var s3i = s1i + s2i;
                var s0r = s1r - s2r;
                var s0i = s1i - s2i;
                var f0r = a[r + j];
                var f0i = a[q + j];
                var fmr = f0r - s3r * .5f;
                var fmi = f0i - s3i * .5f;
                s0r *= epi3i;
                s0i *= epi3i;
                a[r + j] = f0r + s3r;
                a[q + j] = f0i + s3i;
                a[r + j + 2 * m] = fmr + s0i;
                a[q + j + 2 * m] = fmi - s0r;
                a[r + j + m] = fmr - s0i;
                a[q + j + m] = fmi + s0r;
            }
        }
    }

    /**
     * Runs the butterflies {@code jFrom} to {@code m - 1} of every group of a radix-5 stage.
     *
     * @param a         the data
     * @param re        the index of the first real part
     * @param im        the index of the first imaginary part
     * @param tw        the stage twiddles: real then imaginary parts of the four twiddle sequences
     * @param constants the stage constants: {@code ya.r, ya.i, yb.r, yb.i}
     * @param m         the length of each sub-transform
     * @param N         the number of butterfly groups
     * @param mm        the distance between groups
     * @param jFrom     the first butterfly processed in each group
     */
    static void bfly5(float[] a, int re, int im, float[] tw, float[] constants, int m, int N, int mm, int jFrom) {
        var yar = constants[0];
        var yai = constants[1];
        var ybr = constants[2];
        var ybi = constants[3];
        for (var i = 0; i < N; i++) {
            var r = re + i * mm;
            var q = im + i * mm;
            for (var u = jFrom; u < m; u++) {
                var s0r = a[r + u];
                var s0i = a[q + u];
                var x1r = a[r + u + m];
                var x1i = a[q + u + m];
                var x2r = a[r + u + 2 * m];
                var x2i = a[q + u + 2 * m];
                var x3r = a[r + u + 3 * m];
                var x3i = a[q + u + 3 * m];
                var x4r = a[r + u + 4 * m];
                var x4i = a[q + u + 4 * m];
                var s1r = x1r * tw[u] - x1i * tw[m + u];
                var s1i = x1r * tw[m + u] + x1i * tw[u];
                var s2r = x2r * tw[2 * m + u] - x2i * tw[3 * m + u];
                var s2i = x2r * tw[3 * m + u] + x2i * tw[2 * m + u];
                var s3r = x3r * tw[4 * m + u] - x3i * tw[5 * m + u];
                var s3i = x3r * tw[5 * m + u] + x3i * tw[4 * m + u];
                var s4r = x4r * tw[6 * m + u] - x4i * tw[7 * m + u];
                var s4i = x4r * tw[7 * m + u] + x4i * tw[6 * m + u];
                var s7r = s1r + s4r;
                var s7i = s1i + s4i;
                var s10r = s1r - s4r;
                var s10i = s1i - s4i;
                var s8r = s2r + s3r;
                var s8i = s2i + s3i;
                var s9r = s2r - s3r;
                var s9i = s2i - s3i;
                a[r + u] = s0r + (s7r + s8r);
                a[q + u] = s0i + (s7i + s8i);
                var s5r = s0r + (s7r * yar + s8r * ybr);
                var s5i = s0i + (s7i * yar + s8i * ybr);
                var s6r = s10i * yai + s9i * ybi;
                var s6i = -(s10r * yai + s9r * ybi);
                a[r + u + m] = s5r - s6r;
                a[q + u + m] = s5i - s6i;
                a[r + u + 4 * m] = s5r + s6r;
                a[q + u + 4 * m] = s5i + s6i;
                var s11r = s0r + (s7r * ybr + s8r * yar);
                var s11i = s0i + (s7i * ybr + s8i * yar);
                var s12r = s9i * yai - s10i * ybi;
                var s12i = s10r * ybi - s9r * yai;
                a[r + u + 2 * m] = s11r + s12r;
                a[q + u + 2 * m] = s11i + s12i;
                a[r + u + 3 * m] = s11r - s12r;
                a[q + u + 3 * m] = s11i - s12i;
            }
        }
    }
}
