package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.loudness.LoudnessNormalization;
import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.TrackKind;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of what a transcode does with the audio streams. Instances are immutable and
 * thread-safe; create them with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>An encoded stream is decoded to exactly the samples a player outputs (encoder priming and end
 * padding removed), mixed to {@link #layout()} or down to {@link #maxChannels()}, resampled to
 * {@link #sampleRate()}, scaled by {@link #gainDb()}, and encoded by the encoder
 * {@link com.github.auties00.smalt.audio.AudioEncoders} finds for {@link #codec()}, configured through
 * {@link com.github.auties00.smalt.audio.AudioEncoderOptions} with {@link #bitrate()}. The output
 * sample format is the codec's: the samples are processed as 32-bit floats and a PCM codec
 * ({@link Codec#PCM_S16LE}, {@link Codec#PCM_F32LE} and the others) picks the stored format.
 */
public final class AudioTranscodeOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final AudioTranscodeOptions DEFAULTS = new Builder().build();

    private final StreamMode mode;

    /**
     * The identifier of the selected track, or {@code -1} for the first audio track.
     */
    private final int trackId;

    private final boolean allTracks;

    /**
     * The codec to produce, or {@code null} to let the container choose.
     */
    private final Codec codec;

    /**
     * The target bitrate, or {@code 0} for the codec's default.
     */
    private final int bitrate;

    /**
     * The output sampling rate, or {@code 0} to keep the source rate.
     */
    private final int sampleRate;

    /**
     * The output channel layout, or {@code null} to keep the source layout.
     */
    private final ChannelLayout layout;

    /**
     * The largest output channel count, or {@code 0} for no limit.
     */
    private final int maxChannels;

    private final double gainDb;

    /**
     * The loudness normalization, or {@code null}.
     */
    private final LoudnessNormalization loudnessNormalization;

    private AudioTranscodeOptions(Builder builder, int trackId, int bitrate, int sampleRate, int maxChannels) {
        this.mode = builder.mode;
        this.trackId = trackId;
        this.allTracks = builder.allTracks;
        this.codec = builder.codec;
        this.bitrate = bitrate;
        this.sampleRate = sampleRate;
        this.layout = builder.layout;
        this.maxChannels = maxChannels;
        this.gainDb = builder.gainDb;
        this.loudnessNormalization = builder.loudnessNormalization;
    }

    /**
     * Returns the default options: the first audio track, copied when possible and encoded
     * otherwise, at its rate and layout.
     *
     * @return the options
     */
    public static AudioTranscodeOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options that copy the first audio track unchanged.
     *
     * @return the options
     */
    public static AudioTranscodeOptions copy() {
        return DEFAULTS.toBuilder().mode(StreamMode.COPY).build();
    }

    /**
     * Returns options that encode the first audio track with a codec.
     *
     * @param codec the codec to produce
     * @return the options
     * @throws IllegalArgumentException if {@code codec} is not an audio codec
     */
    public static AudioTranscodeOptions encode(Codec codec) {
        return new Builder().mode(StreamMode.ENCODE).codec(codec).build();
    }

    /**
     * Returns options that leave audio out of the output.
     *
     * @return the options
     */
    public static AudioTranscodeOptions drop() {
        return DEFAULTS.toBuilder().mode(StreamMode.DROP).build();
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
     * {@return whether the selected streams are copied, encoded or dropped; {@link StreamMode#AUTO} by
     * default}
     */
    public StreamMode mode() {
        return mode;
    }

    /**
     * {@return the identifier of the selected track, zero or more, or empty to select the first audio
     * track (or every audio track when {@link #allTracks()} is set); empty by default}
     */
    public OptionalInt trackId() {
        return trackId < 0 ? OptionalInt.empty() : OptionalInt.of(trackId);
    }

    /**
     * {@return whether every audio track of the input is selected, each becoming one output stream;
     * {@code false} by default}
     */
    public boolean allTracks() {
        return allTracks;
    }

    /**
     * {@return the audio codec to produce, or empty to keep the source codec when it can be copied and
     * otherwise use the first encodable codec of {@link OutputContainer#audioCodecs()}; empty by
     * default}
     */
    public Optional<Codec> codec() {
        return Optional.ofNullable(codec);
    }

    /**
     * {@return the target bitrate of an encoded stream, in bits per second, positive, or empty for the
     * codec's default; empty by default}
     */
    public OptionalInt bitrate() {
        return bitrate == 0 ? OptionalInt.empty() : OptionalInt.of(bitrate);
    }

    /**
     * {@return the output sampling rate, in hertz, positive, or empty to keep the source rate; empty by
     * default}
     *
     * <p>When the source rate is kept, a codec that cannot code it (Opus codes 8, 12, 16, 24 and
     * 48 kHz only) gets 48 kHz instead.
     */
    public OptionalInt sampleRate() {
        return sampleRate == 0 ? OptionalInt.empty() : OptionalInt.of(sampleRate);
    }

    /**
     * {@return the output channel layout, or empty to keep the source layout; empty by default}
     *
     * <p>When the source layout is kept, a codec that cannot code it gets the stream mixed down to
     * stereo.
     */
    public Optional<ChannelLayout> layout() {
        return Optional.ofNullable(layout);
    }

    /**
     * {@return the largest output channel count, positive, or empty for no limit; empty by default}
     *
     * <p>A source with more channels is mixed down to the default layout of this count
     * ({@link ChannelLayout#defaultFor(int)}).
     */
    public OptionalInt maxChannels() {
        return maxChannels == 0 ? OptionalInt.empty() : OptionalInt.of(maxChannels);
    }

    /**
     * {@return the gain applied to the samples, in decibels, finite; 0 (none) by default}
     *
     * <p>Samples that exceed full scale are clipped by the encoder.
     */
    public double gainDb() {
        return gainDb;
    }

    /**
     * {@return the loudness normalization applied to encoded streams, or empty for none; empty by
     * default}
     *
     * <p>When present, every selected stream is encoded: the transcode first decodes the kept
     * range once to measure its integrated loudness and true peak
     * ({@link com.github.auties00.smalt.audio.loudness.LoudnessMeter}, on the samples mixed and
     * resampled for the encoder), then encodes it with the gain
     * {@link LoudnessNormalization#gainDb(com.github.auties00.smalt.audio.loudness.LoudnessStats)}
     * computes, which replaces {@link #gainDb()}.
     */
    public Optional<LoudnessNormalization> loudnessNormalization() {
        return Optional.ofNullable(loudnessNormalization);
    }

    /**
     * Returns the output layout for a source layout under these options.
     *
     * @param source the source layout
     * @return the output layout
     */
    ChannelLayout outputLayout(ChannelLayout source) {
        if (layout != null) {
            return layout;
        }
        if (maxChannels > 0 && source.channels() > maxChannels) {
            return ChannelLayout.defaultFor(maxChannels);
        }
        return source;
    }

    /**
     * Returns whether the options change the samples of a source, which a copy cannot do.
     *
     * @param sourceRate   the source sampling rate
     * @param sourceLayout the source channel layout, or {@code null} when unknown
     * @return {@code true} when the stream has to be decoded
     */
    boolean changesSamples(int sourceRate, ChannelLayout sourceLayout) {
        if (gainDb != 0 || loudnessNormalization != null || (sampleRate != 0 && sampleRate != sourceRate)) {
            return true;
        }
        if (sourceLayout == null) {
            return layout != null || maxChannels > 0;
        }
        return !outputLayout(sourceLayout).equals(sourceLayout);
    }

    /**
     * Returns the state compared by {@link #equals(Object)}.
     *
     * @return the field values
     */
    private Object[] state() {
        return new Object[]{mode, trackId, allTracks, codec, bitrate, sampleRate, layout, maxChannels, gainDb,
                loudnessNormalization};
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AudioTranscodeOptions that && Arrays.equals(state(), that.state());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(state());
    }

    @Override
    public String toString() {
        return "AudioTranscodeOptions[mode=" + mode + ", trackId=" + trackId() + ", allTracks=" + allTracks + ", codec="
                + codec() + ", bitrate=" + bitrate() + ", sampleRate=" + sampleRate() + ", layout=" + layout()
                + ", maxChannels=" + maxChannels() + ", gainDb=" + gainDb + ", loudnessNormalization="
                + loudnessNormalization() + "]";
    }

    /**
     * Assembles {@link AudioTranscodeOptions} setting by setting.
     *
     * <p>The track selections {@link #trackId(int)}, {@link #firstTrack()} and {@link #allTracks()}
     * exclude each other: the last one called wins. Setters reject {@code null} unless documented
     * otherwise and store their value unchecked; {@link #build()} checks the ranges and the codec.
     * Instances are not thread-safe.
     */
    public static final class Builder {
        private StreamMode mode = StreamMode.AUTO;
        private int trackId;

        /**
         * Whether {@link #trackId} selects the track.
         */
        private boolean byId;

        private boolean allTracks;
        private Codec codec;
        private int bitrate;

        /**
         * Whether {@link #bitrate} is set; {@code false} for the codec's default.
         */
        private boolean bitrateSet;

        private int sampleRate;

        /**
         * Whether {@link #sampleRate} is set; {@code false} to keep the source rate.
         */
        private boolean sampleRateSet;

        private ChannelLayout layout;
        private int maxChannels;

        /**
         * Whether {@link #maxChannels} limits the output.
         */
        private boolean channelsLimited;

        private double gainDb;
        private LoudnessNormalization loudnessNormalization;

        private Builder() {
        }

        private Builder(AudioTranscodeOptions options) {
            this.mode = options.mode;
            this.trackId = options.trackId;
            this.byId = options.trackId >= 0;
            this.allTracks = options.allTracks;
            this.codec = options.codec;
            this.bitrate = options.bitrate;
            this.bitrateSet = options.bitrate != 0;
            this.sampleRate = options.sampleRate;
            this.sampleRateSet = options.sampleRate != 0;
            this.layout = options.layout;
            this.maxChannels = options.maxChannels;
            this.channelsLimited = options.maxChannels != 0;
            this.gainDb = options.gainDb;
            this.loudnessNormalization = options.loudnessNormalization;
        }

        /**
         * Sets the mode; see {@link AudioTranscodeOptions#mode()}.
         *
         * @param mode the value
         * @return this builder
         */
        public Builder mode(StreamMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Selects one track by identifier; see {@link AudioTranscodeOptions#trackId()}.
         *
         * @param trackId the value
         * @return this builder
         */
        public Builder trackId(int trackId) {
            this.trackId = trackId;
            this.byId = true;
            this.allTracks = false;
            return this;
        }

        /**
         * Selects the first audio track; see {@link AudioTranscodeOptions#trackId()}.
         *
         * @return this builder
         */
        public Builder firstTrack() {
            this.byId = false;
            this.allTracks = false;
            return this;
        }

        /**
         * Selects every audio track; see {@link AudioTranscodeOptions#allTracks()}.
         *
         * @return this builder
         */
        public Builder allTracks() {
            this.byId = false;
            this.allTracks = true;
            return this;
        }

        /**
         * Sets the codec to produce; see {@link AudioTranscodeOptions#codec()}.
         *
         * @param codec the value
         * @return this builder
         */
        public Builder codec(Codec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * Lets the output container choose the codec; see {@link AudioTranscodeOptions#codec()}.
         *
         * @return this builder
         */
        public Builder automaticCodec() {
            this.codec = null;
            return this;
        }

        /**
         * Sets the target bitrate; see {@link AudioTranscodeOptions#bitrate()}.
         *
         * @param bitrate the value
         * @return this builder
         */
        public Builder bitrate(int bitrate) {
            this.bitrate = bitrate;
            this.bitrateSet = true;
            return this;
        }

        /**
         * Lets the encoder choose the bitrate; see {@link AudioTranscodeOptions#bitrate()}.
         *
         * @return this builder
         */
        public Builder automaticBitrate() {
            this.bitrateSet = false;
            return this;
        }

        /**
         * Sets the output sampling rate; see {@link AudioTranscodeOptions#sampleRate()}.
         *
         * @param sampleRate the value
         * @return this builder
         */
        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            this.sampleRateSet = true;
            return this;
        }

        /**
         * Keeps the sampling rate of the source; see {@link AudioTranscodeOptions#sampleRate()}.
         *
         * @return this builder
         */
        public Builder sourceSampleRate() {
            this.sampleRateSet = false;
            return this;
        }

        /**
         * Sets the output channel layout; see {@link AudioTranscodeOptions#layout()}.
         *
         * @param layout the value
         * @return this builder
         */
        public Builder layout(ChannelLayout layout) {
            this.layout = Objects.requireNonNull(layout, "layout");
            return this;
        }

        /**
         * Keeps the channel layout of the source; see {@link AudioTranscodeOptions#layout()}.
         *
         * @return this builder
         */
        public Builder sourceLayout() {
            this.layout = null;
            return this;
        }

        /**
         * Sets the largest output channel count; see {@link AudioTranscodeOptions#maxChannels()}.
         *
         * @param maxChannels the value
         * @return this builder
         */
        public Builder maxChannels(int maxChannels) {
            this.maxChannels = maxChannels;
            this.channelsLimited = true;
            return this;
        }

        /**
         * Removes the limit on the output channel count; see {@link AudioTranscodeOptions#maxChannels()}.
         *
         * @return this builder
         */
        public Builder noMaxChannels() {
            this.channelsLimited = false;
            return this;
        }

        /**
         * Sets the gain; see {@link AudioTranscodeOptions#gainDb()}.
         *
         * @param gainDb the value
         * @return this builder
         */
        public Builder gainDb(double gainDb) {
            this.gainDb = gainDb;
            return this;
        }

        /**
         * Sets the loudness normalization; see {@link AudioTranscodeOptions#loudnessNormalization()}.
         *
         * @param normalization the value
         * @return this builder
         */
        public Builder loudnessNormalization(LoudnessNormalization normalization) {
            this.loudnessNormalization = Objects.requireNonNull(normalization, "normalization");
            return this;
        }

        /**
         * Removes the loudness normalization; see {@link AudioTranscodeOptions#loudnessNormalization()}.
         *
         * @return this builder
         */
        public Builder noLoudnessNormalization() {
            this.loudnessNormalization = null;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the track identifier is negative, the codec is not an
         * audio codec, the bitrate, sampling rate or channel limit is not positive, or the gain is not
         * finite
         */
        public AudioTranscodeOptions build() {
            if (codec != null && codec.kind() != TrackKind.AUDIO) {
                throw new IllegalArgumentException("codec " + codec.displayName() + " is not an audio codec");
            }
            if (!Double.isFinite(gainDb)) {
                throw new IllegalArgumentException("gain " + gainDb + " is not finite");
            }
            return new AudioTranscodeOptions(this,
                    byId ? Checks.nonNegative("track identifier", trackId) : -1,
                    bitrateSet ? Checks.positive("bitrate", bitrate) : 0,
                    sampleRateSet ? Checks.positive("sample rate", sampleRate) : 0,
                    channelsLimited ? Checks.positive("maximum channel count", maxChannels) : 0);
        }
    }
}
