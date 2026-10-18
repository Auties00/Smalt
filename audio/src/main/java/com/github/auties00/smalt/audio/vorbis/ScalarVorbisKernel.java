package com.github.auties00.smalt.audio.vorbis;

/**
 * Implements {@link VorbisKernel} with plain scalar arithmetic; the reference implementation.
 */
final class ScalarVorbisKernel extends VorbisKernel {
    /**
     * Constructs the kernel.
     */
    ScalarVorbisKernel() {
    }

    @Override
    void imdct(Imdct plan, float[] spectrum, float[] output, float[] re, float[] im) {
        preRotate(plan, spectrum, re, im);
        fft(plan, re, im);
        postRotate(plan, re, im, output);
    }

    /**
     * Packs the coefficients into complex FFT input and applies the pre-rotation.
     *
     * @param plan the tables of the block size
     * @param spectrum the {@code n/2} coefficients
     * @param re receives the real parts
     * @param im receives the imaginary parts
     */
    static void preRotate(Imdct plan, float[] spectrum, float[] re, float[] im) {
        var quarter = plan.quarter;
        var last = plan.half - 1;
        var cos = plan.rotationCos;
        var sin = plan.rotationSin;
        for (var p = 0; p < quarter; p++) {
            var a = spectrum[2 * p];
            var b = spectrum[last - 2 * p];
            var c = cos[p];
            var s = sin[p];
            re[p] = a * c + b * s;
            im[p] = b * c - a * s;
        }
    }

    /**
     * Applies the post-rotation to the bit-reversed FFT output and unfolds the resulting DCT-IV
     * values into the {@code n} inverse MDCT samples.
     *
     * @param plan the tables of the block size
     * @param re the real parts of the FFT output, bit-reversed
     * @param im the imaginary parts of the FFT output, bit-reversed
     * @param output receives the {@code n} samples
     */
    static void postRotate(Imdct plan, float[] re, float[] im, float[] output) {
        var quarter = plan.quarter;
        var half = plan.half;
        var cos = plan.rotationCos;
        var sin = plan.rotationSin;
        var reverse = plan.bitReverse;
        var mirror = half + half / 2 - 1;
        var eighth = quarter / 2;
        var lowQuarter = half / 2;
        for (var q = 0; q < eighth; q++) {
            var r = reverse[q];
            var tr = re[r];
            var ti = im[r];
            var c = cos[q];
            var s = sin[q];
            var even = tr * c + ti * s;
            var odd = tr * s - ti * c;
            output[mirror - 2 * q] = -even;
            output[lowQuarter + 2 * q] = -odd;
            output[mirror + 1 + 2 * q] = -even;
            output[lowQuarter - 1 - 2 * q] = odd;
        }
        var top = 2 * half + lowQuarter - 1;
        for (var q = eighth; q < quarter; q++) {
            var r = reverse[q];
            var tr = re[r];
            var ti = im[r];
            var c = cos[q];
            var s = sin[q];
            var even = tr * c + ti * s;
            var odd = tr * s - ti * c;
            output[mirror - 2 * q] = -even;
            output[lowQuarter + 2 * q] = -odd;
            output[2 * q - lowQuarter] = even;
            output[top - 2 * q] = -odd;
        }
    }

    /**
     * Computes the in-place decimation-in-frequency FFT in the canonical order of {@link Imdct},
     * leaving the output bit-reversed.
     *
     * @param plan the tables of the block size
     * @param re the real parts
     * @param im the imaginary parts
     */
    static void fft(Imdct plan, float[] re, float[] im) {
        var n = plan.quarter;
        var length = n;
        if (plan.radix2) {
            var h = n / 2;
            var tw = plan.radix2Twiddles;
            for (var k = 0; k < h; k++) {
                var ar = re[k];
                var ai = im[k];
                var br = re[k + h];
                var bi = im[k + h];
                re[k] = ar + br;
                im[k] = ai + bi;
                var dr = ar - br;
                var di = ai - bi;
                var wr = tw[k];
                var wi = tw[h + k];
                re[k + h] = dr * wr - di * wi;
                im[k + h] = dr * wi + di * wr;
            }
            length = h;
        }
        var tw = plan.radix4Twiddles;
        var offset = 0;
        for (; length > 4; length >>= 2) {
            var s = length / 4;
            for (var base = 0; base < n; base += length) {
                radix4(re, im, base, s, tw, offset);
            }
            offset += 6 * s;
        }
        lastRadix4(re, im, n);
    }

    /**
     * Runs the last radix-4 stage, whose butterflies take four adjacent inputs and whose twiddles
     * are all one.
     *
     * @param re the real parts
     * @param im the imaginary parts
     * @param n the transform size
     */
    private static void lastRadix4(float[] re, float[] im, int n) {
        for (var i0 = 0; i0 < n; i0 += 4) {
            var x0r = re[i0];
            var x0i = im[i0];
            var x1r = re[i0 + 1];
            var x1i = im[i0 + 1];
            var x2r = re[i0 + 2];
            var x2i = im[i0 + 2];
            var x3r = re[i0 + 3];
            var x3i = im[i0 + 3];
            var a0r = x0r + x2r;
            var a0i = x0i + x2i;
            var a1r = x0r - x2r;
            var a1i = x0i - x2i;
            var a2r = x1r + x3r;
            var a2i = x1i + x3i;
            var a3r = x1r - x3r;
            var a3i = x1i - x3i;
            re[i0] = a0r + a2r;
            im[i0] = a0i + a2i;
            re[i0 + 1] = a0r - a2r;
            im[i0 + 1] = a0i - a2i;
            re[i0 + 2] = a1r + a3i;
            im[i0 + 2] = a1i - a3r;
            re[i0 + 3] = a1r - a3i;
            im[i0 + 3] = a1i + a3r;
        }
    }

    /**
     * Runs the radix-4 butterflies of one group.
     *
     * @param re the real parts
     * @param im the imaginary parts
     * @param base the first index of the group
     * @param s the distance between the four inputs of a butterfly
     * @param tw the radix-4 twiddle table
     * @param offset the offset of this stage's twiddles
     */
    private static void radix4(float[] re, float[] im, int base, int s, float[] tw, int offset) {
        for (var k = 0; k < s; k++) {
            var i0 = base + k;
            var i1 = i0 + s;
            var i2 = i1 + s;
            var i3 = i2 + s;
            var x0r = re[i0];
            var x0i = im[i0];
            var x1r = re[i1];
            var x1i = im[i1];
            var x2r = re[i2];
            var x2i = im[i2];
            var x3r = re[i3];
            var x3i = im[i3];
            var a0r = x0r + x2r;
            var a0i = x0i + x2i;
            var a1r = x0r - x2r;
            var a1i = x0i - x2i;
            var a2r = x1r + x3r;
            var a2i = x1i + x3i;
            var a3r = x1r - x3r;
            var a3i = x1i - x3i;
            re[i0] = a0r + a2r;
            im[i0] = a0i + a2i;
            var t2r = a0r - a2r;
            var t2i = a0i - a2i;
            var t1r = a1r + a3i;
            var t1i = a1i - a3r;
            var t3r = a1r - a3i;
            var t3i = a1i + a3r;
            var w1r = tw[offset + k];
            var w1i = tw[offset + s + k];
            var w2r = tw[offset + 2 * s + k];
            var w2i = tw[offset + 3 * s + k];
            var w3r = tw[offset + 4 * s + k];
            var w3i = tw[offset + 5 * s + k];
            re[i1] = t2r * w2r - t2i * w2i;
            im[i1] = t2r * w2i + t2i * w2r;
            re[i2] = t1r * w1r - t1i * w1i;
            im[i2] = t1r * w1i + t1i * w1r;
            re[i3] = t3r * w3r - t3i * w3i;
            im[i3] = t3r * w3i + t3i * w3r;
        }
    }

    @Override
    void inverseCoupling(float[] magnitude, float[] angle, int count) {
        for (var i = 0; i < count; i++) {
            var m = magnitude[i];
            var a = angle[i];
            var mBits = Float.floatToRawIntBits(m);
            var positiveM = positive(mBits);
            var positiveA = positive(Float.floatToRawIntBits(a));
            var same = ~(positiveM ^ positiveA);
            var t = Float.floatToRawIntBits(m - a) & same | Float.floatToRawIntBits(m + a) & ~same;
            magnitude[i] = Float.intBitsToFloat(mBits & positiveA | t & ~positiveA);
            angle[i] = Float.intBitsToFloat(t & positiveA | mBits & ~positiveA);
        }
    }

    /**
     * Returns an all-ones mask when the float with the given bits is greater than zero, as
     * {@code value > 0} decides it: false for zeros, negatives and NaN, true for positive infinity.
     *
     * @implNote This implementation tests {@code bits - 1} against {@code [0, 0x7F7FFFFF]}, the
     * range of positive non-NaN floats shifted down by one, with sign-bit arithmetic only, so the
     * coupling loop has no branches.
     *
     * @param bits the raw float bits
     * @return {@code -1} when the value is positive, {@code 0} otherwise
     */
    private static int positive(int bits) {
        var shifted = bits - 1;
        return (~shifted & (shifted - 0x7F800000)) >> 31;
    }

    @Override
    void multiplyAdd(float[] a, int i, float[] b, int j, float[] dst, int d, int count) {
        for (var k = 0; k < count; k++) {
            dst[d + k] += a[i + k] * b[j + k];
        }
    }

    @Override
    void multiply(float[] a, int i, float[] b, int j, float[] dst, int d, int count) {
        for (var k = 0; k < count; k++) {
            dst[d + k] = a[i + k] * b[j + k];
        }
    }

    @Override
    void add(float[] a, int i, float[] dst, int d, int count) {
        for (var k = 0; k < count; k++) {
            dst[d + k] += a[i + k];
        }
    }
}
