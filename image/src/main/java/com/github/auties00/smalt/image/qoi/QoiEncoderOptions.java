package com.github.auties00.smalt.image.qoi;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Holds the settings of a {@link QoiEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class QoiEncoderOptions {
    private static final QoiEncoderOptions DEFAULTS = new Builder().build();

    private final int channels;

    private final boolean linear;

    private QoiEncoderOptions(Builder builder) {
        this.channels = builder.channels;
        this.linear = builder.linear;
    }

    /**
     * Returns options writing four channels for a picture with alpha and three otherwise, labeled sRGB.
     *
     * @return the default options
     */
    public static QoiEncoderOptions defaults() {
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
     * {@return the channel count the header declares, 3 or 4, or empty to declare 4 for a picture with alpha and 3
     * otherwise; empty by default}
     *
     * <p>With 3, alpha is written opaque.
     */
    public OptionalInt channels() {
        return channels == 0 ? OptionalInt.empty() : OptionalInt.of(channels);
    }

    /**
     * {@return whether the header labels every channel linear (colorspace 1) rather than sRGB with linear alpha
     * (colorspace 0); {@code false} by default}
     *
     * <p>The label does not change the samples written.
     */
    public boolean linear() {
        return linear;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof QoiEncoderOptions that && channels == that.channels && linear == that.linear;
    }

    @Override
    public int hashCode() {
        return Objects.hash(channels, linear);
    }

    @Override
    public String toString() {
        return "QoiEncoderOptions[channels=" + (channels == 0 ? "automatic" : channels) + ", linear=" + linear + "]";
    }

    /**
     * A builder of {@link QoiEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private int channels;

        private boolean channelsSet;

        private boolean linear;

        private Builder() {
        }

        private Builder(QoiEncoderOptions options) {
            this.channels = options.channels;
            this.channelsSet = options.channels != 0;
            this.linear = options.linear;
        }

        /**
         * Sets the declared channel count; see {@link QoiEncoderOptions#channels()}.
         *
         * @param channels the value
         * @return this builder
         */
        public Builder channels(int channels) {
            this.channels = channels;
            this.channelsSet = true;
            return this;
        }

        /**
         * Declares the channel count from the picture's alpha; see {@link QoiEncoderOptions#channels()}.
         *
         * @return this builder
         */
        public Builder automaticChannels() {
            this.channels = 0;
            this.channelsSet = false;
            return this;
        }

        /**
         * Sets whether the header labels the channels linear; see {@link QoiEncoderOptions#linear()}.
         *
         * @param linear the value
         * @return this builder
         */
        public Builder linear(boolean linear) {
            this.linear = linear;
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the channel count is set to neither 3 nor 4
         */
        public QoiEncoderOptions build() {
            if (channelsSet) {
                Checks.inRange("channels", channels, 3, 4);
            }
            return new QoiEncoderOptions(this);
        }
    }
}
