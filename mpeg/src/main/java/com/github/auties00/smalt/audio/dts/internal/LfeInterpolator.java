package com.github.auties00.smalt.audio.dts.internal;

import java.util.Arrays;

/**
 * The LFE interpolation of the DTS core (ETSI TS 102 114 clause C.3.7), 64 or 128 times, with its history.
 *
 * <p>Instances are not thread-safe.
 */
final class LfeInterpolator {
    /**
     * The 64 times interpolation filter (D.8).
     */
    private static final float[] FILTER_64 = Qmf32.floats(DtsCoreTables.parse(DtsCoreTables.LFE_64, 512));

    /**
     * The 128 times interpolation filter (D.8).
     */
    private static final float[] FILTER_128 = Qmf32.floats(DtsCoreTables.parse(DtsCoreTables.LFE_128, 512));

    /**
     * The decimated samples: the last seven of the previous calls, then the current ones.
     */
    private final float[] samples = new float[7 + 64];

    /**
     * Interpolates decimated LFE samples.
     *
     * @param decimated the decimated samples
     * @param count the number of decimated samples
     * @param factor the interpolation factor, 64 or 128
     * @param out receives {@code factor * count} samples
     * @param outOffset the index of the first output sample
     * @param scale the factor applied to the output
     */
    void interpolate(float[] decimated, int count, int factor, float[] out, int outOffset, float scale) {
        var filter = factor == 128 ? FILTER_128 : FILTER_64;
        var taps = 512 / factor;
        System.arraycopy(decimated, 0, samples, 7, count);
        var position = outOffset;
        for (var d = 0; d < count; d++) {
            var current = 7 + d;
            for (var k = 0; k < factor; k++) {
                var sum = 0.0f;
                for (var j = 0; j < taps; j++) {
                    sum += samples[current - j] * filter[k + j * factor];
                }
                out[position++] = scale * sum;
            }
        }
        System.arraycopy(samples, count, samples, 0, 7);
    }

    /**
     * Clears the history.
     */
    void reset() {
        Arrays.fill(samples, 0);
    }
}
