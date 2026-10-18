package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of {@link Mp4Remuxer#remux}. Instances are immutable and thread-safe; create them
 * with {@link #builder()} or a preset such as {@link #defaults()}.
 *
 * <p>The output is always a fast-start file, so it can be written to any
 * {@link com.github.auties00.smalt.io.MediaSink}, including a stream.
 */
public final class RemuxOptions {
    /**
     * The value of a track field that selects the first track of its kind.
     */
    private static final int FIRST = -1;

    /**
     * The value of a track field that selects no track.
     */
    private static final int NONE = 0;

    /**
     * The options of {@link #video()}.
     */
    private static final RemuxOptions VIDEO = new RemuxOptions(MediaLimits.DEFAULT, Mp4MuxerOptions.Brand.MP4, FIRST,
            FIRST, null);

    /**
     * The options of {@link #audio()}.
     */
    private static final RemuxOptions AUDIO = new RemuxOptions(MediaLimits.DEFAULT, Mp4MuxerOptions.Brand.M4A, NONE,
            FIRST, null);

    private final MediaLimits limits;
    private final Mp4MuxerOptions.Brand brand;

    /**
     * The video selection: a track identifier, {@link #FIRST} or {@link #NONE}.
     */
    private final int videoTrack;

    /**
     * The audio selection: a track identifier, {@link #FIRST} or {@link #NONE}.
     */
    private final int audioTrack;

    private final Path temporaryDirectory;

    private RemuxOptions(MediaLimits limits, Mp4MuxerOptions.Brand brand, int videoTrack, int audioTrack,
                         Path temporaryDirectory) {
        this.limits = limits;
        this.brand = brand;
        this.videoTrack = videoTrack;
        this.audioTrack = audioTrack;
        this.temporaryDirectory = temporaryDirectory;
    }

    /**
     * Returns the options for a video, the same as {@link #video()}.
     *
     * @return the default options
     */
    public static RemuxOptions defaults() {
        return VIDEO;
    }

    /**
     * Returns the options for a video: the first video and the first audio track into a
     * fast-start MP4, with the default limits.
     *
     * @return the options
     */
    public static RemuxOptions video() {
        return VIDEO;
    }

    /**
     * Returns the options for an audio file: the first audio track into a fast-start M4A, with
     * the default limits.
     *
     * @return the options
     */
    public static RemuxOptions audio() {
        return AUDIO;
    }

    /**
     * Returns a builder initialized with {@link #defaults()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder(VIDEO);
    }

    /**
     * {@return a builder initialized with these options}
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the limits applied while demuxing the input; {@link MediaLimits#defaults()} by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * {@return the file type of the output; {@link Mp4MuxerOptions.Brand#MP4} by default}
     *
     * <p>{@link Mp4MuxerOptions.Brand#M4A} keeps no video: the first-video selection is ignored and a
     * video track identifier is rejected.
     */
    public Mp4MuxerOptions.Brand brand() {
        return brand;
    }

    /**
     * {@return whether a video track is selected, so that the output takes a video track of the input
     * when it has one; {@code true} by default}
     */
    public boolean includesVideo() {
        return videoTrack != NONE;
    }

    /**
     * {@return the identifier, as the input's demuxer reports it, of the selected video track, or empty
     * when the first video track is selected or none is; empty by default}
     */
    public OptionalInt videoTrackId() {
        return videoTrack > 0 ? OptionalInt.of(videoTrack) : OptionalInt.empty();
    }

    /**
     * {@return whether an audio track is selected, so that the output takes an audio track of the input
     * when it has one; {@code true} by default}
     */
    public boolean includesAudio() {
        return audioTrack != NONE;
    }

    /**
     * {@return the identifier, as the input's demuxer reports it, of the selected audio track, or empty
     * when the first audio track is selected or none is; empty by default}
     */
    public OptionalInt audioTrackId() {
        return audioTrack > 0 ? OptionalInt.of(audioTrack) : OptionalInt.empty();
    }

    /**
     * {@return the directory of the fast-start spool file, or empty for the default temporary-file
     * directory; empty by default}
     */
    public Optional<Path> temporaryDirectory() {
        return Optional.ofNullable(temporaryDirectory);
    }

    /**
     * Returns the selection field of a kind: a track identifier, {@link #FIRST} or {@link #NONE}.
     *
     * @param video whether to return the video selection
     * @return the selection field
     */
    int selection(boolean video) {
        return video ? videoTrack : audioTrack;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RemuxOptions that
                && limits.equals(that.limits)
                && brand == that.brand
                && videoTrack == that.videoTrack
                && audioTrack == that.audioTrack
                && Objects.equals(temporaryDirectory, that.temporaryDirectory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, brand, videoTrack, audioTrack, temporaryDirectory);
    }

    @Override
    public String toString() {
        return "RemuxOptions[limits=" + limits + ", brand=" + brand + ", videoTrack=" + describe(videoTrack)
                + ", audioTrack=" + describe(audioTrack) + ", temporaryDirectory=" + temporaryDirectory + "]";
    }

    /**
     * Describes a selection field.
     *
     * @param selection the field
     * @return {@code "first"}, {@code "none"} or the identifier
     */
    private static String describe(int selection) {
        return selection == FIRST ? "first" : selection == NONE ? "none" : Integer.toString(selection);
    }

    /**
     * Assembles {@link RemuxOptions} setting by setting.
     *
     * <p>For each kind (video and audio) the builder selects the first track of that kind, a track by
     * the identifier the input's demuxer reports, or no track; the last call for a kind wins. Setters
     * reject {@code null} unless documented otherwise and store identifiers unchecked; {@link #build()}
     * checks them. Instances are not thread-safe.
     */
    public static final class Builder {
        /**
         * The builder value of a selection that takes the first track; outside the {@code int} range.
         */
        private static final long FIRST_TRACK = Long.MIN_VALUE;

        /**
         * The builder value of a selection that takes no track; outside the {@code int} range.
         */
        private static final long NO_TRACK = Long.MIN_VALUE + 1;

        private MediaLimits limits;
        private Mp4MuxerOptions.Brand brand;
        private long videoTrack;
        private long audioTrack;
        private Path temporaryDirectory;

        private Builder(RemuxOptions options) {
            this.limits = options.limits;
            this.brand = options.brand;
            this.videoTrack = toBuilderValue(options.videoTrack);
            this.audioTrack = toBuilderValue(options.audioTrack);
            this.temporaryDirectory = options.temporaryDirectory;
        }

        /**
         * Sets the demuxing limits; see {@link RemuxOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Sets the file type of the output; see {@link RemuxOptions#brand()}.
         *
         * @param brand the value
         * @return this builder
         */
        public Builder brand(Mp4MuxerOptions.Brand brand) {
            this.brand = Objects.requireNonNull(brand, "brand");
            return this;
        }

        /**
         * Selects a video track by identifier; see {@link RemuxOptions#videoTrackId()}.
         *
         * @param id the value, positive
         * @return this builder
         */
        public Builder videoTrack(int id) {
            this.videoTrack = id;
            return this;
        }

        /**
         * Selects the first video track; see {@link RemuxOptions#includesVideo()}.
         *
         * @return this builder
         */
        public Builder firstVideoTrack() {
            this.videoTrack = FIRST_TRACK;
            return this;
        }

        /**
         * Selects no video track; see {@link RemuxOptions#includesVideo()}.
         *
         * @return this builder
         */
        public Builder noVideo() {
            this.videoTrack = NO_TRACK;
            return this;
        }

        /**
         * Selects an audio track by identifier; see {@link RemuxOptions#audioTrackId()}.
         *
         * @param id the value, positive
         * @return this builder
         */
        public Builder audioTrack(int id) {
            this.audioTrack = id;
            return this;
        }

        /**
         * Selects the first audio track; see {@link RemuxOptions#includesAudio()}.
         *
         * @return this builder
         */
        public Builder firstAudioTrack() {
            this.audioTrack = FIRST_TRACK;
            return this;
        }

        /**
         * Selects no audio track; see {@link RemuxOptions#includesAudio()}.
         *
         * @return this builder
         */
        public Builder noAudio() {
            this.audioTrack = NO_TRACK;
            return this;
        }

        /**
         * Sets the spool directory; see {@link RemuxOptions#temporaryDirectory()}.
         *
         * @param directory the value, or {@code null} for the default temporary-file directory
         * @return this builder
         */
        public Builder temporaryDirectory(Path directory) {
            this.temporaryDirectory = directory;
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if a track identifier is not positive, or a video track
         * identifier is selected for the {@link Mp4MuxerOptions.Brand#M4A} brand
         */
        public RemuxOptions build() {
            var video = toSelection("video track identifier", videoTrack);
            var audio = toSelection("audio track identifier", audioTrack);
            if (brand == Mp4MuxerOptions.Brand.M4A && video > 0) {
                throw new IllegalArgumentException("video track identifier " + video + " is selected for the M4A brand, "
                        + "which carries no video");
            }
            return new RemuxOptions(limits, brand, video, audio, temporaryDirectory);
        }

        /**
         * Converts a selection field of the options to its builder value.
         *
         * @param selection a track identifier, {@link #FIRST} or {@link #NONE}
         * @return the builder value
         */
        private static long toBuilderValue(int selection) {
            return selection == FIRST ? FIRST_TRACK : selection == NONE ? NO_TRACK : selection;
        }

        /**
         * Converts a builder value to the selection field of the options, checking an identifier.
         *
         * @param name the setting, for the message
         * @param value the builder value
         * @return a track identifier, {@link #FIRST} or {@link #NONE}
         * @throws IllegalArgumentException if {@code value} is an identifier that is not positive
         */
        private static int toSelection(String name, long value) {
            if (value == FIRST_TRACK) {
                return FIRST;
            }
            if (value == NO_TRACK) {
                return NONE;
            }
            return Checks.positive(name, (int) value);
        }
    }
}
