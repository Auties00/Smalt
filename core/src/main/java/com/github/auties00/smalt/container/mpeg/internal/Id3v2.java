package com.github.auties00.smalt.container.mpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.internal.tags.Id3v2Tag;
import com.github.auties00.smalt.io.MediaSource;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Skips the ID3v2 tags that prefix MPEG audio, ADTS, FLAC and some RIFF files, reporting their
 * text frames as metadata and their pictures and chapters to a collector.
 *
 * <p>Skipping needs only the ten-byte header of each tag. The frames are decoded by
 * {@link Id3v2Tag}; the metadata keys are the version 2.3 frame identifiers that
 * {@link Id3v2Tag#fields(int)} reports.
 */
public final class Id3v2 {
    /**
     * The largest tag body whose frames are decoded into metadata; bigger tags are skipped whole.
     */
    private static final int MAX_PARSED_TAG = 1 << 20;

    /**
     * The largest tag body decoded when pictures are collected.
     */
    private static final int MAX_PARSED_PICTURE_TAG = 64 << 20;

    /**
     * The largest single frame decoded into metadata; bigger frames are skipped.
     */
    private static final int MAX_PARSED_FRAME = 64 * 1024;

    /**
     * The largest number of consecutive tags skipped at one position, which bounds the work on a
     * file made of nothing but tag headers.
     */
    private static final int MAX_CONSECUTIVE_TAGS = 64;

    /**
     * Accepts the frames reported as metadata (text frames, comments, unique file identifiers and
     * popularimeters) and the {@code SEEK} frame.
     */
    private static final Predicate<String> TEXT_FRAMES = id -> id.charAt(0) == 'T' || id.startsWith("COM")
            || id.startsWith("UFI") || id.startsWith("POP") || id.equals("SEEK");

    /**
     * Accepts the frames reported as metadata, pictures or chapters.
     */
    private static final Predicate<String> ALL_FRAMES = TEXT_FRAMES.or(id -> id.equals("APIC") || id.equals("PIC")
            || id.equals("CHAP"));

    private Id3v2() {
        throw new AssertionError();
    }

    /**
     * Names how the frames of one tag combine with those collected before it.
     */
    public enum Merge {
        /**
         * A leading tag: the values of a key already present are joined with {@code "; "}, and
         * every picture and chapter is added.
         */
        JOIN,

        /**
         * A tag found after the leading ones, through a {@code SEEK} frame or by its footer, in a
         * file whose main tags are ID3v2 (ID3v2.4 section 5): it discards everything collected so
         * far, unless its extended header flags it as an update, in which case it replaces the
         * values of the keys it holds and, when it holds any, the pictures and the chapters.
         */
        REPLACE,

        /**
         * A tag after the audio of a file whose main tags are in another format: it adds the keys
         * still missing, and its pictures and chapters when none were collected.
         */
        FILL
    }

    /**
     * Describes the ID3v2 tags at the start of a file.
     *
     * @param end the offset of the first byte after the tags
     * @param seekTarget the offset the {@code SEEK} frame of the first tag holding one points at,
     * or {@code -1} when no decoded tag has one
     */
    public record Leading(long end, long seekTarget) {
    }

    /**
     * Skips every ID3v2 tag that starts at {@code position}, collecting their text frames.
     *
     * @param source the source to read
     * @param position the offset where a tag may start
     * @param end the offset of the end of the readable region
     * @param metadata receives the text frames, or {@code null} to skip decoding
     * @return the offset of the first byte after the tags, or {@code position} when none starts there
     * @throws MediaException.Io if reading the source fails
     */
    public static long skip(MediaSource source, long position, long end, Map<String, String> metadata) {
        return read(source, position, end, metadata, null).end();
    }

    /**
     * Skips every ID3v2 tag that starts at {@code position}, collecting their text frames, their
     * attached pictures and their chapters.
     *
     * @param source the source to read
     * @param position the offset where a tag may start
     * @param end the offset of the end of the readable region
     * @param metadata receives the text frames, or {@code null} to skip decoding
     * @param annotations receives the pictures and chapters, or {@code null} to skip them
     * @return the offset of the first byte after the tags, or {@code position} when none starts there
     * @throws MediaException.Io if reading the source fails
     * @see #read(MediaSource, long, long, Map, Annotations)
     */
    public static long skip(MediaSource source, long position, long end, Map<String, String> metadata,
                            Annotations annotations) {
        return read(source, position, end, metadata, annotations).end();
    }

    /**
     * Skips every ID3v2 tag that starts at {@code position}, collecting their text frames, their
     * attached pictures and their chapters, and reports where the tags end and where the
     * {@code SEEK} frame of the first of them that holds one points.
     *
     * <p>Consecutive tags are skipped one after the other, since some taggers prepend a new tag
     * instead of rewriting the old one. A tag whose declared size runs past {@code end} ends at
     * {@code end}. A tag too large to decode, or whose frames exceed the default limits, is
     * skipped without being reported.
     *
     * @param source the source to read
     * @param position the offset where a tag may start
     * @param end the offset of the end of the readable region
     * @param metadata receives the text frames, or {@code null} to skip decoding, which also leaves
     * the {@code SEEK} frames unread
     * @param annotations receives the pictures and chapters, or {@code null} to skip them
     * @return the end of the tags and the {@code SEEK} target
     * @throws MediaException.Io if reading the source fails
     */
    public static Leading read(MediaSource source, long position, long end, Map<String, String> metadata,
                               Annotations annotations) {
        var header = new byte[Id3v2Tag.HEADER_SIZE];
        var seekTarget = -1L;
        for (var count = 0; count < MAX_CONSECUTIVE_TAGS && end - position >= Id3v2Tag.HEADER_SIZE; count++) {
            if (!readAll(source, position, header, header.length)) {
                break;
            }
            var size = Id3v2Tag.size(header, 0);
            if (size < 0) {
                break;
            }
            var available = Math.min(size, end - position);
            if (metadata != null) {
                var tag = decode(source, position, available, metadata, annotations);
                if (seekTarget < 0 && tag != null && tag.seekOffset() >= 0) {
                    seekTarget = position + available + tag.seekOffset();
                }
            }
            position += available;
        }
        return new Leading(position, seekTarget);
    }

    /**
     * Reads one leading tag and reports its frames when it is small enough.
     *
     * @param source the source to read
     * @param position the offset of the tag header
     * @param size the number of bytes of the tag available in the source
     * @param metadata receives the text frames
     * @param annotations receives the pictures and chapters, or {@code null}
     * @return the parsed tag, or {@code null} when it was skipped
     */
    private static Id3v2Tag decode(MediaSource source, long position, long size, Map<String, String> metadata,
                                   Annotations annotations) {
        var bodySize = size - Id3v2Tag.HEADER_SIZE;
        if (bodySize <= 0 || bodySize > (annotations != null ? MAX_PARSED_PICTURE_TAG : MAX_PARSED_TAG)) {
            return null;
        }
        var bytes = new byte[(int) size];
        if (!readAll(source, position, bytes, bytes.length)) {
            return null;
        }
        return decode(bytes, metadata, annotations, Merge.JOIN);
    }

    /**
     * Reports the frames of one tag. A tag that exceeds the default limits is skipped.
     *
     * @param bytes the header and body of the tag
     * @param metadata receives the text frames
     * @param annotations receives the pictures and chapters, or {@code null}
     * @param merge how the frames combine with those collected before
     * @return the parsed tag, or {@code null} when it was skipped
     */
    static Id3v2Tag decode(byte[] bytes, Map<String, String> metadata, Annotations annotations, Merge merge) {
        Id3v2Tag tag;
        try {
            tag = Id3v2Tag.parse(bytes, MediaLimits.DEFAULT, annotations != null ? ALL_FRAMES : TEXT_FRAMES);
        } catch (MediaException ignored) {
            // a tag that exceeds the limits is skipped; the audio after it stays readable
            return null;
        }
        var fields = tag.fields(MAX_PARSED_FRAME);
        var pictures = annotations != null ? tag.pictures() : List.<AttachedPicture>of();
        var chapters = annotations != null ? tag.chapterSpans() : List.<Id3v2Tag.ChapterSpan>of();
        var addPictures = true;
        var addChapters = true;
        switch (merge) {
            case JOIN -> fields.forEach((key, value) -> metadata.merge(key, value, (previous, next) -> previous + "; "
                    + next));
            case REPLACE -> {
                if (!tag.isUpdate()) {
                    metadata.clear();
                }
                metadata.putAll(fields);
                if (annotations != null && (!tag.isUpdate() || !pictures.isEmpty())) {
                    annotations.clearPictures();
                }
                if (annotations != null && (!tag.isUpdate() || !chapters.isEmpty())) {
                    annotations.clearChapters();
                }
            }
            case FILL -> {
                fields.forEach(metadata::putIfAbsent);
                addPictures = annotations != null && annotations.pictures().isEmpty();
                addChapters = annotations != null && !annotations.hasChapters();
            }
        }
        if (annotations != null) {
            if (addPictures) {
                pictures.forEach(annotations::addPicture);
            }
            if (addChapters) {
                for (var chapter : chapters) {
                    annotations.addChapter(chapter.startMillis() * 1000,
                            chapter.endMillis() < 0 ? -1 : chapter.endMillis() * 1000, chapter.title());
                }
            }
        }
        return tag;
    }

    /**
     * Reads exactly {@code length} bytes, reporting a short source instead of failing.
     *
     * @param source the input
     * @param position the offset of the first byte
     * @param target the destination
     * @param length the number of bytes
     * @return {@code true} when every byte was read
     */
    private static boolean readAll(MediaSource source, long position, byte[] target, int length) {
        var done = 0;
        while (done < length) {
            var read = source.read(position + done, target, done, length - done);
            if (read <= 0) {
                return false;
            }
            done += read;
        }
        return true;
    }
}
