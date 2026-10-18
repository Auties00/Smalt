/* Ports libopus 1.5.2 (src/analysis.c). Copyright Xiph.Org Foundation and Skype Limited; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal;

import com.github.auties00.smalt.audio.opus.internal.celt.MathOps;

/**
 * Implements {@link AnalysisKernels} in plain Java; this is the reference implementation, in the evaluation order of
 * the libopus C code.
 */
final class ScalarAnalysisKernels implements AnalysisKernels {
    /**
     * Creates the scalar kernels.
     */
    ScalarAnalysisKernels() {
    }

    @Override
    public void tonalityBins(float[] re, float[] im, float[] reRev, float[] imRev, float[] A, float[] dA, float[] d2A,
                             float[] noisiness, float[] tonality, float[] tonality2) {
        bins(1, 240, re, im, reRev, imRev, A, dA, d2A, noisiness, tonality, tonality2);
    }

    /**
     * Processes bins {@code [from, to)}.
     *
     * @param from      the first bin
     * @param to        one past the last bin
     * @param re        the real parts of bins {@code i}
     * @param im        the imaginary parts of bins {@code i}
     * @param reRev     the real parts of bins {@code 480 - i}
     * @param imRev     the imaginary parts of bins {@code 480 - i}
     * @param A         the phase of each bin, updated
     * @param dA        the phase increment of each bin, updated
     * @param d2A       the wrapped phase acceleration term of each bin, updated
     * @param noisiness receives the noisiness of each bin
     * @param tonality  receives the delayed tonality of each bin
     * @param tonality2 receives the immediate tonality of each bin
     */
    static void bins(int from, int to, float[] re, float[] im, float[] reRev, float[] imRev, float[] A, float[] dA,
                     float[] d2A, float[] noisiness, float[] tonality, float[] tonality2) {
        for (var i = from; i < to; i++) {
            var X1r = re[i] + reRev[i];
            var X1i = im[i] - imRev[i];
            var X2r = im[i] + imRev[i];
            var X2i = reRev[i] - re[i];
            var angle = TURNS * MathOps.fastAtan2f(X1i, X1r);
            var d_angle = angle - A[i];
            var d2_angle = d_angle - dA[i];
            var angle2 = TURNS * MathOps.fastAtan2f(X2i, X2r);
            var d_angle2 = angle2 - angle;
            var d2_angle2 = d_angle2 - d_angle;
            var mod1 = d2_angle - (float) MathOps.float2int(d2_angle);
            noisiness[i] = Math.abs(mod1);
            mod1 *= mod1;
            mod1 *= mod1;
            var mod2 = d2_angle2 - (float) MathOps.float2int(d2_angle2);
            noisiness[i] += Math.abs(mod2);
            mod2 *= mod2;
            mod2 *= mod2;
            var avg_mod = .25f * (d2A[i] + mod1 + 2 * mod2);
            tonality[i] = 1.f / (1.f + TONALITY_WEIGHT * avg_mod) - .015f;
            tonality2[i] = 1.f / (1.f + TONALITY_WEIGHT * mod2) - .015f;
            A[i] = angle2;
            dA[i] = d_angle2;
            d2A[i] = mod2;
        }
    }
}
