package com.github.auties00.smalt.audio.process;

/**
 * Implements {@link FirKernel} with scalar fused multiply-adds; the reference for the vector
 * kernel.
 */
final class ScalarFirKernel implements FirKernel {
    /**
     * Constructs the kernel.
     */
    ScalarFirKernel() {
    }

    @Override
    public float dot(float[] x, int xOffset, float[] h, int hOffset, int taps) {
        var c0 = 0.0f;
        var c1 = 0.0f;
        var c2 = 0.0f;
        var c3 = 0.0f;
        var c4 = 0.0f;
        var c5 = 0.0f;
        var c6 = 0.0f;
        var c7 = 0.0f;
        for (var k = 0; k < taps; k += CHAINS) {
            var xi = xOffset + k;
            var hi = hOffset + k;
            c0 = Math.fma(x[xi], h[hi], c0);
            c1 = Math.fma(x[xi + 1], h[hi + 1], c1);
            c2 = Math.fma(x[xi + 2], h[hi + 2], c2);
            c3 = Math.fma(x[xi + 3], h[hi + 3], c3);
            c4 = Math.fma(x[xi + 4], h[hi + 4], c4);
            c5 = Math.fma(x[xi + 5], h[hi + 5], c5);
            c6 = Math.fma(x[xi + 6], h[hi + 6], c6);
            c7 = Math.fma(x[xi + 7], h[hi + 7], c7);
        }
        return ((c0 + c1) + (c2 + c3)) + ((c4 + c5) + (c6 + c7));
    }

    @Override
    public void run(float[] x, int base, int phase, int step, int remainder, int up, float[] table, int taps,
                    float[] out, int offset, int count) {
        for (var i = 0; i < count; i++) {
            out[offset + i] = dot(x, base, table, phase * taps, taps);
            base += step;
            phase += remainder;
            if (phase >= up) {
                phase -= up;
                base++;
            }
        }
    }
}
