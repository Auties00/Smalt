/* Ports PFFFT (third_party/pffft/src/pffft.c of webrtc-audio-processing 2.1, scalar code). Copyright (c) 2013 Julien
   Pommier, based on FFTPACKv4, Copyright (c) 2004 the University Corporation for Atmospheric Research; FFTPACK
   license. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.agc2;

/**
 * Computes real FFTs of sizes {@code 2^a 3^b 5^c} that are multiples of 32, in PFFFT's unordered or
 * ordered layout, with its scalar code: the forward and backward FFTPACK transforms and the
 * accumulating spectral product.
 *
 * <p>Transforms are unnormalized: a forward and a backward transform scale the input by the size.
 * Instances are not thread-safe.
 *
 * @implNote This implementation ports the {@code PFFFT_SIMD_DISABLE} path of {@code pffft.c}
 * ({@code rffti1_ps}, {@code rfftf1_ps}, {@code rfftb1_ps}, the radix-2 and radix-4 butterflies,
 * {@code pffft_zconvolve_accumulate}, {@code pffft_zreorder}) and the radix-3 and radix-5 butterflies.
 */
final class Pffft {
    /**
     * The transform size.
     */
    private final int n;

    /**
     * The twiddle factors.
     */
    private final float[] twiddle;

    /**
     * The factorization: {@code n}, the number of factors, then the factors.
     */
    private final int[] ifac = new int[15];

    /**
     * The first work buffer.
     */
    private final float[] work1;

    /**
     * The second work buffer.
     */
    private final float[] work2;

    /**
     * Creates a transform.
     *
     * @param n the size, a multiple of 32 whose other factors are 2, 3 and 5
     * @throws IllegalArgumentException if {@code n} is not such a size
     */
    Pffft(int n) {
        var m = n;
        for (var f : new int[]{2, 3, 5}) {
            while (m > 0 && m % f == 0) {
                m /= f;
            }
        }
        if (n <= 0 || n % 32 != 0 || m != 1) {
            throw new IllegalArgumentException("size must be a multiple of 32 with factors 2, 3 and 5: " + n);
        }
        this.n = n;
        this.twiddle = new float[n];
        this.work1 = new float[n];
        this.work2 = new float[n];
        rffti1(n, twiddle, ifac);
    }

    /**
     * Transforms {@code n} real samples into the unordered spectrum.
     *
     * @param input the samples; may be {@code output}
     * @param output receives the spectrum
     */
    void forward(float[] input, float[] output) {
        System.arraycopy(input, 0, work1, 0, n);
        var result = rfftf1(work1, work2);
        System.arraycopy(result, 0, output, 0, n);
    }

    /**
     * Transforms {@code n} real samples into the ordered spectrum: bins 0 and {@code n / 2}
     * (both real) first, then the real and imaginary parts of bins 1 to {@code n / 2 - 1}.
     *
     * @param input the samples; may be {@code output}
     * @param output receives the spectrum
     */
    void forwardOrdered(float[] input, float[] output) {
        System.arraycopy(input, 0, work1, 0, n);
        var result = rfftf1(work1, work2);
        var xN = result[n - 1];
        for (var k = n - 1; k > 1; --k) {
            output[k] = result[k - 1];
        }
        output[0] = result[0];
        output[1] = xN;
    }

    /**
     * Transforms an unordered spectrum back into {@code n} real samples.
     *
     * @param input the spectrum; may be {@code output}
     * @param output receives the samples
     */
    void backward(float[] input, float[] output) {
        System.arraycopy(input, 0, work1, 0, n);
        var result = rfftb1(work1, work2);
        System.arraycopy(result, 0, output, 0, n);
    }

    /**
     * Adds the scaled product of two unordered spectra to a third ({@code pffft_zconvolve_accumulate}).
     *
     * @param a the first spectrum
     * @param b the second spectrum
     * @param ab the accumulated product, updated
     * @param scaling the scale factor
     */
    void convolveAccumulate(float[] a, float[] b, float[] ab, float scaling) {
        var ncvec = n / 2;
        ab[0] += a[0] * b[0] * scaling;
        ab[2 * ncvec - 1] += a[2 * ncvec - 1] * b[2 * ncvec - 1] * scaling;
        ncvec--;
        for (var i = 0; i < ncvec; ++i) {
            var ar = a[1 + 2 * i];
            var ai = a[1 + 2 * i + 1];
            var br = b[1 + 2 * i];
            var bi = b[1 + 2 * i + 1];
            var tmp = ar * bi;
            ar = ar * br;
            ar = ar - ai * bi;
            ai = ai * br;
            ai = ai + tmp;
            ab[1 + 2 * i] += ar * scaling;
            ab[1 + 2 * i + 1] += ai * scaling;
        }
    }

    private float[] rfftf1(float[] in, float[] out) {
        var nf = ifac[1];
        var l2 = n;
        var iw = n - 1;
        for (var k1 = 1; k1 <= nf; ++k1) {
            var kh = nf - k1;
            var ip = ifac[kh + 2];
            var l1 = l2 / ip;
            var ido = n / l2;
            iw -= (ip - 1) * ido;
            switch (ip) {
                case 5 -> radf5(ido, l1, in, out, iw, iw + ido, iw + 2 * ido, iw + 3 * ido);
                case 4 -> radf4(ido, l1, in, out, iw, iw + ido, iw + 2 * ido);
                case 3 -> radf3(ido, l1, in, out, iw, iw + ido);
                default -> radf2(ido, l1, in, out, iw);
            }
            l2 = l1;
            var t = in;
            in = out;
            out = t;
        }
        return in;
    }

    private float[] rfftb1(float[] in, float[] out) {
        var nf = ifac[1];
        var l1 = 1;
        var iw = 0;
        for (var k1 = 1; k1 <= nf; k1++) {
            var ip = ifac[k1 + 1];
            var l2 = ip * l1;
            var ido = n / l2;
            switch (ip) {
                case 5 -> radb5(ido, l1, in, out, iw, iw + ido, iw + 2 * ido, iw + 3 * ido);
                case 4 -> radb4(ido, l1, in, out, iw, iw + ido, iw + 2 * ido);
                case 3 -> radb3(ido, l1, in, out, iw, iw + ido);
                default -> radb2(ido, l1, in, out, iw);
            }
            l1 = l2;
            iw += (ip - 1) * ido;
            var t = in;
            in = out;
            out = t;
        }
        return in;
    }

    private void radf2(int ido, int l1, float[] cc, float[] ch, int wa1) {
        var wa = twiddle;
        var l1ido = l1 * ido;
        for (var k = 0; k < l1ido; k += ido) {
            var a = cc[k];
            var b = cc[k + l1ido];
            ch[2 * k] = a + b;
            ch[2 * (k + ido) - 1] = a - b;
        }
        if (ido < 2) {
            return;
        }
        if (ido != 2) {
            for (var k = 0; k < l1ido; k += ido) {
                for (var i = 2; i < ido; i += 2) {
                    var tr2 = cc[i - 1 + k + l1ido];
                    var ti2 = cc[i + k + l1ido];
                    var br = cc[i - 1 + k];
                    var bi = cc[i + k];
                    var wr = wa[wa1 + i - 2];
                    var wi = wa[wa1 + i - 1];
                    var tmp = tr2 * wi;
                    tr2 = tr2 * wr;
                    tr2 = tr2 + ti2 * wi;
                    ti2 = ti2 * wr;
                    ti2 = ti2 - tmp;
                    ch[i + 2 * k] = bi + ti2;
                    ch[2 * (k + ido) - i] = ti2 - bi;
                    ch[i - 1 + 2 * k] = br + tr2;
                    ch[2 * (k + ido) - i - 1] = br - tr2;
                }
            }
            if (ido % 2 == 1) {
                return;
            }
        }
        for (var k = 0; k < l1ido; k += ido) {
            ch[2 * k + ido] = -1.f * cc[ido - 1 + k + l1ido];
            ch[2 * k + ido - 1] = cc[k + ido - 1];
        }
    }

    private void radb2(int ido, int l1, float[] cc, float[] ch, int wa1) {
        var wa = twiddle;
        var l1ido = l1 * ido;
        for (var k = 0; k < l1ido; k += ido) {
            var a = cc[2 * k];
            var b = cc[2 * (k + ido) - 1];
            ch[k] = a + b;
            ch[k + l1ido] = a - b;
        }
        if (ido < 2) {
            return;
        }
        if (ido != 2) {
            for (var k = 0; k < l1ido; k += ido) {
                for (var i = 2; i < ido; i += 2) {
                    var a = cc[i - 1 + 2 * k];
                    var b = cc[2 * (k + ido) - i - 1];
                    var c = cc[i + 2 * k];
                    var d = cc[2 * (k + ido) - i];
                    ch[i - 1 + k] = a + b;
                    var tr2 = a - b;
                    ch[i + k] = c - d;
                    var ti2 = c + d;
                    var wr = wa[wa1 + i - 2];
                    var wi = wa[wa1 + i - 1];
                    var tmp = tr2 * wi;
                    tr2 = tr2 * wr;
                    tr2 = tr2 - ti2 * wi;
                    ti2 = ti2 * wr;
                    ti2 = ti2 + tmp;
                    ch[i - 1 + k + l1ido] = tr2;
                    ch[i + k + l1ido] = ti2;
                }
            }
            if (ido % 2 == 1) {
                return;
            }
        }
        for (var k = 0; k < l1ido; k += ido) {
            var a = cc[2 * k + ido - 1];
            var b = cc[2 * k + ido];
            ch[k + ido - 1] = a + a;
            ch[k + ido - 1 + l1ido] = -2.f * b;
        }
    }

    private void radf4(int ido, int l1, float[] cc, float[] ch, int wa1, int wa2, int wa3) {
        var wa = twiddle;
        var minusHsqt2 = (float) -0.7071067811865475;
        var l1ido = l1 * ido;
        for (int c = 0, h = 0; c < l1ido; c += ido, h += 4 * ido) {
            var a0 = cc[c];
            var a1 = cc[c + l1ido];
            var a2 = cc[c + 2 * l1ido];
            var a3 = cc[c + 3 * l1ido];
            var tr1 = a1 + a3;
            var tr2 = a0 + a2;
            ch[h + 2 * ido - 1] = a0 - a2;
            ch[h + 2 * ido] = a3 - a1;
            ch[h] = tr1 + tr2;
            ch[h + 4 * ido - 1] = tr2 - tr1;
        }
        if (ido < 2) {
            return;
        }
        if (ido != 2) {
            for (var k = 0; k < l1ido; k += ido) {
                var pc = 1 + k;
                for (var i = 2; i < ido; i += 2, pc += 2) {
                    var ic = ido - i;
                    var cr2 = cc[pc + l1ido];
                    var ci2 = cc[pc + l1ido + 1];
                    var wr = wa[wa1 + i - 2];
                    var wi = wa[wa1 + i - 1];
                    var tmp = cr2 * wi;
                    cr2 = cr2 * wr;
                    cr2 = cr2 + ci2 * wi;
                    ci2 = ci2 * wr;
                    ci2 = ci2 - tmp;
                    var cr3 = cc[pc + 2 * l1ido];
                    var ci3 = cc[pc + 2 * l1ido + 1];
                    wr = wa[wa2 + i - 2];
                    wi = wa[wa2 + i - 1];
                    tmp = cr3 * wi;
                    cr3 = cr3 * wr;
                    cr3 = cr3 + ci3 * wi;
                    ci3 = ci3 * wr;
                    ci3 = ci3 - tmp;
                    var cr4 = cc[pc + 3 * l1ido];
                    var ci4 = cc[pc + 3 * l1ido + 1];
                    wr = wa[wa3 + i - 2];
                    wi = wa[wa3 + i - 1];
                    tmp = cr4 * wi;
                    cr4 = cr4 * wr;
                    cr4 = cr4 + ci4 * wi;
                    ci4 = ci4 * wr;
                    ci4 = ci4 - tmp;
                    var tr1 = cr2 + cr4;
                    var tr4 = cr4 - cr2;
                    var tr2 = cc[pc] + cr3;
                    var tr3 = cc[pc] - cr3;
                    ch[i - 1 + 4 * k] = tr1 + tr2;
                    ch[ic - 1 + 4 * k + 3 * ido] = tr2 - tr1;
                    var ti1 = ci2 + ci4;
                    var ti4 = ci2 - ci4;
                    ch[i - 1 + 4 * k + 2 * ido] = ti4 + tr3;
                    ch[ic - 1 + 4 * k + ido] = tr3 - ti4;
                    var ti2 = cc[pc + 1] + ci3;
                    var ti3 = cc[pc + 1] - ci3;
                    ch[i + 4 * k] = ti1 + ti2;
                    ch[ic + 4 * k + 3 * ido] = ti1 - ti2;
                    ch[i + 4 * k + 2 * ido] = tr4 + ti3;
                    ch[ic + 4 * k + ido] = tr4 - ti3;
                }
            }
            if (ido % 2 == 1) {
                return;
            }
        }
        for (var k = 0; k < l1ido; k += ido) {
            var a = cc[ido - 1 + k + l1ido];
            var b = cc[ido - 1 + k + 3 * l1ido];
            var c = cc[ido - 1 + k];
            var d = cc[ido - 1 + k + 2 * l1ido];
            var ti1 = minusHsqt2 * (a + b);
            var tr1 = minusHsqt2 * (b - a);
            ch[ido - 1 + 4 * k] = tr1 + c;
            ch[ido - 1 + 4 * k + 2 * ido] = c - tr1;
            ch[4 * k + ido] = ti1 - d;
            ch[4 * k + 3 * ido] = ti1 + d;
        }
    }

    private void radb4(int ido, int l1, float[] cc, float[] ch, int wa1, int wa2, int wa3) {
        var wa = twiddle;
        var minusSqrt2 = (float) -1.414213562373095;
        var l1ido = l1 * ido;
        for (int c = 0, h = 0; h < l1ido; c += 4 * ido, h += ido) {
            var a = cc[c];
            var b = cc[c + 4 * ido - 1];
            var cv = cc[c + 2 * ido];
            var d = cc[c + 2 * ido - 1];
            var tr3 = 2.f * d;
            var tr2 = a + b;
            var tr1 = a - b;
            var tr4 = 2.f * cv;
            ch[h] = tr2 + tr3;
            ch[h + 2 * l1ido] = tr2 - tr3;
            ch[h + l1ido] = tr1 - tr4;
            ch[h + 3 * l1ido] = tr1 + tr4;
        }
        if (ido < 2) {
            return;
        }
        if (ido != 2) {
            for (var k = 0; k < l1ido; k += ido) {
                var pc = -1 + 4 * k;
                var ph = k + 1;
                for (var i = 2; i < ido; i += 2) {
                    var tr1 = cc[pc + i] - cc[pc + 4 * ido - i];
                    var tr2 = cc[pc + i] + cc[pc + 4 * ido - i];
                    var ti4 = cc[pc + 2 * ido + i] - cc[pc + 2 * ido - i];
                    var tr3 = cc[pc + 2 * ido + i] + cc[pc + 2 * ido - i];
                    ch[ph] = tr2 + tr3;
                    var cr3 = tr2 - tr3;
                    var ti3 = cc[pc + 2 * ido + i + 1] - cc[pc + 2 * ido - i + 1];
                    var tr4 = cc[pc + 2 * ido + i + 1] + cc[pc + 2 * ido - i + 1];
                    var cr2 = tr1 - tr4;
                    var cr4 = tr1 + tr4;
                    var ti1 = cc[pc + i + 1] + cc[pc + 4 * ido - i + 1];
                    var ti2 = cc[pc + i + 1] - cc[pc + 4 * ido - i + 1];
                    ch[ph + 1] = ti2 + ti3;
                    ph += l1ido;
                    var ci3 = ti2 - ti3;
                    var ci2 = ti1 + ti4;
                    var ci4 = ti1 - ti4;
                    var wr = wa[wa1 + i - 2];
                    var wi = wa[wa1 + i - 1];
                    var tmp = cr2 * wi;
                    cr2 = cr2 * wr;
                    cr2 = cr2 - ci2 * wi;
                    ci2 = ci2 * wr;
                    ci2 = ci2 + tmp;
                    ch[ph] = cr2;
                    ch[ph + 1] = ci2;
                    ph += l1ido;
                    wr = wa[wa2 + i - 2];
                    wi = wa[wa2 + i - 1];
                    tmp = cr3 * wi;
                    cr3 = cr3 * wr;
                    cr3 = cr3 - ci3 * wi;
                    ci3 = ci3 * wr;
                    ci3 = ci3 + tmp;
                    ch[ph] = cr3;
                    ch[ph + 1] = ci3;
                    ph += l1ido;
                    wr = wa[wa3 + i - 2];
                    wi = wa[wa3 + i - 1];
                    tmp = cr4 * wi;
                    cr4 = cr4 * wr;
                    cr4 = cr4 - ci4 * wi;
                    ci4 = ci4 * wr;
                    ci4 = ci4 + tmp;
                    ch[ph] = cr4;
                    ch[ph + 1] = ci4;
                    ph = ph - 3 * l1ido + 2;
                }
            }
            if (ido % 2 == 1) {
                return;
            }
        }
        for (var k = 0; k < l1ido; k += ido) {
            var i0 = 4 * k + ido;
            var c = cc[i0 - 1];
            var d = cc[i0 + 2 * ido - 1];
            var a = cc[i0];
            var b = cc[i0 + 2 * ido];
            var tr1 = c - d;
            var tr2 = c + d;
            var ti1 = b + a;
            var ti2 = b - a;
            ch[ido - 1 + k] = tr2 + tr2;
            ch[ido - 1 + k + l1ido] = minusSqrt2 * (ti1 - tr1);
            ch[ido - 1 + k + 2 * l1ido] = ti2 + ti2;
            ch[ido - 1 + k + 3 * l1ido] = minusSqrt2 * (ti1 + tr1);
        }
    }

    private void radf3(int ido, int l1, float[] cc, float[] ch, int wa1, int wa2) {
        var wa = twiddle;
        final var taur = -0.5f;
        final var taui = 0.866025403784439f;
        for (var k = 0; k < l1; k++) {
            var cr2 = cc[(k + l1) * ido] + cc[(k + 2 * l1) * ido];
            ch[3 * k * ido] = cc[k * ido] + cr2;
            ch[(3 * k + 2) * ido] = taui * (cc[(k + l1 * 2) * ido] - cc[(k + l1) * ido]);
            ch[ido - 1 + (3 * k + 1) * ido] = cc[k * ido] + taur * cr2;
        }
        if (ido == 1) {
            return;
        }
        for (var k = 0; k < l1; k++) {
            for (var i = 2; i < ido; i += 2) {
                var ic = ido - i;
                var wr1 = wa[wa1 + i - 2];
                var wi1 = wa[wa1 + i - 1];
                var dr2 = cc[i - 1 + (k + l1) * ido];
                var di2 = cc[i + (k + l1) * ido];
                var tmp = dr2 * wi1;
                dr2 = dr2 * wr1;
                dr2 = dr2 + di2 * wi1;
                di2 = di2 * wr1;
                di2 = di2 - tmp;
                var wr2 = wa[wa2 + i - 2];
                var wi2 = wa[wa2 + i - 1];
                var dr3 = cc[i - 1 + (k + l1 * 2) * ido];
                var di3 = cc[i + (k + l1 * 2) * ido];
                tmp = dr3 * wi2;
                dr3 = dr3 * wr2;
                dr3 = dr3 + di3 * wi2;
                di3 = di3 * wr2;
                di3 = di3 - tmp;
                var cr2 = dr2 + dr3;
                var ci2 = di2 + di3;
                ch[i - 1 + 3 * k * ido] = cc[i - 1 + k * ido] + cr2;
                ch[i + 3 * k * ido] = cc[i + k * ido] + ci2;
                var tr2 = cc[i - 1 + k * ido] + taur * cr2;
                var ti2 = cc[i + k * ido] + taur * ci2;
                var tr3 = taui * (di2 - di3);
                var ti3 = taui * (dr3 - dr2);
                ch[i - 1 + (3 * k + 2) * ido] = tr2 + tr3;
                ch[ic - 1 + (3 * k + 1) * ido] = tr2 - tr3;
                ch[i + (3 * k + 2) * ido] = ti2 + ti3;
                ch[ic + (3 * k + 1) * ido] = ti3 - ti2;
            }
        }
    }

    private void radb3(int ido, int l1, float[] cc, float[] ch, int wa1, int wa2) {
        var wa = twiddle;
        final var taur = -0.5f;
        final var taui = 0.866025403784439f;
        final var taui2 = 0.866025403784439f * 2;
        for (var k = 0; k < l1; k++) {
            var tr2 = cc[ido - 1 + (3 * k + 1) * ido];
            tr2 = tr2 + tr2;
            var cr2 = taur * tr2 + cc[3 * k * ido];
            ch[k * ido] = cc[3 * k * ido] + tr2;
            var ci3 = taui2 * cc[(3 * k + 2) * ido];
            ch[(k + l1) * ido] = cr2 - ci3;
            ch[(k + 2 * l1) * ido] = cr2 + ci3;
        }
        if (ido == 1) {
            return;
        }
        for (var k = 0; k < l1; k++) {
            for (var i = 2; i < ido; i += 2) {
                var ic = ido - i;
                var tr2 = cc[i - 1 + (3 * k + 2) * ido] + cc[ic - 1 + (3 * k + 1) * ido];
                var cr2 = taur * tr2 + cc[i - 1 + 3 * k * ido];
                ch[i - 1 + k * ido] = cc[i - 1 + 3 * k * ido] + tr2;
                var ti2 = cc[i + (3 * k + 2) * ido] - cc[ic + (3 * k + 1) * ido];
                var ci2 = taur * ti2 + cc[i + 3 * k * ido];
                ch[i + k * ido] = cc[i + 3 * k * ido] + ti2;
                var cr3 = taui * (cc[i - 1 + (3 * k + 2) * ido] - cc[ic - 1 + (3 * k + 1) * ido]);
                var ci3 = taui * (cc[i + (3 * k + 2) * ido] + cc[ic + (3 * k + 1) * ido]);
                var dr2 = cr2 - ci3;
                var dr3 = cr2 + ci3;
                var di2 = ci2 + cr3;
                var di3 = ci2 - cr3;
                var wr = wa[wa1 + i - 2];
                var wi = wa[wa1 + i - 1];
                var tmp = dr2 * wi;
                dr2 = dr2 * wr;
                dr2 = dr2 - di2 * wi;
                di2 = di2 * wr;
                di2 = di2 + tmp;
                ch[i - 1 + (k + l1) * ido] = dr2;
                ch[i + (k + l1) * ido] = di2;
                wr = wa[wa2 + i - 2];
                wi = wa[wa2 + i - 1];
                tmp = dr3 * wi;
                dr3 = dr3 * wr;
                dr3 = dr3 - di3 * wi;
                di3 = di3 * wr;
                di3 = di3 + tmp;
                ch[i - 1 + (k + 2 * l1) * ido] = dr3;
                ch[i + (k + 2 * l1) * ido] = di3;
            }
        }
    }

    private void radf5(int ido, int l1, float[] cc, float[] ch, int wa1, int wa2, int wa3, int wa4) {
        var wa = twiddle;
        final var tr11 = .309016994374947f;
        final var ti11 = .951056516295154f;
        final var tr12 = -.809016994374947f;
        final var ti12 = .587785252292473f;
        var chOffset = 1 + ido * 6;
        var ccOffset = 1 + ido * (1 + l1);
        for (var k = 1; k <= l1; ++k) {
            var c5 = cc[(5 * l1 + k) * ido + 1 - ccOffset];
            var c2 = cc[(2 * l1 + k) * ido + 1 - ccOffset];
            var c4 = cc[(4 * l1 + k) * ido + 1 - ccOffset];
            var c3 = cc[(3 * l1 + k) * ido + 1 - ccOffset];
            var cr2 = c5 + c2;
            var ci5 = c5 - c2;
            var cr3 = c4 + c3;
            var ci4 = c4 - c3;
            var c1 = cc[(l1 + k) * ido + 1 - ccOffset];
            ch[(k * 5 + 1) * ido + 1 - chOffset] = c1 + (cr2 + cr3);
            ch[(k * 5 + 2) * ido + ido - chOffset] = c1 + (tr11 * cr2 + tr12 * cr3);
            ch[(k * 5 + 3) * ido + 1 - chOffset] = ti11 * ci5 + ti12 * ci4;
            ch[(k * 5 + 4) * ido + ido - chOffset] = c1 + (tr12 * cr2 + tr11 * cr3);
            ch[(k * 5 + 5) * ido + 1 - chOffset] = ti12 * ci5 - ti11 * ci4;
        }
        if (ido == 1) {
            return;
        }
        var idp2 = ido + 2;
        for (var k = 1; k <= l1; ++k) {
            for (var i = 3; i <= ido; i += 2) {
                var ic = idp2 - i;
                var dr2 = wa[wa1 + i - 3];
                var di2 = wa[wa1 + i - 2];
                var dr3 = wa[wa2 + i - 3];
                var di3 = wa[wa2 + i - 2];
                var dr4 = wa[wa3 + i - 3];
                var di4 = wa[wa3 + i - 2];
                var dr5 = wa[wa4 + i - 3];
                var di5 = wa[wa4 + i - 2];
                var br = cc[(2 * l1 + k) * ido + i - 1 - ccOffset];
                var bi = cc[(2 * l1 + k) * ido + i - ccOffset];
                var tmp = dr2 * bi;
                dr2 = dr2 * br;
                dr2 = dr2 + di2 * bi;
                di2 = di2 * br;
                di2 = di2 - tmp;
                br = cc[(3 * l1 + k) * ido + i - 1 - ccOffset];
                bi = cc[(3 * l1 + k) * ido + i - ccOffset];
                tmp = dr3 * bi;
                dr3 = dr3 * br;
                dr3 = dr3 + di3 * bi;
                di3 = di3 * br;
                di3 = di3 - tmp;
                br = cc[(4 * l1 + k) * ido + i - 1 - ccOffset];
                bi = cc[(4 * l1 + k) * ido + i - ccOffset];
                tmp = dr4 * bi;
                dr4 = dr4 * br;
                dr4 = dr4 + di4 * bi;
                di4 = di4 * br;
                di4 = di4 - tmp;
                br = cc[(5 * l1 + k) * ido + i - 1 - ccOffset];
                bi = cc[(5 * l1 + k) * ido + i - ccOffset];
                tmp = dr5 * bi;
                dr5 = dr5 * br;
                dr5 = dr5 + di5 * bi;
                di5 = di5 * br;
                di5 = di5 - tmp;
                var cr2 = dr2 + dr5;
                var ci5 = dr5 - dr2;
                var cr5 = di2 - di5;
                var ci2 = di2 + di5;
                var cr3 = dr3 + dr4;
                var ci4 = dr4 - dr3;
                var cr4 = di3 - di4;
                var ci3 = di3 + di4;
                var c1r = cc[(l1 + k) * ido + i - 1 - ccOffset];
                var c1i = cc[(l1 + k) * ido + i - ccOffset];
                ch[(k * 5 + 1) * ido + i - 1 - chOffset] = c1r + (cr2 + cr3);
                ch[(k * 5 + 1) * ido + i - chOffset] = c1i - (ci2 + ci3);
                var tr2 = c1r + (tr11 * cr2 + tr12 * cr3);
                var ti2 = c1i - (tr11 * ci2 + tr12 * ci3);
                var tr3 = c1r + (tr12 * cr2 + tr11 * cr3);
                var ti3 = c1i - (tr12 * ci2 + tr11 * ci3);
                var tr5 = ti11 * cr5 + ti12 * cr4;
                var ti5 = ti11 * ci5 + ti12 * ci4;
                var tr4 = ti12 * cr5 - ti11 * cr4;
                var ti4 = ti12 * ci5 - ti11 * ci4;
                ch[(k * 5 + 3) * ido + i - 1 - chOffset] = tr2 - tr5;
                ch[(k * 5 + 2) * ido + ic - 1 - chOffset] = tr2 + tr5;
                ch[(k * 5 + 3) * ido + i - chOffset] = ti2 + ti5;
                ch[(k * 5 + 2) * ido + ic - chOffset] = ti5 - ti2;
                ch[(k * 5 + 5) * ido + i - 1 - chOffset] = tr3 - tr4;
                ch[(k * 5 + 4) * ido + ic - 1 - chOffset] = tr3 + tr4;
                ch[(k * 5 + 5) * ido + i - chOffset] = ti3 + ti4;
                ch[(k * 5 + 4) * ido + ic - chOffset] = ti4 - ti3;
            }
        }
    }

    private void radb5(int ido, int l1, float[] cc, float[] ch, int wa1, int wa2, int wa3, int wa4) {
        var wa = twiddle;
        final var tr11 = .309016994374947f;
        final var ti11 = .951056516295154f;
        final var tr12 = -.809016994374947f;
        final var ti12 = .587785252292473f;
        var chOffset = 1 + ido * (1 + l1);
        var ccOffset = 1 + ido * 6;
        for (var k = 1; k <= l1; ++k) {
            var a3 = cc[(k * 5 + 3) * ido + 1 - ccOffset];
            var a5 = cc[(k * 5 + 5) * ido + 1 - ccOffset];
            var a2 = cc[(k * 5 + 2) * ido + ido - ccOffset];
            var a4 = cc[(k * 5 + 4) * ido + ido - ccOffset];
            var ti5 = a3 + a3;
            var ti4 = a5 + a5;
            var tr2 = a2 + a2;
            var tr3 = a4 + a4;
            var c1 = cc[(k * 5 + 1) * ido + 1 - ccOffset];
            ch[(l1 + k) * ido + 1 - chOffset] = c1 + (tr2 + tr3);
            var cr2 = c1 + (tr11 * tr2 + tr12 * tr3);
            var cr3 = c1 + (tr12 * tr2 + tr11 * tr3);
            var ci5 = ti11 * ti5 + ti12 * ti4;
            var ci4 = ti12 * ti5 - ti11 * ti4;
            ch[(2 * l1 + k) * ido + 1 - chOffset] = cr2 - ci5;
            ch[(3 * l1 + k) * ido + 1 - chOffset] = cr3 - ci4;
            ch[(4 * l1 + k) * ido + 1 - chOffset] = cr3 + ci4;
            ch[(5 * l1 + k) * ido + 1 - chOffset] = cr2 + ci5;
        }
        if (ido == 1) {
            return;
        }
        var idp2 = ido + 2;
        for (var k = 1; k <= l1; ++k) {
            for (var i = 3; i <= ido; i += 2) {
                var ic = idp2 - i;
                var b3i = cc[(k * 5 + 3) * ido + i - ccOffset];
                var b2i = cc[(k * 5 + 2) * ido + ic - ccOffset];
                var b5i = cc[(k * 5 + 5) * ido + i - ccOffset];
                var b4i = cc[(k * 5 + 4) * ido + ic - ccOffset];
                var b3r = cc[(k * 5 + 3) * ido + i - 1 - ccOffset];
                var b2r = cc[(k * 5 + 2) * ido + ic - 1 - ccOffset];
                var b5r = cc[(k * 5 + 5) * ido + i - 1 - ccOffset];
                var b4r = cc[(k * 5 + 4) * ido + ic - 1 - ccOffset];
                var ti5 = b3i + b2i;
                var ti2 = b3i - b2i;
                var ti4 = b5i + b4i;
                var ti3 = b5i - b4i;
                var tr5 = b3r - b2r;
                var tr2 = b3r + b2r;
                var tr4 = b5r - b4r;
                var tr3 = b5r + b4r;
                var c1r = cc[(k * 5 + 1) * ido + i - 1 - ccOffset];
                var c1i = cc[(k * 5 + 1) * ido + i - ccOffset];
                ch[(l1 + k) * ido + i - 1 - chOffset] = c1r + (tr2 + tr3);
                ch[(l1 + k) * ido + i - chOffset] = c1i + (ti2 + ti3);
                var cr2 = c1r + (tr11 * tr2 + tr12 * tr3);
                var ci2 = c1i + (tr11 * ti2 + tr12 * ti3);
                var cr3 = c1r + (tr12 * tr2 + tr11 * tr3);
                var ci3 = c1i + (tr12 * ti2 + tr11 * ti3);
                var cr5 = ti11 * tr5 + ti12 * tr4;
                var ci5 = ti11 * ti5 + ti12 * ti4;
                var cr4 = ti12 * tr5 - ti11 * tr4;
                var ci4 = ti12 * ti5 - ti11 * ti4;
                var dr3 = cr3 - ci4;
                var dr4 = cr3 + ci4;
                var di3 = ci3 + cr4;
                var di4 = ci3 - cr4;
                var dr5 = cr2 + ci5;
                var dr2 = cr2 - ci5;
                var di5 = ci2 - cr5;
                var di2 = ci2 + cr5;
                var wr = wa[wa1 + i - 3];
                var wi = wa[wa1 + i - 2];
                var tmp = dr2 * wi;
                dr2 = dr2 * wr;
                dr2 = dr2 - di2 * wi;
                di2 = di2 * wr;
                di2 = di2 + tmp;
                wr = wa[wa2 + i - 3];
                wi = wa[wa2 + i - 2];
                tmp = dr3 * wi;
                dr3 = dr3 * wr;
                dr3 = dr3 - di3 * wi;
                di3 = di3 * wr;
                di3 = di3 + tmp;
                wr = wa[wa3 + i - 3];
                wi = wa[wa3 + i - 2];
                tmp = dr4 * wi;
                dr4 = dr4 * wr;
                dr4 = dr4 - di4 * wi;
                di4 = di4 * wr;
                di4 = di4 + tmp;
                wr = wa[wa4 + i - 3];
                wi = wa[wa4 + i - 2];
                tmp = dr5 * wi;
                dr5 = dr5 * wr;
                dr5 = dr5 - di5 * wi;
                di5 = di5 * wr;
                di5 = di5 + tmp;
                ch[(2 * l1 + k) * ido + i - 1 - chOffset] = dr2;
                ch[(2 * l1 + k) * ido + i - chOffset] = di2;
                ch[(3 * l1 + k) * ido + i - 1 - chOffset] = dr3;
                ch[(3 * l1 + k) * ido + i - chOffset] = di3;
                ch[(4 * l1 + k) * ido + i - 1 - chOffset] = dr4;
                ch[(4 * l1 + k) * ido + i - chOffset] = di4;
                ch[(5 * l1 + k) * ido + i - 1 - chOffset] = dr5;
                ch[(5 * l1 + k) * ido + i - chOffset] = di5;
            }
        }
    }

    /**
     * Factors the size into fours, twos, threes and fives, a two first ({@code decompose}).
     *
     * @param n the size
     * @param ifac receives the factorization
     * @return the number of factors
     */
    private static int decompose(int n, int[] ifac) {
        int[] ntryh = {4, 2, 3, 5};
        var nl = n;
        var nf = 0;
        for (var ntry : ntryh) {
            while (nl != 1) {
                var nq = nl / ntry;
                var nr = nl - ntry * nq;
                if (nr == 0) {
                    ifac[2 + nf++] = ntry;
                    nl = nq;
                    if (ntry == 2 && nf != 1) {
                        for (var i = 2; i <= nf; ++i) {
                            var ib = nf - i + 2;
                            ifac[ib + 1] = ifac[ib];
                        }
                        ifac[2] = 2;
                    }
                } else {
                    break;
                }
            }
        }
        ifac[0] = n;
        ifac[1] = nf;
        return nf;
    }

    /**
     * Computes the twiddle factors ({@code rffti1_ps}).
     *
     * @param n the size
     * @param wa receives the twiddle factors
     * @param ifac receives the factorization
     */
    private static void rffti1(int n, float[] wa, int[] ifac) {
        var nf = decompose(n, ifac);
        var argh = (float) ((2 * Math.PI) / n);
        var is = 0;
        var nfm1 = nf - 1;
        var l1 = 1;
        for (var k1 = 1; k1 <= nfm1; k1++) {
            var ip = ifac[k1 + 1];
            var ld = 0;
            var l2 = l1 * ip;
            var ido = n / l2;
            var ipm = ip - 1;
            for (var j = 1; j <= ipm; ++j) {
                var i = is;
                var fi = 0;
                ld += l1;
                var argld = ld * argh;
                for (var ii = 3; ii <= ido; ii += 2) {
                    i += 2;
                    fi += 1;
                    wa[i - 2] = (float) StrictMath.cos(fi * argld);
                    wa[i - 1] = (float) StrictMath.sin(fi * argld);
                }
                is += ido;
            }
            l1 = l2;
        }
    }
}
