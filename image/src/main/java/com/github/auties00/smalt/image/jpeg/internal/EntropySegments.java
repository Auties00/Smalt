package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extracts the entropy-coded data of one scan, removing byte stuffing and splitting it at markers.
 *
 * <p>Entropy-coded data follows the SOS header and ends at the first marker that is neither a
 * restart marker nor a stuffed {@code FF 00}. A stuffed zero is removed, fill bytes ({@code FF}
 * repeated before a marker) are dropped, and every marker inside the data splits it into
 * <em>segments</em>: restart markers {@code RSTn} and invalid marker codes below {@code C0} end a
 * segment and start the next, while any other marker ends the scan. Each segment records the
 * marker that terminated it, so the entropy decoder can apply libjpeg's restart resynchronization
 * rules without looking at the input again. The segments are stored back to back in one array,
 * starting after an all-zero prefix of {@link #EMPTY_LENGTH} bytes that serves as the data of an
 * empty segment.
 *
 * <p>An instance is reused across the scans of one image; {@link #load} replaces its contents.
 *
 * <p>A scan that is still going after its first {@link #MIN_CHUNK} bytes has the rest of the input
 * split into chunks extracted on several threads. Every chunk boundary follows a byte other than
 * {@code 0xFF}, so no stuffed pair, run of fill bytes or marker straddles two chunks, and each
 * chunk keeps and drops exactly the bytes a single pass would. A first pass counts each chunk's
 * kept bytes and finds its markers; the chunks are then laid out back to back, up to the one
 * holding the marker that ends the scan, and a second pass copies them into place.
 *
 * @implNote This implementation copies eight bytes at a time, finding {@code 0xFF} bytes with a
 * SWAR zero-byte test on the complement of each word, and copies the last eight output bytes of a
 * chunk one at a time so that no word store reaches the next chunk's output. It extracts the first
 * {@link #MIN_CHUNK} bytes on the calling thread, so a small scan does not pay for the threads.
 */
public final class EntropySegments {
    /*
     * The bytes a word copies past a 0xFF byte are overwritten by what follows, and the buffer
     * keeps room for them. The input given to load runs to the end of the stream, far past a scan
     * of a progressive image, so counting stops in every chunk after the first one found to end
     * the scan.
     */

    /**
     * The marker code recorded for a segment that ends at the end of the input.
     */
    public static final int END_OF_INPUT = -1;

    /**
     * The length of the zero prefix of {@link #data()}, which is the data of an empty segment.
     */
    public static final int EMPTY_LENGTH = 8;

    /**
     * Reads and writes a little-endian {@code long} of a byte array at any offset.
     */
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * A {@code long} with every byte equal to {@code 0x01}.
     */
    private static final long ONES = 0x0101010101010101L;

    /**
     * A {@code long} with the high bit of every byte set.
     */
    private static final long HIGHS = 0x8080808080808080L;

    /**
     * The number of input bytes extracted on the calling thread before the rest of a scan is split
     * into chunks, and the smallest chunk.
     */
    private static final int MIN_CHUNK = 256 * 1024;

    /**
     * The limits bounding the buffers of this instance.
     */
    private final MediaLimits limits;

    /**
     * The unstuffed data of all segments, after the zero prefix.
     */
    private byte[] data;

    /**
     * The index in {@link #data} of the first byte of each segment.
     */
    private int[] starts;

    /**
     * The index in {@link #data} one past the last byte of each segment.
     */
    private int[] ends;

    /**
     * The marker code that terminated each segment, or {@link #END_OF_INPUT}.
     */
    private int[] markers;

    /**
     * The number of segments.
     */
    private int count;

    /**
     * The input index of the marker that ended the scan, or the input length when the input ended
     * first.
     */
    private int scanEnd;

    /**
     * The index in {@link #data} one past the last byte extracted so far.
     */
    private int extracted;

    /**
     * The index in {@link #data} of the first byte of the segment being extracted.
     */
    private int segmentStart;

    /**
     * Constructs an empty instance.
     *
     * @param limits the limits bounding the buffers
     */
    public EntropySegments(MediaLimits limits) {
        this.limits = limits;
        this.data = new byte[EMPTY_LENGTH + 64];
        this.starts = new int[8];
        this.ends = new int[8];
        this.markers = new int[8];
    }

    /**
     * Extracts the entropy-coded data starting at {@code from}.
     *
     * @param input the whole JPEG stream
     * @param from the index of the first entropy-coded byte, just after the SOS header
     * @param to the index one past the last byte of the stream
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the extracted data needs a
     * buffer beyond the limits
     */
    public void load(byte[] input, int from, int to) {
        var needed = (long) EMPTY_LENGTH + (to - from) + 16;
        if (data.length < needed) {
            LimitChecks.checkAllocation(limits, needed);
            data = new byte[(int) needed];
        } else {
            Arrays.fill(data, 0, EMPTY_LENGTH, (byte) 0);
        }
        count = 0;
        extracted = EMPTY_LENGTH;
        segmentStart = EMPTY_LENGTH;
        if (ParallelBands.bands((to - from) / MIN_CHUNK, to - from) <= 1) {
            extract(input, from, to, to);
            return;
        }
        var probeEnd = boundary(input, from + MIN_CHUNK, to);
        if (!extract(input, from, probeEnd, to)) {
            extractChunks(input, probeEnd, to);
        }
    }

    /**
     * Moves an input index forward past any bytes that follow a {@code 0xFF}, so that it never
     * falls inside a stuffed pair, a run of fill bytes or a marker.
     *
     * @param input the whole JPEG stream
     * @param index the candidate index, greater than zero
     * @param to the index one past the last byte of the stream
     * @return the first index at or after {@code index} that follows a byte other than
     * {@code 0xFF}, or {@code to}
     */
    private static int boundary(byte[] input, int index, int to) {
        var at = Math.min(index, to);
        while (at < to && input[at - 1] == (byte) 0xFF) {
            at++;
        }
        return at;
    }

    /**
     * Extracts input bytes on the calling thread, continuing the segment being extracted.
     *
     * @param input the whole JPEG stream
     * @param from the index of the first byte to extract, which follows a byte other than
     * {@code 0xFF} or is the first entropy-coded byte
     * @param stop the index to stop at when the scan has not ended before it, which follows a byte
     * other than {@code 0xFF} or equals {@code to}
     * @param to the index one past the last byte of the stream
     * @return {@code true} when the scan ended, {@code false} when extraction stopped at
     * {@code stop} with the scan still going
     */
    private boolean extract(byte[] input, int from, int stop, int to) {
        var data = this.data;
        var out = extracted;
        var segmentStart = this.segmentStart;
        var i = from;
        while (true) {
            while (i + 8 <= stop) {
                var word = (long) LONG_LE.get(input, i);
                var inverted = ~word;
                var found = (inverted - ONES) & ~inverted & HIGHS;
                LONG_LE.set(data, out, word);
                if (found != 0) {
                    var run = Long.numberOfTrailingZeros(found) >>> 3;
                    i += run;
                    out += run;
                    break;
                }
                i += 8;
                out += 8;
            }
            while (i < stop && input[i] != (byte) 0xFF) {
                data[out++] = input[i++];
            }
            if (i >= stop && stop < to) {
                extracted = out;
                this.segmentStart = segmentStart;
                return false;
            }
            if (i + 1 >= to) {
                addSegment(segmentStart, out, END_OF_INPUT);
                scanEnd = to;
                return true;
            }
            var next = input[i + 1] & 0xFF;
            if (next == 0x00) {
                data[out++] = (byte) 0xFF;
                i += 2;
            } else if (next == 0xFF) {
                i++;
            } else if (next < 0xC0 || (next >= 0xD0 && next <= 0xD7)) {
                addSegment(segmentStart, out, next);
                segmentStart = out;
                i += 2;
            } else {
                addSegment(segmentStart, out, next);
                scanEnd = i;
                return true;
            }
        }
    }

    /**
     * Extracts the rest of a scan in chunks on several threads, continuing the segment being
     * extracted.
     *
     * @param input the whole JPEG stream
     * @param from the index of the first byte to extract, which follows a byte other than
     * {@code 0xFF}
     * @param to the index one past the last byte of the stream
     */
    private void extractChunks(byte[] input, int from, int to) {
        var remaining = to - from;
        var chunkCount = ParallelBands.bands(Math.max(1, remaining / MIN_CHUNK), remaining);
        if (chunkCount <= 1) {
            extract(input, from, to, to);
            return;
        }
        var chunks = new Chunk[chunkCount];
        var chunkStart = from;
        for (var k = 0; k < chunkCount; k++) {
            var chunkEnd = k + 1 == chunkCount
                    ? to
                    : boundary(input, from + ParallelBands.start(k + 1, chunkCount, remaining), to);
            chunks[k] = new Chunk(k, chunkStart, chunkEnd);
            chunkStart = chunkEnd;
        }
        var firstTerminal = new AtomicInteger(chunkCount);
        ParallelBands.run(chunkCount, k -> chunks[k].count(input, to, firstTerminal));
        var out = extracted;
        var segmentStart = this.segmentStart;
        var used = chunkCount;
        for (var k = 0; k < chunkCount; k++) {
            var chunk = chunks[k];
            chunk.base = out;
            for (var m = 0; m < chunk.splits; m++) {
                addSegment(segmentStart, out + chunk.splitOffsets[m], chunk.splitMarkers[m]);
                segmentStart = out + chunk.splitOffsets[m];
            }
            out += chunk.kept;
            if (chunk.terminal) {
                addSegment(segmentStart, out, chunk.endMarker);
                scanEnd = chunk.endIndex;
                used = k + 1;
                break;
            }
        }
        var data = this.data;
        ParallelBands.run(used, k -> chunks[k].copy(input, data));
    }

    /**
     * Records a segment, mapping an empty one onto the zero prefix.
     *
     * @param start the index of the segment's first byte
     * @param end the index one past the segment's last byte
     * @param marker the terminating marker code
     */
    private void addSegment(int start, int end, int marker) {
        if (count == starts.length) {
            var grown = (long) count * 2;
            LimitChecks.checkAllocation(limits, grown * 12);
            starts = Arrays.copyOf(starts, (int) grown);
            ends = Arrays.copyOf(ends, (int) grown);
            markers = Arrays.copyOf(markers, (int) grown);
        }
        if (start == end) {
            start = 0;
            end = 0;
        }
        starts[count] = start;
        ends[count] = end;
        markers[count] = marker;
        count++;
    }

    /**
     * Returns the array holding every segment.
     *
     * <p>Bytes past the end of a segment belong to the next segment; readers must not interpret
     * them as data of the current one.
     *
     * @return the backing array, not a copy
     */
    public byte[] data() {
        return data;
    }

    /**
     * Returns the number of segments.
     *
     * @return the count, at least one after {@link #load}
     */
    public int count() {
        return count;
    }

    /**
     * Returns the index of the first byte of a segment.
     *
     * @param segment the segment index
     * @return the start index in {@link #data()}
     */
    public int start(int segment) {
        return starts[segment];
    }

    /**
     * Returns the index one past the last byte of a segment.
     *
     * @param segment the segment index
     * @return the end index in {@link #data()}
     */
    public int end(int segment) {
        return ends[segment];
    }

    /**
     * Returns the marker code that terminated a segment.
     *
     * @param segment the segment index
     * @return the marker code ({@code 0xD0} to {@code 0xD7} for a restart marker), or
     * {@link #END_OF_INPUT}
     */
    public int marker(int segment) {
        return markers[segment];
    }

    /**
     * Returns where the scan ended in the input.
     *
     * @return the index of the {@code 0xFF} of the marker that ended the scan, or the input length
     */
    public int scanEnd() {
        return scanEnd;
    }

    /**
     * Finds the segment an entropy decoder continues with at a restart boundary.
     *
     * <p>The decoder has just finished a restart interval and the next unread marker is the one
     * that terminated segment {@code segment}. When that marker is the expected restart marker,
     * or one too far away to be a missing marker, it is consumed and decoding continues with the
     * next segment. When it precedes the expected one (a duplicate, or an invalid marker), it is
     * skipped together with the data after it, and the next marker is examined. When it is one or
     * two restart numbers ahead, or not a restart marker at all, it is left unread and the next
     * interval decodes as an empty segment.
     *
     * @implNote This implementation follows libjpeg's {@code jpeg_resync_to_restart}
     * (jdmarker.c); the end of the input counts as an EOI marker.
     * @param segment the segment whose terminating marker is the next unread marker
     * @param desired the expected restart number, from 0 to 7
     * @return the index of the segment to decode next, or {@code -1 - segment} when the next
     * interval is empty and the marker of {@code segment} stays unread
     */
    public int resync(int segment, int desired) {
        var s = segment;
        while (true) {
            var marker = markers[s] == END_OF_INPUT ? 0xD9 : markers[s];
            int action;
            if (marker < 0xC0) {
                action = 2;
            } else if (marker < 0xD0 || marker > 0xD7) {
                action = 3;
            } else if (marker == 0xD0 + ((desired + 1) & 7) || marker == 0xD0 + ((desired + 2) & 7)) {
                action = 3;
            } else if (marker == 0xD0 + ((desired - 1) & 7) || marker == 0xD0 + ((desired - 2) & 7)) {
                action = 2;
            } else {
                action = 1;
            }
            if (action == 3 || s + 1 >= count) {
                return -1 - s;
            }
            s++;
            if (action == 1) {
                return s;
            }
        }
    }

    /**
     * One chunk of the input, extracted on its own: its bounds, and what counting it found.
     */
    private static final class Chunk {
        /**
         * The position of the chunk among the chunks of the scan.
         */
        private final int index;

        /**
         * The input index of the chunk's first byte.
         */
        private final int start;

        /**
         * The input index one past the chunk's last byte.
         */
        private final int end;

        /**
         * The number of bytes the chunk keeps, up to the marker that ends the scan if the chunk
         * holds it.
         */
        private int kept;

        /**
         * The number of restart or invalid markers the chunk holds, each of which ends a segment.
         */
        private int splits;

        /**
         * The number of bytes the chunk keeps before each of its splitting markers.
         */
        private int[] splitOffsets;

        /**
         * The code of each splitting marker.
         */
        private int[] splitMarkers;

        /**
         * Whether the scan ends inside the chunk, at a marker or at the end of the input.
         */
        private boolean terminal;

        /**
         * The code of the marker that ends the scan inside the chunk, or {@link #END_OF_INPUT}.
         */
        private int endMarker;

        /**
         * The input index of the {@code 0xFF} of the marker that ends the scan, or the input
         * length when the input ends first.
         */
        private int endIndex;

        /**
         * The index in the extracted data of the chunk's first kept byte, once the chunks are
         * laid out.
         */
        private int base;

        /**
         * Constructs a chunk.
         *
         * @param index the position of the chunk among the chunks of the scan
         * @param start the input index of the chunk's first byte
         * @param end the input index one past the chunk's last byte
         */
        Chunk(int index, int start, int end) {
            this.index = index;
            this.start = start;
            this.end = end;
            this.splitOffsets = new int[4];
            this.splitMarkers = new int[4];
        }

        /**
         * Counts the bytes the chunk keeps and records its markers, following the rules of a
         * single pass over the whole input.
         *
         * <p>Counting gives up early once an earlier chunk is known to end the scan, since this
         * chunk's bytes then belong to no segment.
         *
         * @param input the whole JPEG stream
         * @param to the index one past the last byte of the stream
         * @param firstTerminal the smallest position of a chunk known to end the scan, lowered by
         * this chunk if it ends the scan
         */
        void count(byte[] input, int to, AtomicInteger firstTerminal) {
            var end = this.end;
            var out = 0;
            var i = start;
            while (true) {
                while (i + 8 <= end) {
                    var inverted = ~(long) LONG_LE.get(input, i);
                    var found = (inverted - ONES) & ~inverted & HIGHS;
                    if (found != 0) {
                        var run = Long.numberOfTrailingZeros(found) >>> 3;
                        i += run;
                        out += run;
                        break;
                    }
                    i += 8;
                    out += 8;
                }
                while (i < end && input[i] != (byte) 0xFF) {
                    out++;
                    i++;
                }
                if (i >= end) {
                    kept = out;
                    if (end == to) {
                        terminate(END_OF_INPUT, to, firstTerminal);
                    }
                    return;
                }
                if (i + 1 >= to) {
                    kept = out;
                    terminate(END_OF_INPUT, to, firstTerminal);
                    return;
                }
                if (firstTerminal.get() < index) {
                    return;
                }
                var next = input[i + 1] & 0xFF;
                if (next == 0x00) {
                    out++;
                    i += 2;
                } else if (next == 0xFF) {
                    i++;
                } else if (next < 0xC0 || (next >= 0xD0 && next <= 0xD7)) {
                    split(out, next);
                    i += 2;
                } else {
                    kept = out;
                    terminate(next, i, firstTerminal);
                    return;
                }
            }
        }

        /**
         * Copies the bytes the chunk keeps to its place in the extracted data, once
         * {@link #count} has run and {@link #base} is set.
         *
         * @param input the whole JPEG stream
         * @param data the extracted data
         */
        void copy(byte[] input, byte[] data) {
            var end = this.end;
            var out = base;
            var wordLimit = base + kept - 8;
            var i = start;
            while (true) {
                while (i + 8 <= end && out <= wordLimit) {
                    var word = (long) LONG_LE.get(input, i);
                    var inverted = ~word;
                    var found = (inverted - ONES) & ~inverted & HIGHS;
                    LONG_LE.set(data, out, word);
                    if (found != 0) {
                        var run = Long.numberOfTrailingZeros(found) >>> 3;
                        i += run;
                        out += run;
                        break;
                    }
                    i += 8;
                    out += 8;
                }
                while (i < end && input[i] != (byte) 0xFF) {
                    data[out++] = input[i++];
                }
                if (i + 1 >= end) {
                    return;
                }
                var next = input[i + 1] & 0xFF;
                if (next == 0x00) {
                    data[out++] = (byte) 0xFF;
                    i += 2;
                } else if (next == 0xFF) {
                    i++;
                } else if (next < 0xC0 || (next >= 0xD0 && next <= 0xD7)) {
                    i += 2;
                } else {
                    return;
                }
            }
        }

        /**
         * Records that the scan ends inside the chunk.
         *
         * @param marker the code of the marker that ends the scan, or {@link #END_OF_INPUT}
         * @param at the input index of the marker's {@code 0xFF}, or the input length
         * @param firstTerminal the smallest position of a chunk known to end the scan
         */
        private void terminate(int marker, int at, AtomicInteger firstTerminal) {
            terminal = true;
            endMarker = marker;
            endIndex = at;
            firstTerminal.accumulateAndGet(index, Math::min);
        }

        /**
         * Records a restart or invalid marker.
         *
         * @param offset the number of bytes the chunk keeps before the marker
         * @param marker the marker code
         */
        private void split(int offset, int marker) {
            if (splits == splitOffsets.length) {
                splitOffsets = Arrays.copyOf(splitOffsets, splits * 2);
                splitMarkers = Arrays.copyOf(splitMarkers, splits * 2);
            }
            splitOffsets[splits] = offset;
            splitMarkers[splits] = marker;
            splits++;
        }
    }
}
