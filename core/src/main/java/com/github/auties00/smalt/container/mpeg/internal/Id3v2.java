package com.github.auties00.smalt.container.mpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.internal.tags.Id3v2Tag;
import com.github.auties00.smalt.io.MediaSource;

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
     * Accepts the frames reported as metadata: text frames and comments.
     */
    private static final Predicate<String> TEXT_FRAMES = id -> id.charAt(0) == 'T' || id.startsWith("COM");

    /**
     * Accepts the frames reported as metadata, pictures or chapters.
     */
    private static final Predicate<String> ALL_FRAMES = TEXT_FRAMES.or(id -> id.equals("APIC") || id.equals("PIC")
            || id.equals("CHAP"));

    private Id3v2() {
        throw new AssertionError();
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
        return skip(source, position, end, metadata, null);
    }

    /**
     * Skips every ID3v2 tag that starts at {@code position}, collecting their text frames, their
     * attached pictures and their chapters.
     *
     * <p>Consecutive tags are skipped one after the other, since some taggers prepend a new tag
     * instead of rewriting the old one. A tag whose declared size runs past {@code end} ends at
     * {@code end}. A tag too large to decode, or whose frames exceed the default limits, is
     * skipped without being reported.
     *
     * @param source the source to read
     * @param position the offset where a tag may start
     * @param end the offset of the end of the readable region
     * @param metadata receives the text frames, or {@code null} to skip decoding
     * @param annotations receives the pictures and chapters, or {@code null} to skip them
     * @return the offset of the first byte after the tags, or {@code position} when none starts there
     * @throws MediaException.Io if reading the source fails
     */
    public static long skip(MediaSource source, long position, long end, Map<String, String> metadata,
                            Annotations annotations) {
        var header = new byte[Id3v2Tag.HEADER_SIZE];
        for (var count = 0; count < MAX_CONSECUTIVE_TAGS && end - position >= Id3v2Tag.HEADER_SIZE; count++) {
            if (!readAll(source, position, header, header.length)) {
                break;
            }
            var size = Id3v2Tag.size(header, 0);
            if (size < 0) {
                break;
            }
            if (metadata != null) {
                decode(source, position, Math.min(size, end - position), metadata, annotations);
            }
            position = Math.min(end, position + size);
        }
        return position;
    }

    /**
     * Reads one tag and reports its frames when it is small enough.
     *
     * @param source the source to read
     * @param position the offset of the tag header
     * @param size the number of bytes of the tag available in the source
     * @param metadata receives the text frames
     * @param annotations receives the pictures and chapters, or {@code null}
     */
    private static void decode(MediaSource source, long position, long size, Map<String, String> metadata,
                               Annotations annotations) {
        var bodySize = size - Id3v2Tag.HEADER_SIZE;
        if (bodySize <= 0 || bodySize > (annotations != null ? MAX_PARSED_PICTURE_TAG : MAX_PARSED_TAG)) {
            return;
        }
        var bytes = new byte[(int) size];
        if (!readAll(source, position, bytes, bytes.length)) {
            return;
        }
        Id3v2Tag tag;
        try {
            tag = Id3v2Tag.parse(bytes, MediaLimits.DEFAULT, annotations != null ? ALL_FRAMES : TEXT_FRAMES);
        } catch (MediaException ignored) {
            // a tag that exceeds the limits is skipped; the audio after it stays readable
            return;
        }
        tag.fields(MAX_PARSED_FRAME).forEach((key, value) -> metadata.merge(key, value, (previous, next) -> previous + "; " + next));
        if (annotations != null) {
            tag.pictures().forEach(annotations::addPicture);
            for (var chapter : tag.chapterSpans()) {
                annotations.addChapter(chapter.startMillis() * 1000, chapter.endMillis() < 0 ? -1 : chapter.endMillis() * 1000,
                        chapter.title());
            }
        }
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
