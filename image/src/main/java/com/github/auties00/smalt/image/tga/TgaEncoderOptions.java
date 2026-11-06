package com.github.auties00.smalt.image.tga;

import java.util.Objects;

/**
 * Holds the settings of a {@link TgaEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class TgaEncoderOptions {
    private static final TgaEncoderOptions DEFAULTS = new Builder().build();

    private final boolean runLength;

    private final boolean topDown;

    private TgaEncoderOptions(Builder builder) {
        this.runLength = builder.runLength;
        this.topDown = builder.topDown;
    }

    /**
     * Returns options writing run-length coded images, bottom row first.
     *
     * @return the default options
     */
    public static TgaEncoderOptions defaults() {
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
     * {@return whether the pixels are run-length coded (image types 10 and 11) rather than raw (types 2 and 3);
     * {@code true} by default}
     *
     * <p>Packets never cross a scanline.
     */
    public boolean runLength() {
        return runLength;
    }

    /**
     * {@return whether rows are stored top first (a top-left origin) rather than bottom first (the bottom-left
     * origin readers assume); {@code false} by default}
     */
    public boolean topDown() {
        return topDown;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TgaEncoderOptions that && runLength == that.runLength && topDown == that.topDown;
    }

    @Override
    public int hashCode() {
        return Objects.hash(runLength, topDown);
    }

    @Override
    public String toString() {
        return "TgaEncoderOptions[runLength=" + runLength + ", topDown=" + topDown + "]";
    }

    /**
     * A builder of {@link TgaEncoderOptions}.
     *
     * <p>Every value is valid. Instances are not thread-safe.
     */
    public static final class Builder {
        private boolean runLength = true;

        private boolean topDown;

        private Builder() {
        }

        private Builder(TgaEncoderOptions options) {
            this.runLength = options.runLength;
            this.topDown = options.topDown;
        }

        /**
         * Sets whether the pixels are run-length coded; see {@link TgaEncoderOptions#runLength()}.
         *
         * @param runLength the value
         * @return this builder
         */
        public Builder runLength(boolean runLength) {
            this.runLength = runLength;
            return this;
        }

        /**
         * Sets whether rows are stored top first; see {@link TgaEncoderOptions#topDown()}.
         *
         * @param topDown the value
         * @return this builder
         */
        public Builder topDown(boolean topDown) {
            this.topDown = topDown;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         */
        public TgaEncoderOptions build() {
            return new TgaEncoderOptions(this);
        }
    }
}
