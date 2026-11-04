package com.github.auties00.smalt.image.hap;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Rational;

import java.util.Objects;

/**
 * Holds the settings of a {@link HapEncoder}. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #defaults()}.
 */
public final class HapEncoderOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final HapEncoderOptions DEFAULTS = new Builder().build();

    private final HapFormat format;

    private final boolean snappy;

    private final int chunks;

    private final Rational frameRate;

    private final Rational timeBase;

    private final MediaLimits limits;

    private HapEncoderOptions(Builder builder) {
        this.format = builder.format;
        this.snappy = builder.snappy;
        this.chunks = builder.chunks;
        this.frameRate = builder.frameRate;
        this.timeBase = builder.timeBase;
        this.limits = builder.limits;
    }

    /**
     * Returns the options for {@link HapFormat#HAP} textures compressed with Snappy in one chunk at 30 frames per
     * second, timestamps in 1/90000 s,
     * under the default limits.
     *
     * @return the default options
     */
    public static HapEncoderOptions defaults() {
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
     * {@return the texture format of the frames; {@link HapFormat#HAP} by default}
     */
    public HapFormat format() {
        return format;
    }

    /**
     * {@return whether the textures are compressed with Snappy; {@code true} by default}
     *
     * <p>A chunk that Snappy does not make smaller is stored uncompressed either way.
     */
    public boolean snappy() {
        return snappy;
    }

    /**
     * {@return the number of chunks each texture is split into, compressed and decompressed independently, from 1 to
     * 64; 1 by default}
     *
     * <p>More chunks let a decoder decompress a frame on several threads; a texture of fewer blocks than chunks has
     * one chunk per block.
     */
    public int chunks() {
        return chunks;
    }

    /**
     * {@return the nominal frame rate, from 0.1 to 1000 frames per second, which gives the timing of frames without
     * one; 30 by default}
     */
    public Rational frameRate() {
        return frameRate;
    }

    /**
     * {@return the time base of the timestamps of accepted frames and produced packets; {@code 1/90000} by default}
     */
    public Rational timeBase() {
        return timeBase;
    }

    /**
     * {@return the limits the picture size is checked against; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HapEncoderOptions that
                && format == that.format
                && snappy == that.snappy
                && chunks == that.chunks
                && frameRate.equals(that.frameRate)
                && timeBase.equals(that.timeBase)
                && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(format, snappy, chunks, frameRate, timeBase, limits);
    }

    @Override
    public String toString() {
        return "HapEncoderOptions[format=" + format + ", snappy=" + snappy + ", chunks=" + chunks
                + ", frameRate=" + frameRate + ", timeBase=" + timeBase
                + ", limits=" + limits + "]";
    }

    /**
     * A builder of {@link HapEncoderOptions}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the ranges. Instances are not thread-safe.
     */
    public static final class Builder {
        private HapFormat format = HapFormat.HAP;

        private boolean snappy = true;

        private int chunks = 1;

        private Rational frameRate = new Rational(30, 1);

        private Rational timeBase = new Rational(1, 90_000);

        private MediaLimits limits = MediaLimits.defaults();

        private Builder() {
        }

        private Builder(HapEncoderOptions options) {
            this.format = options.format;
            this.snappy = options.snappy;
            this.chunks = options.chunks;
            this.frameRate = options.frameRate;
            this.timeBase = options.timeBase;
            this.limits = options.limits;
        }

        /**
         * Sets the texture format; see {@link HapEncoderOptions#format()}.
         *
         * @param format the value
         * @return this builder
         */
        public Builder format(HapFormat format) {
            this.format = Objects.requireNonNull(format, "format");
            return this;
        }

        /**
         * Sets whether the textures are compressed with Snappy; see {@link HapEncoderOptions#snappy()}.
         *
         * @param snappy the value
         * @return this builder
         */
        public Builder snappy(boolean snappy) {
            this.snappy = snappy;
            return this;
        }

        /**
         * Sets the number of chunks; see {@link HapEncoderOptions#chunks()}.
         *
         * @param chunks the value
         * @return this builder
         */
        public Builder chunks(int chunks) {
            this.chunks = chunks;
            return this;
        }

        /**
         * Sets the nominal frame rate; see {@link HapEncoderOptions#frameRate()}.
         *
         * @param frameRate the value
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = Objects.requireNonNull(frameRate, "frameRate");
            return this;
        }

        /**
         * Sets the time base; see {@link HapEncoderOptions#timeBase()}.
         *
         * @param timeBase the value
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the limits; see {@link HapEncoderOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Returns the options holding the settings of this builder.
         *
         * @return the options
         * @throws IllegalArgumentException if the number of chunks is not in [1, 64], the frame rate is not in
         * [0.1, 1000] or a time base term is not positive
         */
        public HapEncoderOptions build() {
            Checks.inRange("chunks", chunks, 1, 64);
            Checks.inRange("frame rate", frameRate.toDouble(), 0.1, 1000);
            Checks.positive("time base numerator", timeBase.numerator());
            Checks.positive("time base denominator", timeBase.denominator());
            return new HapEncoderOptions(this);
        }
    }
}
