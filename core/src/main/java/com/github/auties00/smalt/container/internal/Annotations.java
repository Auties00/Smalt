package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.internal.tags.FlacPicture;
import com.github.auties00.smalt.internal.tags.VorbisComment;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Collects the chapters and pictures a demuxer finds while parsing its headers.
 *
 * <p>Chapters are added with an end of {@code -1} when the container stores only start times;
 * {@link #chapters(long)} then ends each at the start of the next and the last at the end of the
 * presentation. Instances are not thread-safe.
 */
public final class Annotations {
    /**
     * The largest number of chapters or pictures kept.
     */
    private static final int MAX_ENTRIES = 4096;

    private final List<Entry> chapters;
    private final List<AttachedPicture> pictures;

    /**
     * The Vorbis comment chapter fields by chapter number: the start clock and the title.
     */
    private final TreeMap<Integer, String[]> vorbisChapters;

    /**
     * Constructs an empty collector.
     */
    public Annotations() {
        this.chapters = new ArrayList<>();
        this.pictures = new ArrayList<>();
        this.vorbisChapters = new TreeMap<>();
    }

    /**
     * Adds a chapter; a negative start is ignored.
     *
     * @param startMicros the start, in microseconds
     * @param endMicros the end, in microseconds, or {@code -1} when the container stores none
     * @param title the title, or {@code null} or empty for none
     */
    public void addChapter(long startMicros, long endMicros, String title) {
        if (chapters.size() < MAX_ENTRIES && startMicros >= 0) {
            chapters.add(new Entry(startMicros, endMicros, title == null || title.isEmpty() ? null : title));
        }
    }

    /**
     * Adds a picture.
     *
     * @param picture the picture, or {@code null}, which is ignored
     */
    public void addPicture(AttachedPicture picture) {
        if (picture != null && pictures.size() < MAX_ENTRIES) {
            pictures.add(picture);
        }
    }

    /**
     * {@return the pictures collected; unmodifiable}
     */
    public List<AttachedPicture> pictures() {
        return List.copyOf(pictures);
    }

    /**
     * Returns the chapters collected, in start order, with every missing end resolved.
     *
     * @param durationMicros the presentation duration, or {@code -1} when unknown, which ends the last chapter at its
     * start
     * @return the chapters; unmodifiable
     */
    public List<Chapter> chapters(long durationMicros) {
        var all = new ArrayList<>(chapters);
        for (var fields : vorbisChapters.values()) {
            var start = VorbisComment.parseClock(fields[0]);
            if (start >= 0 && all.size() < MAX_ENTRIES) {
                all.add(new Entry(start, -1, fields[1]));
            }
        }
        all.sort(Comparator.comparingLong(Entry::start));
        var result = new ArrayList<Chapter>(all.size());
        for (var index = 0; index < all.size(); index++) {
            var entry = all.get(index);
            var end = entry.end();
            if (end < 0) {
                end = index + 1 < all.size() ? all.get(index + 1).start() : durationMicros;
            }
            result.add(Chapter.of(entry.start(), Math.max(end, entry.start()), entry.title()));
        }
        return List.copyOf(result);
    }

    /**
     * Takes one Vorbis comment field: a {@code CHAPTERxxx} or {@code CHAPTERxxxNAME} field becomes
     * part of a chapter, and a {@code METADATA_BLOCK_PICTURE} field becomes a picture.
     *
     * @param name the field name
     * @param value the field value
     * @return {@code true} when the field was consumed and does not belong to the tags
     */
    public boolean acceptVorbisComment(String name, String value) {
        var upper = name.toUpperCase(Locale.ROOT);
        if (upper.equals(VorbisComment.PICTURE)) {
            try {
                var block = Base64.getMimeDecoder().decode(value);
                addPicture(FlacPicture.decode(block, 0, block.length));
            } catch (IllegalArgumentException ignored) {
                // a damaged picture field is dropped; the other fields stay usable
            }
            return true;
        }
        return VorbisComment.chapterField(upper, value, vorbisChapters);
    }

    /**
     * Decodes a Vorbis comment block into {@code metadata}, routing the chapter and picture fields
     * to a collector.
     *
     * <p>Names keep their original spelling; a name that repeats has its values joined with
     * {@code "; "}. Every field {@code annotations} accepts ({@code CHAPTERxxx},
     * {@code CHAPTERxxxNAME} and {@code METADATA_BLOCK_PICTURE}) goes to it instead of
     * {@code metadata}; without a collector, pictures are dropped and chapter fields stay comments.
     *
     * @param data the bytes holding the block
     * @param offset the index of the vendor length
     * @param length the number of bytes of the block
     * @param metadata receives the comments
     * @param annotations receives the chapters and pictures, or {@code null}
     * @return the index of the first byte after the block
     * @throws MediaException.Malformed if a length or the comment count exceeds the block
     */
    public static int readVorbisComments(byte[] data, int offset, int length, Map<String, String> metadata,
                                         Annotations annotations) {
        var parsed = VorbisComment.parse(data, offset, length);
        for (var field : parsed.fields()) {
            var name = field[0];
            var value = field[1];
            if (annotations != null && annotations.acceptVorbisComment(name, value)) {
                continue;
            }
            if (!name.equalsIgnoreCase(VorbisComment.PICTURE)) {
                metadata.merge(name, value, (previous, next) -> previous + "; " + next);
            }
        }
        return parsed.end();
    }

    /**
     * A chapter as the container stores it.
     *
     * @param start the start, in microseconds
     * @param end the end, in microseconds, or {@code -1} when unknown
     * @param title the title, or {@code null}
     */
    private record Entry(long start, long end, String title) {
    }
}
