package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of an {@link ApngEncoder}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class ApngEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final ApngEncoderOptions DEFAULTS = new Builder().build();

    private final int compressionLevel;

    private final PngEncoderOptions.Filter filter;

    private final int loopCount;

    private ApngEncoderOptions(Builder builder) {
        this.compressionLevel = builder.compressionLevel;
        this.filter = builder.filter;
        this.loopCount = builder.loopCount;
    }

    /**
     * Returns compression level 6 with {@link PngEncoderOptions.Filter#ADAPTIVE} filtering, as
     * {@link PngEncoderOptions#defaults()}, and an animation that loops forever.
     *
     * @return the default options
     */
    public static ApngEncoderOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
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
     * {@return the zlib compression level, from 0 (stored, fastest) to 9 (smallest); 6 by default}
     *
     * <p>APNG is lossless, so the level trades encoding time for size only.
     */
    public int compressionLevel() {
        return compressionLevel;
    }

    /**
     * {@return the scanline filter selection, as for a {@link PngEncoder};
     * {@link PngEncoderOptions.Filter#ADAPTIVE} by default}
     */
    public PngEncoderOptions.Filter filter() {
        return filter;
    }

    /**
     * {@return the number of times the animation plays, at least 0, 0 meaning forever; 0 by
     * default}
     */
    public int loopCount() {
        return loopCount;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ApngEncoderOptions that
                && compressionLevel == that.compressionLevel
                && filter == that.filter
                && loopCount == that.loopCount;
    }

    @Override
    public int hashCode() {
        return Objects.hash(compressionLevel, filter, loopCount);
    }

    @Override
    public String toString() {
        return "ApngEncoderOptions[compressionLevel=" + compressionLevel + ", filter=" + filter + ", loopCount="
                + loopCount + "]";
    }

    /**
     * A builder of {@link ApngEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private int compressionLevel = 6;

        private PngEncoderOptions.Filter filter = PngEncoderOptions.Filter.ADAPTIVE;

        private int loopCount;

        private Builder() {
        }

        private Builder(ApngEncoderOptions options) {
            this.compressionLevel = options.compressionLevel;
            this.filter = options.filter;
            this.loopCount = options.loopCount;
        }

        /**
         * Sets the zlib compression level; see {@link ApngEncoderOptions#compressionLevel()}.
         *
         * @param compressionLevel the value
         * @return this builder
         */
        public Builder compressionLevel(int compressionLevel) {
            this.compressionLevel = compressionLevel;
            return this;
        }

        /**
         * Sets the scanline filter selection; see {@link ApngEncoderOptions#filter()}.
         *
         * @param filter the value
         * @return this builder
         */
        public Builder filter(PngEncoderOptions.Filter filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /**
         * Sets the number of times the animation plays; see {@link ApngEncoderOptions#loopCount()}.
         *
         * @param loopCount the value
         * @return this builder
         */
        public Builder loopCount(int loopCount) {
            this.loopCount = loopCount;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the compression level is not in [0, 9] or the loop
         * count is negative
         */
        public ApngEncoderOptions build() {
            Checks.inRange("compression level", compressionLevel, 0, 9);
            Checks.nonNegative("loop count", loopCount);
            return new ApngEncoderOptions(this);
        }
    }
}
