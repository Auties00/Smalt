/* Ports libopus 1.5.2 (silk/float/LPC_analysis_filter_FLP.c). Copyright Skype Limited; BSD 3-Clause License. Modified
   for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.opus.internal.silk.Define.MAX_SHAPE_LPC_ORDER;

/**
 * Implements {@link SilkFlpKernels} in plain Java; this is the reference implementation, in the evaluation order of
 * the libopus C code without SIMD.
 *
 * @implNote This implementation expresses the order-specific filters as one loop over the taps that adds the products
 * in the left-to-right order of the unrolled reference expressions.
 */
final class ScalarSilkFlpKernels implements SilkFlpKernels {
    /**
     * Creates the scalar kernels.
     */
    ScalarSilkFlpKernels() {
    }

    @Override
    public void lpcAnalysisFilter(float[] rLpc, int rLpcOff, float[] coef, int coefOff, float[] s, int sOff, int length,
                                  int order) {
        for (var ix = order; ix < length; ix++) {
            var sPtr = sOff + ix - 1;
            var pred = s[sPtr] * coef[coefOff];
            for (var j = 1; j < order; j++) {
                pred = pred + s[sPtr - j] * coef[coefOff + j];
            }
            rLpc[rLpcOff + ix] = s[sPtr + 1] - pred;
        }
    }

    @Override
    public void warpedAutocorrelation4(float[] corr, int corrStride, float[] input, int inputStride, float warping,
                                       int length, int order, double[] scratch) {
        final var cOff = 4 * (MAX_SHAPE_LPC_ORDER + 1);
        Arrays.fill(scratch, 0, 2 * cOff, 0.0);
        var state = scratch;
        var i1 = inputStride;
        var i2 = 2 * inputStride;
        var i3 = 3 * inputStride;
        for (var n = 0; n < length; n++) {
            double a1 = input[n];
            double b1 = input[i1 + n];
            double c1 = input[i2 + n];
            double d1 = input[i3 + n];
            double a2, b2, c2, d2;
            for (var i = 0; i < order; i += 2) {
                var p = i * 4;
                a2 = state[p] + warping * state[p + 4] - warping * a1;
                b2 = state[p + 1] + warping * state[p + 5] - warping * b1;
                c2 = state[p + 2] + warping * state[p + 6] - warping * c1;
                d2 = state[p + 3] + warping * state[p + 7] - warping * d1;
                state[p] = a1;
                state[p + 1] = b1;
                state[p + 2] = c1;
                state[p + 3] = d1;
                scratch[cOff + p] += state[0] * a1;
                scratch[cOff + p + 1] += state[1] * b1;
                scratch[cOff + p + 2] += state[2] * c1;
                scratch[cOff + p + 3] += state[3] * d1;
                a1 = state[p + 4] + warping * state[p + 8] - warping * a2;
                b1 = state[p + 5] + warping * state[p + 9] - warping * b2;
                c1 = state[p + 6] + warping * state[p + 10] - warping * c2;
                d1 = state[p + 7] + warping * state[p + 11] - warping * d2;
                state[p + 4] = a2;
                state[p + 5] = b2;
                state[p + 6] = c2;
                state[p + 7] = d2;
                scratch[cOff + p + 4] += state[0] * a2;
                scratch[cOff + p + 5] += state[1] * b2;
                scratch[cOff + p + 6] += state[2] * c2;
                scratch[cOff + p + 7] += state[3] * d2;
            }
            var q = order * 4;
            state[q] = a1;
            state[q + 1] = b1;
            state[q + 2] = c1;
            state[q + 3] = d1;
            scratch[cOff + q] += state[0] * a1;
            scratch[cOff + q + 1] += state[1] * b1;
            scratch[cOff + q + 2] += state[2] * c1;
            scratch[cOff + q + 3] += state[3] * d1;
        }
        for (var i = 0; i < order + 1; i++) {
            corr[i] = (float) scratch[cOff + i * 4];
            corr[corrStride + i] = (float) scratch[cOff + i * 4 + 1];
            corr[2 * corrStride + i] = (float) scratch[cOff + i * 4 + 2];
            corr[3 * corrStride + i] = (float) scratch[cOff + i * 4 + 3];
        }
    }
}

