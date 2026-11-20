package com.github.auties00.smalt.image.gainmap;

/**
 * Holds the gain map metadata of one color channel (ISO 21496-1): how the samples of the gain map image
 * map to gains, and the offsets that keep the gain of dark colors finite.
 *
 * <p>A gain map sample {@code v}, normalized to the range 0 to 1, gives the base-2 logarithm of a gain:
 * {@snippet :
 * double log2Gain = minimum + (maximum - minimum) * Math.pow(v, 1 / gamma);
 * }
 * and, when the gain map is fully applied, a linear-light base value {@code b} becomes the alternate value
 * {@code (b + baseOffset) * Math.pow(2, log2Gain) - alternateOffset} for a base that is the dimmer rendition, and
 * {@code (b + baseOffset) * Math.pow(2, -log2Gain) - alternateOffset} for one that is the brighter
 * ({@link GainMapMetadata#isBaseBrighter()}).
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param minimum the base-2 logarithm of the gain of a gain map sample of 0, finite
 * @param maximum the base-2 logarithm of the gain of a gain map sample of 1, finite and at least
 * {@code minimum}
 * @param gamma the gamma the gain map samples are encoded with, finite and positive; 1 stores the log2 gains
 * linearly
 * @param baseOffset the offset added to the linear-light base values, finite
 * @param alternateOffset the offset added to the linear-light alternate values, finite
 */
public record GainMapChannel(double minimum, double maximum, double gamma, double baseOffset,
                             double alternateOffset) {
    /**
     * The offset of both renditions that ISO 21496-1 writers and the Adobe gain map specification use by
     * default, 1/64.
     */
    public static final double DEFAULT_OFFSET = 1.0 / 64;

    /**
     * Validates the channel.
     *
     * @throws IllegalArgumentException if a value is not finite, {@code maximum} is less than {@code minimum}
     * or {@code gamma} is not positive
     */
    public GainMapChannel {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || !Double.isFinite(gamma)
                || !Double.isFinite(baseOffset) || !Double.isFinite(alternateOffset)) {
            throw new IllegalArgumentException("gain map channel values must be finite");
        }
        if (maximum < minimum) {
            throw new IllegalArgumentException("gain map maximum " + maximum + " is less than its minimum "
                    + minimum);
        }
        if (gamma <= 0) {
            throw new IllegalArgumentException("gain map gamma " + gamma + " is not positive");
        }
    }

    /**
     * Returns a channel with a gamma of 1 and the {@linkplain #DEFAULT_OFFSET default offsets}.
     *
     * @param minimum the base-2 logarithm of the smallest gain
     * @param maximum the base-2 logarithm of the largest gain, at least {@code minimum}
     * @return the channel
     * @throws IllegalArgumentException if a value is not finite or {@code maximum} is less than
     * {@code minimum}
     */
    public static GainMapChannel of(double minimum, double maximum) {
        return new GainMapChannel(minimum, maximum, 1, DEFAULT_OFFSET, DEFAULT_OFFSET);
    }

    /**
     * Returns the base-2 logarithm of the gain of a normalized gain map sample.
     *
     * @param sample the gain map sample, from 0 to 1; values outside are clamped
     * @return the log2 gain, from {@link #minimum()} to {@link #maximum()}
     */
    public double log2Gain(double sample) {
        var clamped = sample >= 1 ? 1 : sample > 0 ? sample : 0;
        var shaped = gamma == 1 ? clamped : StrictMath.pow(clamped, 1 / gamma);
        return minimum + (maximum - minimum) * shaped;
    }
}
