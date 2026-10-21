package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.internal.edit.FileEdits;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.tags.TagFiles;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Reads and writes the tags, cover art and chapters of audio and video files without touching
 * their audio or video data.
 *
 * <p>The supported containers and the tag formats they carry:
 * <ul>
 *   <li>MP3, ADTS AAC and raw AC-3: ID3v2.2 (read), ID3v2.3 and ID3v2.4 at the start, an APEv1
 *       (read) or APEv2 tag and an ID3v1 tag at the end; pictures in {@code APIC} frames and APE
 *       {@code "Cover Art (...)"} items, chapters in {@code CHAP}/{@code CTOC} frames. ID3v2.4
 *       tags found through a {@code SEEK} frame or appended after the audio with a footer are read
 *       as ID3v2.4 section 5 says (each replaces the ID3v2 tags before it, or updates them when
 *       its extended header flags it as an update) and merged into the leading tag on a rewrite;
 *       Lyrics3 blocks are kept, a Lyrics3v1 block only while an ID3v1 tag follows it;</li>
 *   <li>FLAC: the {@code VORBIS_COMMENT} and {@code PICTURE} metadata blocks, reusing the
 *       {@code PADDING} block, and an APE and an ID3v1 tag after the frames;</li>
 *   <li>Ogg Vorbis, Opus and FLAC: the comment header, pictures as
 *       {@code METADATA_BLOCK_PICTURE}, chapters as {@code CHAPTERxxx} comments;</li>
 *   <li>MP4, M4A and MOV: the iTunes {@code ilst} list, with the integer atoms ({@code tmpo},
 *       {@code rtng}, {@code stik}, {@code pgap}, {@code hdvd}, {@code tvsn}, {@code tves},
 *       {@code plID}, the other store identifiers, and any atom the file stores as an integer) read
 *       and written as decimal text, cover art in {@code covr}, free-form {@code ----} atoms, and
 *       chapters as a Nero {@code chpl} box and a QuickTime chapter track (a disabled text track the
 *       audio and video tracks reference through {@code tref/chap}), which is kept while the
 *       chapters are unchanged and otherwise replaced in the space of the old one. A rewrite that
 *       moves media data past 4 GiB turns the 32-bit chunk offset tables it overflows into 64-bit
 *       ones;</li>
 *   <li>Matroska and WebM: global {@code Tags}, {@code Chapters} and picture
 *       {@code Attachments};</li>
 *   <li>WAV: the {@code LIST/INFO} chunk and an {@code id3 } chunk; AIFF: the text chunks and an
 *       {@code ID3 } chunk.</li>
 * </ul>
 *
 * <p>{@link #write(Path, MediaTags, TagWriteOptions)} replaces every tag, picture and chapter of
 * the file with the given ones, so the usual cycle is read, modify, write. When the new metadata
 * fits the space the old one used (padding included), only the metadata bytes are rewritten in
 * place; otherwise the file is streamed to a temporary file with fresh padding, which then
 * replaces it. Items {@link MediaTags} cannot represent, such as binary ID3v2 frames or
 * non-picture Matroska attachments, are kept unless
 * {@link TagWriteOptions#preserveUnknown()} is off.
 *
 * <p>When a file holds tags of several formats, a read takes each key from the first format that
 * has it (in an MP3, ADTS or AC-3 file the ID3v2 tags, then the APE tag, then the ID3v1 tag; in a
 * FLAC file the Vorbis comments, then the APE and the ID3v1 tag) and the pictures from the first
 * that holds any. A write stores the new tags in every format the file keeps, as
 * {@link TagWriteOptions#apeMode()} and {@link TagWriteOptions#id3v1Mode()} decide; the APE and
 * ID3v1 tags store what their format can hold.
 *
 * <p>The class is stateless and thread-safe; editing the same file from several threads at once
 * is not.
 *
 * @apiNote The keys and values {@code Media.probe} reports for a file are the same normalized vocabulary
 * ({@link StandardTag}), so a probe and a read agree on the standard tags.
 */
public final class TagEditor {
    /**
     * The input named in failure messages.
     */
    private static final String METADATA = "metadata";

    private TagEditor() {
        throw new AssertionError();
    }

    /**
     * Returns whether the tags of a container format can be read and written.
     *
     * @param format the container format
     * @return {@code true} for {@link ContainerFormat#MPEG_AUDIO}, {@link ContainerFormat#ADTS},
     * {@link ContainerFormat#AC3}, {@link ContainerFormat#FLAC}, {@link ContainerFormat#OGG},
     * {@link ContainerFormat#MP4}, {@link ContainerFormat#MATROSKA}, {@link ContainerFormat#WAV} and
     * {@link ContainerFormat#AIFF}
     */
    public static boolean isSupported(ContainerFormat format) {
        Objects.requireNonNull(format, "format");
        return TagFiles.isSupported(format);
    }

    /**
     * Reads the tags of a file with {@link TagReadOptions#defaults()}.
     *
     * @param source the file, read from offset zero; not closed
     * @return the tags, pictures and chapters
     * @throws MediaException.Unsupported if the container is not recognized or not supported
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if a metadata item exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public static MediaTags read(MediaSource source) {
        return read(source, TagReadOptions.defaults());
    }

    /**
     * Reads the tags of a file under the limits of {@code options}.
     *
     * @param source the file, read from offset zero; not closed
     * @param options the reading options
     * @return the tags, pictures and chapters
     * @throws MediaException.Unsupported if the container is not recognized or not supported
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if a metadata item exceeds the limits
     * @throws MediaException.Io if reading fails
     */
    public static MediaTags read(MediaSource source, TagReadOptions options) {
        Objects.requireNonNull(source, "source");
        var limits = options.limits();
        return Guards.malformed(METADATA, () -> TagFiles.open(source, limits).read());
    }

    /**
     * Reads the tags of a file on disk with {@link TagReadOptions#defaults()}.
     *
     * @param file the file, opened and closed by this method
     * @return the tags, pictures and chapters
     * @throws MediaException.Unsupported if the container is not recognized or not supported
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if a metadata item exceeds the limits
     * @throws MediaException.Io if the file cannot be read
     */
    public static MediaTags read(Path file) {
        return read(file, TagReadOptions.defaults());
    }

    /**
     * Reads the tags of a file on disk under the limits of {@code options}.
     *
     * @param file the file, opened and closed by this method
     * @param options the reading options
     * @return the tags, pictures and chapters
     * @throws MediaException.Unsupported if the container is not recognized or not supported
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if a metadata item exceeds the limits
     * @throws MediaException.Io if the file cannot be read
     */
    public static MediaTags read(Path file, TagReadOptions options) {
        Objects.requireNonNull(options, "options");
        try (var source = MediaSource.of(file)) {
            return read(source, options);
        }
    }

    /**
     * Replaces the tags of a file on disk with {@link TagWriteOptions#defaults()}.
     *
     * @param file the file, rewritten in place or replaced
     * @param tags the tags, pictures and chapters to store; everything else the file held is removed, apart from the
     * items {@link MediaTags} cannot represent
     * @return how the file was changed
     * @throws MediaException.Unsupported if the container is not supported
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if the metadata exceeds a format or resource limit
     * @throws MediaException.Io if reading or writing fails
     */
    public static TagWriteResult write(Path file, MediaTags tags) {
        return write(file, tags, TagWriteOptions.defaults());
    }

    /**
     * Replaces the tags of a file on disk.
     *
     * <p>When the new metadata fits the space of the old and {@link TagWriteOptions#inPlace()}
     * allows it, only the metadata bytes are rewritten; otherwise the file is rewritten to a
     * temporary file in the same directory that replaces it, atomically where the file system
     * supports it. A failed rewrite leaves the original untouched.
     *
     * @param file the file, rewritten in place or replaced
     * @param tags the tags, pictures and chapters to store
     * @param options how to write them
     * @return how the file was changed
     * @throws MediaException.Unsupported if the container is not supported
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if the metadata exceeds a format or resource limit
     * @throws MediaException.Io if reading or writing fails
     */
    public static TagWriteResult write(Path file, MediaTags tags, TagWriteOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(options, "options");
        var outcome = Guards.malformed(METADATA, () -> FileEdits.apply(file, options.inPlace(),
                source -> TagFiles.open(source, options.limits()).plan(tags, options)));
        return switch (outcome) {
            case UNCHANGED -> TagWriteResult.UNCHANGED;
            case IN_PLACE -> TagWriteResult.IN_PLACE;
            case REWRITTEN -> TagWriteResult.REWRITTEN;
        };
    }

    /**
     * Writes a copy of a file whose tags are replaced.
     *
     * <p>The output is streamed with bounded memory: only the metadata is held in memory, the
     * audio and video data are copied through a fixed buffer.
     *
     * @param source the original file, read from offset zero; not closed
     * @param sink the destination, written from its current position; not closed
     * @param tags the tags, pictures and chapters to store
     * @param options how to write them
     * @throws MediaException.Unsupported if the container is not supported
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if the metadata exceeds a format or resource limit
     * @throws MediaException.Io if reading or writing fails
     */
    public static void write(MediaSource source, MediaSink sink, MediaTags tags, TagWriteOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(options, "options");
        Guards.malformed(METADATA, () -> {
            TagFiles.open(source, options.limits()).plan(tags, options).writeTo(source, sink);
            return null;
        });
    }

    /**
     * Returns a copy of an in-memory file whose tags are replaced.
     *
     * @param file the original file
     * @param tags the tags, pictures and chapters to store
     * @param options how to write them
     * @return the edited file
     * @throws MediaException.Unsupported if the container is not supported
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if the metadata exceeds a format or resource limit
     */
    public static byte[] write(byte[] file, MediaTags tags, TagWriteOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(tags, "tags");
        Objects.requireNonNull(options, "options");
        return Guards.malformed(METADATA, () -> FileEdits.apply(file, TagFiles.open(MediaSource.of(file), options.limits()).plan(tags, options)));
    }
}
