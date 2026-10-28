package com.github.auties00.smalt.image.raw.internal;

/**
 * Maps the four camera values of a photosite of a four-color sensor to the three channels the
 * rendering works in: white balance, highlight treatment, then the {@code 3 x 4} projection.
 *
 * <p>With clipping, each white-balanced value is clipped to 1. With blending, each is clipped where
 * its channel saturates (its multiplier); a value whose largest channel exceeds 1 is blended toward
 * the mean of its channels, clipped to 1, the more the farther it lies above 1, so that the
 * highlights where some channels have saturated turn neutral smoothly instead of taking the color
 * of the channels that have not, as the three-color rendering does.
 */
final class Projection {
    /**
     * Prevents instantiation.
     */
    private Projection() {
        throw new AssertionError();
    }

    /**
     * Projects one photosite.
     *
     * @param values the four camera values, modified
     * @param four the {@code 3 x 4} projection matrix followed by the four white balance multipliers
     * @param blend whether highlights are blended rather than clipped
     * @param out receives the three channels
     * @param at the index of the first channel in {@code out}
     */
    static void project(float[] values, float[] four, boolean blend, float[] out, int at) {
        if (blend) {
            var max = 0f;
            var lowest = Float.MAX_VALUE;
            var highest = 0f;
            for (var p = 0; p < 4; p++) {
                var multiplier = four[12 + p];
                values[p] = Math.min(values[p] * multiplier, multiplier);
                max = Math.max(max, values[p]);
                lowest = Math.min(lowest, multiplier);
                highest = Math.max(highest, multiplier);
            }
            if (max > 1f) {
                var luminance = Math.min((values[0] + values[1] + values[2] + values[3]) / 4f, 1f);
                var span = Math.max(highest / lowest - 1, 1e-3f);
                var t = Math.min((max - 1f) / span, 1f);
                t = t * t * (3 - 2 * t);
                for (var p = 0; p < 4; p++) {
                    values[p] = Math.min(values[p], 1f) * (1 - t) + luminance * t;
                }
            }
        } else {
            for (var p = 0; p < 4; p++) {
                values[p] = Math.min(values[p] * four[12 + p], 1f);
            }
        }
        out[at] = four[0] * values[0] + four[1] * values[1] + four[2] * values[2] + four[3] * values[3];
        out[at + 1] = four[4] * values[0] + four[5] * values[1] + four[6] * values[2] + four[7] * values[3];
        out[at + 2] = four[8] * values[0] + four[9] * values[1] + four[10] * values[2] + four[11] * values[3];
    }
}
