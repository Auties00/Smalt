package com.github.auties00.smalt.metadata;

/**
 * Selects the tags, attached pictures, chapters and track tags a conversion writes into its output.
 *
 * <p>A conversion that writes a new file from an input (a transcode, an MP4 remux, an adaptive streaming
 * package) describes its output with a {@link MediaTags} on the input's terms: chapter times on the input's
 * timeline and {@linkplain MediaTags#tracks() track tags} naming the input's track identifiers. The conversion then
 * maps that description to its output: the track tags follow their tracks to the output's identifiers, those of
 * tracks left out are dropped, and the chapters are cut to the kept time range and shifted to the output's
 * timeline. Each output container stores what its muxer can
 * ({@link com.github.auties00.smalt.container.Muxer#setTags(MediaTags) Muxer.setTags} lists it); a container that
 * stores no tags gets none.
 */
public enum TagMode {
    /**
     * The input's tags, pictures, chapters and track tags.
     *
     * <p>Values the output container rejects, such as a text value of an iTunes integer atom, are left out
     * rather than failing the conversion. The {@code BPS}, {@code DURATION}, {@code NUMBER_OF_FRAMES},
     * {@code NUMBER_OF_BYTES} and {@code _STATISTICS_} statistics tags Matroska files keep per track describe the
     * streams as the input's muxer stored them, and the output's muxer stores them again and declares their
     * durations itself, so they are left out. The {@link StandardTag#ENCODER encoder} and {@code encoder_settings}
     * of a track stop describing it once it is encoded or trimmed, and are left out of such a track. The file's
     * {@code encoder} is left out when any stream is encoded, and the ReplayGain tags when an encoded audio stream
     * changes its level.
     */
    COPY,

    /**
     * No tag, picture, chapter or track tag; comment headers that a stream's codec configuration carries, such as
     * the Vorbis comments of an Ogg Vorbis, Opus or FLAC stream, are rewritten empty where the output container
     * stores them as tags.
     */
    DROP,

    /**
     * The given tags instead of the input's, mapped like the input's. Every value is written as given, so a value
     * the output container rejects fails the conversion.
     */
    REPLACE,

    /**
     * The input's tags as {@link #COPY} writes them, updated with the given ones.
     *
     * <p>Each key of the given tags replaces every value the input has for it, and a given entry with an empty
     * value removes the key. Given pictures replace the input's when there is at least one, and so do given
     * chapters. Given track tags update the input's tags of the same track the same way, and their dispositions
     * replace the track's when they hold at least one. A given value the output container rejects fails the
     * conversion.
     */
    MERGE
}
