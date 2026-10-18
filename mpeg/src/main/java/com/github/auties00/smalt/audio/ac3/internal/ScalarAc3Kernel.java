package com.github.auties00.smalt.audio.ac3.internal;

/**
 * Implements {@link Ac3Kernel} with plain loops: the reference the Vector API implementation must
 * match exactly.
 */
public final class ScalarAc3Kernel implements Ac3Kernel {
    /**
     * Creates the kernel.
     */
    public ScalarAc3Kernel() {
    }

    @Override
    public void twiddle(float[] re, float[] im, float[] cos, float[] sin, int count) {
        for (var i = 0; i < count; i++) {
            var r = re[i];
            var m = im[i];
            re[i] = r * cos[i] - m * sin[i];
            im[i] = m * cos[i] + r * sin[i];
        }
    }

    @Override
    public void butterflies(float[] re, float[] im, float[] cos, float[] sin, int half, int length) {
        for (var start = 0; start < length; start += 2 * half) {
            for (var k = 0; k < half; k++) {
                var wr = cos[k];
                var wi = sin[k];
                var a = start + k;
                var b = a + half;
                var tr = re[b] * wr - im[b] * wi;
                var ti = im[b] * wr + re[b] * wi;
                re[b] = re[a] - tr;
                im[b] = im[a] - ti;
                re[a] = re[a] + tr;
                im[a] = im[a] + ti;
            }
        }
    }

    @Override
    public void butterflyPair(float[] re, float[] im, float[] cos1, float[] sin1, float[] cos2, float[] sin2,
                              int half, int length) {
        butterflies(re, im, cos1, sin1, half, length);
        butterflies(re, im, cos2, sin2, 2 * half, length);
    }

    @Override
    public void firstStages(float[] re, float[] im, float[] cos, float[] sin, int length) {
        for (var stage = 0; stage < 3; stage++) {
            var half = 1 << stage;
            for (var start = 0; start < length; start += 2 * half) {
                for (var k = 0; k < half; k++) {
                    var wr = cos[8 * stage + k];
                    var wi = sin[8 * stage + k];
                    var a = start + k;
                    var b = a + half;
                    var tr = re[b] * wr - im[b] * wi;
                    var ti = im[b] * wr + re[b] * wi;
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] = re[a] + tr;
                    im[a] = im[a] + ti;
                }
            }
        }
    }

    @Override
    public void interleave(float[] forward, int forwardOffset, float[] backward, int backwardEnd, float[] window,
                           int windowOffset, float[] out, int outOffset, int pairs) {
        for (var n = 0; n < pairs; n++) {
            out[outOffset + 2 * n] = forward[forwardOffset + n] * window[windowOffset + 2 * n];
            out[outOffset + 2 * n + 1] = backward[backwardEnd - n] * window[windowOffset + 2 * n + 1];
        }
    }

    @Override
    public void overlap(float[] block, float[] delay, float[] out, int offset, int half) {
        for (var n = 0; n < half; n++) {
            out[offset + n] = block[n] + delay[n];
            delay[n] = block[half + n];
        }
    }

    @Override
    public void scale(float[] in, int inOffset, float gain, float[] out, int outOffset, int count) {
        for (var i = 0; i < count; i++) {
            out[outOffset + i] = in[inOffset + i] * gain;
        }
    }

    @Override
    public void accumulate(float[] in, int inOffset, float gain, float[] out, int outOffset, int count) {
        for (var i = 0; i < count; i++) {
            out[outOffset + i] = out[outOffset + i] + in[inOffset + i] * gain;
        }
    }
}
