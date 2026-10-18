package com.github.auties00.smalt.container;

import java.util.Objects;
import java.util.Optional;

/**
 * Marks a named section of a presentation, such as a chapter of an audiobook or a scene of a
 * film.
 *
 * <p>Chapters come from the container: MP4 Nero {@code chpl} boxes and QuickTime chapter text
 * tracks, Matroska {@code Chapters}, Vorbis comment {@code CHAPTERxxx} fields (Ogg and FLAC) and
 * ID3v2 {@code CHAP} frames. A format that stores only start times ends each chapter where the
 * next starts, and the last one at the end of the presentation.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param startMicros the start of the chapter, in microseconds from the start of the presentation
 * @param endMicros   the end of the chapter, in microseconds, at or after {@code startMicros}
 * @param title       the chapter title, when the container names it
 */
public record Chapter(long startMicros, long endMicros, Optional<String> title) {
    /**
     * Validates the chapter.
     *
     * @throws NullPointerException     if {@code title} is {@code null}
     * @throws IllegalArgumentException if {@code startMicros} is negative or {@code endMicros} is
     *                                  before it
     */
    public Chapter {
        Objects.requireNonNull(title, "title");
        if (startMicros < 0 || endMicros < startMicros) {
            throw new IllegalArgumentException("invalid chapter span " + startMicros + ".." + endMicros);
        }
    }

    /**
     * Creates a chapter with a title.
     *
     * @param startMicros the start in microseconds
     * @param endMicros   the end in microseconds
     * @param title       the title, or {@code null} when the chapter has none
     * @return the chapter
     * @throws IllegalArgumentException if the span is invalid
     */
    public static Chapter of(long startMicros, long endMicros, String title) {
        return new Chapter(startMicros, endMicros, Optional.ofNullable(title));
    }
}
