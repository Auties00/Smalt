package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.track.Codec;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of what a transcode does with the text subtitle streams. Instances are
 * immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 *
 * <p>Containers that hold one track ({@link OutputContainer#SRT}, {@link OutputContainer#WEBVTT})
 * take the first selected stream only.
 */
public final class SubtitleTranscodeOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final SubtitleTranscodeOptions DEFAULTS = new Builder().build();

    private final StreamMode mode;

    /**
     * The identifier of the selected track, or {@code -1}.
     */
    private final int trackId;

    private final boolean allTracks;

    /**
     * The codec to produce, or {@code null} to let the container choose.
     */
    private final Codec codec;

    private SubtitleTranscodeOptions(StreamMode mode, int trackId, boolean allTracks, Codec codec) {
        this.mode = mode;
        this.trackId = trackId;
        this.allTracks = allTracks;
        this.codec = codec;
    }

    /**
     * Returns the default options: every text subtitle track, copied when the container carries
     * its codec, converted when it carries another, and left out otherwise.
     *
     * @return the options
     */
    public static SubtitleTranscodeOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options that copy every text subtitle track unchanged.
     *
     * @return the options
     */
    public static SubtitleTranscodeOptions copy() {
        return DEFAULTS.toBuilder().mode(StreamMode.COPY).build();
    }

    /**
     * Returns options that convert every text subtitle track to a codec.
     *
     * @param codec the text subtitle codec to produce
     * @return the options
     * @throws IllegalArgumentException if {@code codec} is not a text subtitle codec
     */
    public static SubtitleTranscodeOptions encode(Codec codec) {
        return new Builder().mode(StreamMode.ENCODE).codec(codec).build();
    }

    /**
     * Returns options that leave subtitles out of the output.
     *
     * @return the options
     */
    public static SubtitleTranscodeOptions drop() {
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
     * {@return what the transcode does with the selected streams; {@link StreamMode#AUTO} by default}
     *
     * <p>In {@link StreamMode#AUTO} a stream is copied when the output container can carry its codec
     * and converted otherwise, to {@link #codec()} or the first codec of
     * {@link OutputContainer#subtitleCodecs()}; a stream the container cannot carry in any form is
     * left out. {@link StreamMode#ENCODE} always converts, which also rewrites the markup of a stream
     * kept in its codec. Converting keeps the text, line breaks and the bold, italic and underline
     * styles ({@link com.github.auties00.smalt.subtitle.Subtitles}).
     *
     * <p>Copied streams keep their packets, retimed to the output timeline; converted streams are
     * decoded into cues, cut to the kept time range, and encoded again. A cue still shown at the start
     * of the range is kept from the start, and a cue still shown at its end stops there.
     */
    public StreamMode mode() {
        return mode;
    }

    /**
     * {@return the identifier of the selected track, zero or more, or empty to select by
     * {@link #allTracks()}; empty by default}
     */
    public OptionalInt trackId() {
        return trackId < 0 ? OptionalInt.empty() : OptionalInt.of(trackId);
    }

    /**
     * {@return whether every text subtitle track is selected, each becoming one output stream, rather
     * than the first one; {@code true} by default}
     */
    public boolean allTracks() {
        return allTracks;
    }

    /**
     * {@return the text subtitle codec to produce, or empty to keep the source codec when the container
     * carries it and use the first of {@link OutputContainer#subtitleCodecs()} otherwise; empty by
     * default}
     */
    public Optional<Codec> codec() {
        return Optional.ofNullable(codec);
    }

    /**
     * Returns the state compared by {@link #equals(Object)}.
     *
     * @return the field values
     */
    private Object[] state() {
        return new Object[]{mode, trackId, allTracks, codec};
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SubtitleTranscodeOptions that && Arrays.equals(state(), that.state());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(state());
    }

    @Override
    public String toString() {
        return "SubtitleTranscodeOptions[mode=" + mode + ", trackId=" + trackId() + ", allTracks=" + allTracks
                + ", codec=" + codec() + "]";
    }

    /**
     * Assembles {@link SubtitleTranscodeOptions} setting by setting.
     *
     * <p>The track selections {@link #trackId(int)}, {@link #firstTrack()} and {@link #allTracks()}
     * exclude each other: the last one called wins. Setters reject {@code null} and store their value
     * unchecked; {@link #build()} checks the track identifier and the codec. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private StreamMode mode = StreamMode.AUTO;
        private int trackId;

        /**
         * Whether {@link #trackId} selects the track.
         */
        private boolean byId;

        private boolean allTracks = true;
        private Codec codec;

        private Builder() {
        }

        private Builder(SubtitleTranscodeOptions options) {
            this.mode = options.mode;
            this.trackId = options.trackId;
            this.byId = options.trackId >= 0;
            this.allTracks = options.allTracks;
            this.codec = options.codec;
        }

        /**
         * Sets the mode; see {@link SubtitleTranscodeOptions#mode()}.
         *
         * @param mode the value
         * @return this builder
         */
        public Builder mode(StreamMode mode) {
            this.mode = Objects.requireNonNull(mode, "mode");
            return this;
        }

        /**
         * Selects one track by identifier; see {@link SubtitleTranscodeOptions#trackId()}.
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
         * Selects the first text subtitle track; see {@link SubtitleTranscodeOptions#allTracks()}.
         *
         * @return this builder
         */
        public Builder firstTrack() {
            this.byId = false;
            this.allTracks = false;
            return this;
        }

        /**
         * Selects every text subtitle track; see {@link SubtitleTranscodeOptions#allTracks()}.
         *
         * @return this builder
         */
        public Builder allTracks() {
            this.byId = false;
            this.allTracks = true;
            return this;
        }

        /**
         * Sets the codec to produce; see {@link SubtitleTranscodeOptions#codec()}.
         *
         * @param codec the value
         * @return this builder
         */
        public Builder codec(Codec codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        /**
         * Lets the output container choose the codec; see {@link SubtitleTranscodeOptions#codec()}.
         *
         * @return this builder
         */
        public Builder automaticCodec() {
            this.codec = null;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the track identifier is negative or the codec is not a
         * text subtitle codec
         */
        public SubtitleTranscodeOptions build() {
            if (codec != null && !codec.isSubtitle()) {
                throw new IllegalArgumentException("codec " + codec.displayName() + " is not a text subtitle codec");
            }
            var id = byId ? Checks.nonNegative("track identifier", trackId) : -1;
            return new SubtitleTranscodeOptions(mode, id, allTracks, codec);
        }
    }
}
