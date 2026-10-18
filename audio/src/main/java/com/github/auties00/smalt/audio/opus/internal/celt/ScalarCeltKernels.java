/* Ports libopus 1.5.2 (celt/pitch.h, celt/pitch.c, celt/celt.c, celt/vq.c, celt/bands.c). Copyright Xiph.Org
   Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

/**
 * Implements {@link CeltKernels} in plain Java; this is the reference implementation, in the evaluation order of the
 * libopus C code without SIMD.
 */
public final class ScalarCeltKernels implements CeltKernels {
    /**
     * Creates the scalar kernels.
     */
    public ScalarCeltKernels() {
    }

    @Override
    public void pitchXcorr(float[] x, int xOff, float[] y, int yOff, float[] xcorr, int xcorrOff, int len, int maxPitch) {
        for (var i = 0; i < maxPitch; i++) {
            var sum = 0f;
            var yi = yOff + i;
            for (var j = 0; j < len; j++) {
                sum = sum + x[xOff + j] * y[yi + j];
            }
            xcorr[xcorrOff + i] = sum;
        }
    }

    @Override
    public void combFilterConst(float[] y, int yOff, float[] x, int xOff, int T, int N, float g10, float g11, float g12) {
        var x4 = x[xOff - T - 2];
        var x3 = x[xOff - T - 1];
        var x2 = x[xOff - T];
        var x1 = x[xOff - T + 1];
        for (var i = 0; i < N; i++) {
            var x0 = x[xOff + i - T + 2];
            y[yOff + i] = x[xOff + i] + g10 * x2 + g11 * (x1 + x3) + g12 * (x0 + x4);
            x4 = x3;
            x3 = x2;
            x2 = x1;
            x1 = x0;
        }
    }

    @Override
    public void fftSoa(KissFftState st, float[] a, int re, int im) {
        FftSoa.impl(st, a, re, im);
    }

    @Override
    public int pvqSearchBest(float[] X, int xOff, float[] y, int N, float xy, float yy) {
        var bestId = 0;
        var Rxy = xy + X[xOff];
        var Ryy = yy + y[0];
        Rxy = Rxy * Rxy;
        var bestDen = Ryy;
        var bestNum = Rxy;
        var j = 1;
        do {
            Rxy = xy + X[xOff + j];
            Ryy = yy + y[j];
            Rxy = Rxy * Rxy;
            if (bestDen * Rxy > Ryy * bestNum) {
                bestDen = Ryy;
                bestNum = Rxy;
                bestId = j;
            }
        } while (++j < N);
        return bestId;
    }

    @Override
    public float maxabs(float[] x, int off, int len) {
        var maxval = 0f;
        var minval = 0f;
        for (var i = 0; i < len; i++) {
            maxval = MathOps.max(maxval, x[off + i]);
            minval = MathOps.min(minval, x[off + i]);
        }
        return MathOps.max(maxval, -minval);
    }

    @Override
    public void expRotation1(float[] X, int xOff, int len, int stride, float c, float s) {
        var ms = -s;
        var xp = xOff;
        for (var i = 0; i < len - stride; i++) {
            var x1 = X[xp];
            var x2 = X[xp + stride];
            X[xp + stride] = c * x2 + s * x1;
            X[xp++] = c * x1 + ms * x2;
        }
        xp = xOff + len - 2 * stride - 1;
        for (var i = len - 2 * stride - 1; i >= 0; i--) {
            var x1 = X[xp];
            var x2 = X[xp + stride];
            X[xp + stride] = c * x2 + s * x1;
            X[xp--] = c * x1 + ms * x2;
        }
    }

    @Override
    public void haar1(float[] X, int xOff, int N0, int stride) {
        N0 >>= 1;
        if (stride == 1) {
            for (var j = 0; j < N0; j++) {
                var a = xOff + 2 * j;
                var tmp1 = .70710678f * X[a];
                var tmp2 = .70710678f * X[a + 1];
                X[a] = tmp1 + tmp2;
                X[a + 1] = tmp1 - tmp2;
            }
            return;
        }
        for (var i = 0; i < stride; i++) {
            for (var j = 0; j < N0; j++) {
                var a = xOff + stride * 2 * j + i;
                var b = xOff + stride * (2 * j + 1) + i;
                var tmp1 = .70710678f * X[a];
                var tmp2 = .70710678f * X[b];
                X[a] = tmp1 + tmp2;
                X[b] = tmp1 - tmp2;
            }
        }
    }
}
