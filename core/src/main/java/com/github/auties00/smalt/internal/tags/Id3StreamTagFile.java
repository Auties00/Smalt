package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.mpeg.internal.TrailingTags;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagWriteOptions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Edits the tags of a raw frame stream, MP3, ADTS or AC-3: ID3v2 tags at the start, and after the
 * audio any of an appended ID3v2.4 tag, an APE tag, Lyrics3 blocks and an ID3v1 tag.
 *
 * <p>Consecutive ID3v2 tags at the start are read as one region; the first tag provides the
 * pictures, chapters and opaque frames, and later tags only add text keys the first lacks. The tags
 * found after them, through the {@code SEEK} frame of a leading tag or by the footer of a tag
 * appended after the audio, follow ID3v2.4 section 5 in file order: each discards the ID3v2
 * content read before it, unless its extended header flags it as an update, in which case it
 * replaces the values of the keys it holds and, when it holds any, the pictures and the chapters.
 * The APE tag and then the ID3v1 tag add the keys still missing, and the APE cover art counts
 * when no ID3v2 tag holds a picture.
 *
 * <p>The rewrite replaces the leading region with one tag, padded to the region's size when it
 * fits so the audio does not move, and drops the tags a {@code SEEK} frame or a footer located,
 * whose content the new tag carries. The APE and ID3v1 tags follow their
 * {@link TagWriteOptions} modes; Lyrics3v2 blocks are kept unchanged, and so are Lyrics3v1
 * blocks while an ID3v1 tag follows them (a Lyrics3v1 block is defined only before one, so it is
 * dropped with that tag); an enhanced {@code TAG+} block is dropped.
 *
 * @implNote This implementation writes the trailing tags kept or rewritten as literal bytes, so an
 * edit whose leading tag fits its region stays in place even when the trailing tags change size.
 */
final class Id3StreamTagFile implements TagFile {
    /**
     * The largest number of consecutive ID3v2 tags read at the start, and of {@code SEEK} frames
     * followed.
     */
    private static final int MAX_TAGS = 64;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The ID3v2 tags at the start, in file order.
     */
    private final List<Id3v2Tag> tags;

    /**
     * The offset where the audio starts, after the leading tags.
     */
    private final long audioStart;

    /**
     * The offset where the audio ends and the trailing tags start.
     */
    private final long audioEnd;

    /**
     * The tags after the leading region, located by a {@code SEEK} frame or by their footer, in
     * file order.
     */
    private final List<Located> later;

    /**
     * The trailing tags, in file order.
     */
    private final List<TrailingTags.Region> trailing;

    /**
     * The APE tag, or {@code null}.
     */
    private final ApeTag ape;

    /**
     * The ID3v1 tag, or {@code null}.
     */
    private final byte[] id3v1;

    /**
     * Parses the tag structure of a stream.
     *
     * @param source the MP3, ADTS or AC-3 file; not closed
     * @param limits the resource bounds
     */
    Id3StreamTagFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        this.limits = limits;
        var size = source.size();
        var parsed = new ArrayList<Id3v2Tag>();
        var ends = new ArrayList<Long>();
        var position = 0L;
        while (parsed.size() < MAX_TAGS && size - position >= Id3v2Tag.HEADER_SIZE) {
            var header = TagIo.read(source, position, Id3v2Tag.HEADER_SIZE, limits);
            var tagSize = Id3v2Tag.size(header, 0);
            if (tagSize < 0) {
                break;
            }
            var available = Math.min(tagSize, size - position);
            parsed.add(Id3v2Tag.parse(TagIo.read(source, position, available, limits), limits));
            position += available;
            ends.add(position);
        }
        this.tags = List.copyOf(parsed);
        this.audioStart = position;
        this.trailing = TrailingTags.regions(source, audioStart, size);
        this.audioEnd = trailing.isEmpty() ? size : trailing.getFirst().offset();
        var located = new ArrayList<Located>();
        ApeTag apeTag = null;
        byte[] v1 = null;
        for (var region : trailing) {
            switch (region.kind()) {
                case ID3V1 -> v1 = TagIo.read(source, region.offset(), region.length(), limits);
                case APE -> {
                    if (apeTag == null) {
                        apeTag = ApeTag.parse(TagIo.read(source, region.offset(), region.length(), limits));
                    }
                }
                case ID3V2 -> located.add(new Located(Id3v2Tag.parse(TagIo.read(source, region.offset(), region.length(),
                        limits), limits), region.offset(), region.length()));
                default -> {
                }
            }
        }
        for (var index = 0; index < tags.size(); index++) {
            if (tags.get(index).seekOffset() >= 0) {
                follow(tags.get(index), ends.get(index), located);
                break;
            }
        }
        located.sort((first, second) -> Long.compare(first.offset(), second.offset()));
        this.later = List.copyOf(located);
        this.ape = apeTag;
        this.id3v1 = v1;
    }

    /**
     * Adds the tags a chain of {@code SEEK} frames leads to inside the audio.
     *
     * <p>A target counts only when a valid tag header starts there, after the leading region, and
     * the whole tag lies before the trailing tags; a target that is already a located tag ends
     * the chain.
     *
     * @param tag the tag holding the first {@code SEEK} frame
     * @param end the offset of the end of that tag
     * @param located the located tags, which receives the new ones
     */
    private void follow(Id3v2Tag tag, long end, List<Located> located) {
        for (var hop = 0; hop < MAX_TAGS; hop++) {
            var offset = tag.seekOffset();
            if (offset < 0) {
                return;
            }
            // TODO: search forward from a target that holds no tag; ID3v2.4 makes the SEEK offset a minimum
            var target = end + offset;
            for (var known : located) {
                if (known.offset() == target) {
                    return;
                }
            }
            if (target < audioStart || audioEnd - target < Id3v2Tag.HEADER_SIZE) {
                return;
            }
            var size = Id3v2Tag.size(TagIo.read(source, target, Id3v2Tag.HEADER_SIZE, limits), 0);
            if (size < 0 || size > audioEnd - target) {
                return;
            }
            tag = Id3v2Tag.parse(TagIo.read(source, target, size, limits), limits);
            located.add(new Located(tag, target, size));
            end = target + size;
        }
    }

    @Override
    public MediaTags read() {
        var builder = MediaTags.builder();
        var keys = new HashSet<String>();
        List<AttachedPicture> pictures = List.of();
        List<Chapter> chapters = List.of();
        for (var index = 0; index < tags.size(); index++) {
            var content = tags.get(index).decode();
            addMissing(builder, keys, content.entries(), index == 0);
            if (index == 0) {
                pictures = content.pictures();
                chapters = content.chapters();
            }
        }
        for (var tag : later) {
            var content = tag.tag().decode();
            if (tag.tag().isUpdate()) {
                var grouped = new LinkedHashMap<String, List<String>>();
                for (var entry : content.entries()) {
                    grouped.computeIfAbsent(entry.key(), _ -> new ArrayList<>()).add(entry.value());
                }
                for (var group : grouped.entrySet()) {
                    keys.add(group.getKey().toLowerCase(Locale.ROOT));
                    builder.set(group.getKey(), group.getValue().toArray(String[]::new));
                }
                if (!content.pictures().isEmpty()) {
                    pictures = content.pictures();
                }
                if (!content.chapters().isEmpty()) {
                    chapters = content.chapters();
                }
            } else {
                builder.clearEntries();
                keys.clear();
                addMissing(builder, keys, content.entries(), true);
                pictures = content.pictures();
                chapters = content.chapters();
            }
        }
        if (ape != null) {
            var content = ape.decode();
            addMissing(builder, keys, content.entries(), false);
            if (pictures.isEmpty()) {
                pictures = content.pictures();
            }
        }
        if (id3v1 != null) {
            addMissing(builder, keys, Id3v1Tag.parse(id3v1, 0), false);
        }
        return builder.pictures(pictures).chapters(chapters).build();
    }

    /**
     * Adds the entries whose key was not read before, or every entry.
     *
     * @param builder the tags being read
     * @param keys the lower-case keys read so far, which receives the keys added
     * @param entries the entries of one tag
     * @param all whether every entry is added
     */
    private static void addMissing(MediaTags.Builder builder, Set<String> keys, List<MediaTags.Entry> entries,
                                   boolean all) {
        var added = new HashSet<String>();
        for (var entry : entries) {
            var key = entry.key().toLowerCase(Locale.ROOT);
            if (all || !keys.contains(key)) {
                builder.add(entry.key(), entry.value());
                added.add(key);
            }
        }
        keys.addAll(added);
    }

    @Override
    public EditPlan plan(MediaTags newTags, TagWriteOptions options) {
        var plan = EditPlan.builder();
        var major = major(options);
        var content = tags.isEmpty() ? null : tags.getFirst().decode();
        var preserved = new ArrayList<Id3v2Tag.Frame>();
        if (options.preserveUnknown()) {
            if (content != null) {
                preserved.addAll(content.opaque());
            }
            for (var tag : later) {
                if (!tag.tag().isUpdate()) {
                    preserved.clear();
                }
                for (var frame : tag.tag().decode().opaque()) {
                    if (!contains(preserved, frame)) {
                        preserved.add(frame);
                    }
                }
            }
        }
        var chapterFrames = content != null && !content.chapterFrames().isEmpty()
                && content.chapters().equals(newTags.chapters()) && tags.getFirst().major() == major
                ? content.chapterFrames() : null;
        if (!newTags.isEmpty() || !preserved.isEmpty()) {
            var minimal = Id3v2Tag.framesSize(newTags, preserved, chapterFrames, major) + Id3v2Tag.HEADER_SIZE;
            var exact = audioStart > 0 && minimal <= audioStart ? audioStart : 0;
            plan.bytes(Id3v2Tag.encode(newTags, preserved, chapterFrames, major, exact, options.padding()));
        }
        var position = audioStart;
        for (var tag : later) {
            if (tag.offset() >= position && tag.end() <= audioEnd) {
                plan.copy(position, tag.offset() - position);
                position = tag.end();
            }
        }
        plan.copy(position, audioEnd - position);
        var apeBytes = switch (options.apeMode()) {
            case REMOVE -> null;
            case UPDATE_EXISTING -> ape != null ? apeTag(newTags, options) : null;
            case ALWAYS -> apeTag(newTags, options);
        };
        var writeV1 = switch (options.id3v1Mode()) {
            case REMOVE -> false;
            case UPDATE_EXISTING -> id3v1 != null && Id3v1Tag.hasFields(newTags);
            case ALWAYS -> Id3v1Tag.hasFields(newTags);
        };
        var apeWritten = false;
        if (ape == null && apeBytes != null) {
            plan.bytes(apeBytes);
            apeWritten = true;
        }
        for (var region : trailing) {
            switch (region.kind()) {
                case APE -> {
                    if (!apeWritten && apeBytes != null) {
                        plan.bytes(apeBytes);
                    }
                    apeWritten = true;
                }
                case LYRICS3 -> {
                    var block = TagIo.read(source, region.offset(), region.length(), limits);
                    if (writeV1 || !TagIo.isLyrics3v1(block)) {
                        plan.bytes(block);
                    }
                }
                default -> {
                }
            }
        }
        if (writeV1) {
            plan.bytes(Id3v1Tag.encode(newTags));
        }
        return plan.build();
    }

    /**
     * Encodes the APE tag that mirrors the new tags.
     *
     * @param newTags the tags to write
     * @param options the write options
     * @return the tag, or {@code null} when there is nothing to store
     */
    private byte[] apeTag(MediaTags newTags, TagWriteOptions options) {
        var preserved = ape != null && options.preserveUnknown() ? ape.decode().opaque() : List.<ApeTag.Item>of();
        var bytes = ApeTag.encode(newTags, preserved);
        return bytes.length == 0 ? null : bytes;
    }

    /**
     * Returns whether a list holds a frame with the same identifier, flags and content.
     *
     * @param frames the frames searched
     * @param frame the frame to look for
     * @return {@code true} when an equal frame is present
     */
    private static boolean contains(List<Id3v2Tag.Frame> frames, Id3v2Tag.Frame frame) {
        for (var candidate : frames) {
            if (candidate.id().equals(frame.id()) && candidate.flags() == frame.flags()
                    && Arrays.equals(candidate.content(), frame.content())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Chooses the ID3v2 version to write.
     *
     * @param options the write options
     * @return 3 or 4
     */
    private int major(TagWriteOptions options) {
        if (options.id3Version().isPresent()) {
            return options.id3Version().get() == TagWriteOptions.Id3Version.V2_3 ? 3 : 4;
        }
        return !tags.isEmpty() && tags.getFirst().major() == 3 ? 3 : 4;
    }

    /**
     * Holds an ID3v2 tag found after the leading region.
     *
     * @param tag the parsed tag
     * @param offset the offset of its header
     * @param length its size, footer included
     */
    private record Located(Id3v2Tag tag, long offset, long length) {
        /**
         * {@return the offset of the first byte after the tag}
         */
        long end() {
            return offset + length;
        }
    }
}
