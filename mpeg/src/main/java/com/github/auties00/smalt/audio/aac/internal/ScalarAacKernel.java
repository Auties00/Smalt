package com.github.auties00.smalt.audio.aac.internal;

/**
 * Implements {@link AacKernel} with plain scalar loops; the reference for the vector kernel.
 */
public final class ScalarAacKernel implements AacKernel {
    /**
     * Constructs the kernel.
     */
    public ScalarAacKernel() {
    }

    @Override
    public void fftRadix4(float[] re, float[] im, int n, int quarter, float[] twiddles, int offset) {
        var w1r = offset;
        var w1i = offset + quarter;
        var w2r = offset + 2 * quarter;
        var w2i = offset + 3 * quarter;
        var w3r = offset + 4 * quarter;
        var w3i = offset + 5 * quarter;
        for (var block = 0; block < n; block += 4 * quarter) {
            for (var j = 0; j < quarter; j++) {
                var i0 = block + j;
                var i1 = i0 + quarter;
                var i2 = i1 + quarter;
                var i3 = i2 + quarter;
                var x0r = re[i0];
                var x0i = im[i0];
                var x1r = re[i1];
                var x1i = im[i1];
                var x2r = re[i2];
                var x2i = im[i2];
                var x3r = re[i3];
                var x3i = im[i3];
                var ar = x0r + x2r;
                var ai = x0i + x2i;
                var cr = x0r - x2r;
                var ci = x0i - x2i;
                var br = x1r + x3r;
                var bi = x1i + x3i;
                var dr = x1i - x3i;
                var di = x3r - x1r;
                re[i0] = ar + br;
                im[i0] = ai + bi;
                var tr = ar - br;
                var ti = ai - bi;
                var wr = twiddles[w2r + j];
                var wi = twiddles[w2i + j];
                re[i1] = tr * wr - ti * wi;
                im[i1] = tr * wi + ti * wr;
                tr = cr + dr;
                ti = ci + di;
                wr = twiddles[w1r + j];
                wi = twiddles[w1i + j];
                re[i2] = tr * wr - ti * wi;
                im[i2] = tr * wi + ti * wr;
                tr = cr - dr;
                ti = ci - di;
                wr = twiddles[w3r + j];
                wi = twiddles[w3i + j];
                re[i3] = tr * wr - ti * wi;
                im[i3] = tr * wi + ti * wr;
            }
        }
    }

    @Override
    public void fftRadix2(float[] re, float[] im, int n, int half, float[] twiddles, int offset) {
        for (var block = 0; block < n; block += 2 * half) {
            for (var j = 0; j < half; j++) {
                var i0 = block + j;
                var i1 = i0 + half;
                var ar = re[i0];
                var ai = im[i0];
                var cr = re[i1];
                var ci = im[i1];
                re[i0] = ar + cr;
                im[i0] = ai + ci;
                var tr = ar - cr;
                var ti = ai - ci;
                var wr = twiddles[offset + j];
                var wi = twiddles[offset + half + j];
                re[i1] = tr * wr - ti * wi;
                im[i1] = tr * wi + ti * wr;
            }
        }
    }

    @Override
    public void dequantize(int[] quant, float[] out, int from, int to, float gain) {
        var table = Dequantizer.POW43;
        for (var i = from; i < to; i++) {
            var value = quant[i];
            // A negative value flips the sign bit of its positive magnitude, which negates it exactly.
            var magnitude = table[Math.abs(value)] * gain;
            out[i] = Float.intBitsToFloat(Float.floatToRawIntBits(magnitude) ^ (value & 0x80000000));
        }
    }

    @Override
    public void overlapLong(float[] u, int m, float[] window, float[] overlap, float[] out, int outOffset) {
        var h = m >> 1;
        for (var n = 0; n < h; n++) {
            out[outOffset + n] = overlap[n] + window[n] * u[h + n];
        }
        for (var n = h; n < m; n++) {
            out[outOffset + n] = overlap[n] - window[n] * u[m + h - 1 - n];
        }
        for (var n = 0; n < h; n++) {
            overlap[n] = -(window[m + n] * u[h - 1 - n]);
        }
        for (var n = h; n < m; n++) {
            overlap[n] = -(window[m + n] * u[n - h]);
        }
    }

    @Override
    public void powerLaw(float[] x, float[] magnitude, float[] power34, int from, int to) {
        for (var i = from; i < to; i++) {
            var value = Math.abs(x[i]);
            magnitude[i] = value;
            power34[i] = (float) Math.sqrt(value * (float) Math.sqrt(value));
        }
    }

    @Override
    public int quantize(float[] power34, int[] quant, int from, int to, float inverseStep) {
        var max = 0;
        for (var i = from; i < to; i++) {
            var q = (int) (power34[i] * inverseStep + Quantization.ROUNDING);
            quant[i] = q;
            max = Math.max(max, q);
        }
        return max;
    }

    @Override
    public float distortion(float[] magnitude, float[] power34, int[] scratch, int from, int to, float inverseStep,
                            float step) {
        var table = Dequantizer.POW43;
        var limit = Dequantizer.POW43_SIZE - 1;
        var sum = 0.0f;
        for (var i = from; i < to; i++) {
            var q = Math.min((int) (power34[i] * inverseStep + Quantization.ROUNDING), limit);
            scratch[i] = q;
            var error = magnitude[i] - table[q] * step;
            sum += error * error;
        }
        return sum;
    }

    @Override
    public float energy(float[] x, int from, int to) {
        var sum = 0.0f;
        for (var i = from; i < to; i++) {
            sum += x[i] * x[i];
        }
        return sum;
    }

    @Override
    public void fold(float[] x, int offset, float[] window, float[] folded, int m) {
        var h = m >> 1;
        for (var k = 0; k < h; k++) {
            var a = 3 * h - 1 - k;
            var b = 3 * h + k;
            folded[k] = -(x[offset + a] * window[a]) - x[offset + b] * window[b];
        }
        for (var k = h; k < m; k++) {
            var a = k - h;
            var b = 3 * h - 1 - k;
            folded[k] = x[offset + a] * window[a] - x[offset + b] * window[b];
        }
    }

    @Override
    public void preTwiddle(float[] in, int offset, int size, float[] cos, float[] sin, float[] re, float[] im) {
        var half = size >> 1;
        var last = offset + size - 1;
        for (var k = 0; k < half; k++) {
            var a = in[offset + 2 * k];
            var b = in[last - 2 * k];
            var c = cos[k];
            var s = sin[k];
            re[k] = a * c + b * s;
            im[k] = b * c - a * s;
        }
    }

    @Override
    public void midSide(float[] left, float[] right, int from, int to) {
        for (var i = from; i < to; i++) {
            var mid = left[i];
            var side = right[i];
            left[i] = mid + side;
            right[i] = mid - side;
        }
    }

    @Override
    public float dot(float[] a, int aOffset, float[] b, int bOffset, int length) {
        var sum = 0.0f;
        for (var i = 0; i < length; i++) {
            sum += a[aOffset + i] * b[bOffset + i];
        }
        return sum;
    }
}
