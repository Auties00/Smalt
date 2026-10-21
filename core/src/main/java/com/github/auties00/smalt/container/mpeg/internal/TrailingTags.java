package com.github.auties00.smalt.container.mpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.internal.io.ArrayBacked;
import com.github.auties00.smalt.internal.tags.ApeTag;
import com.github.auties00.smalt.internal.tags.Id3v1Tag;
import com.github.auties00.smalt.internal.tags.Id3v2Tag;
import com.github.auties00.smalt.internal.io.SegmentBacked;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Finds the metadata tags that follow the audio payload of a file, and the end of that payload.
 *
 * <p>MPEG audio, ADTS, raw AC-3 and FLAC files are often followed by one or more of: an ID3v1 tag
 * (128 bytes starting with {@code "TAG"}), optionally preceded by an enhanced {@code "TAG+"} block
 * of 227 bytes; an APEv1/APEv2 tag, recognized by its 32-byte {@code "APETAGEX"} footer; a
 * Lyrics3v2 block ending in a six-digit size and {@code "LYRICS200"}; a Lyrics3v1 block from
 * {@code "LYRICSBEGIN"} to {@code "LYRICSEND"} right before an ID3v1 tag (or its enhanced block);
 * and an appended ID3v2.4 tag ending in a {@code "3DI"} footer and starting with a matching
 * header. Tags can be stacked in any order, so they are peeled off repeatedly until none is found.
 * The 32-byte header of an APE tag counts only when the footer flags it and it is present.
 *
 * <p>The probes of each round are served by a {@link Tail}, which reads the last
 * {@value #TAIL_SIZE} bytes before the current end of the region in one source read and reads
 * again only when a round's probes fall outside them, so a file whose tags fit in that window is
 * probed with a single read instead of one read per probe.
 */
public final class TrailingTags {
    /**
     * The largest number of tags peeled off, which bounds the work on crafted input.
     */
    private static final int MAX_TAGS = 16;

    /**
     * The number of bytes a {@link Tail} reads before the end of the region it serves.
     *
     * @implNote This implementation reads 4 KiB: every probe of a round lies within the last 355 bytes (an enhanced
     * ID3v1 tag) except the {@code LYRICSBEGIN} check of a Lyrics3 block and the header check of an appended ID3v2
     * tag, so the window covers a whole round, and the rounds after small tags such as ID3v1, short APE tags and
     * Lyrics3v2 blocks, while costing a network source no more transfer time than the round trip each probe would
     * otherwise need.
     */
    static final int TAIL_SIZE = 4096;

    /**
     * The number of bytes before the end of a region that every probe of a round reads within,
     * except the {@code LYRICSBEGIN} and appended ID3v2 header checks: an enhanced ID3v1 tag, 128
     * plus 227 bytes.
     */
    private static final int PROBE_SPAN = 128 + 227;

    /**
     * The largest lyrics text of a Lyrics3v1 block, in bytes.
     */
    private static final int MAX_LYRICS3V1_TEXT = 5100;

    /**
     * The largest tag body decoded into demuxer metadata when pictures are not collected.
     */
    private static final int MAX_PARSED_TAG = 1 << 20;

    /**
     * The largest tag body decoded into demuxer metadata when pictures are collected.
     */
    private static final int MAX_PARSED_PICTURE_TAG = 64 << 20;

    private TrailingTags() {
        throw new AssertionError();
    }

    /**
     * Names the kinds of tag that can follow the audio.
     */
    public enum Kind {
        /**
         * A 128-byte ID3v1 or ID3v1.1 tag.
         */
        ID3V1,

        /**
         * The 227-byte enhanced {@code "TAG+"} block that precedes an ID3v1 tag.
         */
        ID3V1_ENHANCED,

        /**
         * An APEv1 or APEv2 tag, header included when it has one.
         */
        APE,

        /**
         * A Lyrics3v1 or Lyrics3v2 block.
         */
        LYRICS3,

        /**
         * An ID3v2.4 tag with a footer, appended after the audio.
         */
        ID3V2
    }

    /**
     * Locates one trailing tag.
     *
     * @param kind the kind of tag
     * @param offset the offset of its first byte
     * @param length its size in bytes, at least one
     */
    public record Region(Kind kind, long offset, long length) {
        /**
         * {@return the offset of the first byte after the tag}
         */
        public long end() {
            return offset + length;
        }
    }

    /**
     * Returns the offset where the trailing tags of a region begin.
     *
     * @param source the source to read
     * @param start the offset of the first audio byte; tags are never assumed to extend before it
     * @param end the offset of the end of the region, usually the source size
     * @return the offset of the first tag byte, or {@code end} when the region has no trailing tag
     */
    public static long audioEnd(MediaSource source, long start, long end) {
        return audioEnd(new Tail(source, start), start, end);
    }

    /**
     * Returns the offset where the trailing tags of a region begin, reading through a tail
     * window that may already hold the end of the region.
     *
     * @param tail the tail window over the source; its lower bound must not exceed {@code start}
     * @param start the offset of the first audio byte; tags are never assumed to extend before it
     * @param end the offset of the end of the region, usually the source size
     * @return the offset of the first tag byte, or {@code end} when the region has no trailing tag
     */
    public static long audioEnd(Tail tail, long start, long end) {
        var regions = regions(tail, start, end);
        return regions.isEmpty() ? end : regions.getFirst().offset();
    }

    /**
     * Returns the trailing tags of a region.
     *
     * @param source the source to read
     * @param start the offset of the first audio byte; tags are never assumed to extend before it
     * @param end the offset of the end of the region, usually the source size
     * @return the tags in file order, contiguous and ending at {@code end}; empty when there is none
     */
    public static List<Region> regions(MediaSource source, long start, long end) {
        return regions(new Tail(source, start), start, end);
    }

    /**
     * Returns the trailing tags of a region, reading through a tail window that may already hold
     * the end of the region.
     *
     * @param tail the tail window over the source; its lower bound must not exceed {@code start}
     * @param start the offset of the first audio byte; tags are never assumed to extend before it
     * @param end the offset of the end of the region, usually the source size
     * @return the tags in file order, contiguous and ending at {@code end}; empty when there is none
     */
    public static List<Region> regions(Tail tail, long start, long end) {
        var buffer = new byte[128];
        var found = new ArrayList<Region>(4);
        Kind later = null;
        for (var round = 0; round < MAX_TAGS; round++) {
            tail.load(end);
            var before = found.size();
            peel(tail, start, end, buffer, later, found);
            if (found.size() == before) {
                break;
            }
            var last = found.getLast();
            end = last.offset();
            later = last.kind();
        }
        Collections.reverse(found);
        return found;
    }

    /**
     * Adds the tags that end at {@code end}, nearest first: an ID3v1 tag adds itself and the
     * enhanced block before it, any other tag one region.
     *
     * @param tail the tail window over the source
     * @param start the lowest offset a tag may start at
     * @param end the offset the tag must end at
     * @param buffer a scratch buffer of at least 128 bytes
     * @param later the kind of the tag that follows {@code end}, or {@code null} at the end of the region
     * @param found receives the tags
     */
    private static void peel(Tail tail, long start, long end, byte[] buffer, Kind later, List<Region> found) {
        var available = end - start;
        if (available >= 128 && tail.read(end - 128, buffer, 3) && startsWith(buffer, "TAG")) {
            found.add(new Region(Kind.ID3V1, end - 128, 128));
            if (available >= 128 + 227 && tail.read(end - 128 - 227, buffer, 4) && startsWith(buffer, "TAG+")) {
                found.add(new Region(Kind.ID3V1_ENHANCED, end - 128 - 227, 227));
            }
            return;
        }
        if (available >= 32 && tail.read(end - 32, buffer, 32) && startsWith(buffer, "APETAGEX")) {
            var size = le32(buffer, 12);
            var flags = le32(buffer, 20);
            if (size < 32 || size > available) {
                return;
            }
            var total = size;
            // the header counts only when it is there: a footer flagging an absent header must not cut into the audio
            if ((flags & 0x80000000L) != 0 && size + 32 <= available) {
                // the block the next round needs also holds the header
                tail.load(end - size);
                if (tail.read(end - size - 32, buffer, 8) && startsWith(buffer, "APETAGEX")) {
                    total += 32;
                }
            }
            found.add(new Region(Kind.APE, end - total, total));
            return;
        }
        if (available >= 15 && tail.read(end - 15, buffer, 15)
                && new String(buffer, 6, 9, StandardCharsets.ISO_8859_1).equals("LYRICS200")) {
            var size = 0L;
            for (var i = 0; i < 6; i++) {
                var digit = buffer[i] - '0';
                if (digit < 0 || digit > 9) {
                    return;
                }
                size = size * 10 + digit;
            }
            var total = size + 15;
            if (total <= available && size >= 11 && tail.read(end - total, buffer, 11)
                    && new String(buffer, 0, 11, StandardCharsets.ISO_8859_1).equals("LYRICSBEGIN")) {
                found.add(new Region(Kind.LYRICS3, end - total, total));
            }
            return;
        }
        if ((later == Kind.ID3V1 || later == Kind.ID3V1_ENHANCED) && available >= 20 && tail.read(end - 9, buffer, 9)
                && new String(buffer, 0, 9, StandardCharsets.ISO_8859_1).equals("LYRICSEND")) {
            var begin = lyrics3v1Begin(tail, start, end);
            if (begin >= 0) {
                found.add(new Region(Kind.LYRICS3, begin, end - begin));
            }
            return;
        }
        if (available >= 20 && tail.read(end - 10, buffer, 10) && buffer[0] == '3' && buffer[1] == 'D'
                && buffer[2] == 'I' && buffer[3] == 4 && (buffer[5] & 0x10) != 0) {
            buffer[0] = 'I';
            buffer[1] = 'D';
            buffer[2] = '3';
            var total = Id3v2Tag.size(buffer, 0);
            var version = buffer[3];
            var flags = buffer[5];
            if (total > 20 && total <= available && tail.read(end - total, buffer, 10) && buffer[0] == 'I'
                    && buffer[1] == 'D' && buffer[2] == '3' && buffer[3] == version && buffer[5] == flags
                    && Id3v2Tag.size(buffer, 0) == total) {
                found.add(new Region(Kind.ID3V2, end - total, total));
            }
        }
    }

    /**
     * Finds the {@code LYRICSBEGIN} marker of a Lyrics3v1 block whose {@code LYRICSEND} marker
     * ends at {@code end}.
     *
     * @param tail the tail window over the source
     * @param start the lowest offset the block may start at
     * @param end the offset of the end of the {@code LYRICSEND} marker
     * @return the offset of the marker nearest to {@code end}, or {@code -1} when there is none
     */
    private static long lyrics3v1Begin(Tail tail, long start, long end) {
        var from = Math.max(start, end - 9 - MAX_LYRICS3V1_TEXT - 11);
        var length = (int) (end - 9 - from);
        if (length < 11) {
            return -1;
        }
        var window = new byte[length];
        if (!tail.read(from, window, length)) {
            return -1;
        }
        for (var index = length - 11; index >= 0; index--) {
            if (window[index] == 'L'
                    && new String(window, index, 11, StandardCharsets.ISO_8859_1).equals("LYRICSBEGIN")) {
                return from + index;
            }
        }
        return -1;
    }

    /**
     * Returns the ID3v2 tags that a chain of {@code SEEK} frames leads to inside the audio.
     *
     * <p>A target counts only when a valid ID3v2 header starts there and the whole tag lies
     * between {@code start} and {@code end}; the chain also ends at a target already found, at a
     * tag too large to decode or corrupt, and after {@value #MAX_TAGS} tags.
     *
     * @param tail the tail window over the source
     * @param target the offset the {@code SEEK} frame of a leading tag points at, or {@code -1}
     * @param start the offset of the first audio byte
     * @param end the offset where the trailing tags start
     * @return the tags, in chain order, each of kind {@link Kind#ID3V2}
     */
    public static List<Region> seekRegions(Tail tail, long target, long start, long end) {
        var found = new ArrayList<Region>(1);
        var header = new byte[Id3v2Tag.HEADER_SIZE];
        // TODO: search forward from a target that holds no tag; ID3v2.4 makes the SEEK offset a minimum
        while (target >= start && found.size() < MAX_TAGS && end - target >= Id3v2Tag.HEADER_SIZE) {
            for (var known : found) {
                if (known.offset() == target) {
                    return found;
                }
            }
            if (!tail.read(target, header, header.length)) {
                break;
            }
            var size = Id3v2Tag.size(header, 0);
            if (size < 0 || size > end - target || size > MAX_PARSED_PICTURE_TAG) {
                break;
            }
            var bytes = new byte[(int) size];
            if (!tail.read(target, bytes, bytes.length)) {
                break;
            }
            Id3v2Tag tag;
            try {
                tag = Id3v2Tag.parse(bytes, MediaLimits.DEFAULT, id -> id.equals("SEEK"));
            } catch (MediaException ignored) {
                // a corrupt tag ends the chain; the tags found before it stay usable
                break;
            }
            found.add(new Region(Kind.ID3V2, target, size));
            var offset = tag.seekOffset();
            if (offset < 0) {
                break;
            }
            target += size + offset;
        }
        return found;
    }

    /**
     * Decodes the tags after the leading ID3v2 tags into the metadata a demuxer reports.
     *
     * <p>The ID3v2 tags come first, those a {@code SEEK} frame located and then those appended
     * after the audio, each combined with what was collected before it as {@code merge} says.
     * The APE tag and then the ID3v1 tag follow, adding only the keys {@code metadata} lacks.
     * ID3v2 keys are the version 2.3 frame identifiers, APE keys the item keys with the standard
     * ones normalized, and ID3v1 keys the ID3v2 frame identifiers. The APE cover art goes to
     * {@code annotations} only when it holds no picture yet. Tags too large to decode, or
     * corrupt, are skipped.
     *
     * @param tail the tail window over the source
     * @param located the tags {@link #seekRegions(Tail, long, long, long)} found, in chain order
     * @param regions the trailing tags, as {@link #regions(Tail, long, long)} returns them
     * @param metadata receives the fields
     * @param annotations receives the pictures and chapters, or {@code null} to skip them
     * @param merge how a located or appended ID3v2 tag combines with the tags before it:
     * {@link Id3v2.Merge#REPLACE} when the file's main tags are ID3v2, {@link Id3v2.Merge#FILL} otherwise
     */
    public static void readMetadata(Tail tail, List<Region> located, List<Region> regions,
                                    Map<String, String> metadata, Annotations annotations, Id3v2.Merge merge) {
        var limit = annotations != null ? MAX_PARSED_PICTURE_TAG : MAX_PARSED_TAG;
        for (var region : located) {
            var bytes = readRegion(tail, region, limit);
            if (bytes != null) {
                Id3v2.decode(bytes, metadata, annotations, merge);
            }
        }
        for (var region : regions) {
            if (region.kind() == Kind.ID3V2) {
                var bytes = readRegion(tail, region, limit);
                if (bytes != null) {
                    Id3v2.decode(bytes, metadata, annotations, merge);
                }
            }
        }
        for (var region : regions) {
            if (region.kind() == Kind.APE) {
                var bytes = readRegion(tail, region, annotations != null ? MAX_PARSED_PICTURE_TAG : MAX_PARSED_TAG);
                if (bytes != null) {
                    readApe(bytes, metadata, annotations);
                }
                break;
            }
        }
        for (var index = regions.size() - 1; index >= 0; index--) {
            var region = regions.get(index);
            if (region.kind() == Kind.ID3V1) {
                var tag = new byte[Id3v1Tag.SIZE];
                if (tail.read(region.offset(), tag, tag.length)) {
                    for (var entry : Id3v1Tag.fields(tag, 0).entrySet()) {
                        metadata.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                }
                break;
            }
        }
    }

    /**
     * Decodes an APE tag into demuxer metadata.
     *
     * @param bytes the tag
     * @param metadata receives the fields not present yet
     * @param annotations receives the pictures when it holds none, or {@code null}
     */
    private static void readApe(byte[] bytes, Map<String, String> metadata, Annotations annotations) {
        ApeTag tag;
        try {
            tag = ApeTag.parse(bytes);
        } catch (MediaException ignored) {
            // a corrupt tag is skipped; the audio before it stays readable
            return;
        }
        for (var entry : tag.fields().entrySet()) {
            metadata.putIfAbsent(entry.getKey(), entry.getValue());
        }
        if (annotations != null && annotations.pictures().isEmpty()) {
            tag.decode().pictures().forEach(annotations::addPicture);
        }
    }

    /**
     * Reads a trailing tag when it is small enough to decode.
     *
     * @param tail the tail window over the source
     * @param region the tag
     * @param maxBytes the largest tag read
     * @return the bytes, or {@code null} when the tag is too large or the source ends first
     */
    private static byte[] readRegion(Tail tail, Region region, int maxBytes) {
        if (region.length() > maxBytes) {
            return null;
        }
        var bytes = new byte[(int) region.length()];
        return tail.read(region.offset(), bytes, bytes.length) ? bytes : null;
    }

    /**
     * Returns whether the buffer starts with the ASCII characters of {@code magic}.
     *
     * @param buffer the bytes
     * @param magic the expected prefix
     * @return {@code true} on a match
     */
    private static boolean startsWith(byte[] buffer, String magic) {
        for (var i = 0; i < magic.length(); i++) {
            if (buffer[i] != magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads an unsigned little-endian 32-bit value.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @return the value
     */
    private static long le32(byte[] data, int offset) {
        return (data[offset] & 0xFFL) | (data[offset + 1] & 0xFFL) << 8 | (data[offset + 2] & 0xFFL) << 16
                | (data[offset + 3] & 0xFFL) << 24;
    }

    /**
     * Serves small reads near the end of a region from one block read there.
     *
     * <p>{@link #load(long)} reads the {@value #TAIL_SIZE} bytes before an end offset, never below
     * the lower bound given at construction, in one sequence of source reads;
     * {@link #read(long, byte[], int)} then answers exactly as reading the source directly would,
     * serving from the block the reads that lie inside it and reading the source for any other.
     * When the source ends inside the block, every read reaching past that end fails, as it
     * would on the source. An in-memory source ({@link ArrayBacked} or {@link SegmentBacked}) is
     * read directly, without a block.
     */
    public static final class Tail {
        /**
         * The source read.
         */
        private final MediaSource source;

        /**
         * The lowest offset the block may start at.
         */
        private final long floor;

        /**
         * Whether the source is in memory, in which case no block is loaded.
         */
        private final boolean direct;

        /**
         * The block, allocated by the first {@link #load(long)}.
         */
        private byte[] block;

        /**
         * The source offset of {@code block[0]}.
         */
        private long blockStart;

        /**
         * The number of bytes requested for the block.
         */
        private int blockSize;

        /**
         * The number of bytes of the block the source delivered, less than {@link #blockSize}
         * only when the source ended inside the block.
         */
        private int blockLength;

        /**
         * Constructs a window with no block loaded.
         *
         * @param source the source to read
         * @param floor the lowest offset the block may start at
         * @throws NullPointerException if {@code source} is {@code null}
         */
        public Tail(MediaSource source, long floor) {
            this.source = Objects.requireNonNull(source, "source");
            this.floor = floor;
            this.direct = SegmentBacked.isInMemory(source);
        }

        /**
         * Reads the block ending at {@code end}, unless the loaded block already holds the bytes
         * that the probes of a round ending there read.
         *
         * @param end the offset the block ends at
         */
        public void load(long end) {
            var start = Math.max(floor, end - TAIL_SIZE);
            if (direct || start < 0 || end <= start) {
                return;
            }
            if (block != null && Math.max(floor, end - PROBE_SPAN) >= blockStart && end <= blockStart + blockSize) {
                return;
            }
            if (block == null) {
                block = new byte[TAIL_SIZE];
            }
            blockStart = start;
            blockSize = (int) (end - start);
            var done = 0;
            while (done < blockSize) {
                var count = source.read(start + done, block, done, blockSize - done);
                if (count <= 0) {
                    break;
                }
                done += count;
            }
            blockLength = done;
        }

        /**
         * Reads exactly {@code length} bytes, reporting a short source instead of failing.
         *
         * @param position the offset of the first byte
         * @param target the destination
         * @param length the number of bytes
         * @return {@code true} when every byte was read
         */
        public boolean read(long position, byte[] target, int length) {
            if (position < 0) {
                return false;
            }
            if (block != null && position >= blockStart && position + length <= blockStart + blockSize) {
                if (position + length > blockStart + blockLength) {
                    return false;
                }
                System.arraycopy(block, (int) (position - blockStart), target, 0, length);
                return true;
            }
            var done = 0;
            while (done < length) {
                var count = source.read(position + done, target, done, length - done);
                if (count <= 0) {
                    return false;
                }
                done += count;
            }
            return true;
        }
    }
}
