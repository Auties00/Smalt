package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.internal.check.Checks;
import com.github.auties00.smalt.subtitle.CaptionService;
import com.github.auties00.smalt.track.Codec;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of what a transcode does with the subtitle streams: text subtitles, bitmap subtitles and
 * captions. Instances are immutable and thread-safe; create them with {@link #builder()} or a preset such as
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

    private final boolean closedCaptions;

    /**
     * The caption service decoded, or {@code null} for each track's default.
     */
    private final CaptionService captionService;

    /**
     * The index of the input the tracks come from, or {@code -1} for any input.
     */
    private final int input;

    private SubtitleTranscodeOptions(StreamMode mode, int trackId, boolean allTracks, Codec codec,
                                     boolean closedCaptions, CaptionService captionService, int input) {
        this.mode = mode;
        this.trackId = trackId;
        this.allTracks = allTracks;
        this.codec = codec;
        this.closedCaptions = closedCaptions;
        this.captionService = captionService;
        this.input = input;
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
     * @param codec the text subtitle codec or {@link Codec#TTML}, to produce
     * @return the options
     * @throws IllegalArgumentException if {@code codec} is neither a text subtitle codec nor TTML
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
     * kept in its codec. Converting keeps the text, line breaks, styles, colors, fonts, placements and karaoke
     * times as far as the target codec holds them, an ASS stream with the look of its styles
     * ({@link com.github.auties00.smalt.subtitle.Subtitles}). A TTML stream is copied only when nothing is
     * trimmed, as its documents count time from the start of the input, and converted otherwise.
     *
     * <p>Bitmap subtitle streams ({@link Codec#isBitmapSubtitle()}) are pictures: they are copied when the
     * container carries their codec and the options ask for no other codec, and are otherwise left out, or rejected
     * with {@link com.github.auties00.smalt.MediaException.Unsupported} when the options select them explicitly (a
     * mode other than {@link StreamMode#AUTO} or a track identifier). Caption streams
     * ({@link Codec#isCaption()}: teletext and closed caption tracks) are copied when the container carries their
     * codec and converted to text otherwise, the text of {@link #captionService()}.
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
     * {@return the index of the input the subtitle tracks are selected from, in the list the transcode was given, or
     * empty for any input; empty by default}
     *
     * <p>With any input, the subtitle tracks of every input are candidates, in input order. {@link #trackId()} names
     * a track of this input, or of the first input when this is empty.
     */
    public OptionalInt input() {
        return input < 0 ? OptionalInt.empty() : OptionalInt.of(input);
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
     * {@return whether the closed captions the selected video stream carries are also written as a text subtitle
     * stream; {@code false} by default}
     *
     * <p>The captions of H.264, HEVC, MPEG-1, MPEG-2 and AV1 video
     * ({@link com.github.auties00.smalt.subtitle.ClosedCaptions}) of {@link #captionService()} are converted to
     * {@link #codec()} or the first codec of {@link OutputContainer#subtitleCodecs()}, as one more subtitle stream.
     * Video re-encoded to H.264 keeps its captions in the bitstream whatever this setting; video re-encoded to
     * another codec loses them unless this setting extracts them.
     */
    public boolean closedCaptions() {
        return closedCaptions;
    }

    /**
     * {@return the caption service converted from caption streams and from the captions carried in video, or empty
     * for each stream's default ({@link CaptionService#defaultFor}); empty by default}
     *
     * <p>A service a stream cannot carry (a teletext page for a closed caption stream) leaves that stream at its
     * default.
     */
    public Optional<CaptionService> captionService() {
        return Optional.ofNullable(captionService);
    }

    /**
     * Returns the state compared by {@link #equals(Object)}.
     *
     * @return the field values
     */
    private Object[] state() {
        return new Object[]{mode, trackId, allTracks, codec, closedCaptions, captionService, input};
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
                + ", codec=" + codec() + ", closedCaptions=" + closedCaptions + ", captionService="
                + captionService() + ", input=" + input() + "]";
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
        private boolean closedCaptions;
        private CaptionService captionService;
        private int input = -1;

        private Builder() {
        }

        private Builder(SubtitleTranscodeOptions options) {
            this.mode = options.mode;
            this.trackId = options.trackId;
            this.byId = options.trackId >= 0;
            this.allTracks = options.allTracks;
            this.codec = options.codec;
            this.closedCaptions = options.closedCaptions;
            this.captionService = options.captionService;
            this.input = options.input;
        }

        /**
         * Selects the tracks from one input; see {@link SubtitleTranscodeOptions#input()}.
         *
         * @param input the value, zero or more
         * @return this builder
         * @throws IllegalArgumentException if the index is negative
         */
        public Builder input(int input) {
            this.input = Checks.nonNegative("input index", input);
            return this;
        }

        /**
         * Selects the tracks from any input; see {@link SubtitleTranscodeOptions#input()}.
         *
         * @return this builder
         */
        public Builder anyInput() {
            this.input = -1;
            return this;
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
         * Sets whether the closed captions carried in video are written as a text subtitle stream; see
         * {@link SubtitleTranscodeOptions#closedCaptions()}.
         *
         * @param closedCaptions the value
         * @return this builder
         */
        public Builder closedCaptions(boolean closedCaptions) {
            this.closedCaptions = closedCaptions;
            return this;
        }

        /**
         * Sets the caption service converted; see {@link SubtitleTranscodeOptions#captionService()}.
         *
         * @param captionService the value
         * @return this builder
         */
        public Builder captionService(CaptionService captionService) {
            this.captionService = Objects.requireNonNull(captionService, "captionService");
            return this;
        }

        /**
         * Converts each caption stream's default service; see {@link SubtitleTranscodeOptions#captionService()}.
         *
         * @return this builder
         */
        public Builder defaultCaptionService() {
            this.captionService = null;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the track identifier is negative or the codec is neither a
         * text subtitle codec nor TTML
         */
        public SubtitleTranscodeOptions build() {
            if (codec != null && !codec.isSubtitle() && codec != Codec.TTML) {
                throw new IllegalArgumentException("codec " + codec.displayName() + " is not a text subtitle codec");
            }
            var id = byId ? Checks.nonNegative("track identifier", trackId) : -1;
            return new SubtitleTranscodeOptions(mode, id, allTracks, codec, closedCaptions, captionService, input);
        }
    }
}
