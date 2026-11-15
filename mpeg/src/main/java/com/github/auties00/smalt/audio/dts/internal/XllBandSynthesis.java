package com.github.auties00.smalt.audio.dts.internal;

/**
 * Joins the two frequency bands of an XLL channel into samples at twice their rate, the inverse of the integer
 * decimator of the encoder.
 *
 * <p>ETSI TS 102 114 does not give this filter. It is a lattice of eight plane rotations with seven delays between
 * them, every rotation three lifting steps on integers, after a scaling of the bands by the square root of two, also
 * in lifting steps. The delays start each frame from the decimator history the frame carries, so that a frame does not
 * depend on the one before it.
 *
 * @implNote This implementation has the structure, the coefficients and the rounding measured from ffmpeg's lossless
 * output, as a black box: every coefficient is the only value that reproduces 2.5 million random samples.
 */
final class XllBandSynthesis {
    /**
     * The number of history samples of a channel.
     */
    static final int HISTORY = 7;

    /**
     * The sine of the angle of each rotation, Q23, from the output side.
     */
    private static final int[] SINE = {8204883, -6928550, 5074941, -3259333, 1788164, -785564, 245210, -41153};

    /**
     * The tangent of half the angle of each rotation with its sign reversed, Q23.
     */
    private static final int[] TANGENT = {-6791313, 4430736, -2825313, 1696305, -904476, 393647, -122631, 20577};

    /**
     * The first step of the scaling, Q23: the high band into the low one.
     */
    private static final int SCALE_FIRST = -1737338;

    /**
     * The second step of the scaling, the square root of two, Q23: the low band into the high one.
     */
    private static final int SCALE_SECOND = 11863284;

    /**
     * The third step of the scaling, Q23: the high band into the low one.
     */
    private static final int SCALE_THIRD = 2456966;

    private XllBandSynthesis() {
    }

    /**
     * Joins two bands.
     *
     * @param low the samples of band 0
     * @param high the samples of band 1
     * @param history the decimator history of the channel, {@value #HISTORY} samples, overwritten
     * @param output receives {@code 2 * samples} samples
     * @param samples the number of samples per band
     */
    static void synthesize(int[] low, int[] high, int[] history, int[] output, int samples) {
        for (var n = 0; n < samples; n++) {
            var odd = low[n] + multiply(SCALE_FIRST, high[n]);
            var even = high[n] + multiply(SCALE_SECOND, odd);
            odd += multiply(SCALE_THIRD, even);
            even -= odd;
            for (var stage = HISTORY; stage >= 0; stage--) {
                var tangent = TANGENT[stage];
                odd += multiply(tangent, even);
                even += multiply(SINE[stage], odd);
                odd += multiply(tangent, even);
                if (stage > 0) {
                    var delayed = history[stage - 1];
                    history[stage - 1] = odd;
                    odd = delayed;
                }
            }
            output[2 * n] = even;
            output[2 * n + 1] = odd;
        }
    }

    /**
     * {@return the product of a Q23 coefficient and a sample, rounded to the nearest integer, a tie towards negative
     * infinity}
     */
    private static int multiply(int coefficient, int sample) {
        return (int) ((long) coefficient * sample + (1 << 22) - 1 >> 23);
    }
}
