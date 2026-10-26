package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.track.TrackDisposition;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Describes one track of a container: its name, its tags and its dispositions.
 *
 * <p>The name is the {@link StandardTag#TITLE title} entry of {@link #tags()}. The muxers of the containers below
 * take track tags through {@link com.github.auties00.smalt.container.Muxer#setTags(MediaTags) setTags} and their
 * demuxers report them ({@code trackMetadata}, {@code trackDisposition}); {@link TagEditor} reads the track tags of
 * every track of MP4, Matroska, AVI and MPEG-TS files and, on a write, replaces the name, tags and dispositions of
 * each track the {@link MediaTags#tracks() written tags} hold, keeping those of the other tracks. What a container
 * cannot store is dropped.
 * <table class="striped">
 * <caption>Track metadata by container</caption>
 * <thead><tr><th scope="col">Container</th><th scope="col">Name</th><th scope="col">Other tags</th>
 * <th scope="col">Dispositions</th></tr></thead>
 * <tbody>
 * <tr><td>MP4, MOV</td><td>the {@code name} box of the track's {@code udta}</td><td>an {@code ilst} in a
 * {@code meta} box of the track's {@code udta}, as the movie's tags</td><td>{@link TrackDisposition#DEFAULT}: the
 * enabled flag of {@code tkhd}; the others as {@code kind} boxes of scheme {@code urn:mpeg:dash:role:2011}:
 * {@code dub} ({@link TrackDisposition#DUB}), {@code commentary} ({@link TrackDisposition#COMMENTARY}),
 * {@code forced-subtitle} ({@link TrackDisposition#FORCED}), {@code karaoke} ({@link TrackDisposition#KARAOKE}),
 * {@code caption} ({@link TrackDisposition#CAPTIONS} or {@link TrackDisposition#HEARING_IMPAIRED}, read as both)
 * and {@code description} ({@link TrackDisposition#DESCRIPTIONS} or {@link TrackDisposition#VISUAL_IMPAIRED}, read
 * as both)</td></tr>
 * <tr><td>Matroska, WebM</td><td>{@code Name}</td><td>a {@code Tag} whose {@code Targets} name the track's
 * {@code TrackUID}</td><td>{@code FlagDefault}, {@code FlagOriginal}, {@code FlagCommentary}, {@code FlagForced},
 * {@code FlagHearingImpaired}, {@code FlagVisualImpaired} and {@code FlagTextDescriptions}
 * ({@link TrackDisposition#DESCRIPTIONS})</td></tr>
 * <tr><td>AVI</td><td>{@code strn}</td><td>none</td><td>none</td></tr>
 * <tr><td>MPEG-TS</td><td>an ATSC A/65 {@code component_name_descriptor}</td><td>none</td>
 * <td>for an audio track, the audio type of the ISO 639 language descriptor, one of
 * {@link TrackDisposition#CLEAN_EFFECTS} (1), {@link TrackDisposition#HEARING_IMPAIRED} (2) and
 * {@link TrackDisposition#VISUAL_IMPAIRED} (3, the first of them in that order)</td></tr>
 * </tbody>
 * </table>
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param trackId the identifier of the track, as {@link com.github.auties00.smalt.track.Track#id() Track.id()}
 * reports it
 * @param tags the tags of the track; no pictures, chapters, track tags or XMP packet
 * @param disposition the dispositions of the track; copied
 */
public record TrackTags(int trackId, MediaTags tags, Set<TrackDisposition> disposition) {
    /**
     * Validates and copies the components.
     *
     * @throws IllegalArgumentException if {@code tags} holds pictures, attachments, chapter editions, track tags or an
     * XMP packet
     * @throws NullPointerException if {@code disposition} holds {@code null}
     */
    public TrackTags {
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(disposition, "disposition");
        if (!tags.pictures().isEmpty() || !tags.attachments().isEmpty() || !tags.editions().isEmpty()
                || !tags.tracks().isEmpty() || tags.xmp().isPresent()) {
            throw new IllegalArgumentException("the tags of a track hold no pictures, attachments, chapters, track tags"
                    + " or XMP packet");
        }
        var copy = EnumSet.noneOf(TrackDisposition.class);
        for (var value : disposition) {
            copy.add(Objects.requireNonNull(value, "disposition"));
        }
        disposition = Collections.unmodifiableSet(copy);
    }

    /**
     * Returns the description of a track with no name, tags or dispositions.
     *
     * @param trackId the identifier of the track
     * @return the track tags
     */
    public static TrackTags of(int trackId) {
        return new TrackTags(trackId, MediaTags.empty(), Set.of());
    }

    /**
     * Returns the description of a track with a name.
     *
     * @param trackId the identifier of the track
     * @param name the name, the {@link StandardTag#TITLE title} of the track
     * @param disposition the dispositions
     * @return the track tags
     */
    public static TrackTags of(int trackId, String name, TrackDisposition... disposition) {
        var set = EnumSet.noneOf(TrackDisposition.class);
        Collections.addAll(set, disposition);
        return new TrackTags(trackId, MediaTags.builder().set(StandardTag.TITLE, name).build(), set);
    }

    /**
     * {@return the name of the track, the first {@link StandardTag#TITLE title} of its tags, or empty}
     */
    public Optional<String> name() {
        return tags.get(StandardTag.TITLE);
    }

    /**
     * Returns whether the track has a disposition.
     *
     * @param value the disposition
     * @return {@code true} when {@link #disposition()} holds it
     */
    public boolean has(TrackDisposition value) {
        return disposition.contains(value);
    }

    /**
     * Returns a copy with another name, its first entry, as the containers read it back.
     *
     * @param name the name, or {@code null} for none
     * @return the copy
     */
    public TrackTags withName(String name) {
        var builder = MediaTags.builder();
        if (name != null) {
            builder.set(StandardTag.TITLE, name);
        }
        for (var entry : tags.toBuilder().remove(StandardTag.TITLE).build().entries()) {
            builder.add(entry.key(), entry.value());
        }
        return new TrackTags(trackId, builder.build(), disposition);
    }

    /**
     * Returns a copy with other tags.
     *
     * @param tags the tags; no pictures, chapters, track tags or XMP packet
     * @return the copy
     * @throws IllegalArgumentException if {@code tags} holds pictures, attachments, chapter editions, track tags or an
     * XMP packet
     */
    public TrackTags withTags(MediaTags tags) {
        return new TrackTags(trackId, tags, disposition);
    }

    /**
     * Returns a copy with other dispositions.
     *
     * @param disposition the dispositions
     * @return the copy
     * @throws NullPointerException if {@code disposition} holds {@code null}
     */
    public TrackTags withDisposition(Set<TrackDisposition> disposition) {
        return new TrackTags(trackId, tags, disposition);
    }
}
