package com.github.auties00.smalt.subtitle;

/**
 * A markup convention of cue text: the tags or override blocks that style it and the characters it escapes.
 *
 * <p>Every text subtitle codec writes its cues in one of these conventions
 * ({@link Subtitles#markupOf(com.github.auties00.smalt.track.Codec) Subtitles.markupOf}): 3GPP timed text cues
 * decode to {@link #SUBRIP} markup, the other codecs to their own.
 */
public enum SubtitleMarkup {
    /**
     * SubRip markup: {@code <b>}, {@code <i>}, {@code <u>} and {@code <font>} tags and {@code {\anN}} placements,
     * no escaping.
     */
    SUBRIP,

    /**
     * WebVTT markup: {@code <b>}, {@code <i>}, {@code <u>}, class, voice, language, ruby and timestamp tags, with
     * {@code &}, {@code <} and {@code >} written as character references.
     */
    WEBVTT,

    /**
     * Advanced SubStation Alpha markup: override blocks in braces, {@code \N} line breaks and {@code \h} hard
     * spaces.
     */
    ASS
}
