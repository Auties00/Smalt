package com.github.auties00.smalt.container.mpeg.internal;

import com.github.auties00.smalt.internal.io.ArrayBacked;
import com.github.auties00.smalt.internal.tags.Id3v2Tag;
import com.github.auties00.smalt.internal.io.SegmentBacked;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Finds the end of the audio payload of a file that may carry metadata tags after it.
 *
 * <p>MPEG audio, ADTS and FLAC files are often followed by one or more of: an ID3v1 tag (128
 * bytes starting with {@code "TAG"}), optionally preceded by an enhanced {@code "TAG+"} block of
 * 227 bytes; an APEv1/APEv2 tag, recognized by its 32-byte {@code "APETAGEX"} footer; a Lyrics3v2
 * block ending in a six-digit size and {@code "LYRICS200"}; and an appended ID3v2.4 tag ending in
 * a {@code "3DI"} footer. Tags can be stacked in any order, so they are peeled off repeatedly
 * until none is found.
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
     * ID3v1 tag) except the {@code LYRICSBEGIN} check of a Lyrics3v2 block, so the window covers a whole round, and the
     * rounds after small tags such as ID3v1, short APE tags and Lyrics3v2 blocks, while costing a network source no
     * more transfer time than the round trip each probe would otherwise need.
     */
    static final int TAIL_SIZE = 4096;

    /**
     * The number of bytes before the end of a region that every probe of a round reads within,
     * except the {@code LYRICSBEGIN} check: an enhanced ID3v1 tag, 128 plus 227 bytes.
     */
    private static final int PROBE_SPAN = 128 + 227;
    private TrailingTags() {
        throw new AssertionError();
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
        var buffer = new byte[128];
        for (var round = 0; round < MAX_TAGS; round++) {
            tail.load(end);
            var tag = tagBefore(tail, start, end, buffer);
            if (tag <= 0) {
                return end;
            }
            end -= tag;
        }
        return end;
    }

    /**
     * Returns the size of the tag ending at {@code end}.
     *
     * @param tail the tail window over the source
     * @param start the lowest offset a tag may start at
     * @param end the offset the tag must end at
     * @param buffer a scratch buffer of at least 128 bytes
     * @return the tag size in bytes, or {@code 0} when no tag ends there
     */
    private static long tagBefore(Tail tail, long start, long end, byte[] buffer) {
        var available = end - start;
        if (available >= 128 && tail.read(end - 128, buffer, 3) && startsWith(buffer, "TAG")) {
            var size = 128L;
            if (available >= 128 + 227 && tail.read(end - 128 - 227, buffer, 4) && startsWith(buffer, "TAG+")) {
                size += 227;
            }
            return size;
        }
        if (available >= 32 && tail.read(end - 32, buffer, 32) && startsWith(buffer, "APETAGEX")) {
            var size = le32(buffer, 12);
            var flags = le32(buffer, 20);
            var total = size + ((flags & 0x80000000L) != 0 ? 32 : 0);
            if (size >= 32 && total <= available) {
                return total;
            }
            return 0;
        }
        if (available >= 15 && tail.read(end - 15, buffer, 15)
                && new String(buffer, 6, 9, StandardCharsets.ISO_8859_1).equals("LYRICS200")) {
            var size = 0L;
            for (var i = 0; i < 6; i++) {
                var digit = buffer[i] - '0';
                if (digit < 0 || digit > 9) {
                    return 0;
                }
                size = size * 10 + digit;
            }
            var total = size + 15;
            if (total <= available && size >= 11 && tail.read(end - total, buffer, 11)
                    && new String(buffer, 0, 11, StandardCharsets.ISO_8859_1).equals("LYRICSBEGIN")) {
                return total;
            }
            return 0;
        }
        if (available >= 20 && tail.read(end - 10, buffer, 10) && buffer[0] == '3' && buffer[1] == 'D' && buffer[2] == 'I') {
            buffer[0] = 'I';
            buffer[1] = 'D';
            buffer[2] = '3';
            var total = Id3v2Tag.size(buffer, 0);
            if (total > 0 && total <= available) {
                return total;
            }
        }
        return 0;
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
