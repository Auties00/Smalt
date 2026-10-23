package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaLimits;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
     * The options of {@link #video()}.
     */
    private static final RemuxOptions VIDEO = new RemuxOptions(MediaLimits.DEFAULT, Mp4MuxerOptions.Brand.MP4,
            Selection.FIRST, Selection.FIRST, Selection.NONE, null);

    /**
     * The options of {@link #audio()}.
     */
    private static final RemuxOptions AUDIO = new RemuxOptions(MediaLimits.DEFAULT, Mp4MuxerOptions.Brand.M4A,
            Selection.NONE, Selection.FIRST, Selection.NONE, null);

    private final MediaLimits limits;
    private final Mp4MuxerOptions.Brand brand;
    private final Selection video;
    private final Selection audio;
    private final Selection subtitles;
    private final Path temporaryDirectory;

    private RemuxOptions(MediaLimits limits, Mp4MuxerOptions.Brand brand, Selection video, Selection audio,
                         Selection subtitles, Path temporaryDirectory) {
        this.limits = limits;
        this.brand = brand;
        this.video = video;
        this.audio = audio;
        this.subtitles = subtitles;
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
     * fast-start MP4, without subtitles, with the default limits.
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
     * <p>The brand decides which codecs the output carries ({@link Mp4Muxer#supports}).
     * {@link Mp4MuxerOptions.Brand#M4A} keeps no video and no subtitles: the first-video and
     * every-subtitle selections are ignored and video or subtitle track identifiers are rejected.
     */
    public Mp4MuxerOptions.Brand brand() {
        return brand;
    }

    /**
     * {@return whether a video track is selected, so that the output takes a video track of the input
     * when it has one; {@code true} by default}
     */
    public boolean includesVideo() {
        return video.mode != Mode.NONE;
    }

    /**
     * {@return the identifier, as the input's demuxer reports it, of the selected video track, or empty
     * when the first video track is selected or none is; empty by default}
     */
    public OptionalInt videoTrackId() {
        return video.single();
    }

    /**
     * {@return whether an audio track is selected, so that the output takes the audio tracks of the
     * input the selection names, or its first audio track; {@code true} by default}
     */
    public boolean includesAudio() {
        return audio.mode != Mode.NONE;
    }

    /**
     * {@return the identifier, as the input's demuxer reports it, of the selected audio track when
     * exactly one is selected by identifier, otherwise empty; empty by default}
     */
    public OptionalInt audioTrackId() {
        return audio.single();
    }

    /**
     * {@return the identifiers, as the input's demuxer reports them, of the audio tracks selected by
     * identifier, in output order, or an empty list when the first, every or no audio track is
     * selected; empty by default}
     */
    public List<Integer> audioTrackIds() {
        return audio.ids;
    }

    /**
     * {@return whether every audio track of the input is selected, in input order; {@code false} by
     * default}
     */
    public boolean includesEveryAudioTrack() {
        return audio.mode == Mode.ALL;
    }

    /**
     * {@return whether subtitle tracks are selected; {@code false} by default}
     *
     * <p>The subtitle tracks are those of the text formats of
     * {@link com.github.auties00.smalt.track.Codec#isSubtitle()} and of
     * {@link com.github.auties00.smalt.track.Codec#TTML}. They are copied as they are, so they must
     * be 3GPP timed text, WebVTT or TTML, which MP4 carries.
     */
    public boolean includesSubtitles() {
        return subtitles.mode != Mode.NONE;
    }

    /**
     * {@return the identifiers, as the input's demuxer reports them, of the subtitle tracks selected by
     * identifier, in output order, or an empty list when every or no subtitle track is selected;
     * empty by default}
     */
    public List<Integer> subtitleTrackIds() {
        return subtitles.ids;
    }

    /**
     * {@return whether every subtitle track of the input is selected, in input order;
     * {@code false} by default}
     */
    public boolean includesEverySubtitleTrack() {
        return subtitles.mode == Mode.ALL;
    }

    /**
     * {@return the directory of the fast-start spool file, or empty for the default temporary-file
     * directory; empty by default}
     */
    public Optional<Path> temporaryDirectory() {
        return Optional.ofNullable(temporaryDirectory);
    }

    /**
     * {@return the video selection}
     */
    Selection videoSelection() {
        return video;
    }

    /**
     * {@return the audio selection}
     */
    Selection audioSelection() {
        return audio;
    }

    /**
     * {@return the subtitle selection}
     */
    Selection subtitleSelection() {
        return subtitles;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RemuxOptions that
                && limits.equals(that.limits)
                && brand == that.brand
                && video.equals(that.video)
                && audio.equals(that.audio)
                && subtitles.equals(that.subtitles)
                && Objects.equals(temporaryDirectory, that.temporaryDirectory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(limits, brand, video, audio, subtitles, temporaryDirectory);
    }

    @Override
    public String toString() {
        return "RemuxOptions[limits=" + limits + ", brand=" + brand + ", videoTrack=" + video + ", audioTracks=" + audio
                + ", subtitleTracks=" + subtitles + ", temporaryDirectory=" + temporaryDirectory + "]";
    }

    /**
     * Enumerates the ways tracks of one kind are selected.
     */
    enum Mode {
        /**
         * The first track of the kind, when the input has one.
         */
        FIRST,

        /**
         * Every track of the kind, in input order.
         */
        ALL,

        /**
         * The tracks named by identifier, in the order named.
         */
        IDS,

        /**
         * No track of the kind.
         */
        NONE
    }

    /**
     * Selects the tracks of one kind.
     *
     * @param mode how the tracks are selected
     * @param ids the identifiers of the {@link Mode#IDS} selection, an empty list otherwise
     */
    record Selection(Mode mode, List<Integer> ids) {
        /**
         * The selection of the first track.
         */
        static final Selection FIRST = new Selection(Mode.FIRST, List.of());

        /**
         * The selection of every track.
         */
        static final Selection ALL = new Selection(Mode.ALL, List.of());

        /**
         * The selection of no track.
         */
        static final Selection NONE = new Selection(Mode.NONE, List.of());

        /**
         * Copies the identifiers.
         */
        Selection {
            ids = List.copyOf(ids);
        }

        /**
         * {@return the identifier when exactly one track is selected by identifier, otherwise empty}
         */
        OptionalInt single() {
            return mode == Mode.IDS && ids.size() == 1 ? OptionalInt.of(ids.getFirst()) : OptionalInt.empty();
        }

        @Override
        public String toString() {
            return mode == Mode.IDS ? ids.toString() : mode.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Assembles {@link RemuxOptions} setting by setting.
     *
     * <p>For each kind of track (video, audio and subtitles) the builder selects tracks of that kind
     * by the identifiers the input's demuxer reports, the first track (video and audio), every track
     * (audio and subtitles), or no track; the last call for a kind wins. Setters reject {@code null}
     * unless documented otherwise and store identifiers unchecked; {@link #build()} checks that no
     * identifier is selected twice. Instances are not thread-safe.
     */
    public static final class Builder {
        private MediaLimits limits;
        private Mp4MuxerOptions.Brand brand;
        private Mode video;
        private int[] videoIds;
        private Mode audio;
        private int[] audioIds;
        private Mode subtitles;
        private int[] subtitleIds;
        private Path temporaryDirectory;

        private Builder(RemuxOptions options) {
            this.limits = options.limits;
            this.brand = options.brand;
            this.video = options.video.mode;
            this.videoIds = toArray(options.video.ids);
            this.audio = options.audio.mode;
            this.audioIds = toArray(options.audio.ids);
            this.subtitles = options.subtitles.mode;
            this.subtitleIds = toArray(options.subtitles.ids);
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
         * @param id the value, any identifier the input's demuxer reports
         * @return this builder
         */
        public Builder videoTrack(int id) {
            this.video = Mode.IDS;
            this.videoIds = new int[]{id};
            return this;
        }

        /**
         * Selects the first video track; see {@link RemuxOptions#includesVideo()}.
         *
         * @return this builder
         */
        public Builder firstVideoTrack() {
            this.video = Mode.FIRST;
            this.videoIds = new int[0];
            return this;
        }

        /**
         * Selects no video track; see {@link RemuxOptions#includesVideo()}.
         *
         * @return this builder
         */
        public Builder noVideo() {
            this.video = Mode.NONE;
            this.videoIds = new int[0];
            return this;
        }

        /**
         * Selects an audio track by identifier; see {@link RemuxOptions#audioTrackId()}.
         *
         * @param id the value, any identifier the input's demuxer reports
         * @return this builder
         */
        public Builder audioTrack(int id) {
            return audioTracks(id);
        }

        /**
         * Selects audio tracks by identifier, in output order; see
         * {@link RemuxOptions#audioTrackIds()}.
         *
         * @param ids the values, distinct identifiers the input's demuxer reports, at least one
         * @return this builder
         */
        public Builder audioTracks(int... ids) {
            this.audio = Mode.IDS;
            this.audioIds = ids.clone();
            return this;
        }

        /**
         * Selects the first audio track; see {@link RemuxOptions#includesAudio()}.
         *
         * @return this builder
         */
        public Builder firstAudioTrack() {
            this.audio = Mode.FIRST;
            this.audioIds = new int[0];
            return this;
        }

        /**
         * Selects every audio track; see {@link RemuxOptions#includesEveryAudioTrack()}.
         *
         * @return this builder
         */
        public Builder allAudioTracks() {
            this.audio = Mode.ALL;
            this.audioIds = new int[0];
            return this;
        }

        /**
         * Selects no audio track; see {@link RemuxOptions#includesAudio()}.
         *
         * @return this builder
         */
        public Builder noAudio() {
            this.audio = Mode.NONE;
            this.audioIds = new int[0];
            return this;
        }

        /**
         * Selects subtitle tracks by identifier, in output order; see
         * {@link RemuxOptions#subtitleTrackIds()}.
         *
         * @param ids the values, distinct identifiers the input's demuxer reports, at least one
         * @return this builder
         */
        public Builder subtitleTracks(int... ids) {
            this.subtitles = Mode.IDS;
            this.subtitleIds = ids.clone();
            return this;
        }

        /**
         * Selects every subtitle track; see {@link RemuxOptions#includesEverySubtitleTrack()}.
         *
         * @return this builder
         */
        public Builder allSubtitleTracks() {
            this.subtitles = Mode.ALL;
            this.subtitleIds = new int[0];
            return this;
        }

        /**
         * Selects no subtitle track; see {@link RemuxOptions#includesSubtitles()}.
         *
         * @return this builder
         */
        public Builder noSubtitles() {
            this.subtitles = Mode.NONE;
            this.subtitleIds = new int[0];
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
         * @throws IllegalArgumentException if the same identifier is selected twice, a selection by
         * identifier names no track, or a video or subtitle track identifier is selected for the
         * {@link Mp4MuxerOptions.Brand#M4A} brand
         */
        public RemuxOptions build() {
            var videoSelection = toSelection("video", video, videoIds);
            var audioSelection = toSelection("audio", audio, audioIds);
            var subtitleSelection = toSelection("subtitle", subtitles, subtitleIds);
            if (brand == Mp4MuxerOptions.Brand.M4A && videoSelection.mode == Mode.IDS) {
                throw new IllegalArgumentException("video track identifier " + videoSelection.ids.getFirst()
                        + " is selected for the M4A brand, which carries no video");
            }
            if (brand == Mp4MuxerOptions.Brand.M4A && subtitleSelection.mode == Mode.IDS) {
                throw new IllegalArgumentException("subtitle track identifier " + subtitleSelection.ids.getFirst()
                        + " is selected for the M4A brand, which carries no subtitles");
            }
            var seen = new HashSet<Integer>();
            for (var selection : List.of(videoSelection, audioSelection, subtitleSelection)) {
                for (var id : selection.ids) {
                    if (!seen.add(id)) {
                        throw new IllegalArgumentException("track identifier " + id + " is selected twice");
                    }
                }
            }
            return new RemuxOptions(limits, brand, videoSelection, audioSelection, subtitleSelection, temporaryDirectory);
        }

        /**
         * Converts a builder selection to the selection of the options.
         *
         * @param kind the kind of track, for the message
         * @param mode the selection mode
         * @param ids the identifiers of a selection by identifier
         * @return the selection
         * @throws IllegalArgumentException if a selection by identifier names none
         */
        private static Selection toSelection(String kind, Mode mode, int[] ids) {
            return switch (mode) {
                case FIRST -> Selection.FIRST;
                case ALL -> Selection.ALL;
                case NONE -> Selection.NONE;
                case IDS -> {
                    if (ids.length == 0) {
                        throw new IllegalArgumentException("no " + kind + " track identifier is selected");
                    }
                    var list = new ArrayList<Integer>(ids.length);
                    for (var id : ids) {
                        list.add(id);
                    }
                    yield new Selection(Mode.IDS, list);
                }
            };
        }

        /**
         * Copies a list of identifiers to an array.
         *
         * @param ids the identifiers
         * @return the array
         */
        private static int[] toArray(List<Integer> ids) {
            var result = new int[ids.size()];
            for (var index = 0; index < result.length; index++) {
                result[index] = ids.get(index);
            }
            return result;
        }
    }
}
