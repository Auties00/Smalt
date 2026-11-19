package com.github.auties00.smalt.track;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes a stream this module does not decode: subtitles, timed metadata, chapters, or
 * anything else a container carries alongside audio and video.
 *
 * <p>Instances are created with {@link #builder()} or derived from another track with
 * {@link #toBuilder()}. The codec configuration array is shared, not copied. Instances are
 * immutable and thread-safe, provided the configuration array is not modified.
 */
public final class DataTrack implements Track {
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
     * The out-of-band configuration, or {@code null}.
     */
    private final byte[] codecConfig;

    /**
     * The ISO 639-2 language code, or {@code null}.
     */
    private final String language;

    /**
     * The container's identifier for the stream type, or {@code null}.
     */
    private final String handler;

    /**
     * The width of the presentation region in pixels, or {@code 0}.
     */
    private final int width;

    /**
     * The height of the presentation region in pixels, or {@code 0}.
     */
    private final int height;

    private DataTrack(Builder builder) {
        this.id = builder.id;
        this.codec = builder.codec;
        this.timeBase = builder.timeBase;
        this.duration = builder.duration;
        this.codecConfig = builder.codecConfig;
        this.language = builder.language;
        this.handler = builder.handler;
        this.width = builder.width;
        this.height = builder.height;
    }

    /**
     * Returns a builder of data tracks.
     *
     * <p>The time base must be set; every other property starts at its default: codec
     * {@link Codec#DATA}, identifier {@code 0}, duration {@code -1}, and no configuration,
     * language, handler or size.
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
        builder.handler = handler;
        builder.width = width;
        builder.height = height;
        return builder;
    }

    @Override
    public int id() {
        return id;
    }

    /**
     * {@inheritDoc}
     *
     * @return the codec; its kind is {@link TrackKind#DATA}
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
     * Returns the container's own identifier for the stream type, for example an MP4 handler
     * type or a Matroska codec id.
     *
     * @return the identifier, or empty when absent
     */
    public Optional<String> handler() {
        return Optional.ofNullable(handler);
    }

    /**
     * Returns the width of the region the track is presented in, the size its positions are measured in.
     *
     * <p>An MP4 track keeps it in its track header: a 3GPP timed text box ({@code tbox}) of a track whose
     * sample entry declares no default text box is measured in this region, and a VobSub track is drawn in it.
     *
     * @return the width in pixels, or {@code 0} when the track declares no size
     */
    public int width() {
        return width;
    }

    /**
     * Returns the height of the region the track is presented in; see {@link #width()}.
     *
     * @return the height in pixels, or {@code 0} when the track declares no size
     */
    public int height() {
        return height;
    }

    /**
     * Compares two tracks property by property, the configuration by content.
     *
     * @param other the object to compare with
     * @return whether {@code other} is a data track with equal properties
     */
    @Override
    public boolean equals(Object other) {
        return other == this || other instanceof DataTrack that
                && id == that.id && codec == that.codec && timeBase.equals(that.timeBase) && duration == that.duration
                && Arrays.equals(codecConfig, that.codecConfig) && Objects.equals(language, that.language)
                && Objects.equals(handler, that.handler) && width == that.width && height == that.height;
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, codec, timeBase, duration, Arrays.hashCode(codecConfig), language, handler, width,
                height);
    }

    /**
     * Returns a description of the properties, with the configuration size instead of its
     * contents.
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "DataTrack[id=" + id + ", codec=" + codec + ", timeBase=" + timeBase + ", duration=" + duration
                + ", codecConfig=" + (codecConfig == null ? "none" : codecConfig.length + " bytes")
                + ", language=" + language + ", handler=" + handler
                + (width > 0 ? ", size=" + width + "x" + height : "") + "]";
    }

    /**
     * Builds {@link DataTrack} instances.
     *
     * <p>Instances are not thread-safe; confine a builder to one thread.
     */
    public static final class Builder {
        /**
         * The identifier.
         */
        private int id;

        /**
         * The coding format.
         */
        private Codec codec = Codec.DATA;

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
         * The container's identifier for the stream type, or {@code null}.
         */
        private String handler;

        /**
         * The width, or {@code 0}.
         */
        private int width;

        /**
         * The height, or {@code 0}.
         */
        private int height;

        /**
         * Constructs a builder with the default properties.
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
         * @param codec the codec; its kind must be {@link TrackKind#DATA}
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
         * Sets the out-of-band configuration.
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
         * Sets the container's own identifier for the stream type.
         *
         * @param handler the identifier, for example an MP4 handler type or a Matroska codec id,
         * or {@code null} when absent
         * @return this builder
         */
        public Builder handler(String handler) {
            this.handler = handler;
            return this;
        }

        /**
         * Sets the size of the region the track is presented in; see {@link DataTrack#width()}.
         *
         * @param width the width in pixels, from 1 to 65535
         * @param height the height in pixels, from 1 to 65535
         * @return this builder
         * @throws IllegalArgumentException if {@code width} or {@code height} is out of range
         */
        public Builder size(int width, int height) {
            if (width < 1 || width > 0xFFFF || height < 1 || height > 0xFFFF) {
                throw new IllegalArgumentException("size " + width + "x" + height + " is out of range");
            }
            this.width = width;
            this.height = height;
            return this;
        }

        /**
         * Removes the size, so that the track declares none.
         *
         * @return this builder
         */
        public Builder noSize() {
            this.width = 0;
            this.height = 0;
            return this;
        }

        /**
         * Builds the track.
         *
         * @return the track
         * @throws NullPointerException if the time base is not set
         * @throws IllegalArgumentException if the codec is not a data codec
         */
        public DataTrack build() {
            Objects.requireNonNull(timeBase, "timeBase");
            if (codec.kind() != TrackKind.DATA) {
                throw new IllegalArgumentException(codec + " is not a data codec");
            }
            return new DataTrack(this);
        }
    }
}
