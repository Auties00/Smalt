package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagWriteOptions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Edits the tags of a raw frame stream, MP3 or ADTS: ID3v2 tags at the start and an ID3v1 tag at
 * the end.
 *
 * <p>Consecutive ID3v2 tags at the start are read as one region; the first tag provides the
 * pictures, chapters and opaque frames, and later tags only add text keys the first lacks. The
 * rewrite replaces the whole region with one tag, padded to the region's size when it fits so the
 * audio does not move. APE and Lyrics3 tags before the ID3v1 tag are kept untouched; an enhanced
 * {@code TAG+} block is dropped when the ID3v1 tag is rewritten or removed.
 */
final class Id3StreamTagFile implements TagFile {
    /**
     * The largest number of consecutive ID3v2 tags read at the start.
     */
    private static final int MAX_TAGS = 64;

    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The ID3v2 tags at the start, in file order.
     */
    private final List<Id3v2Tag> tags;

    /**
     * The offset where the audio starts, after the leading tags.
     */
    private final long audioStart;

    /**
     * The offset where the audio and any APE or Lyrics3 tag end.
     */
    private final long audioEnd;

    /**
     * The ID3v1 tag, or {@code null}.
     */
    private final byte[] id3v1;

    /**
     * Parses the tag structure of a stream.
     *
     * @param source the MP3 or ADTS file; not closed
     * @param limits the resource bounds
     */
    Id3StreamTagFile(MediaSource source, MediaLimits limits) {
        this.source = source;
        var size = source.size();
        var parsed = new ArrayList<Id3v2Tag>();
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
        }
        this.tags = List.copyOf(parsed);
        this.audioStart = position;
        // TODO: read APEv2 tags; they are kept untouched before the ID3v1 tag but not decoded.
        var end = size;
        byte[] v1 = null;
        if (end - audioStart >= Id3v1Tag.SIZE) {
            var tail = TagIo.read(source, end - Id3v1Tag.SIZE, Id3v1Tag.SIZE, limits);
            if (Id3v1Tag.isTag(tail, 0)) {
                v1 = tail;
                end -= Id3v1Tag.SIZE;
                if (end - audioStart >= Id3v1Tag.ENHANCED_SIZE) {
                    var enhanced = TagIo.read(source, end - Id3v1Tag.ENHANCED_SIZE, 4, limits);
                    if (TagIo.matches(enhanced, 0, "TAG+")) {
                        end -= Id3v1Tag.ENHANCED_SIZE;
                    }
                }
            }
        }
        this.id3v1 = v1;
        this.audioEnd = end;
    }

    @Override
    public MediaTags read() {
        var builder = MediaTags.builder();
        var keys = new HashSet<String>();
        for (var index = 0; index < tags.size(); index++) {
            var content = tags.get(index).decode();
            var added = new HashSet<String>();
            for (var entry : content.entries()) {
                var key = entry.key().toLowerCase(Locale.ROOT);
                if (index == 0 || !keys.contains(key)) {
                    builder.add(entry.key(), entry.value());
                    added.add(key);
                }
            }
            keys.addAll(added);
            if (index == 0) {
                builder.pictures(content.pictures()).chapters(content.chapters());
            }
        }
        if (id3v1 != null) {
            for (var entry : Id3v1Tag.parse(id3v1, 0)) {
                if (keys.add(entry.key().toLowerCase(Locale.ROOT))) {
                    builder.add(entry.key(), entry.value());
                }
            }
        }
        return builder.build();
    }

    @Override
    public EditPlan plan(MediaTags newTags, TagWriteOptions options) {
        var plan = EditPlan.builder();
        var major = major(options);
        var content = tags.isEmpty() ? null : tags.getFirst().decode();
        var preserved = content != null && options.preserveUnknown() ? content.opaque() : List.<Id3v2Tag.Frame>of();
        var chapterFrames = content != null && !content.chapterFrames().isEmpty()
                && content.chapters().equals(newTags.chapters()) && tags.getFirst().major() == major
                ? content.chapterFrames() : null;
        if (!newTags.isEmpty() || !preserved.isEmpty()) {
            var minimal = Id3v2Tag.framesSize(newTags, preserved, chapterFrames, major) + Id3v2Tag.HEADER_SIZE;
            var exact = audioStart > 0 && minimal <= audioStart ? audioStart : 0;
            plan.bytes(Id3v2Tag.encode(newTags, preserved, chapterFrames, major, exact, options.padding()));
        }
        plan.copy(audioStart, audioEnd - audioStart);
        var writeV1 = switch (options.id3v1Mode()) {
            case REMOVE -> false;
            case UPDATE_EXISTING -> id3v1 != null && Id3v1Tag.hasFields(newTags);
            case ALWAYS -> Id3v1Tag.hasFields(newTags);
        };
        if (writeV1) {
            plan.bytes(Id3v1Tag.encode(newTags));
        }
        return plan.build();
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
}
