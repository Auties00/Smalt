package com.github.auties00.smalt.audio.dts.internal;

import java.util.Arrays;

/**
 * The fixed-point 64 times LFE interpolation of the DTS core, with its history: the integer reconstruction that an
 * LFE residual of the lossless extension is coded against.
 *
 * <p>Each output sample is the sum of eight decimated samples times the Q23 taps of
 * {@link DtsFixedTables#LFE_64}, rounded half up and clipped to 24 bits. For lossless channels at twice the rate of
 * their core each of those samples is then interpolated two times, between the nearer and the farther of two
 * neighbours, rounded half up.
 *
 * <p>Instances are not thread-safe.
 */
final class FixedLfeInterpolator {
    /**
     * The decimated samples: the last seven of the previous calls, then the current ones.
     */
    private final int[] samples = new int[7 + 64];

    /**
     * The last sample of the 64 times interpolation, in the history of the two times one.
     */
    private int previous;

    /**
     * The output of the 64 times interpolation before the two times one, allocated on first use.
     */
    private int[] single;

    /**
     * Interpolates decimated LFE samples 64 times.
     *
     * @param decimated the decimated 24-bit samples
     * @param count the number of decimated samples, at most 64
     * @param out receives {@code 64 * count} 24-bit samples
     * @param outOffset the index of the first output sample
     */
    void interpolate(int[] decimated, int count, int[] out, int outOffset) {
        var filter = DtsFixedTables.LFE_64;
        System.arraycopy(decimated, 0, samples, 7, count);
        var position = outOffset;
        for (var d = 0; d < count; d++) {
            var current = 7 + d;
            for (var k = 0; k < 64; k++) {
                long sum = 0;
                for (var j = 0; j < 8; j++) {
                    sum += (long) samples[current - j] * filter[k + 64 * j];
                }
                out[position++] = (int) Math.clamp((sum + (1 << 22)) >> 23, -(1 << 23), (1 << 23) - 1);
            }
        }
        System.arraycopy(samples, count, samples, 0, 7);
        if (count > 0) {
            previous = out[position - 1];
        }
    }

    /**
     * Interpolates decimated LFE samples 128 times: 64 times, then two times.
     *
     * @param decimated the decimated 24-bit samples
     * @param count the number of decimated samples, at most 64
     * @param out receives {@code 128 * count} 24-bit samples
     * @param outOffset the index of the first output sample
     */
    void interpolateDouble(int[] decimated, int count, int[] out, int outOffset) {
        if (single == null) {
            single = new int[64 * 64];
        }
        var last = previous;
        interpolate(decimated, count, single, 0);
        var position = outOffset;
        for (var n = 0; n < 64 * count; n++) {
            long current = single[n];
            out[position++] = FixedDct.round23(current * DtsFixedTables.LFE_DOUBLE_FAR
                    + last * (long) DtsFixedTables.LFE_DOUBLE_NEAR);
            out[position++] = FixedDct.round23(current * DtsFixedTables.LFE_DOUBLE_NEAR
                    + last * (long) DtsFixedTables.LFE_DOUBLE_FAR);
            last = single[n];
        }
    }

    /**
     * {@return the decimated LFE sample of a code and a 7-bit scale factor index, clipped to 24 bits}
     *
     * @param code the quantization index, from -128 to 127
     * @param index the scale factor index into {@link DtsCoreTables#RMS7}
     */
    static int dequantize(int code, int index) {
        var scale = ((long) DtsCoreTables.RMS7[index] * DtsFixedTables.LFE_SCALE + (1 << 22)) >> 23;
        return (int) Math.clamp((code * scale) >> 4, -(1 << 23), (1 << 23) - 1);
    }

    /**
     * Takes decimated samples into the history without interpolating them, for a frame outside the fixed-point
     * reconstruction, which also restarts the two times interpolation.
     *
     * @param decimated the decimated samples
     * @param count the number of decimated samples, at most 64
     */
    void advance(int[] decimated, int count) {
        System.arraycopy(decimated, 0, samples, 7, count);
        System.arraycopy(samples, count, samples, 0, 7);
        previous = 0;
    }

    /**
     * Clears the history.
     */
    void reset() {
        Arrays.fill(samples, 0);
        previous = 0;
    }
}
