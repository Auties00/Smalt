package com.github.auties00.smalt.image.hdr;

import java.util.Objects;

/**
 * Holds the settings of an {@link HdrEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class HdrEncoderOptions {
    private static final HdrEncoderOptions DEFAULTS = new Builder().build();

    private final boolean runLength;

    private HdrEncoderOptions(Builder builder) {
        this.runLength = builder.runLength;
    }

    /**
     * Returns options writing run-length coded scanlines.
     *
     * @return the default options
     */
    public static HdrEncoderOptions defaults() {
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
     * {@return whether scanlines 8 to 32767 pixels wide are written with the adaptive run-length coding rather than
     * flat; {@code true} by default}
     *
     * <p>Scanlines of other widths are always flat.
     */
    public boolean runLength() {
        return runLength;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HdrEncoderOptions that && runLength == that.runLength;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(runLength);
    }

    @Override
    public String toString() {
        return "HdrEncoderOptions[runLength=" + runLength + "]";
    }

    /**
     * A builder of {@link HdrEncoderOptions}.
     *
     * <p>Every value is valid. Instances are not thread-safe.
     */
    public static final class Builder {
        private boolean runLength = true;

        private Builder() {
        }

        private Builder(HdrEncoderOptions options) {
            this.runLength = options.runLength;
        }

        /**
         * Sets whether scanlines are run-length coded; see {@link HdrEncoderOptions#runLength()}.
         *
         * @param runLength the value
         * @return this builder
         */
        public Builder runLength(boolean runLength) {
            this.runLength = runLength;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         */
        public HdrEncoderOptions build() {
            return new HdrEncoderOptions(this);
        }
    }
}
