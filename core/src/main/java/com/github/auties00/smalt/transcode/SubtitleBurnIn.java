package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.SubtitleRenderer;
import com.github.auties00.smalt.subtitle.SubtitleStyle;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Holds the settings of the subtitles a transcode draws into the video pictures
 * ({@link VideoTranscodeOptions#subtitleBurnIn()}). Instances are immutable and thread-safe; create
 * them with {@link #builder(SubtitleStyle)} or a preset such as {@link #of(SubtitleStyle)}.
 *
 * <p>The cues come from a text subtitle track of the input, or are given. Each is drawn with
 * {@link SubtitleRenderer} for the size of the encoded pictures, after scaling, and shown on the
 * output frames whose presentation time on the input timeline lies from its start, inclusive, to its
 * end, exclusive. Cues shown at the same time and placed in the same keypad row are stacked away from
 * the edge they are aligned to, in the order they start.
 *
 * <p>When the cues come from a track of an input that can be read again, they are all read before
 * the transcode starts, so a cue appears exactly when it starts however the input interleaves its
 * packets; an input read once delivers each cue with its packet, and frames shown before the packet
 * arrives lack it. Burning a track in does not remove it from the output: drop it with
 * {@link SubtitleTranscodeOptions} when it should not also be written.
 */
public final class SubtitleBurnIn {
    private final SubtitleStyle style;

    /**
     * The identifier of the track, {@code -1} for the first text subtitle track, ignored when
     * {@link #cues} is set.
     */
    private final int trackId;

    /**
     * The given cues, or {@code null} to read a track.
     */
    private final List<SubtitleCue> cues;

    private final SubtitleMarkup markup;

    private SubtitleBurnIn(Builder builder) {
        this.style = builder.style;
        this.trackId = builder.trackId;
        this.cues = builder.cues;
        this.markup = builder.markup;
    }

    /**
     * Returns settings that draw the first text subtitle track of the input with a style.
     *
     * @param style the font, size, colors and placement
     * @return the settings
     */
    public static SubtitleBurnIn of(SubtitleStyle style) {
        return builder(style).build();
    }

    /**
     * Returns a builder that draws the first text subtitle track of the input with a style.
     *
     * @param style the font, size, colors and placement
     * @return the builder
     */
    public static Builder builder(SubtitleStyle style) {
        return new Builder(Objects.requireNonNull(style, "style"));
    }

    /**
     * Returns a builder initialized with these settings.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the font, size, colors and placement of the drawn text}
     */
    public SubtitleStyle style() {
        return style;
    }

    /**
     * {@return the identifier of the track whose cues are drawn, or empty for the first text subtitle
     * track, or when the cues are given; empty by default}
     */
    public OptionalInt trackId() {
        return cues != null || trackId < 0 ? OptionalInt.empty() : OptionalInt.of(trackId);
    }

    /**
     * {@return the given cues, with their times on the input timeline, or empty when the cues are read
     * from a track; empty by default}
     */
    public Optional<List<SubtitleCue>> cues() {
        return Optional.ofNullable(cues);
    }

    /**
     * {@return the markup of the given cues; {@link SubtitleMarkup#SUBRIP} by default, and ignored for
     * cues read from a track, whose codec gives it}
     */
    public SubtitleMarkup markup() {
        return markup;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SubtitleBurnIn that && style.equals(that.style) && trackId == that.trackId
                && Objects.equals(cues, that.cues) && markup == that.markup;
    }

    @Override
    public int hashCode() {
        return Objects.hash(style, trackId, cues, markup);
    }

    @Override
    public String toString() {
        return "SubtitleBurnIn[style=" + style + ", trackId=" + trackId() + ", cues="
                + (cues == null ? "track" : cues.size()) + ", markup=" + markup + "]";
    }

    /**
     * Builds {@link SubtitleBurnIn}.
     *
     * <p>Setters store their value; {@link #build()} checks the track identifier. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private SubtitleStyle style;
        private int trackId = -1;
        private List<SubtitleCue> cues;
        private SubtitleMarkup markup = SubtitleMarkup.SUBRIP;

        /**
         * Whether {@link #trackId} selects the track.
         */
        private boolean byId;

        private Builder(SubtitleStyle style) {
            this.style = style;
        }

        private Builder(SubtitleBurnIn burnIn) {
            this.style = burnIn.style;
            this.trackId = burnIn.trackId;
            this.byId = burnIn.trackId >= 0;
            this.cues = burnIn.cues;
            this.markup = burnIn.markup;
        }

        /**
         * Sets the style; see {@link SubtitleBurnIn#style()}.
         *
         * @param style the value
         * @return this builder
         */
        public Builder style(SubtitleStyle style) {
            this.style = Objects.requireNonNull(style, "style");
            return this;
        }

        /**
         * Draws the cues of a track, discarding given cues; see {@link SubtitleBurnIn#trackId()}.
         *
         * @param trackId the identifier of a text subtitle track of the input
         * @return this builder
         */
        public Builder trackId(int trackId) {
            this.trackId = trackId;
            this.byId = true;
            this.cues = null;
            return this;
        }

        /**
         * Draws the cues of the first text subtitle track, discarding given cues; see
         * {@link SubtitleBurnIn#trackId()}.
         *
         * @return this builder
         */
        public Builder firstTrack() {
            this.byId = false;
            this.cues = null;
            return this;
        }

        /**
         * Draws given cues rather than those of a track; see {@link SubtitleBurnIn#cues()} and
         * {@link SubtitleBurnIn#markup()}.
         *
         * @param cues the cues, copied
         * @param markup the markup of their text
         * @return this builder
         * @throws NullPointerException if a cue is {@code null}
         */
        public Builder cues(List<SubtitleCue> cues, SubtitleMarkup markup) {
            this.cues = List.copyOf(cues);
            this.markup = Objects.requireNonNull(markup, "markup");
            return this;
        }

        /**
         * Builds the settings.
         *
         * @return the settings
         * @throws IllegalArgumentException if the track identifier is negative
         */
        public SubtitleBurnIn build() {
            if (byId && trackId < 0) {
                throw new IllegalArgumentException("track identifier " + trackId + " is negative");
            }
            if (!byId) {
                trackId = -1;
            }
            return new SubtitleBurnIn(this);
        }
    }
}
