package com.github.auties00.smalt.track;

import com.github.auties00.smalt.audio.ChannelLayout;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Describes an audio elementary stream.
 *
 * <p>The {@link #skipSamples()} property carries the leading decoder output the container asks
 * players to discard, typically the encoder priming signaled by an MP4 edit list or an iTunes
 * gapless tag. Codecs that signal priming in band (Opus pre-skip) handle it themselves and leave
 * this at zero.
 *
 * <p>Instances are created with {@link #builder()} or derived from another track with
 * {@link #toBuilder()}. The codec configuration array is shared, not copied. Instances are
 * immutable and thread-safe, provided the configuration array is not modified.
 */
public final class AudioTrack implements Track {
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
     * The declared sampling rate, or {@code 0}.
     */
    private final int sampleRate;

    /**
     * The declared channel layout, or {@code null}.
     */
    private final ChannelLayout layout;

    /**
     * The declared PCM sample size, or {@code 0}.
     */
    private final int bitsPerSample;

    /**
     * The leading decoded samples per channel to discard.
     */
    private final long skipSamples;

    private AudioTrack(Builder builder) {
        this.id = builder.id;
        this.codec = builder.codec;
        this.timeBase = builder.timeBase;
        this.duration = builder.duration;
        this.codecConfig = builder.codecConfig;
        this.language = builder.language;
        this.sampleRate = builder.sampleRate;
        this.layout = builder.layout;
        this.bitsPerSample = builder.bitsPerSample;
        this.skipSamples = builder.skipSamples;
    }

    /**
     * Returns a builder of audio tracks.
     *
     * <p>The codec and the time base must be set; every other property starts at its "unknown"
     * value: identifier {@code 0}, duration {@code -1}, no configuration, language or layout,
     * sampling rate and sample size {@code 0}, and no samples to skip.
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
        builder.sampleRate = sampleRate;
        builder.layout = layout;
        builder.bitsPerSample = bitsPerSample;
        builder.skipSamples = skipSamples;
        return builder;
    }

    @Override
    public int id() {
        return id;
    }

    /**
     * {@inheritDoc}
     *
     * @return the codec; its kind is {@link TrackKind#AUDIO}
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
     * Returns the sampling rate declared by the container.
     *
     * @return the rate in hertz, or {@code 0} when the decoder must take it from the bitstream
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * Returns the channel layout declared by the container.
     *
     * @return the layout, or empty when the decoder must take it from the bitstream
     */
    public Optional<ChannelLayout> layout() {
        return Optional.ofNullable(layout);
    }

    /**
     * Returns the declared sample size of a PCM codec.
     *
     * @return the size in bits, or {@code 0} when not applicable
     */
    public int bitsPerSample() {
        return bitsPerSample;
    }

    /**
     * Returns the number of leading decoded samples per channel to discard.
     *
     * @return the count, non-negative
     */
    public long skipSamples() {
        return skipSamples;
    }

    /**
     * Compares two tracks property by property, the configuration by content.
     *
     * @param other the object to compare with
     * @return whether {@code other} is an audio track with equal properties
     */
    @Override
    public boolean equals(Object other) {
        return other == this || other instanceof AudioTrack that
                && id == that.id && codec == that.codec && timeBase.equals(that.timeBase) && duration == that.duration
                && Arrays.equals(codecConfig, that.codecConfig) && Objects.equals(language, that.language)
                && sampleRate == that.sampleRate && Objects.equals(layout, that.layout)
                && bitsPerSample == that.bitsPerSample && skipSamples == that.skipSamples;
    }

    /**
     * Returns a hash code consistent with {@link #equals(Object)}.
     *
     * @return the hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(id, codec, timeBase, duration, Arrays.hashCode(codecConfig), language, sampleRate, layout,
                bitsPerSample, skipSamples);
    }

    /**
     * Returns a description of the properties, with the configuration size instead of its
     * contents.
     *
     * @return the description
     */
    @Override
    public String toString() {
        return "AudioTrack[id=" + id + ", codec=" + codec + ", timeBase=" + timeBase + ", duration=" + duration
                + ", codecConfig=" + (codecConfig == null ? "none" : codecConfig.length + " bytes")
                + ", language=" + language + ", sampleRate=" + sampleRate + ", layout=" + layout
                + ", bitsPerSample=" + bitsPerSample + ", skipSamples=" + skipSamples + "]";
    }

    /**
     * Builds {@link AudioTrack} instances.
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
         * The sampling rate, or {@code 0}.
         */
        private int sampleRate;

        /**
         * The channel layout, or {@code null}.
         */
        private ChannelLayout layout;

        /**
         * The PCM sample size, or {@code 0}.
         */
        private int bitsPerSample;

        /**
         * The samples to skip.
         */
        private long skipSamples;

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
         * @param codec the codec; its kind must be {@link TrackKind#AUDIO}
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
         * Sets the sampling rate declared by the container.
         *
         * @param sampleRate the rate in hertz, or {@code 0} when the decoder must take it from the
         * bitstream; non-negative
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        /**
         * Sets the channel layout declared by the container.
         *
         * @param layout the layout, or {@code null} when the decoder must take it from the
         * bitstream
         * @return this builder
         */
        public Builder layout(ChannelLayout layout) {
            this.layout = layout;
            return this;
        }

        /**
         * Sets the declared sample size of a PCM codec.
         *
         * @param bitsPerSample the size in bits, or {@code 0} when not applicable; non-negative
         * @return this builder
         */
        public Builder bitsPerSample(int bitsPerSample) {
            this.bitsPerSample = bitsPerSample;
            return this;
        }

        /**
         * Sets the number of leading decoded samples per channel to discard.
         *
         * @param skipSamples the count; non-negative
         * @return this builder
         */
        public Builder skipSamples(long skipSamples) {
            this.skipSamples = skipSamples;
            return this;
        }

        /**
         * Builds the track.
         *
         * @return the track
         * @throws NullPointerException if the codec or the time base is not set
         * @throws IllegalArgumentException if the codec is not an audio codec or a numeric
         * property is negative
         */
        public AudioTrack build() {
            Objects.requireNonNull(codec, "codec");
            Objects.requireNonNull(timeBase, "timeBase");
            if (codec.kind() != TrackKind.AUDIO) {
                throw new IllegalArgumentException(codec + " is not an audio codec");
            }
            if (sampleRate < 0 || bitsPerSample < 0 || skipSamples < 0) {
                throw new IllegalArgumentException("negative audio track parameter");
            }
            return new AudioTrack(this);
        }
    }
}
