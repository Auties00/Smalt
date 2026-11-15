package com.github.auties00.smalt.track;

/**
 * Names the roles a container flags a track with, which players use to choose among the tracks of one kind.
 *
 * <p>Each container stores some of them; the table of
 * {@link com.github.auties00.smalt.metadata.TrackTags TrackTags} lists which, and how.
 *
 * <p>This enum may gain constants in later releases; a {@code switch} over it needs a {@code default} branch.
 */
public enum TrackDisposition {
    /**
     * The track a player selects when the user expressed no preference.
     */
    DEFAULT,

    /**
     * A track in the original language of the content.
     */
    ORIGINAL,

    /**
     * A dubbed translation.
     */
    DUB,

    /**
     * Commentary on the content.
     */
    COMMENTARY,

    /**
     * A track a player selects whatever the user preference, such as subtitles of foreign dialogue.
     */
    FORCED,

    /**
     * A track for hearing-impaired users.
     */
    HEARING_IMPAIRED,

    /**
     * A track for visually impaired users.
     */
    VISUAL_IMPAIRED,

    /**
     * Captions: a transcription of the dialogue and the sounds, for viewers who cannot hear them.
     */
    CAPTIONS,

    /**
     * Descriptions of the visual content, as audio or as text.
     */
    DESCRIPTIONS,

    /**
     * Audio of the music and effects only, without dialogue.
     */
    CLEAN_EFFECTS,

    /**
     * A karaoke track.
     */
    KARAOKE
}
