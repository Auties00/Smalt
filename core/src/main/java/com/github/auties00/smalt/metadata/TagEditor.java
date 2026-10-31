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
 * Reads and writes the tags, cover art, attachments, chapters and XMP packet of audio and video files without
 * touching their audio or video data.
 *
 * <p>The supported containers and the tag formats they carry:
 * <ul>
 *   <li>MP3, ADTS AAC, raw AC-3 and E-AC-3, and AMR: ID3v2.2 (read), ID3v2.3 and ID3v2.4 at the start, an APEv1
 *       (read) or APEv2 tag and an ID3v1 tag at the end; pictures in {@code APIC} frames and APE
 *       {@code "Cover Art (...)"} items, chapters in {@code CHAP}/{@code CTOC} frames, the XMP packet in a
 *       {@code PRIV} frame of owner {@code XMP} (XMP Specification Part 3). ID3v2.4
 *       tags found through a {@code SEEK} frame or appended after the audio with a footer are read
 *       as ID3v2.4 section 5 says (each replaces the ID3v2 tags before it, or updates them when
 *       its extended header flags it as an update) and merged into the leading tag on a rewrite;
 *       Lyrics3 blocks are kept, a Lyrics3v1 block only while an ID3v1 tag follows it;</li>
 *   <li>FLAC: the {@code VORBIS_COMMENT} and {@code PICTURE} metadata blocks, reusing the
 *       {@code PADDING} block, and an APE and an ID3v1 tag after the frames; chapters in
 *       {@code CHAPTERxxx} comments or, without any, the tracks of a {@code CUESHEET} block (each starting at
 *       its index point 1), which is kept while the chapters are unchanged and otherwise replaced by one
 *       built from them;</li>
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
 *       ones. A {@code timecode} key is stored as a free-form atom, which {@code Mp4Demuxer} reads as the
 *       timecode of a file without timecode track; the timecode track of a file is kept as it is, and only
 *       the MP4 muxers write one. QuickTime keyed metadata (a {@code meta} box of handler {@code mdta} in
 *       {@code moov} or its {@code udta}, where phones store the make, model, creation date and location under keys
 *       such as {@code com.apple.quicktime.location.ISO6709}) is read under its key names, numbers as decimal text,
 *       after the {@code ilst} items, which take precedence for a key both hold; a write stores a key in the
 *       boxes that held it, and a reverse-DNS key the file lacks in its first keyed metadata box or a new one,
 *       keeping each item's data type and locale. The QuickTime user data text atoms ({@code \u00A9xyz},
 *       {@code \u00A9mak}, ...) are read under their codes and rewritten into the {@code ilst}; the 3GPP location
 *       information box ({@code loci}) is read as the key {@code loci}, its coordinates in ISO 6709 form
 *       ({@code +37.3349-122.009+30/}), and rewritten keeping its place name, role, body and notes. The XMP
 *       packet is read from the top-level {@code uuid} box of XMP Specification Part 3 or a {@code udta/XMP_} atom
 *       and written back to where the file had it (a new one to a {@code uuid} box, or to {@code udta/XMP_} in a
 *       QuickTime movie);</li>
 *   <li>Matroska and WebM: global {@code Tags}, {@code Chapters} (every edition that is not hidden, with
 *       its nested chapters and localized titles) and {@code Attachments} (pictures, and the other files as
 *       {@linkplain MediaTags#attachments() attachments}); edition names raise the {@code DocTypeVersion} of
 *       a Matroska file to 5, the version of their {@code EditionDisplay} elements. A WebM file is
 *       written the chapters of the default edition alone, without edition flags or names and keeping only
 *       the localized titles in ISO 639-2 languages, and gains no attachment that is not a picture, as the
 *       WebM specification allows none of them;</li>
 *   <li>WAV (RIFF, big-endian RIFX, RF64 and BW64): the {@code LIST/INFO} chunk, an {@code id3 }
 *       chunk and the XMP packet of a {@code _PMX} chunk, a RIFF file the edit takes past 4 GiB becoming an RF64
 *       file; AIFF: the text chunks, an {@code ID3 } chunk and the XMP packet of an {@code APPL} chunk of signature
 *       {@code XMP }. A packet the file kept only in a {@code PRIV} frame of its ID3v2 chunk is read and written
 *       back there. The descriptive chunks below are read into their keys and rewritten from them;
 *       the ID3v2 chapters take precedence over the cue points and markers;</li>
 *   <li>AVI: the {@code LIST INFO} chunk, which stores no pictures or chapters;</li>
 *   <li>ASF (WMA, WMV): the Content Description, Extended Content Description, Metadata and Metadata Library
 *       Objects of the header, under the {@linkplain StandardTag#asfName() ASF attribute names}, pictures in
 *       {@code WM/Picture} attributes and chapters in the Marker Object (one level, without nesting); a rewrite
 *       pads the header to its old size when the new tags fit, and otherwise moves the data after it;</li>
 *   <li>MPEG-TS: track tags only (the program map table of the program {@code TsDemuxer} exposes),
 *       so a write holding tags, pictures or chapters for the whole file is rejected; every copy of
 *       the table is rewritten in its own transport packets, and an edit that outgrows them is
 *       rejected.</li>
 * </ul>
 *
 * <p>The WAV and AIFF chunks that are not text map to these keys and to the chapters:
 * <table class="striped">
 * <caption>Descriptive chunks of WAV and AIFF files</caption>
 * <thead><tr><th scope="col">Chunk</th><th scope="col">Keys or chapters</th></tr></thead>
 * <tbody>
 * <tr><td>WAV {@code bext} (EBU Tech 3285 version 2)</td><td>{@code description}, {@code originator},
 * {@code originator_reference}, {@code origination_date}, {@code origination_time}, {@code time_reference}
 * (samples since midnight), {@code umid} ({@code 0x} and the hexadecimal digits of 32 or 64 bytes),
 * {@code loudness_value}, {@code loudness_range}, {@code max_true_peak_level}, {@code max_momentary_loudness},
 * {@code max_short_term_loudness} (two decimals) and {@code coding_history}</td></tr>
 * <tr><td>WAV {@code iXML}, {@code axml}</td><td>{@code ixml}, {@code axml}: the XML text</td></tr>
 * <tr><td>WAV {@code chna} (ITU-R BS.2088)</td><td>{@code chna}: one line per audio identifier, its track
 * index, UID, track format and pack format references separated by spaces</td></tr>
 * <tr><td>WAV {@code smpl}</td><td>{@code smpl:manufacturer}, {@code smpl:product},
 * {@code smpl:sample_period}, {@code smpl:unity_note}, {@code smpl:pitch_fraction}, {@code smpl:smpte_format},
 * {@code smpl:smpte_offset} ({@code HH:MM:SS:FF}) and one {@code smpl:loop} per loop (cue point identifier,
 * type, start, end, fraction and play count)</td></tr>
 * <tr><td>WAV {@code cue } and {@code LIST adtl}</td><td>the chapters: one cue point each, titled by a
 * {@code labl} entry and lasting the length of an {@code ltxt} region (or until the next cue point)</td></tr>
 * <tr><td>AIFF {@code MARK}</td><td>the chapters: one marker each, named by its title, lasting until the next
 * one</td></tr>
 * <tr><td>AIFF {@code INST}</td><td>{@code inst:base_note}, {@code inst:detune}, {@code inst:low_note},
 * {@code inst:high_note}, {@code inst:low_velocity}, {@code inst:high_velocity}, {@code inst:gain} and
 * {@code inst:sustain_loop} and {@code inst:release_loop} (play mode and the sample positions of the two loop
 * markers, which are not chapters)</td></tr>
 * <tr><td>AIFF {@code COMT}</td><td>{@code comment} values, written back as {@code ANNO} chunks</td></tr>
 * </tbody>
 * </table>
 *
 * <p>A container that another artifact or an application adds through a
 * {@link com.github.auties00.smalt.spi.ContainerProvider ContainerProvider} is edited by that provider, when it
 * {@linkplain com.github.auties00.smalt.spi.ContainerProvider#editsTags() edits tags}, under the same contract: the
 * read, write and location-stripping methods below apply to its files, in place when its
 * {@linkplain com.github.auties00.smalt.spi.ContainerProvider#planTags plan} allows it.
 *
 * <p>In MP4, Matroska, AVI and MPEG-TS files the {@linkplain MediaTags#tracks() track tags} of every
 * track are read too, and a write replaces the name, tags and dispositions of each track the written
 * tags hold, keeping those of the other tracks; {@link TrackTags} lists how each container stores them.
 *
 * <p>{@link #write(Path, MediaTags, TagWriteOptions)} replaces every tag, picture and chapter of
 * the file with the given ones, so the usual cycle is read, modify, write. When the new metadata
 * fits the space the old one used (padding included), only the metadata bytes are rewritten in
 * place; otherwise the file is streamed to a temporary file with fresh padding, which then
 * replaces it. Items {@link MediaTags} cannot represent, such as binary ID3v2 frames or
 * Matroska attachments without a name or media type, are kept unless
 * {@link TagWriteOptions#preserveUnknown()} is off.
 *
 * <p>When a file holds tags of several formats, a read takes each key from the first format that
 * has it (in an MP3, ADTS, AC-3 or AMR file the ID3v2 tags, then the APE tag, then the ID3v1 tag; in a
 * FLAC file the Vorbis comments, then the APE and the ID3v1 tag) and the pictures from the first
 * that holds any. A write stores the new tags in every format the file keeps, as
 * {@link TagWriteOptions#apeMode()} and {@link TagWriteOptions#id3v1Mode()} decide; the APE and
 * ID3v1 tags store what their format can hold.
 *
 * <p>The class is stateless and thread-safe; editing the same file from several threads at once
 * is not.
 *
 * <p>{@link #stripLocation(Path)} removes where the media was made, as {@link MediaTags.Builder#removeLocation()}
 * describes, keeping everything else: the counterpart for audio and video of the photo metadata editor's location
 * stripping.
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
     * {@link ContainerFormat#AC3}, {@link ContainerFormat#AMR}, {@link ContainerFormat#FLAC},
     * {@link ContainerFormat#OGG}, {@link ContainerFormat#MP4}, {@link ContainerFormat#MATROSKA},
     * {@link ContainerFormat#WAV}, {@link ContainerFormat#AIFF}, {@link ContainerFormat#AVI},
     * {@link ContainerFormat#ASF} and {@link ContainerFormat#MPEG_TS}, and for a format whose
     * {@link com.github.auties00.smalt.spi.ContainerProvider ContainerProvider} edits its tags; {@code false} for
     * {@link ContainerFormat#OTHER}, which names no single container
     */
    public static boolean isSupported(ContainerFormat format) {
        Objects.requireNonNull(format, "format");
        return TagFiles.isSupported(format);
    }

    /**
     * Returns whether the tags of a container, named by its short name, can be read and written.
     *
     * @param name the {@link ContainerFormat#shortName()} of a format, or the
     * {@linkplain com.github.auties00.smalt.spi.ContainerProvider#name() name} of a container a provider adds
     * @return {@code true} when {@link #isSupported(ContainerFormat)} holds for the format of that name, or a
     * provider of that name edits the tags of its container; {@code false} for a name no container has
     */
    public static boolean isSupported(String name) {
        Objects.requireNonNull(name, "name");
        return TagFiles.isSupported(name);
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
     * Removes the records of where a file on disk was made with {@link TagWriteOptions#defaults()}.
     *
     * @param file the file, rewritten in place or replaced
     * @return how the file was changed; {@link TagWriteResult#UNCHANGED} when it recorded no location
     * @throws MediaException.Unsupported if the container is not supported
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if the metadata exceeds a format or resource limit
     * @throws MediaException.Io if reading or writing fails
     * @see #stripLocation(Path, TagWriteOptions)
     */
    public static TagWriteResult stripLocation(Path file) {
        return stripLocation(file, TagWriteOptions.defaults());
    }

    /**
     * Removes the records of where a file on disk was made.
     *
     * <p>The tags are read, stripped as {@link MediaTags.Builder#removeLocation()} describes (the location keys of
     * the file and of its tracks, such as {@code com.apple.quicktime.location.ISO6709}, {@code \u00A9xyz} and
     * {@code loci}, and the location properties of the XMP packet) and written back; nothing else changes, and a
     * file without a location is left as it is.
     *
     * @param file the file, rewritten in place or replaced
     * @param options how to write the tags
     * @return how the file was changed; {@link TagWriteResult#UNCHANGED} when it recorded no location
     * @throws MediaException.Unsupported if the container is not supported
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if the metadata exceeds a format or resource limit
     * @throws MediaException.Io if reading or writing fails
     */
    public static TagWriteResult stripLocation(Path file, TagWriteOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        var tags = read(file, TagReadOptions.builder().limits(options.limits()).build());
        if (!tags.hasLocation()) {
            return TagWriteResult.UNCHANGED;
        }
        return write(file, tags.toBuilder().removeLocation().build(), options);
    }

    /**
     * Returns a copy of an in-memory file without the records of where it was made, as
     * {@link #stripLocation(Path, TagWriteOptions)} writes it.
     *
     * @param file the original file
     * @param options how to write the tags
     * @return the edited file, or {@code file} itself when it records no location
     * @throws MediaException.Unsupported if the container is not supported
     * @throws MediaException.Malformed if the metadata structure is corrupt
     * @throws MediaException.LimitExceeded if the metadata exceeds a format or resource limit
     */
    public static byte[] stripLocation(byte[] file, TagWriteOptions options) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(options, "options");
        var tags = read(MediaSource.of(file), TagReadOptions.builder().limits(options.limits()).build());
        if (!tags.hasLocation()) {
            return file;
        }
        return write(file, tags.toBuilder().removeLocation().build(), options);
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
