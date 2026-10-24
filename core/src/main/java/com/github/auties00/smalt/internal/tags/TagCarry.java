package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.internal.io.ForwardOnlySource;
import com.github.auties00.smalt.internal.io.LocalSource;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagMode;
import com.github.auties00.smalt.metadata.TrackTags;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Track;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Carries the tags, pictures, chapters and track tags of an input into the output of a conversion, as
 * {@link TagMode} describes.
 */
public final class TagCarry {
    /**
     * The prefix of the names the ID3v2 demuxers give {@code TXXX} frames.
     */
    private static final String USER_TEXT_PREFIX = "TXXX:";

    /**
     * The key of the track tag naming the statistics tags a Matroska muxer wrote.
     */
    private static final String STATISTICS_TAGS = "_STATISTICS_TAGS";

    /**
     * The track tags that describe a stream as it was stored, in normalized form.
     */
    private static final Set<String> STREAM_STATISTICS = Set.of("bps", "duration", "number_of_frames",
            "number_of_bytes", StandardTag.ENCODER.key(), "encoder_settings");

    /**
     * The tags that state the loudness of the audio.
     */
    private static final List<StandardTag> REPLAY_GAIN = List.of(StandardTag.REPLAYGAIN_TRACK_GAIN,
            StandardTag.REPLAYGAIN_TRACK_PEAK, StandardTag.REPLAYGAIN_ALBUM_GAIN, StandardTag.REPLAYGAIN_ALBUM_PEAK);

    private TagCarry() {
        throw new AssertionError();
    }

    /**
     * Describes how the streams of a conversion relate to its input.
     *
     * @param outputIds the output track identifier of every input track written, by input track identifier
     * @param rewritten the input track identifiers of the streams written encoded or trimmed
     * @param encoded whether any stream is encoded
     * @param levelChanged whether an encoded audio stream changes its level
     * @param startMicros the start of the kept range on the input's timeline, zero or more
     * @param endMicros the end of the kept range on the input's timeline, or {@code -1} for the end of the input
     */
    public record Mapping(Map<Integer, Integer> outputIds, Set<Integer> rewritten, boolean encoded,
                          boolean levelChanged, long startMicros, long endMicros) {
        /**
         * Copies the collections.
         */
        public Mapping {
            outputIds = Map.copyOf(outputIds);
            rewritten = Set.copyOf(rewritten);
        }
    }

    /**
     * Reads the tags of an input: through its tag file when its format has one and the source is local (in memory
     * or in a file) and can be read again, otherwise, and for every part the tag file lacks, from its demuxer.
     *
     * <p>A tag file that cannot be read is ignored, so that corrupt metadata never fails a conversion of the
     * media. A source that is not local is not read again for its tags, since that could transfer it again.
     *
     * @param input the input, whose demuxer is open; not closed
     * @param demuxer the demuxer of the input
     * @param limits the bounds of the read
     * @return the tags, keyed by the input's track identifiers
     * @throws MediaException.Io if reading the input fails
     */
    public static MediaTags read(MediaSource input, Demuxer demuxer, MediaLimits limits) {
        MediaTags file = null;
        if (TagFiles.isSupported(demuxer.format()) && LocalSource.isLocal(input)
                && !ForwardOnlySource.isForwardOnly(input)) {
            try {
                file = TagFiles.open(input, limits).read();
            } catch (MediaException.Malformed | MediaException.Unsupported | MediaException.LimitExceeded
                     | IllegalArgumentException ignored) {
                // the demuxer's view of the metadata stands in for an unreadable tag file
            }
        }
        var builder = file != null ? file.toBuilder() : MediaTags.builder();
        if (file == null || file.entries().isEmpty()) {
            builder.clearEntries();
            addDemuxerEntries(builder, demuxer.metadata(), demuxer.format());
        }
        if (file == null || file.pictures().isEmpty()) {
            builder.pictures(demuxer.attachedPictures());
        }
        if (file == null || file.chapters().isEmpty()) {
            builder.chapters(demuxer.chapters());
        }
        if (file == null || file.tracks().isEmpty()) {
            var tracks = new ArrayList<TrackTags>();
            for (var track : demuxer.tracks()) {
                var tags = MediaTags.builder();
                addDemuxerEntries(tags, demuxer.trackMetadata(track.id()), demuxer.format());
                var disposition = demuxer.trackDisposition(track.id());
                var built = tags.build();
                if (!built.isEmpty() || !disposition.isEmpty()) {
                    tracks.add(new TrackTags(track.id(), built, disposition));
                }
            }
            builder.tracks(tracks);
        }
        return builder.build();
    }

    /**
     * Adds the entries of a demuxer's tag map, under the normalized names of {@link StandardTag}.
     *
     * @param builder receives the entries
     * @param metadata the tags as the demuxer names them
     * @param format the container of the demuxer, whose RIFF {@code INFO} identifiers are renamed
     */
    private static void addDemuxerEntries(MediaTags.Builder builder, Map<String, String> metadata,
                                          ContainerFormat format) {
        var riff = format == ContainerFormat.WAV || format == ContainerFormat.AVI;
        for (var entry : metadata.entrySet()) {
            var key = riff ? TagFiles.riffInfoKey(entry.getKey()) : entry.getKey();
            if (StandardTag.fromName(key).isEmpty() && key.toUpperCase(Locale.ROOT).startsWith(USER_TEXT_PREFIX)) {
                key = key.substring(USER_TEXT_PREFIX.length());
            }
            try {
                builder.add(key, entry.getValue());
            } catch (IllegalArgumentException ignored) {
                // a name or value no tag format stores, such as a blank key, is left out
            }
        }
    }

    /**
     * Returns the tags a conversion writes, in the output's terms.
     *
     * @param mode what the output carries
     * @param given the tags of {@link TagMode#REPLACE} and {@link TagMode#MERGE}, on the input's terms, or
     * {@code null} for the other modes
     * @param input the tags of the input, on the input's terms, or {@code null} for {@link TagMode#DROP} and
     * {@link TagMode#REPLACE}
     * @param mapping how the output relates to the input
     * @return the tags, chapters on the output's timeline and track tags naming output tracks
     */
    public static MediaTags resolve(TagMode mode, MediaTags given, MediaTags input, Mapping mapping) {
        return switch (mode) {
            case DROP -> MediaTags.empty();
            case REPLACE -> map(Objects.requireNonNull(given, "given"), mapping);
            case COPY -> map(withoutStale(Objects.requireNonNull(input, "input"), mapping), mapping);
            case MERGE -> map(merge(withoutStale(Objects.requireNonNull(input, "input"), mapping),
                    Objects.requireNonNull(given, "given")), mapping);
        };
    }

    /**
     * Removes the tags that stop describing the output once streams are encoded or trimmed.
     *
     * @param tags the input's tags
     * @param mapping how the output relates to the input
     * @return the tags left
     */
    private static MediaTags withoutStale(MediaTags tags, Mapping mapping) {
        var builder = tags.toBuilder();
        if (mapping.encoded()) {
            builder.remove(StandardTag.ENCODER);
        }
        if (mapping.levelChanged()) {
            for (var tag : REPLAY_GAIN) {
                builder.remove(tag);
            }
        }
        var tracks = new ArrayList<TrackTags>();
        for (var track : tags.tracks()) {
            var trackTags = track.tags();
            if (mapping.rewritten().contains(track.trackId())) {
                trackTags = withoutStatistics(trackTags);
            }
            if (mapping.levelChanged()) {
                var trackBuilder = trackTags.toBuilder();
                for (var tag : REPLAY_GAIN) {
                    trackBuilder.remove(tag);
                }
                trackTags = trackBuilder.build();
            }
            tracks.add(track.withTags(trackTags));
        }
        return builder.tracks(tracks).build();
    }

    /**
     * Removes the statistics tags of a track.
     *
     * @param tags the tags of the track
     * @return the tags left
     */
    private static MediaTags withoutStatistics(MediaTags tags) {
        var listed = new HashSet<String>();
        for (var value : tags.getAll(STATISTICS_TAGS)) {
            for (var name : value.trim().split("\\s+")) {
                if (!name.isEmpty()) {
                    listed.add(StandardTag.normalize(name).toLowerCase(Locale.ROOT));
                }
            }
        }
        var builder = MediaTags.builder();
        for (var entry : tags.entries()) {
            var key = entry.key().toLowerCase(Locale.ROOT);
            if (!STREAM_STATISTICS.contains(key) && !listed.contains(key) && !key.startsWith("_statistics_")) {
                builder.add(entry.key(), entry.value());
            }
        }
        return builder.build();
    }

    /**
     * Updates tags with others, as {@link TagMode#MERGE} describes.
     *
     * @param base the tags updated
     * @param update the tags that take precedence
     * @return the merged tags
     */
    private static MediaTags merge(MediaTags base, MediaTags update) {
        var builder = mergeEntries(base, update).toBuilder();
        builder.pictures(update.pictures().isEmpty() ? base.pictures() : update.pictures());
        builder.chapters(update.chapters().isEmpty() ? base.chapters() : update.chapters());
        var tracks = new LinkedHashMap<Integer, TrackTags>();
        for (var track : base.tracks()) {
            tracks.put(track.trackId(), track);
        }
        for (var track : update.tracks()) {
            var old = tracks.get(track.trackId());
            if (old == null) {
                tracks.put(track.trackId(), track.withTags(mergeEntries(MediaTags.empty(), track.tags())));
            } else {
                var disposition = track.disposition().isEmpty() ? old.disposition() : track.disposition();
                tracks.put(track.trackId(), new TrackTags(track.trackId(), mergeEntries(old.tags(), track.tags()),
                        disposition));
            }
        }
        return builder.tracks(new ArrayList<>(tracks.values())).build();
    }

    /**
     * Updates the entries of tags with those of others: every key of {@code update} replaces the values of
     * {@code base}, and an empty value removes the key.
     *
     * @param base the tags updated
     * @param update the tags that take precedence
     * @return the entries of {@code base} updated, with no picture, chapter or track tags
     */
    private static MediaTags mergeEntries(MediaTags base, MediaTags update) {
        var builder = MediaTags.builder();
        for (var entry : base.entries()) {
            builder.add(entry.key(), entry.value());
        }
        for (var key : update.keys()) {
            var values = new ArrayList<String>();
            for (var value : update.getAll(key)) {
                if (!value.isEmpty()) {
                    values.add(value);
                }
            }
            builder.set(key, values.toArray(String[]::new));
        }
        return builder.build();
    }

    /**
     * Maps tags on the input's terms to the output's: track tags follow their tracks and chapters the kept range.
     *
     * @param tags the tags on the input's terms
     * @param mapping how the output relates to the input
     * @return the tags on the output's terms
     */
    private static MediaTags map(MediaTags tags, Mapping mapping) {
        var builder = tags.toBuilder();
        var chapters = new ArrayList<Chapter>();
        for (var chapter : tags.chapters()) {
            var mapped = cut(chapter, mapping.startMicros(), mapping.endMicros());
            if (mapped != null) {
                chapters.add(mapped);
            }
        }
        builder.chapters(chapters);
        var tracks = new ArrayList<TrackTags>();
        for (var track : tags.tracks()) {
            var output = mapping.outputIds().get(track.trackId());
            if (output != null && (!track.tags().isEmpty() || !track.disposition().isEmpty())) {
                tracks.add(new TrackTags(output, track.tags(), track.disposition()));
            }
        }
        return builder.tracks(tracks).build();
    }

    /**
     * Cuts a chapter to a kept range and moves it to the output's timeline.
     *
     * <p>A chapter that overlaps the range keeps the part inside it; an empty chapter is kept when it lies at or
     * after the start and before the end.
     *
     * @param chapter the chapter on the input's timeline
     * @param start the start of the range in microseconds
     * @param end the end of the range in microseconds, or {@code -1} for the end of the input
     * @return the chapter on the output's timeline, or {@code null} when it lies outside the range
     */
    static Chapter cut(Chapter chapter, long start, long end) {
        var limit = end < 0 ? Long.MAX_VALUE : end;
        var empty = chapter.startMicros() == chapter.endMicros();
        var inside = empty
                ? chapter.startMicros() >= start && chapter.startMicros() < limit
                : chapter.startMicros() < limit && chapter.endMicros() > start;
        if (!inside) {
            return null;
        }
        var from = Math.max(chapter.startMicros(), start) - start;
        var to = Math.min(chapter.endMicros(), limit) - start;
        return new Chapter(from, to, chapter.title());
    }

    /**
     * Gives a muxer the tags of a conversion when it stores tags.
     *
     * <p>With {@link TagMode#COPY} and {@link TagMode#MERGE}, entries, pictures and track entries the muxer
     * rejects ({@link MediaException.Unsupported} or {@link MediaException.LimitExceeded}) are left out one by one,
     * unless {@code given} holds them; with {@link TagMode#REPLACE} the rejection fails the conversion.
     *
     * @param muxer the muxer, with its tracks declared and no packet written
     * @param tags the tags on the output's terms
     * @param mode what the output carries
     * @param given the tags of {@link TagMode#REPLACE} and {@link TagMode#MERGE}, or {@code null}
     * @throws MediaException.Unsupported if the muxer rejects a value that must be written
     * @throws MediaException.LimitExceeded if a value that must be written exceeds a limit of the container
     */
    public static void apply(Muxer muxer, MediaTags tags, TagMode mode, MediaTags given) {
        if (!muxer.supportsTags()) {
            return;
        }
        try {
            muxer.setTags(tags);
            return;
        } catch (MediaException.Unsupported | MediaException.LimitExceeded rejected) {
            if (mode == TagMode.REPLACE) {
                throw rejected;
            }
        }
        var strictEntries = new HashSet<MediaTags.Entry>();
        var strictPictures = new HashSet<AttachedPicture>();
        if (given != null) {
            strictEntries.addAll(given.entries());
            for (var track : given.tracks()) {
                strictEntries.addAll(track.tags().entries());
            }
            strictPictures.addAll(given.pictures());
        }
        var kept = MediaTags.builder();
        for (var entry : tags.entries()) {
            if (accepts(muxer, MediaTags.builder().add(entry.key(), entry.value()).build(), strictEntries.contains(entry))) {
                kept.add(entry.key(), entry.value());
            }
        }
        for (var picture : tags.pictures()) {
            if (accepts(muxer, MediaTags.builder().addPicture(picture).build(), strictPictures.contains(picture))) {
                kept.addPicture(picture);
            }
        }
        kept.chapters(tags.chapters());
        for (var track : tags.tracks()) {
            var trackTags = MediaTags.builder();
            for (var entry : track.tags().entries()) {
                var single = MediaTags.builder().track(new TrackTags(track.trackId(),
                        MediaTags.builder().add(entry.key(), entry.value()).build(), Set.of())).build();
                if (accepts(muxer, single, strictEntries.contains(entry))) {
                    trackTags.add(entry.key(), entry.value());
                }
            }
            kept.track(new TrackTags(track.trackId(), trackTags.build(), track.disposition()));
        }
        muxer.setTags(kept.build());
    }

    /**
     * Returns a track whose codec configuration holds no tags or pictures: the {@code VORBIS_COMMENT} and
     * {@code PICTURE} blocks of a FLAC configuration are removed, and the comment header of a Vorbis configuration
     * keeps its vendor string and no comment.
     *
     * <p>A container that stores such a configuration as it is (Matroska, MP4) would otherwise carry tags beside its
     * own, which a conversion replaces. A configuration that cannot be parsed, and the track of any other codec, are
     * returned unchanged.
     *
     * @param track the track
     * @return the track, or a copy with the reduced configuration
     */
    public static Track withoutEmbeddedTags(Track track) {
        if (!(track instanceof AudioTrack audio) || audio.codecConfig().isEmpty()) {
            return track;
        }
        var config = audio.codecConfig().get();
        var reduced = switch (audio.codec()) {
            case FLAC -> flacWithoutTags(config);
            case VORBIS -> vorbisWithoutTags(config);
            default -> null;
        };
        return reduced == null ? track : audio.toBuilder().codecConfig(reduced).build();
    }

    /**
     * Removes the {@code VORBIS_COMMENT} (type 4) and {@code PICTURE} (type 6) blocks of a FLAC configuration.
     *
     * @param config the {@code fLaC} marker and the metadata blocks
     * @return the reduced configuration, or {@code null} when nothing is removed or the blocks cannot be parsed
     */
    static byte[] flacWithoutTags(byte[] config) {
        if (config.length < 4 || config[0] != 'f' || config[1] != 'L' || config[2] != 'a' || config[3] != 'C') {
            return null;
        }
        var kept = new ArrayList<int[]>();
        var removed = false;
        var offset = 4;
        while (offset < config.length) {
            if (offset + 4 > config.length) {
                return null;
            }
            var type = config[offset] & 0x7F;
            var length = (config[offset + 1] & 0xFF) << 16 | (config[offset + 2] & 0xFF) << 8 | config[offset + 3] & 0xFF;
            if (offset + 4 + length > config.length) {
                return null;
            }
            if (type == 4 || type == 6) {
                removed = true;
            } else {
                kept.add(new int[]{offset, 4 + length});
            }
            var last = (config[offset] & 0x80) != 0;
            offset += 4 + length;
            if (last) {
                break;
            }
        }
        if (!removed || kept.isEmpty()) {
            return null;
        }
        var out = new ByteArrayOutputStream(config.length);
        out.write(config, 0, 4);
        for (var index = 0; index < kept.size(); index++) {
            var block = Arrays.copyOfRange(config, kept.get(index)[0], kept.get(index)[0] + kept.get(index)[1]);
            block[0] = (byte) (index == kept.size() - 1 ? block[0] | 0x80 : block[0] & 0x7F);
            out.write(block, 0, block.length);
        }
        return out.toByteArray();
    }

    /**
     * Replaces the comment header of a Vorbis configuration by one with its vendor string and no comment.
     *
     * @param config the three header packets, Xiph-laced
     * @return the reduced configuration, or {@code null} when the header holds no comment or cannot be parsed
     */
    static byte[] vorbisWithoutTags(byte[] config) {
        byte[][] packets;
        try {
            packets = XiphLacing.unlace(config);
        } catch (RuntimeException malformed) {
            return null;
        }
        if (packets == null || packets.length != 3) {
            return null;
        }
        var comment = packets[1];
        var prefix = new byte[]{3, 'v', 'o', 'r', 'b', 'i', 's'};
        if (comment.length < prefix.length + 8 || !Arrays.equals(comment, 0, prefix.length, prefix, 0, prefix.length)) {
            return null;
        }
        var vendor = littleEndian(comment, prefix.length);
        var countAt = prefix.length + 4 + vendor;
        if (vendor < 0 || countAt + 4 > comment.length) {
            return null;
        }
        if (littleEndian(comment, countAt) == 0) {
            return null;
        }
        var rebuilt = Arrays.copyOf(comment, countAt + 5);
        Arrays.fill(rebuilt, countAt, countAt + 4, (byte) 0);
        rebuilt[countAt + 4] = 1;
        return XiphLacing.lace(packets[0], rebuilt, packets[2]);
    }

    /**
     * {@return the unsigned 32-bit little-endian value at an offset, or {@code -1} past {@link Integer#MAX_VALUE}}
     *
     * @param data the bytes
     * @param offset the offset of the value
     */
    private static int littleEndian(byte[] data, int offset) {
        var value = (data[offset] & 0xFFL) | (data[offset + 1] & 0xFFL) << 8 | (data[offset + 2] & 0xFFL) << 16
                | (data[offset + 3] & 0xFFL) << 24;
        return value > Integer.MAX_VALUE ? -1 : (int) value;
    }

    /**
     * Returns whether a muxer accepts tags.
     *
     * @param muxer the muxer, whose tags are replaced
     * @param tags the tags to try
     * @param strict whether a rejection fails the conversion
     * @return {@code true} when the muxer accepts them
     * @throws MediaException.Unsupported if the muxer rejects strict tags
     * @throws MediaException.LimitExceeded if strict tags exceed a limit of the container
     */
    private static boolean accepts(Muxer muxer, MediaTags tags, boolean strict) {
        try {
            muxer.setTags(tags);
            return true;
        } catch (MediaException.Unsupported | MediaException.LimitExceeded rejected) {
            if (strict) {
                throw rejected;
            }
            return false;
        }
    }
}
