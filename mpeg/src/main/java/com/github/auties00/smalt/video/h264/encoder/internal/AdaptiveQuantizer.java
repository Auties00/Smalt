package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.encoder.H264AdaptiveQuantization;

/**
 * Derives the adaptive quantization offsets of the macroblocks of a picture from their luma activity and
 * brightness, as {@link H264AdaptiveQuantization} describes the modes.
 *
 * <p>A macroblock's activity is the sum of the variances of its four 8x8 luma blocks times 64, plus 64,
 * on the 8-bit scale; its brightness is the sum of its 256 luma samples on the same scale. Arguments are not
 * checked; values outside the documented ranges give unspecified results.
 */
final class AdaptiveQuantizer {
    /**
     * The quantizer steps per binary order of magnitude of activity at a strength of one.
     */
    static final double SCALE = 0.75;

    /**
     * The largest offset either way, in quantizer steps.
     */
    static final int MAX_OFFSET = 6;

    /**
     * The spread (standard deviation) of the binary logarithm of the activity that
     * {@link H264AdaptiveQuantization#AUTO_VARIANCE} scales every picture's to.
     */
    private static final double AUTO_SPREAD = 2.0;

    /**
     * The smallest spread {@link H264AdaptiveQuantization#AUTO_VARIANCE} divides by, so a uniform picture's
     * offsets are amplified at most four times.
     */
    private static final double MIN_SPREAD = 0.5;

    /**
     * The offset of a black macroblock in {@link H264AdaptiveQuantization#AUTO_VARIANCE_DARK} at a strength of
     * one, before the offsets are centered again.
     */
    private static final double DARK_OFFSET = 3;

    /**
     * The share of the luma range from black below which a macroblock counts as dark; the dark offset falls
     * linearly from black to there.
     */
    private static final double DARK_KNEE = 0.25;

    /**
     * The reciprocal of the natural logarithm of 2.
     */
    private static final double INVERSE_LOG2 = 1 / StrictMath.log(2);

    private AdaptiveQuantizer() {
        throw new AssertionError();
    }

    /**
     * Computes the offsets of a picture.
     *
     * @param mode the mode, not {@link H264AdaptiveQuantization#NONE}
     * @param strength the strength, positive
     * @param activity the activity of each macroblock
     * @param brightness the brightness of each macroblock, read only for
     * {@link H264AdaptiveQuantization#AUTO_VARIANCE_DARK}
     * @param count the number of macroblocks, positive
     * @param black the 8-bit luma of black, 16 for limited range and 0 for full range
     * @param white the 8-bit luma of white, 235 for limited range and 255 for full range
     * @param offsets receives each macroblock's offset in quantizer steps, unrounded, within
     * {@link #MAX_OFFSET} either way
     */
    static void compute(H264AdaptiveQuantization mode, double strength, int[] activity, int[] brightness, int count,
                        int black, int white, double[] offsets) {
        double logSum = 0;
        for (var i = 0; i < count; i++) {
            offsets[i] = StrictMath.log(activity[i]) * INVERSE_LOG2;
            logSum += offsets[i];
        }
        var mean = logSum / count;
        var scale = strength * SCALE;
        if (mode == H264AdaptiveQuantization.VARIANCE) {
            for (var i = 0; i < count; i++) {
                offsets[i] = Math.clamp(scale * (offsets[i] - mean), -MAX_OFFSET, MAX_OFFSET);
            }
            return;
        }
        double squares = 0;
        for (var i = 0; i < count; i++) {
            var difference = offsets[i] - mean;
            squares += difference * difference;
        }
        scale *= AUTO_SPREAD / Math.max(StrictMath.sqrt(squares / count), MIN_SPREAD);
        for (var i = 0; i < count; i++) {
            offsets[i] = scale * (offsets[i] - mean);
        }
        if (mode == H264AdaptiveQuantization.AUTO_VARIANCE_DARK) {
            var knee = DARK_KNEE * (white - black);
            double sum = 0;
            for (var i = 0; i < count; i++) {
                var darkness = Math.clamp(1 - (brightness[i] / 256.0 - black) / knee, 0, 1);
                offsets[i] -= strength * DARK_OFFSET * darkness;
                sum += offsets[i];
            }
            var center = sum / count;
            for (var i = 0; i < count; i++) {
                offsets[i] -= center;
            }
        }
        for (var i = 0; i < count; i++) {
            offsets[i] = Math.clamp(offsets[i], -MAX_OFFSET, MAX_OFFSET);
        }
    }
}
