package com.github.auties00.smalt.audio.aac.internal.sbr;

/**
 * Implements {@link SbrKernel} with plain loops; the reference the Vector API implementation
 * matches bit for bit.
 *
 * @implNote This implementation transforms {@link #WIDTH} slots per chunk, the width of a
 * 256-bit vector, with the lane loop innermost; the results of a slot do not depend on the
 * width, so the vector implementation matches it whatever width the platform gives it.
 */
final class ScalarSbrKernel implements SbrKernel {
    /**
     * The number of slots per chunk.
     */
    private static final int WIDTH = 8;

    /**
     * Creates the kernel.
     */
    ScalarSbrKernel() {
    }

    @Override
    public int width() {
        return WIDTH;
    }

    @Override
    public void analysisWindow(float[] x, int base, float[] window, float[] out) {
        for (var l = 0; l < WIDTH; l++) {
            for (var m = 0; m < 64; m++) {
                var i = base + 32 * l + m;
                out[64 * l + m] = x[i] * window[m] + x[i - 64] * window[m + 64] + x[i - 128] * window[m + 128]
                        + x[i - 192] * window[m + 192] + x[i - 256] * window[m + 256];
            }
        }
    }

    @Override
    public void foldRows(float[] u, float[] cos, float[] sin, float[] fr, float[] fi) {
        for (var k = 0; k < 16; k++) {
            var c = cos[k];
            var s = sin[k];
            var u0 = (63 - 2 * k) * WIDTH;
            var u1 = (32 + 2 * k) * WIDTH;
            var u2 = (31 - 2 * k) * WIDTH;
            var u3 = 2 * k * WIDTH;
            for (var l = 0; l < WIDTH; l++) {
                var x0 = u[u0 + l];
                var x1 = u[u1 + l];
                var x2 = u[u2 + l];
                var x3 = u[u3 + l];
                var a = x0 - x3;
                var b = x1 - x2;
                fr[k * WIDTH + l] = a * c + b * s;
                fi[k * WIDTH + l] = b * c - a * s;
                var a2 = x1 + x2;
                var b2 = x0 + x3;
                fr[(16 + k) * WIDTH + l] = a2 * c + b2 * s;
                fi[(16 + k) * WIDTH + l] = b2 * c - a2 * s;
            }
        }
    }

    @Override
    public void radix2(float[] fr, float[] fi, int base, float[] tw) {
        final var half = 8;
        for (var j = 0; j < half; j++) {
            var c = tw[j];
            var s = tw[half + j];
            var i0 = (base + j) * WIDTH;
            var i1 = (base + j + half) * WIDTH;
            for (var l = 0; l < WIDTH; l++) {
                var ar = fr[i0 + l];
                var ai = fi[i0 + l];
                var br = fr[i1 + l];
                var bi = fi[i1 + l];
                fr[i0 + l] = ar + br;
                fi[i0 + l] = ai + bi;
                var dr = ar - br;
                var di = ai - bi;
                fr[i1 + l] = dr * c - di * s;
                fi[i1 + l] = dr * s + di * c;
            }
        }
    }

    @Override
    public void radix4(float[] fr, float[] fi, int base, float[] tw) {
        final var quarter = 8;
        for (var j = 0; j < quarter; j++) {
            var c1 = tw[j];
            var c2 = tw[quarter + j];
            var c3 = tw[2 * quarter + j];
            var s1 = tw[3 * quarter + j];
            var s2 = tw[4 * quarter + j];
            var s3 = tw[5 * quarter + j];
            var i0 = (base + j) * WIDTH;
            var i1 = i0 + quarter * WIDTH;
            var i2 = i1 + quarter * WIDTH;
            var i3 = i2 + quarter * WIDTH;
            for (var l = 0; l < WIDTH; l++) {
                var x0r = fr[i0 + l];
                var x0i = fi[i0 + l];
                var x1r = fr[i1 + l];
                var x1i = fi[i1 + l];
                var x2r = fr[i2 + l];
                var x2i = fi[i2 + l];
                var x3r = fr[i3 + l];
                var x3i = fi[i3 + l];
                var ar = x0r + x2r;
                var ai = x0i + x2i;
                var br = x1r + x3r;
                var bi = x1i + x3i;
                var cr = x0r - x2r;
                var ci = x0i - x2i;
                var dr = x1r - x3r;
                var di = x1i - x3i;
                fr[i0 + l] = ar + br;
                fi[i0 + l] = ai + bi;
                var y1r = cr + di;
                var y1i = ci - dr;
                var y2r = ar - br;
                var y2i = ai - bi;
                var y3r = cr - di;
                var y3i = ci + dr;
                fr[i1 + l] = y1r * c1 - y1i * s1;
                fi[i1 + l] = y1r * s1 + y1i * c1;
                fr[i2 + l] = y2r * c2 - y2i * s2;
                fi[i2 + l] = y2r * s2 + y2i * c2;
                fr[i3 + l] = y3r * c3 - y3i * s3;
                fi[i3 + l] = y3r * s3 + y3i * c3;
            }
        }
    }

    @Override
    public void tail8(float[] fr, float[] fi, int base, int blocks) {
        final var r = (float) Math.sqrt(0.5);
        for (var block = 0; block < blocks; block++) {
            var b = (base + 8 * block) * WIDTH;
            for (var l = 0; l < WIDTH; l++) {
                var x0r = fr[b + l];
                var x0i = fi[b + l];
                var x1r = fr[b + WIDTH + l];
                var x1i = fi[b + WIDTH + l];
                var x2r = fr[b + 2 * WIDTH + l];
                var x2i = fi[b + 2 * WIDTH + l];
                var x3r = fr[b + 3 * WIDTH + l];
                var x3i = fi[b + 3 * WIDTH + l];
                var x4r = fr[b + 4 * WIDTH + l];
                var x4i = fi[b + 4 * WIDTH + l];
                var x5r = fr[b + 5 * WIDTH + l];
                var x5i = fi[b + 5 * WIDTH + l];
                var x6r = fr[b + 6 * WIDTH + l];
                var x6i = fi[b + 6 * WIDTH + l];
                var x7r = fr[b + 7 * WIDTH + l];
                var x7i = fi[b + 7 * WIDTH + l];
                var a0r = x0r + x4r;
                var a0i = x0i + x4i;
                var a1r = x1r + x5r;
                var a1i = x1i + x5i;
                var a2r = x2r + x6r;
                var a2i = x2i + x6i;
                var a3r = x3r + x7r;
                var a3i = x3i + x7i;
                var a4r = x0r - x4r;
                var a4i = x0i - x4i;
                var tr = x1r - x5r;
                var ti = x1i - x5i;
                var a5r = (tr + ti) * r;
                var a5i = (ti - tr) * r;
                var a6r = x2i - x6i;
                var a6i = x6r - x2r;
                tr = x3r - x7r;
                ti = x3i - x7i;
                var a7r = (ti - tr) * r;
                var a7i = -(tr + ti) * r;
                var b0r = a0r + a2r;
                var b0i = a0i + a2i;
                var b1r = a1r + a3r;
                var b1i = a1i + a3i;
                var b2r = a0r - a2r;
                var b2i = a0i - a2i;
                var b3r = a1i - a3i;
                var b3i = a3r - a1r;
                var b4r = a4r + a6r;
                var b4i = a4i + a6i;
                var b5r = a5r + a7r;
                var b5i = a5i + a7i;
                var b6r = a4r - a6r;
                var b6i = a4i - a6i;
                var b7r = a5i - a7i;
                var b7i = a7r - a5r;
                fr[b + l] = b0r + b1r;
                fi[b + l] = b0i + b1i;
                fr[b + WIDTH + l] = b0r - b1r;
                fi[b + WIDTH + l] = b0i - b1i;
                fr[b + 2 * WIDTH + l] = b2r + b3r;
                fi[b + 2 * WIDTH + l] = b2i + b3i;
                fr[b + 3 * WIDTH + l] = b2r - b3r;
                fi[b + 3 * WIDTH + l] = b2i - b3i;
                fr[b + 4 * WIDTH + l] = b4r + b5r;
                fi[b + 4 * WIDTH + l] = b4i + b5i;
                fr[b + 5 * WIDTH + l] = b4r - b5r;
                fi[b + 5 * WIDTH + l] = b4i - b5i;
                fr[b + 6 * WIDTH + l] = b6r + b7r;
                fi[b + 6 * WIDTH + l] = b6i + b7i;
                fr[b + 7 * WIDTH + l] = b6r - b7r;
                fi[b + 7 * WIDTH + l] = b6i - b7i;
            }
        }
    }

    @Override
    public void analysisOutput(float[] fr, float[] fi, int[] positions, float[] cos, float[] sin, float[] rotRe,
                               float[] rotIm, float[] re, float[] im) {
        for (var j = 0; j < 16; j++) {
            var c = cos[j];
            var s = sin[j];
            var p = positions[j] * WIDTH;
            var q = (16 + positions[j]) * WIDTH;
            var even = 2 * j;
            var odd = 31 - 2 * j;
            var rEvenRe = rotRe[even];
            var rEvenIm = rotIm[even];
            var rOddRe = rotRe[odd];
            var rOddIm = rotIm[odd];
            for (var l = 0; l < WIDTH; l++) {
                var xr = fr[p + l];
                var xi = fi[p + l];
                var yr = fr[q + l];
                var yi = fi[q + l];
                var x0 = xr * c + xi * s;
                var x1 = xr * s - xi * c;
                var y0 = yr * c + yi * s;
                var y1 = yi * c - yr * s;
                re[even * WIDTH + l] = x0 * rEvenRe - y0 * rEvenIm;
                im[even * WIDTH + l] = y0 * rEvenRe + x0 * rEvenIm;
                re[odd * WIDTH + l] = x1 * rOddRe - y1 * rOddIm;
                im[odd * WIDTH + l] = y1 * rOddRe + x1 * rOddIm;
            }
        }
    }

    @Override
    public void synthesisInput(float[] re, float[] im, int bands, float[] cos, float[] sin, float[] fr, float[] fi) {
        var half = bands / 2;
        for (var k = 0; k < half; k++) {
            var c = cos[k];
            var s = sin[k];
            var even = 2 * k * WIDTH;
            var odd = (bands - 1 - 2 * k) * WIDTH;
            for (var l = 0; l < WIDTH; l++) {
                var a = re[even + l];
                var b = re[odd + l];
                fr[k * WIDTH + l] = a * c + b * s;
                fi[k * WIDTH + l] = b * c - a * s;
                var a2 = im[odd + l];
                var b2 = im[even + l];
                fr[(half + k) * WIDTH + l] = a2 * c + b2 * s;
                fi[(half + k) * WIDTH + l] = b2 * c - a2 * s;
            }
        }
    }

    @Override
    public void synthesisOutput(float[] fr, float[] fi, int[] positions, float[] cos, float[] sin, int bands,
                                float[] v) {
        var half = bands / 2;
        for (var m = 0; m < half; m++) {
            var other = half - 1 - m;
            var cA = cos[m];
            var sA = sin[m];
            var cB = cos[other];
            var sB = sin[other];
            var pA = positions[m] * WIDTH;
            var pB = positions[other] * WIDTH;
            var qA = pA + half * WIDTH;
            var qB = pB + half * WIDTH;
            var low0 = 2 * m * WIDTH;
            var low1 = low0 + WIDTH;
            var high0 = (2 * bands - 1 - 2 * m) * WIDTH;
            var high1 = high0 - WIDTH;
            for (var l = 0; l < WIDTH; l++) {
                var p0 = fr[pA + l] * cA + fi[pA + l] * sA;
                var p1 = fr[pB + l] * sB - fi[pB + l] * cB;
                var q0 = fr[qA + l] * cA + fi[qA + l] * sA;
                var q1 = fi[qB + l] * cB - fr[qB + l] * sB;
                v[low0 + l] = q0 - p0;
                v[low1 + l] = q1 - p1;
                v[high0 + l] = p0 + q0;
                v[high1 + l] = p1 + q1;
            }
        }
    }

    @Override
    public void synthesisWindow(float[] v, int at, float[] window, int bands, float[] out, int offset, int slots) {
        for (var s = 0; s < slots; s++) {
            var base = at + 2 * bands * s;
            for (var j = 0; j < bands; j++) {
                var sum = v[base + j] * window[j];
                sum += v[base - bands + j] * window[bands + j];
                for (var i = 1; i < 5; i++) {
                    sum += v[base - 4 * bands * i + j] * window[2 * bands * i + j];
                    sum += v[base - 4 * bands * i - bands + j] * window[2 * bands * i + bands + j];
                }
                out[offset + bands * s + j] = sum;
            }
        }
    }

    @Override
    public void transpose(float[] src, int srcOffset, int srcStride, float[] dst, int dstOffset, int dstStride,
                          int rows, int cols) {
        for (var i = 0; i < rows; i++) {
            for (var j = 0; j < cols; j++) {
                dst[dstOffset + j * dstStride + i] = src[srcOffset + i * srcStride + j];
            }
        }
    }

    @Override
    public void covariance(float[] re, float[] im, int bands, int count, double[] out) {
        for (var band = 1; band < bands; band++) {
            double r01r = 0;
            double r01i = 0;
            double r02r = 0;
            double r02i = 0;
            double r11 = 0;
            double r12r = 0;
            double r12i = 0;
            double r22 = 0;
            for (var n = 0; n < count; n++) {
                var i0 = (n + 2) * 32 + band;
                var i1 = (n + 1) * 32 + band;
                var i2 = n * 32 + band;
                double ar = re[i0];
                double ai = im[i0];
                double br = re[i1];
                double bi = im[i1];
                double cr = re[i2];
                double ci = im[i2];
                r01r += ar * br + ai * bi;
                r01i += ai * br - ar * bi;
                r02r += ar * cr + ai * ci;
                r02i += ai * cr - ar * ci;
                r11 += br * br + bi * bi;
                r12r += br * cr + bi * ci;
                r12i += bi * cr - br * ci;
                r22 += cr * cr + ci * ci;
            }
            out[band] = r01r;
            out[32 + band] = r01i;
            out[64 + band] = r02r;
            out[96 + band] = r02i;
            out[128 + band] = r11;
            out[160 + band] = r12r;
            out[192 + band] = r12i;
            out[224 + band] = r22;
        }
    }

    @Override
    public void applyGains(float[] re, float[] im, int offset, float[] gain, float[] noise, float[] noiseRe,
                           float[] noiseIm, int noiseOffset, int count) {
        for (var k = 0; k < count; k++) {
            re[offset + k] = re[offset + k] * gain[k] + noise[k] * noiseRe[noiseOffset + k];
            im[offset + k] = im[offset + k] * gain[k] + noise[k] * noiseIm[noiseOffset + k];
        }
    }
}
