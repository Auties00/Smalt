package com.github.auties00.smalt.image;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the format-independent settings of an image encoding through {@link Images}. Instances are
 * immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 *
 * @apiNote These options carry only what every image format understands; a caller that needs a
 * format's other parameters uses that format's encoder directly.
 */
public final class ImageEncoderOptions {
    private static final ImageEncoderOptions DEFAULTS = new ImageEncoderOptions(false, 0, 0);

    private final boolean lossless;
    private final int quality;
    private final int loopCount;

    private ImageEncoderOptions(boolean lossless, int quality, int loopCount) {
        this.lossless = lossless;
        this.quality = quality;
        this.loopCount = loopCount;
    }

    /**
     * Returns lossy coding at the format's default quality (lossless for formats that only code
     * losslessly), with animations looping forever.
     *
     * @return the default options
     */
    public static ImageEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns lossy coding at a quality, with animations looping forever.
     *
     * @param quality the quality, from 1 (smallest) to 100 (best)
     * @return the options
     * @throws IllegalArgumentException if {@code quality} is outside 1..100
     */
    public static ImageEncoderOptions ofQuality(int quality) {
        return builder().quality(quality).build();
    }

    /**
     * Returns lossless coding, with animations looping forever.
     *
     * @return the options
     */
    public static ImageEncoderOptions lossless() {
        return new ImageEncoderOptions(true, 0, 0);
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(DEFAULTS);
    }

    /**
     * Returns a builder initialized with these options.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return whether the image is coded losslessly; {@code false} by default}
     *
     * <p>A format that is always lossless (PNG) codes losslessly whatever this says; JPEG codes a
     * lossless JPEG (ITU-T T.81 Annex H) at the picture's bit depth, an RGB picture in RGB; a format that
     * holds fewer bits per sample than the picture has (8 for WebP, GIF, ICO and BMP, 12 for AVIF, 16 for
     * JPEG) rejects a lossless request with {@link com.github.auties00.smalt.MediaException.Unsupported},
     * unless the picture's samples are exactly those of an 8-bit picture, which it then codes.
     */
    public boolean isLossless() {
        return lossless;
    }

    /**
     * {@return the lossy quality, from 1 (smallest) to 100 (best), or empty for the format's
     * default; empty by default}
     *
     * <p>Each format maps this common scale onto its own quality setting. A lossless coding ignores
     * it.
     */
    public OptionalInt quality() {
        return quality == 0 ? OptionalInt.empty() : OptionalInt.of(quality);
    }

    /**
     * {@return the number of times an animation plays, at least 0, where 0 means forever; 0 by
     * default}
     *
     * <p>An animation written through {@link Images#openAnimationWriter(ImageFormat, int, int,
     * ImageEncoderOptions, com.github.auties00.smalt.io.MediaSink)} takes its loop count from here;
     * a still image ignores it. A format that stores a smaller range rejects a larger count when
     * the writer is opened.
     */
    public int loopCount() {
        return loopCount;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImageEncoderOptions that && lossless == that.lossless && quality == that.quality
                && loopCount == that.loopCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(lossless, quality, loopCount);
    }

    @Override
    public String toString() {
        return "ImageEncoderOptions[lossless=" + lossless + ", quality=" + quality + ", loopCount=" + loopCount + "]";
    }

    /**
     * Builds {@link ImageEncoderOptions}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private boolean lossless;
        private OptionalInt quality;
        private int loopCount;

        private Builder(ImageEncoderOptions options) {
            this.lossless = options.lossless;
            this.quality = options.quality();
            this.loopCount = options.loopCount;
        }

        /**
         * Sets whether the image is coded losslessly; see {@link ImageEncoderOptions#isLossless()}.
         *
         * @param lossless the value
         * @return this builder
         */
        public Builder lossless(boolean lossless) {
            this.lossless = lossless;
            return this;
        }

        /**
         * Sets the lossy quality; see {@link ImageEncoderOptions#quality()}.
         *
         * @param quality the value
         * @return this builder
         */
        public Builder quality(int quality) {
            this.quality = OptionalInt.of(quality);
            return this;
        }

        /**
         * Selects the format's default quality; see {@link ImageEncoderOptions#quality()}.
         *
         * @return this builder
         */
        public Builder automaticQuality() {
            this.quality = OptionalInt.empty();
            return this;
        }

        /**
         * Sets the number of times an animation plays; see {@link ImageEncoderOptions#loopCount()}.
         *
         * @param loopCount the value
         * @return this builder
         */
        public Builder loopCount(int loopCount) {
            this.loopCount = loopCount;
            return this;
        }

        /**
         * Returns options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the quality is outside 1..100 or the loop count is
         * negative
         */
        public ImageEncoderOptions build() {
            var checkedQuality = quality.isPresent() ? Checks.inRange("quality", quality.getAsInt(), 1, 100) : 0;
            Checks.nonNegative("loop count", loopCount);
            return new ImageEncoderOptions(lossless, checkedQuality, loopCount);
        }
    }
}
