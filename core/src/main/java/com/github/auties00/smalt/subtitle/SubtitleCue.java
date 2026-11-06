package com.github.auties00.smalt.subtitle;

import java.util.Objects;

/**
 * Holds one timed text subtitle: what is shown, from when, until when.
 *
 * <p>The text keeps the {@link SubtitleMarkup} of the format it was read from: SubRip tags such as
 * {@code <i>} and {@code {\an8}} for {@link SubtitleMarkup#SUBRIP}, WebVTT tags and character
 * references such as {@code <v Anna>} and {@code &amp;} for {@link SubtitleMarkup#WEBVTT}, the
 * override blocks of an ASS event, or the SubRip style tags a 3GPP timed text sample's {@code styl}
 * runs become. Lines are separated by a single line feed.
 * {@link Subtitles#convertText(String, SubtitleMarkup, SubtitleMarkup)} rewrites the markup for
 * another format.
 *
 * <p>The identifier is the WebVTT cue identifier, empty for cues of other formats and when absent. The settings
 * are the WebVTT cue settings list ({@code align:start line:10%}) of a WebVTT cue and the event fields of an ASS
 * cue, {@code Layer,Style,Name,MarginL,MarginR,MarginV,Effect} ({@code 0,Default,Bob,0,0,0,}), and empty for the
 * other formats and when absent (an ASS cue without settings is an event of layer 0 in the {@code Default}
 * style).
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param startMicros the time the cue appears, in microseconds
 * @param endMicros the time the cue disappears, in microseconds; not before {@code startMicros}
 * @param text the text, with its markup
 * @param identifier the WebVTT cue identifier, empty when absent
 * @param settings the WebVTT cue settings list or the ASS event fields, empty when absent
 */
public record SubtitleCue(long startMicros, long endMicros, String text, String identifier, String settings) {
    /**
     * Validates the cue.
     *
     * @throws IllegalArgumentException if {@code endMicros} is before {@code startMicros}
     */
    public SubtitleCue {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(settings, "settings");
        if (endMicros < startMicros) {
            throw new IllegalArgumentException("cue ends at " + endMicros + " before it starts at " + startMicros);
        }
    }

    /**
     * Creates a cue without identifier or settings.
     *
     * @param startMicros the time the cue appears, in microseconds
     * @param endMicros the time the cue disappears, in microseconds
     * @param text the text, with its markup
     * @return the cue
     * @throws IllegalArgumentException if {@code endMicros} is before {@code startMicros}
     */
    public static SubtitleCue of(long startMicros, long endMicros, String text) {
        return new SubtitleCue(startMicros, endMicros, text, "", "");
    }

    /**
     * Returns how long the cue is shown.
     *
     * @return the duration in microseconds
     */
    public long durationMicros() {
        return endMicros - startMicros;
    }

    /**
     * Returns a copy with other text.
     *
     * @param text the text of the copy, in the same markup
     * @return the copy
     */
    public SubtitleCue withText(String text) {
        return new SubtitleCue(startMicros, endMicros, text, identifier, settings);
    }

    /**
     * Returns a copy shown at other times.
     *
     * @param startMicros the time the cue appears, in microseconds
     * @param endMicros the time the cue disappears, in microseconds
     * @return the copy
     * @throws IllegalArgumentException if {@code endMicros} is before {@code startMicros}
     */
    public SubtitleCue withTiming(long startMicros, long endMicros) {
        return new SubtitleCue(startMicros, endMicros, text, identifier, settings);
    }
}
