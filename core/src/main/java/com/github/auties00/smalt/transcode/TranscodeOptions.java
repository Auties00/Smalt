package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.check.Checks;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Holds the settings of one transcode job: the output container, what happens to the video, audio
 * and subtitle streams, the time range kept, and how the caller follows progress. Instances are
 * immutable and thread-safe; create them with {@link #builder()} or a preset such as
 * {@link #defaults()}.
 *
 * <p>Data tracks other than text subtitles are not written. The output timeline starts at zero at
 * the start of the kept range. Encoded streams are trimmed exactly (to the frame for video, to the
 * sample for audio, with the encoder's end padding marked so players stop at the last kept sample);
 * copied streams are trimmed as described by {@link StreamMode#COPY}.
 */
public final class TranscodeOptions {
    /**
     * The options of {@link #defaults()}.
     */
    private static final TranscodeOptions DEFAULTS = new Builder().build();

    private final OutputContainer container;
    private final VideoTranscodeOptions video;
    private final AudioTranscodeOptions audio;
    private final SubtitleTranscodeOptions subtitles;
    private final long startMicros;

    /**
     * The end of the kept range in microseconds, or {@code -1} for the end of the input.
     */
    private final long endMicros;

    /**
     * The longest output duration in microseconds, or {@code -1} for no limit.
     */
    private final long maxDurationMicros;

    private final boolean seeking;

    /**
     * The progress listener, or {@code null}.
     */
    private final TranscodeListener listener;

    private final MediaLimits limits;

    private TranscodeOptions(Builder builder, long startMicros, long endMicros, long maxDurationMicros) {
        this.container = builder.container;
        this.video = builder.video;
        this.audio = builder.audio;
        this.subtitles = builder.subtitles;
        this.startMicros = startMicros;
        this.endMicros = endMicros;
        this.maxDurationMicros = maxDurationMicros;
        this.seeking = builder.seeking;
        this.listener = builder.listener;
        this.limits = builder.limits;
    }

    /**
     * Returns the default options: an MP4 file with the first video and audio streams, each copied
     * when possible and encoded otherwise.
     *
     * @return the options
     */
    public static TranscodeOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns options writing a container, with the first video and audio streams copied when
     * possible and encoded otherwise.
     *
     * @param container the file to write
     * @return the options
     */
    public static TranscodeOptions of(OutputContainer container) {
        return DEFAULTS.toBuilder().container(container).build();
    }

    /**
     * Returns options that copy the first video and audio streams into a container without
     * re-encoding, failing when the container cannot carry them.
     *
     * @param container the file to write
     * @return the options
     */
    public static TranscodeOptions remux(OutputContainer container) {
        return new Builder().container(container).video(VideoTranscodeOptions.copy()).audio(AudioTranscodeOptions.copy()).build();
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
     * {@return the file written; {@link OutputContainer#MP4} by default}
     */
    public OutputContainer container() {
        return container;
    }

    /**
     * {@return what happens to the video stream; {@link VideoTranscodeOptions#defaults()} by default}
     */
    public VideoTranscodeOptions video() {
        return video;
    }

    /**
     * {@return what happens to the audio streams; {@link AudioTranscodeOptions#defaults()} by default}
     */
    public AudioTranscodeOptions audio() {
        return audio;
    }

    /**
     * {@return what happens to the text subtitle streams; {@link SubtitleTranscodeOptions#defaults()} by
     * default}
     */
    public SubtitleTranscodeOptions subtitles() {
        return subtitles;
    }

    /**
     * {@return the start of the kept range, a presentation time in microseconds; zero or more; 0, the
     * start of the input, by default}
     */
    public long startMicros() {
        return startMicros;
    }

    /**
     * {@return the end of the kept range, a presentation time in microseconds after
     * {@link #startMicros()}, or empty for the end of the input; empty by default}
     */
    public OptionalLong endMicros() {
        return endMicros < 0 ? OptionalLong.empty() : OptionalLong.of(endMicros);
    }

    /**
     * {@return the longest output duration in microseconds, positive, or empty for no limit; empty by
     * default}
     *
     * <p>The kept range ends at the earlier of {@link #endMicros()} and {@link #startMicros()} plus
     * this duration.
     */
    public OptionalLong maxDurationMicros() {
        return maxDurationMicros < 0 ? OptionalLong.empty() : OptionalLong.of(maxDurationMicros);
    }

    /**
     * {@return whether the job seeks the input to the start of the kept range when the demuxer supports
     * it, instead of reading and discarding the media before it; {@code true} by default}
     *
     * <p>The output is the same either way. When subtitle streams are kept, the packets before the
     * start are still read (not decoded) to find the cues shown at the start, which costs reading
     * time linear in the start position; a source that reads its input once
     * ({@link com.github.auties00.smalt.io.MediaSource#of(java.io.InputStream, long)}) cannot be read
     * a second time, so such a job does not seek and decodes the media before the start.
     */
    public boolean seeking() {
        return seeking;
    }

    /**
     * {@return the listener told about progress, or empty for none; empty by default}
     */
    public Optional<TranscodeListener> listener() {
        return Optional.ofNullable(listener);
    }

    /**
     * {@return the resource bounds the demuxer and the decoders enforce; {@link MediaLimits#defaults()}
     * by default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * Returns the end of the kept range after the duration limit.
     *
     * @return the presentation time in microseconds, or {@code -1} for the end of the input
     */
    long effectiveEndMicros() {
        var end = endMicros;
        if (maxDurationMicros >= 0) {
            var limit = startMicros + maxDurationMicros;
            end = end < 0 ? limit : Math.min(end, limit);
        }
        return end;
    }

    /**
     * Returns the state compared by {@link #equals(Object)}.
     *
     * @return the field values
     */
    private Object[] state() {
        return new Object[]{container, video, audio, subtitles, startMicros, endMicros, maxDurationMicros, seeking, listener,
                limits};
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof TranscodeOptions that && Arrays.equals(state(), that.state());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(state());
    }

    @Override
    public String toString() {
        return "TranscodeOptions[container=" + container + ", video=" + video + ", audio=" + audio + ", subtitles="
                + subtitles + ", startMicros="
                + startMicros + ", endMicros=" + endMicros() + ", maxDurationMicros=" + maxDurationMicros()
                + ", seeking=" + seeking + ", listener=" + listener + ", limits=" + limits + "]";
    }

    /**
     * Assembles {@link TranscodeOptions} setting by setting.
     *
     * <p>Setters reject {@code null} unless documented otherwise and store numbers unchecked;
     * {@link #build()} checks the time range and the duration limit. Instances are not thread-safe.
     */
    public static final class Builder {
        private OutputContainer container = OutputContainer.MP4;
        private VideoTranscodeOptions video = VideoTranscodeOptions.defaults();
        private AudioTranscodeOptions audio = AudioTranscodeOptions.defaults();
        private SubtitleTranscodeOptions subtitles = SubtitleTranscodeOptions.defaults();
        private long startMicros;
        private long endMicros;

        /**
         * Whether {@link #endMicros} ends the kept range; {@code false} for the end of the input.
         */
        private boolean bounded;

        private long maxDurationMicros;

        /**
         * Whether {@link #maxDurationMicros} limits the output.
         */
        private boolean durationLimited;

        private boolean seeking = true;
        private TranscodeListener listener;
        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(TranscodeOptions options) {
            this.container = options.container;
            this.video = options.video;
            this.audio = options.audio;
            this.subtitles = options.subtitles;
            this.startMicros = options.startMicros;
            this.endMicros = options.endMicros;
            this.bounded = options.endMicros >= 0;
            this.maxDurationMicros = options.maxDurationMicros;
            this.durationLimited = options.maxDurationMicros >= 0;
            this.seeking = options.seeking;
            this.listener = options.listener;
            this.limits = options.limits;
        }

        /**
         * Sets the file written; see {@link TranscodeOptions#container()}.
         *
         * @param container the value
         * @return this builder
         */
        public Builder container(OutputContainer container) {
            this.container = Objects.requireNonNull(container, "container");
            return this;
        }

        /**
         * Sets the video options; see {@link TranscodeOptions#video()}.
         *
         * @param video the value
         * @return this builder
         */
        public Builder video(VideoTranscodeOptions video) {
            this.video = Objects.requireNonNull(video, "video");
            return this;
        }

        /**
         * Leaves video out of the output, setting the video mode to {@link StreamMode#DROP}; see
         * {@link TranscodeOptions#video()}.
         *
         * @return this builder
         */
        public Builder noVideo() {
            this.video = video.toBuilder().mode(StreamMode.DROP).build();
            return this;
        }

        /**
         * Sets the audio options; see {@link TranscodeOptions#audio()}.
         *
         * @param audio the value
         * @return this builder
         */
        public Builder audio(AudioTranscodeOptions audio) {
            this.audio = Objects.requireNonNull(audio, "audio");
            return this;
        }

        /**
         * Leaves audio out of the output, setting the audio mode to {@link StreamMode#DROP}; see
         * {@link TranscodeOptions#audio()}.
         *
         * @return this builder
         */
        public Builder noAudio() {
            this.audio = audio.toBuilder().mode(StreamMode.DROP).build();
            return this;
        }

        /**
         * Sets the subtitle options; see {@link TranscodeOptions#subtitles()}.
         *
         * @param subtitles the value
         * @return this builder
         */
        public Builder subtitles(SubtitleTranscodeOptions subtitles) {
            this.subtitles = Objects.requireNonNull(subtitles, "subtitles");
            return this;
        }

        /**
         * Leaves subtitles out of the output, setting the subtitle mode to {@link StreamMode#DROP}; see
         * {@link TranscodeOptions#subtitles()}.
         *
         * @return this builder
         */
        public Builder noSubtitles() {
            this.subtitles = subtitles.toBuilder().mode(StreamMode.DROP).build();
            return this;
        }

        /**
         * Sets the kept time range; see {@link TranscodeOptions#startMicros()} and
         * {@link TranscodeOptions#endMicros()}.
         *
         * @param startMicros the start in microseconds, zero or more
         * @param endMicros the end in microseconds, after the start
         * @return this builder
         */
        public Builder range(long startMicros, long endMicros) {
            this.startMicros = startMicros;
            this.endMicros = endMicros;
            this.bounded = true;
            return this;
        }

        /**
         * Sets the start of the kept range and keeps the rest of the input; see
         * {@link TranscodeOptions#startMicros()}.
         *
         * @param startMicros the value
         * @return this builder
         */
        public Builder start(long startMicros) {
            this.startMicros = startMicros;
            this.bounded = false;
            return this;
        }

        /**
         * Limits the output duration; see {@link TranscodeOptions#maxDurationMicros()}.
         *
         * @param maxDurationMicros the value
         * @return this builder
         */
        public Builder maxDuration(long maxDurationMicros) {
            this.maxDurationMicros = maxDurationMicros;
            this.durationLimited = true;
            return this;
        }

        /**
         * Removes the limit on the output duration; see {@link TranscodeOptions#maxDurationMicros()}.
         *
         * @return this builder
         */
        public Builder noMaxDuration() {
            this.durationLimited = false;
            return this;
        }

        /**
         * Sets whether the demuxer seeks to the start when it can; see {@link TranscodeOptions#seeking()}.
         *
         * @param seeking the value
         * @return this builder
         */
        public Builder seeking(boolean seeking) {
            this.seeking = seeking;
            return this;
        }

        /**
         * Sets the progress listener; see {@link TranscodeOptions#listener()}.
         *
         * @param listener the value, or {@code null} for none
         * @return this builder
         */
        public Builder listener(TranscodeListener listener) {
            this.listener = listener;
            return this;
        }

        /**
         * Sets the resource bounds; see {@link TranscodeOptions#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Builds the options.
         *
         * @return the options
         * @throws IllegalArgumentException if the start of the range is negative, the end of the range
         * is not after its start, or the duration limit is not positive
         */
        public TranscodeOptions build() {
            Checks.nonNegative("range start", startMicros);
            if (bounded && endMicros <= startMicros) {
                throw new IllegalArgumentException("range end " + endMicros + " is not after the start " + startMicros);
            }
            var duration = durationLimited ? Checks.positive("duration limit", maxDurationMicros) : -1;
            return new TranscodeOptions(this, startMicros, bounded ? endMicros : -1, duration);
        }
    }
}
