package com.github.auties00.smalt.container;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Marks a named section of a presentation, such as a chapter of an audiobook or a scene of a
 * film.
 *
 * <p>Chapters come from the container: MP4 Nero {@code chpl} boxes and QuickTime chapter text
 * tracks, Matroska {@code Chapters}, Vorbis comment {@code CHAPTERxxx} fields (Ogg and FLAC), FLAC
 * {@code CUESHEET} tracks, ID3v2 {@code CHAP} frames, WAV cue points and AIFF markers. A format that
 * stores only start times ends each chapter where the next starts, and the last one at the end of
 * the presentation (or of its parent chapter).
 *
 * <p>A chapter may hold {@linkplain #titles() titles in several languages} and
 * {@linkplain #children() nested chapters}, as Matroska stores them; the other formats store
 * neither, and their writers keep only the top-level chapters and their {@link #title()}.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param startMicros the start of the chapter, in microseconds from the start of the presentation
 * @param endMicros the end of the chapter, in microseconds, at or after {@code startMicros}
 * @param title the chapter title, when the container names it; the text of the first of
 * {@code titles} when there are any
 * @param titles the title in each language the container stores, in container order; empty when
 * the container stores no language
 * @param children the nested chapters, in container order
 */
public record Chapter(long startMicros, long endMicros, Optional<String> title, List<Title> titles,
                      List<Chapter> children) {
    /**
     * Validates the chapter.
     *
     * @throws NullPointerException if an element of {@code titles} or {@code children} is {@code null}
     * @throws IllegalArgumentException if {@code startMicros} is negative, {@code endMicros} is before it, or
     * {@code titles} is not empty and {@code title} is not the text of its first element
     */
    public Chapter {
        Objects.requireNonNull(title, "title");
        titles = List.copyOf(titles);
        children = List.copyOf(children);
        if (startMicros < 0 || endMicros < startMicros) {
            throw new IllegalArgumentException("invalid chapter span " + startMicros + ".." + endMicros);
        }
        if (!titles.isEmpty() && !title.equals(Optional.of(titles.getFirst().text()))) {
            throw new IllegalArgumentException("the title must be the text of the first localized title");
        }
    }

    /**
     * Constructs a chapter with no localized title and no nested chapter.
     *
     * @param startMicros the start in microseconds
     * @param endMicros the end in microseconds
     * @param title the title, or empty when the chapter has none
     * @throws IllegalArgumentException if the span is invalid
     */
    public Chapter(long startMicros, long endMicros, Optional<String> title) {
        this(startMicros, endMicros, title, List.of(), List.of());
    }

    /**
     * Creates a chapter with a title.
     *
     * @param startMicros the start in microseconds
     * @param endMicros the end in microseconds
     * @param title the title, or {@code null} when the chapter has none
     * @return the chapter
     * @throws IllegalArgumentException if the span is invalid
     */
    public static Chapter of(long startMicros, long endMicros, String title) {
        return new Chapter(startMicros, endMicros, Optional.ofNullable(title));
    }

    /**
     * Returns a copy of this chapter with other localized titles; the {@link #title()} becomes the
     * text of the first, or stays as it is when the list is empty.
     *
     * @param titles the titles, in order
     * @return the chapter
     * @throws NullPointerException if an element is {@code null}
     */
    public Chapter withTitles(List<Title> titles) {
        var copy = List.copyOf(titles);
        return new Chapter(startMicros, endMicros, copy.isEmpty() ? title : Optional.of(copy.getFirst().text()), copy,
                children);
    }

    /**
     * Returns a copy of this chapter with other nested chapters.
     *
     * @param children the nested chapters, in order
     * @return the chapter
     * @throws NullPointerException if an element is {@code null}
     */
    public Chapter withChildren(List<Chapter> children) {
        return new Chapter(startMicros, endMicros, title, titles, children);
    }

    /**
     * Holds a title in one language.
     *
     * <p>Instances are immutable and thread-safe.
     *
     * @param text the title, not empty
     * @param language the language: an ISO 639-2 code such as {@code fre}, {@code und} when undetermined, or an
     * IETF BCP 47 tag such as {@code fr-CA}
     */
    public record Title(String text, String language) {
        /**
         * Validates the title.
         *
         * @throws IllegalArgumentException if {@code text} or {@code language} is empty
         */
        public Title {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(language, "language");
            if (text.isEmpty() || language.isBlank()) {
                throw new IllegalArgumentException("empty title or language");
            }
        }
    }
}
