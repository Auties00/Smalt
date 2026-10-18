package com.github.auties00.smalt.audio.voice.internal;

/**
 * Computes in-place real discrete Fourier transforms of a fixed power-of-two length with
 * Takuya Ooura's split-radix routines.
 *
 * <p>The forward transform of {@code a[0..n-1]} leaves the packed spectrum in place:
 * {@code a[0]} holds the real DC term, {@code a[1]} the real Nyquist term, and
 * {@code a[2k], a[2k+1]} the real and imaginary parts of bin {@code k} for
 * {@code 0 < k < n/2}, with the sign convention {@code X[k] = sum x[j] exp(2 pi i j k / n)}.
 * The inverse transform takes the same packing and returns {@code n/2} times the signal, so a
 * round trip scales by {@code 2/n}. An instance holds its twiddle and bit-reversal tables and
 * is not safe for concurrent use.
 *
 * @implNote This implementation follows {@code WebRtc_rdft} in WebRTC's
 * {@code common_audio/third_party/ooura/fft_size_256/fft4g.cc} (Ooura's {@code fft4g.c} with
 * WebRTC's type changes), including the table construction of {@code makewt} and
 * {@code makect}, whose cosines and sines come from {@link FloatMath} so the tables match the
 * reference bit for bit.
 */
public final class OouraRealFft {
    /**
     * The transform length.
     */
    private final int n;

    /**
     * The number of entries of the twiddle table {@code w}.
     */
    private final int nw;

    /**
     * The number of entries of the cosine table that follows the twiddle table.
     */
    private final int nc;

    /**
     * The bit-reversal work area; entries from index 2 are rebuilt by every transform.
     */
    private final int[] ip;

    /**
     * The twiddle table ({@code nw} entries) followed by the real-transform cosine table
     * ({@code nc} entries).
     */
    private final float[] w;

    /**
     * Creates a transform of the given length and computes its tables.
     *
     * @param n the transform length, a power of two of at least 8
     * @throws IllegalArgumentException if {@code n} is not a power of two of at least 8
     */
    public OouraRealFft(int n) {
        if (n < 8 || Integer.bitCount(n) != 1) {
            throw new IllegalArgumentException("length must be a power of two of at least 8: " + n);
        }
        this.n = n;
        this.nw = n >> 2;
        this.nc = n >> 2;
        this.ip = new int[2 + (int) Math.sqrt(n / 2.0) + 2];
        this.w = new float[n / 2];
        makeTwiddles();
        makeCosines();
    }

    /**
     * Computes the twiddle table, {@code makewt} in the reference.
     */
    private void makeTwiddles() {
        if (nw > 2) {
            var nwh = nw >> 1;
            var delta = FloatMath.atanOne() / nwh;
            w[0] = 1;
            w[1] = 0;
            w[nwh] = FloatMath.cosf(delta * nwh);
            w[nwh + 1] = w[nwh];
            if (nwh > 2) {
                for (var j = 2; j < nwh; j += 2) {
                    var x = FloatMath.cosf(delta * j);
                    var y = FloatMath.sinf(delta * j);
                    w[j] = x;
                    w[j + 1] = y;
                    w[nw - j] = y;
                    w[nw - j + 1] = x;
                }
                bitReverse(nw, w, 0);
            }
        }
    }

    /**
     * Computes the real-transform cosine table after the twiddles, {@code makect} in the
     * reference.
     */
    private void makeCosines() {
        if (nc > 1) {
            var nch = nc >> 1;
            var delta = FloatMath.atanOne() / nch;
            w[nw] = FloatMath.cosf(delta * nch);
            w[nw + nch] = 0.5f * w[nw];
            for (var j = 1; j < nch; j++) {
                w[nw + j] = 0.5f * FloatMath.cosf(delta * j);
                w[nw + nc - j] = 0.5f * FloatMath.sinf(delta * j);
            }
        }
    }

    /**
     * Computes the forward transform of {@code a} in place.
     *
     * @param a the {@code n} input samples; receives the packed spectrum described on the class
     */
    public void forward(float[] a) {
        if (n > 4) {
            bitReverse(n, a, 0);
            complexForward(a);
            realForwardPost(a);
        }
        var xi = a[0] - a[1];
        a[0] += a[1];
        a[1] = xi;
    }

    /**
     * Computes the inverse transform of {@code a} in place, unscaled.
     *
     * @param a the packed spectrum described on the class; receives {@code n/2} times the
     *          signal
     */
    public void inverse(float[] a) {
        a[1] = 0.5f * (a[0] - a[1]);
        a[0] -= a[1];
        if (n > 4) {
            realInversePre(a);
            bitReverse(n, a, 0);
            complexInverse(a);
        }
    }

    /**
     * Permutes interleaved complex values into bit-reversed order, {@code bitrv2} in the
     * reference.
     *
     * @param size   the number of floats (twice the number of complex values)
     * @param a      the data
     * @param offset the index of the first float
     */
    private void bitReverse(int size, float[] a, int offset) {
        var ip = this.ip;
        ip[2] = 0;
        var l = size;
        var m = 1;
        while ((m << 3) < l) {
            l >>= 1;
            for (var j = 0; j < m; j++) {
                ip[2 + m + j] = ip[2 + j] + l;
            }
            m <<= 1;
        }
        var m2 = 2 * m;
        if ((m << 3) == l) {
            for (var k = 0; k < m; k++) {
                for (var j = 0; j < k; j++) {
                    var j1 = 2 * j + ip[2 + k];
                    var k1 = 2 * k + ip[2 + j];
                    swap(a, offset + j1, offset + k1);
                    j1 += m2;
                    k1 += 2 * m2;
                    swap(a, offset + j1, offset + k1);
                    j1 += m2;
                    k1 -= m2;
                    swap(a, offset + j1, offset + k1);
                    j1 += m2;
                    k1 += 2 * m2;
                    swap(a, offset + j1, offset + k1);
                }
                var j1 = 2 * k + m2 + ip[2 + k];
                var k1 = j1 + m2;
                swap(a, offset + j1, offset + k1);
            }
        } else {
            for (var k = 1; k < m; k++) {
                for (var j = 0; j < k; j++) {
                    var j1 = 2 * j + ip[2 + k];
                    var k1 = 2 * k + ip[2 + j];
                    swap(a, offset + j1, offset + k1);
                    j1 += m2;
                    k1 += m2;
                    swap(a, offset + j1, offset + k1);
                }
            }
        }
    }

    /**
     * Swaps the complex values at two float indices.
     *
     * @param a the data
     * @param i the index of the real part of the first value
     * @param j the index of the real part of the second value
     */
    private static void swap(float[] a, int i, int j) {
        var xr = a[i];
        var xi = a[i + 1];
        a[i] = a[j];
        a[i + 1] = a[j + 1];
        a[j] = xr;
        a[j + 1] = xi;
    }

    /**
     * Runs the forward complex butterflies, {@code cftfsub} in the reference.
     *
     * @param a the bit-reversed data
     */
    private void complexForward(float[] a) {
        var l = 2;
        if (n > 8) {
            firstStage(a);
            l = 8;
            while ((l << 2) < n) {
                middleStage(l, a);
                l <<= 2;
            }
        }
        if ((l << 2) == n) {
            for (var j = 0; j < l; j += 2) {
                var j1 = j + l;
                var j2 = j1 + l;
                var j3 = j2 + l;
                var x0r = a[j] + a[j1];
                var x0i = a[j + 1] + a[j1 + 1];
                var x1r = a[j] - a[j1];
                var x1i = a[j + 1] - a[j1 + 1];
                var x2r = a[j2] + a[j3];
                var x2i = a[j2 + 1] + a[j3 + 1];
                var x3r = a[j2] - a[j3];
                var x3i = a[j2 + 1] - a[j3 + 1];
                a[j] = x0r + x2r;
                a[j + 1] = x0i + x2i;
                a[j2] = x0r - x2r;
                a[j2 + 1] = x0i - x2i;
                a[j1] = x1r - x3i;
                a[j1 + 1] = x1i + x3r;
                a[j3] = x1r + x3i;
                a[j3 + 1] = x1i - x3r;
            }
        } else {
            for (var j = 0; j < l; j += 2) {
                var j1 = j + l;
                var x0r = a[j] - a[j1];
                var x0i = a[j + 1] - a[j1 + 1];
                a[j] += a[j1];
                a[j + 1] += a[j1 + 1];
                a[j1] = x0r;
                a[j1 + 1] = x0i;
            }
        }
    }

    /**
     * Runs the inverse complex butterflies, {@code cftbsub} in the reference.
     *
     * @param a the bit-reversed data
     */
    private void complexInverse(float[] a) {
        var l = 2;
        if (n > 8) {
            firstStage(a);
            l = 8;
            while ((l << 2) < n) {
                middleStage(l, a);
                l <<= 2;
            }
        }
        if ((l << 2) == n) {
            for (var j = 0; j < l; j += 2) {
                var j1 = j + l;
                var j2 = j1 + l;
                var j3 = j2 + l;
                var x0r = a[j] + a[j1];
                var x0i = -a[j + 1] - a[j1 + 1];
                var x1r = a[j] - a[j1];
                var x1i = -a[j + 1] + a[j1 + 1];
                var x2r = a[j2] + a[j3];
                var x2i = a[j2 + 1] + a[j3 + 1];
                var x3r = a[j2] - a[j3];
                var x3i = a[j2 + 1] - a[j3 + 1];
                a[j] = x0r + x2r;
                a[j + 1] = x0i - x2i;
                a[j2] = x0r - x2r;
                a[j2 + 1] = x0i + x2i;
                a[j1] = x1r - x3i;
                a[j1 + 1] = x1i - x3r;
                a[j3] = x1r + x3i;
                a[j3 + 1] = x1i + x3r;
            }
        } else {
            for (var j = 0; j < l; j += 2) {
                var j1 = j + l;
                var x0r = a[j] - a[j1];
                var x0i = -a[j + 1] + a[j1 + 1];
                a[j] += a[j1];
                a[j + 1] = -a[j + 1] - a[j1 + 1];
                a[j1] = x0r;
                a[j1 + 1] = x0i;
            }
        }
    }

    /**
     * Runs the first radix-4 stage, {@code cft1st} in the reference.
     *
     * @param a the data
     */
    private void firstStage(float[] a) {
        var w = this.w;
        var x0r = a[0] + a[2];
        var x0i = a[1] + a[3];
        var x1r = a[0] - a[2];
        var x1i = a[1] - a[3];
        var x2r = a[4] + a[6];
        var x2i = a[5] + a[7];
        var x3r = a[4] - a[6];
        var x3i = a[5] - a[7];
        a[0] = x0r + x2r;
        a[1] = x0i + x2i;
        a[4] = x0r - x2r;
        a[5] = x0i - x2i;
        a[2] = x1r - x3i;
        a[3] = x1i + x3r;
        a[6] = x1r + x3i;
        a[7] = x1i - x3r;
        var wk1r = w[2];
        x0r = a[8] + a[10];
        x0i = a[9] + a[11];
        x1r = a[8] - a[10];
        x1i = a[9] - a[11];
        x2r = a[12] + a[14];
        x2i = a[13] + a[15];
        x3r = a[12] - a[14];
        x3i = a[13] - a[15];
        a[8] = x0r + x2r;
        a[9] = x0i + x2i;
        a[12] = x2i - x0i;
        a[13] = x0r - x2r;
        x0r = x1r - x3i;
        x0i = x1i + x3r;
        a[10] = wk1r * (x0r - x0i);
        a[11] = wk1r * (x0r + x0i);
        x0r = x3i + x1r;
        x0i = x3r - x1i;
        a[14] = wk1r * (x0i - x0r);
        a[15] = wk1r * (x0i + x0r);
        var k1 = 0;
        for (var j = 16; j < n; j += 16) {
            k1 += 2;
            var k2 = 2 * k1;
            var wk2r = w[k1];
            var wk2i = w[k1 + 1];
            wk1r = w[k2];
            var wk1i = w[k2 + 1];
            var wk3r = wk1r - 2 * wk2i * wk1i;
            var wk3i = 2 * wk2i * wk1r - wk1i;
            x0r = a[j] + a[j + 2];
            x0i = a[j + 1] + a[j + 3];
            x1r = a[j] - a[j + 2];
            x1i = a[j + 1] - a[j + 3];
            x2r = a[j + 4] + a[j + 6];
            x2i = a[j + 5] + a[j + 7];
            x3r = a[j + 4] - a[j + 6];
            x3i = a[j + 5] - a[j + 7];
            a[j] = x0r + x2r;
            a[j + 1] = x0i + x2i;
            x0r -= x2r;
            x0i -= x2i;
            a[j + 4] = wk2r * x0r - wk2i * x0i;
            a[j + 5] = wk2r * x0i + wk2i * x0r;
            x0r = x1r - x3i;
            x0i = x1i + x3r;
            a[j + 2] = wk1r * x0r - wk1i * x0i;
            a[j + 3] = wk1r * x0i + wk1i * x0r;
            x0r = x1r + x3i;
            x0i = x1i - x3r;
            a[j + 6] = wk3r * x0r - wk3i * x0i;
            a[j + 7] = wk3r * x0i + wk3i * x0r;
            wk1r = w[k2 + 2];
            wk1i = w[k2 + 3];
            wk3r = wk1r - 2 * wk2r * wk1i;
            wk3i = 2 * wk2r * wk1r - wk1i;
            x0r = a[j + 8] + a[j + 10];
            x0i = a[j + 9] + a[j + 11];
            x1r = a[j + 8] - a[j + 10];
            x1i = a[j + 9] - a[j + 11];
            x2r = a[j + 12] + a[j + 14];
            x2i = a[j + 13] + a[j + 15];
            x3r = a[j + 12] - a[j + 14];
            x3i = a[j + 13] - a[j + 15];
            a[j + 8] = x0r + x2r;
            a[j + 9] = x0i + x2i;
            x0r -= x2r;
            x0i -= x2i;
            a[j + 12] = -wk2i * x0r - wk2r * x0i;
            a[j + 13] = -wk2i * x0i + wk2r * x0r;
            x0r = x1r - x3i;
            x0i = x1i + x3r;
            a[j + 10] = wk1r * x0r - wk1i * x0i;
            a[j + 11] = wk1r * x0i + wk1i * x0r;
            x0r = x1r + x3i;
            x0i = x1i - x3r;
            a[j + 14] = wk3r * x0r - wk3i * x0i;
            a[j + 15] = wk3r * x0i + wk3i * x0r;
        }
    }

    /**
     * Runs a middle radix-4 stage of span {@code l}, {@code cftmdl} in the reference.
     *
     * @param l the stage span
     * @param a the data
     */
    private void middleStage(int l, float[] a) {
        var w = this.w;
        var m = l << 2;
        for (var j = 0; j < l; j += 2) {
            var j1 = j + l;
            var j2 = j1 + l;
            var j3 = j2 + l;
            var x0r = a[j] + a[j1];
            var x0i = a[j + 1] + a[j1 + 1];
            var x1r = a[j] - a[j1];
            var x1i = a[j + 1] - a[j1 + 1];
            var x2r = a[j2] + a[j3];
            var x2i = a[j2 + 1] + a[j3 + 1];
            var x3r = a[j2] - a[j3];
            var x3i = a[j2 + 1] - a[j3 + 1];
            a[j] = x0r + x2r;
            a[j + 1] = x0i + x2i;
            a[j2] = x0r - x2r;
            a[j2 + 1] = x0i - x2i;
            a[j1] = x1r - x3i;
            a[j1 + 1] = x1i + x3r;
            a[j3] = x1r + x3i;
            a[j3 + 1] = x1i - x3r;
        }
        var wk1r = w[2];
        for (var j = m; j < l + m; j += 2) {
            var j1 = j + l;
            var j2 = j1 + l;
            var j3 = j2 + l;
            var x0r = a[j] + a[j1];
            var x0i = a[j + 1] + a[j1 + 1];
            var x1r = a[j] - a[j1];
            var x1i = a[j + 1] - a[j1 + 1];
            var x2r = a[j2] + a[j3];
            var x2i = a[j2 + 1] + a[j3 + 1];
            var x3r = a[j2] - a[j3];
            var x3i = a[j2 + 1] - a[j3 + 1];
            a[j] = x0r + x2r;
            a[j + 1] = x0i + x2i;
            a[j2] = x2i - x0i;
            a[j2 + 1] = x0r - x2r;
            x0r = x1r - x3i;
            x0i = x1i + x3r;
            a[j1] = wk1r * (x0r - x0i);
            a[j1 + 1] = wk1r * (x0r + x0i);
            x0r = x3i + x1r;
            x0i = x3r - x1i;
            a[j3] = wk1r * (x0i - x0r);
            a[j3 + 1] = wk1r * (x0i + x0r);
        }
        var k1 = 0;
        var m2 = 2 * m;
        for (var k = m2; k < n; k += m2) {
            k1 += 2;
            var k2 = 2 * k1;
            var wk2r = w[k1];
            var wk2i = w[k1 + 1];
            wk1r = w[k2];
            var wk1i = w[k2 + 1];
            var wk3r = wk1r - 2 * wk2i * wk1i;
            var wk3i = 2 * wk2i * wk1r - wk1i;
            for (var j = k; j < l + k; j += 2) {
                var j1 = j + l;
                var j2 = j1 + l;
                var j3 = j2 + l;
                var x0r = a[j] + a[j1];
                var x0i = a[j + 1] + a[j1 + 1];
                var x1r = a[j] - a[j1];
                var x1i = a[j + 1] - a[j1 + 1];
                var x2r = a[j2] + a[j3];
                var x2i = a[j2 + 1] + a[j3 + 1];
                var x3r = a[j2] - a[j3];
                var x3i = a[j2 + 1] - a[j3 + 1];
                a[j] = x0r + x2r;
                a[j + 1] = x0i + x2i;
                x0r -= x2r;
                x0i -= x2i;
                a[j2] = wk2r * x0r - wk2i * x0i;
                a[j2 + 1] = wk2r * x0i + wk2i * x0r;
                x0r = x1r - x3i;
                x0i = x1i + x3r;
                a[j1] = wk1r * x0r - wk1i * x0i;
                a[j1 + 1] = wk1r * x0i + wk1i * x0r;
                x0r = x1r + x3i;
                x0i = x1i - x3r;
                a[j3] = wk3r * x0r - wk3i * x0i;
                a[j3 + 1] = wk3r * x0i + wk3i * x0r;
            }
            wk1r = w[k2 + 2];
            wk1i = w[k2 + 3];
            wk3r = wk1r - 2 * wk2r * wk1i;
            wk3i = 2 * wk2r * wk1r - wk1i;
            for (var j = k + m; j < l + (k + m); j += 2) {
                var j1 = j + l;
                var j2 = j1 + l;
                var j3 = j2 + l;
                var x0r = a[j] + a[j1];
                var x0i = a[j + 1] + a[j1 + 1];
                var x1r = a[j] - a[j1];
                var x1i = a[j + 1] - a[j1 + 1];
                var x2r = a[j2] + a[j3];
                var x2i = a[j2 + 1] + a[j3 + 1];
                var x3r = a[j2] - a[j3];
                var x3i = a[j2 + 1] - a[j3 + 1];
                a[j] = x0r + x2r;
                a[j + 1] = x0i + x2i;
                x0r -= x2r;
                x0i -= x2i;
                a[j2] = -wk2i * x0r - wk2r * x0i;
                a[j2 + 1] = -wk2i * x0i + wk2r * x0r;
                x0r = x1r - x3i;
                x0i = x1i + x3r;
                a[j1] = wk1r * x0r - wk1i * x0i;
                a[j1 + 1] = wk1r * x0i + wk1i * x0r;
                x0r = x1r + x3i;
                x0i = x1i - x3r;
                a[j3] = wk3r * x0r - wk3i * x0i;
                a[j3 + 1] = wk3r * x0i + wk3i * x0r;
            }
        }
    }

    /**
     * Converts the complex transform into the real transform, {@code rftfsub} in the
     * reference.
     *
     * @param a the data
     */
    private void realForwardPost(float[] a) {
        var w = this.w;
        var m = n >> 1;
        var ks = 2 * nc / m;
        var kk = 0;
        for (var j = 2; j < m; j += 2) {
            var k = n - j;
            kk += ks;
            var wkr = 0.5f - w[nw + nc - kk];
            var wki = w[nw + kk];
            var xr = a[j] - a[k];
            var xi = a[j + 1] + a[k + 1];
            var yr = wkr * xr - wki * xi;
            var yi = wkr * xi + wki * xr;
            a[j] -= yr;
            a[j + 1] -= yi;
            a[k] += yr;
            a[k + 1] -= yi;
        }
    }

    /**
     * Converts the real spectrum into a complex one before the inverse butterflies,
     * {@code rftbsub} in the reference.
     *
     * @param a the data
     */
    private void realInversePre(float[] a) {
        var w = this.w;
        a[1] = -a[1];
        var m = n >> 1;
        var ks = 2 * nc / m;
        var kk = 0;
        for (var j = 2; j < m; j += 2) {
            var k = n - j;
            kk += ks;
            var wkr = 0.5f - w[nw + nc - kk];
            var wki = w[nw + kk];
            var xr = a[j] - a[k];
            var xi = a[j + 1] + a[k + 1];
            var yr = wkr * xr + wki * xi;
            var yi = wkr * xi - wki * xr;
            a[j] -= yr;
            a[j + 1] = yi - a[j + 1];
            a[k] += yr;
            a[k + 1] = yi - a[k + 1];
        }
        a[m + 1] = -a[m + 1];
    }
}
