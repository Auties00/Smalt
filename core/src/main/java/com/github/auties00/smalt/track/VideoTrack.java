package com.github.auties00.smalt.track;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes a video elementary stream.
 *
 * <p>Instances are created with {@link #builder()} or derived from another track with
 * {@link #toBuilder()}. The codec configuration array is shared, not copied. Instances are
 * immutable and thread-safe, provided the configuration array is not modified.
 */
public final class VideoTrack implements Track {
    /**
     * The identifier carried by the track's packets.
     */
    private final int id;

    /**
     * The coding format.
     */
    private final Codec codec;

    /**
     * The unit of timestamps and durations.
     */
    private final Rational timeBase;

    /**
     * The duration in time-base units, or {@code -1}.
     */
    private final long duration;

    /**
     * The out-of-band decoder configuration, or {@code null}.
     */
    private final byte[] codecConfig;

    /**
     * The ISO 639-2 language code, or {@code null}.
     */
    private final String language;

    /**
     * The coded picture width, or {@code 0}.
     */
    private final int width;

    /**
     * The coded picture height, or {@code 0}.
     */
    private final int height;

    /**
     * The display transform.
     */
    private final Orientation orientation;

    /**
     * The nominal frame rate, or {@code null}.
     */
    private final Rational frameRate;

    /**
     * The declared color description, or {@code null}.
     */
    private final ColorInfo color;

    private VideoTrack(Builder builder) {
        this.id = builder.id;
        this.codec = builder.codec;
        this.timeBase = builder.timeBase;
        this.duration = builder.duration;
        this.codecConfig = builder.codecConfig;
        this.language = builder.language;
        this.width = builder.width;
        this.height = builder.height;
        this.orientation = builder.orientation;
        this.frameRate = builder.frameRate;
        this.color = builder.color;
    }

    /**
     * Returns a builder of video tracks.
     *
     * <p>The codec and the time base must be set; every other property starts at its "unknown"
     * value: identifier {@code 0}, duration {@code -1}, no configuration, language, frame rate or
     * color, size {@code 0x0} and {@link Orientation#NORMAL}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with the properties of this track.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        var builder = new Builder();
        builder.id = id;
        builder.codec = codec;
        builder.timeBase = timeBase;
        builder.duration = duration;
        builder.codecConfig = codecConfig;
        builder.language = language;
        builder.width = width;
        builder.height = height;
        builder.orientation = orientation;
        builder.frameRate = frameRate;
        builder.color = color;
        return builder;
    }

    @Override
    public int id() {
        return id;
    }

    /**
     * {@inheritDoc}
     *
     * @return the codec; its kind is {@link TrackKind#VIDEO}
     */
    @Override
    public Codec codec() {
        return codec;
    }

    @Override
    public Rational timeBase() {
        return timeBase;
    }

    @Override
    public long duration() {
        return duration;
    }

    @Override
    public Optional<byte[]> codecConfig() {
        return Optional.ofNullable(codecConfig);
    }

    @Override
    public Optional<String> language() {
        return Optional.ofNullable(language);
    }

    /**
     * Returns the coded picture width, before {@link #orientation()} is applied.
     *
     * @return the width in pixels, or {@code 0} when the container does not declare it
     */
    public int width() {
        return width;
    }

    /**
     * Returns the coded picture height, before {@link #orientation()} is applied.
     *
     * @return the height in pixels, or {@code 0} when the container does not declare it
     */
    public int height() {
        return height;
    }

    /**
     * Returns the transform a player applies to display the pictures upright.
     *
     * @return the orientation
     */
    public Orientation orientation() {
        return orientation;
    }

    /**
     * Returns the nominal frame rate.
     *
     * @return the frame rate, or empty when the container does not declare one
     */
    public Optional<Rational> frameRate() {
        return Optional.ofNullable(frameRate);
    }

    /**
     * Returns the color description declared by the container.
     *
     * @return the description, or empty when the decoder must take it from the bitstream
     */
    public Optional<ColorInfo> color() {
        return Optional.ofNullable(color);
    }

    /**
     * Compares two tracks property by property, the configuration by content.
     *
     * @param other the object to compare with
     * @return whether {@code other} is a video track with equal properties
     */
    @Override
    public boolean equals(Object other) {
        return other == this || other instanceof VideoTrack that
                && id == that.id && codec == that.codec && timeBase.equals(that.timeBase) && duration == that.duration
                && Arrays.equals(codecConfig, that.codecConfig) && Objects.equals(language, that.language)
                && width == that.width && height == that.height && orientation == that.orientation
                && Objects.equals(frameRate, that.frameRate) && Objects.equals(color, that.color);
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, codec, timeBase, duration, Arrays.hashCode(codecConfig), language, width, height,
                orientation, frameRate, color);
    }

    /**
     * Returns a description of the properties, with the configuration size instead of its
     * contents.
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "VideoTrack[id=" + id + ", codec=" + codec + ", timeBase=" + timeBase + ", duration=" + duration
                + ", codecConfig=" + (codecConfig == null ? "none" : codecConfig.length + " bytes")
                + ", language=" + language + ", width=" + width + ", height=" + height
                + ", orientation=" + orientation + ", frameRate=" + frameRate + ", color=" + color + "]";
    }

    /**
     * Builds {@link VideoTrack} instances.
     *
     * <p>Instances are not thread-safe; confine a builder to one thread.
     */
    public static final class Builder {
        /**
         * The identifier.
         */
        private int id;

        /**
         * The coding format, or {@code null} until set.
         */
        private Codec codec;

        /**
         * The time base, or {@code null} until set.
         */
        private Rational timeBase;

        /**
         * The duration, or {@code -1}.
         */
        private long duration = -1;

        /**
         * The configuration, or {@code null}.
         */
        private byte[] codecConfig;

        /**
         * The language, or {@code null}.
         */
        private String language;

        /**
         * The coded width, or {@code 0}.
         */
        private int width;

        /**
         * The coded height, or {@code 0}.
         */
        private int height;

        /**
         * The display transform.
         */
        private Orientation orientation = Orientation.NORMAL;

        /**
         * The frame rate, or {@code null}.
         */
        private Rational frameRate;

        /**
         * The color description, or {@code null}.
         */
        private ColorInfo color;

        /**
         * Constructs a builder with every property unknown.
         */
        private Builder() {
        }

        /**
         * Sets the identifier the track's packets carry.
         *
         * @param id the identifier, unique within the container
         * @return this builder
         */
        public Builder id(int id) {
            this.id = id;
            return this;
        }

        /**
         * Sets the coding format.
         *
         * @param codec the codec; its kind must be {@link TrackKind#VIDEO}
         * @return this builder
         */
        public Builder codec(Codec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * Sets the unit of timestamps and durations.
         *
         * @param timeBase the time base
         * @return this builder
         */
        public Builder timeBase(Rational timeBase) {
            this.timeBase = Objects.requireNonNull(timeBase, "timeBase");
            return this;
        }

        /**
         * Sets the stream duration.
         *
         * @param duration the duration in time-base units, or {@code -1} when unknown
         * @return this builder
         */
        public Builder duration(long duration) {
            this.duration = duration;
            return this;
        }

        /**
         * Sets the out-of-band decoder configuration, in the form {@link Track} describes.
         *
         * @param codecConfig the configuration, shared and not copied, or {@code null} for none
         * @return this builder
         */
        public Builder codecConfig(byte[] codecConfig) {
            this.codecConfig = codecConfig;
            return this;
        }

        /**
         * Sets the declared language.
         *
         * @param language the ISO 639-2 language code, or {@code null} when undeclared
         * @return this builder
         */
        public Builder language(String language) {
            this.language = language;
            return this;
        }

        /**
         * Sets the coded picture width, before the orientation is applied.
         *
         * @param width the width in pixels, or {@code 0} when undeclared; non-negative
         * @return this builder
         */
        public Builder width(int width) {
            this.width = width;
            return this;
        }

        /**
         * Sets the coded picture height, before the orientation is applied.
         *
         * @param height the height in pixels, or {@code 0} when undeclared; non-negative
         * @return this builder
         */
        public Builder height(int height) {
            this.height = height;
            return this;
        }

        /**
         * Sets the transform a player applies to display the pictures upright;
         * {@link Orientation#NORMAL} by default.
         *
         * @param orientation the transform
         * @return this builder
         */
        public Builder orientation(Orientation orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * Sets the nominal frame rate.
         *
         * @param frameRate the frame rate, or {@code null} when undeclared
         * @return this builder
         */
        public Builder frameRate(Rational frameRate) {
            this.frameRate = frameRate;
            return this;
        }

        /**
         * Sets the color description declared by the container.
         *
         * @param color the description, or {@code null} when the decoder must take it from the
         * bitstream
         * @return this builder
         */
        public Builder color(ColorInfo color) {
            this.color = color;
            return this;
        }

        /**
         * Builds the track.
         *
         * @return the track
         * @throws NullPointerException if the codec or the time base is not set
         * @throws IllegalArgumentException if the codec is not a video codec or a dimension is
         * negative
         */
        public VideoTrack build() {
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(timeBase, "timeBase");
            if (codec.kind() != TrackKind.VIDEO) {
                throw new IllegalArgumentException(codec + " is not a video codec");
            }
            if (width < 0 || height < 0) {
                throw new IllegalArgumentException("invalid video size " + width + "x" + height);
            }
            return new VideoTrack(this);
        }
    }
}
