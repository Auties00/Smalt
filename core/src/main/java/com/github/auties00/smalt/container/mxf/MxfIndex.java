package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.MediaException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The index table of one essence container: the stream offset, temporal offset, key frame offset and flags of every
 * edit unit, merged from its index table segments (SMPTE ST 377-1 clause 11).
 *
 * <p>Edit units are counted from the first edit unit of the essence container. Segments that repeat a range already
 * read (a body index repeated in the footer) are ignored. A segment of constant bytes per edit unit stands for every
 * edit unit it covers, and with an index duration of zero for every edit unit from its start on.
 */
final class MxfIndex {
    /**
     * The flag of an index entry whose edit unit is a random access point.
     */
    static final int RANDOM_ACCESS = 0x80;

    /**
     * The stream offset of every indexed edit unit of variable size, or {@code -1} where no segment indexes it.
     */
    private long[] offsets = new long[0];

    /**
     * The temporal offset of every indexed edit unit.
     */
    private byte[] temporal = new byte[0];

    /**
     * The key frame offset of every indexed edit unit.
     */
    private byte[] keyOffsets = new byte[0];

    /**
     * The flags of every indexed edit unit.
     */
    private byte[] flags = new byte[0];

    /**
     * The number of edit units the entries cover, from zero.
     */
    private int count;

    /**
     * The segments of constant bytes per edit unit: start position, duration (0 to the end) and byte count.
     */
    private final List<long[]> constant = new ArrayList<>();

    /**
     * Whether any segment carried an entry with a temporal offset other than zero.
     */
    private boolean reordered;

    /**
     * Adds one index table segment.
     *
     * @param segment the parsed segment
     * @param maxEntries the largest edit unit count accepted
     * @throws MediaException.Malformed if the entry array is inconsistent
     * @throws MediaException.LimitExceeded if the segment indexes more than {@code maxEntries} edit units
     */
    void add(MxfSet segment, long maxEntries) {
        var start = segment.s64(0x3F0C, 0);
        var duration = segment.s64(0x3F0D, 0);
        var byteCount = segment.u32(0x3F05, 0);
        if (start < 0 || duration < 0) {
            throw new MediaException.Malformed("MXF index table segment with negative start or duration");
        }
        var entries = segment.get(0x3F0A);
        if (byteCount > 0 && (entries == null || entries.length < 8 || MxfSet.u32(entries, 0) == 0)) {
            for (var existing : constant) {
                if (existing[0] == start) {
                    return;
                }
            }
            constant.add(new long[]{start, duration, byteCount});
            return;
        }
        if (entries == null || entries.length < 8) {
            return;
        }
        var number = MxfSet.u32(entries, 0);
        var size = MxfSet.u32(entries, 4);
        if (size < 11 || number > (entries.length - 8) / size) {
            throw new MediaException.Malformed("invalid MXF index entry array");
        }
        if (start + number > maxEntries) {
            throw new MediaException.LimitExceeded("MXF index of more than " + maxEntries + " edit units");
        }
        pending.add(new Object[]{start, number, size, entries});
    }

    /**
     * The segments of entries added and not merged yet: start, entry count, entry size and entry array.
     */
    private final List<Object[]> pending = new ArrayList<>();

    /**
     * Merges the added segments in start order, keeping only those that continue or overlap the edit units already
     * covered, so that the entries held never outnumber those the segments carry.
     */
    private void merge() {
        if (pending.isEmpty()) {
            return;
        }
        pending.sort((first, second) -> Long.compare((long) first[0], (long) second[0]));
        for (var segment : pending) {
            var start = (long) segment[0];
            if (start <= count) {
                insert(start, (long) segment[1], (long) segment[2], (byte[]) segment[3]);
            }
        }
        pending.clear();
    }

    /**
     * Copies the entries of a segment that starts within the edit units covered.
     */
    private void insert(long start, long number, long size, byte[] entries) {
        var end = (int) (start + number);
        ensure(end);
        for (var index = 0; index < number; index++) {
            var at = 8 + (int) (index * size);
            var unit = (int) start + index;
            if (offsets[unit] >= 0) {
                continue;
            }
            temporal[unit] = entries[at];
            keyOffsets[unit] = entries[at + 1];
            flags[unit] = entries[at + 2];
            offsets[unit] = MxfSet.s64(entries, at + 3);
            reordered |= entries[at] != 0;
        }
        count = Math.max(count, end);
    }

    private void ensure(int end) {
        if (end <= offsets.length) {
            return;
        }
        var capacity = Math.max(end, Math.min(Integer.MAX_VALUE - 8, 2L * offsets.length));
        var old = offsets.length;
        offsets = Arrays.copyOf(offsets, (int) capacity);
        Arrays.fill(offsets, old, offsets.length, -1);
        temporal = Arrays.copyOf(temporal, (int) capacity);
        keyOffsets = Arrays.copyOf(keyOffsets, (int) capacity);
        flags = Arrays.copyOf(flags, (int) capacity);
    }

    /**
     * {@return whether the index holds no segment}
     */
    boolean isEmpty() {
        merge();
        return count == 0 && constant.isEmpty();
    }

    /**
     * {@return the number of edit units of variable size the entries cover}
     */
    int entryCount() {
        merge();
        return count;
    }

    /**
     * {@return whether an entry is flagged as a picture predicted from a later one (a B picture), whatever the
     * temporal offsets say}
     */
    boolean hasBidirectional() {
        merge();
        for (var unit = 0; unit < count; unit++) {
            if (offsets[unit] >= 0 && (flags[unit] & 0x30) == 0x30) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@return whether an entry has a nonzero temporal offset, so that stored order differs from display order}
     */
    boolean isReordered() {
        merge();
        return reordered;
    }

    /**
     * Returns the stream offset of an edit unit.
     *
     * @param unit the edit unit, from zero
     * @return the offset in the essence container stream, or {@code -1} when the index does not cover the edit unit
     */
    long offset(long unit) {
        merge();
        if (unit < 0) {
            return -1;
        }
        if (unit < count && offsets[(int) unit] >= 0) {
            return offsets[(int) unit];
        }
        for (var segment : constant) {
            if (unit >= segment[0] && (segment[1] == 0 || unit < segment[0] + segment[1])) {
                var base = 0L;
                for (var other : constant) {
                    if (other[0] < segment[0] && other[1] > 0) {
                        base += other[1] * other[2];
                    }
                }
                return base + (unit - segment[0]) * segment[2];
            }
        }
        return -1;
    }

    /**
     * {@return the constant byte count of the edit unit, or {@code 0} when the edit unit has an entry of its own or
     * no segment covers it}
     *
     * @param unit the edit unit, from zero
     */
    long constantSize(long unit) {
        merge();
        if (unit >= 0 && unit < count && offsets[(int) unit] >= 0) {
            return 0;
        }
        for (var segment : constant) {
            if (unit >= segment[0] && (segment[1] == 0 || unit < segment[0] + segment[1])) {
                return segment[2];
            }
        }
        return 0;
    }

    /**
     * {@return the temporal offset of an entry: the stored edit unit {@code unit + offset} is displayed at
     * {@code unit}; zero without an entry}
     *
     * @param unit the edit unit, from zero
     */
    int temporalOffset(long unit) {
        merge();
        return unit >= 0 && unit < count && offsets[(int) unit] >= 0 ? temporal[(int) unit] : 0;
    }

    /**
     * {@return the key frame offset of an entry: the stored edit unit {@code unit + offset} is the random access
     * point decoding of {@code unit} starts from; zero without an entry}
     *
     * @param unit the edit unit, from zero
     */
    int keyFrameOffset(long unit) {
        merge();
        return unit >= 0 && unit < count && offsets[(int) unit] >= 0 ? keyOffsets[(int) unit] : 0;
    }

    /**
     * {@return the flags of an entry, {@link #RANDOM_ACCESS} for an edit unit without one}
     *
     * @param unit the edit unit, from zero
     */
    int flags(long unit) {
        merge();
        return unit >= 0 && unit < count && offsets[(int) unit] >= 0 ? flags[(int) unit] & 0xFF : RANDOM_ACCESS;
    }

    /**
     * Returns the display position of every stored edit unit the entries cover.
     *
     * <p>The entry of display position {@code n} names the stored edit unit {@code n + temporalOffset(n)}; a stored
     * edit unit no entry names keeps its own position.
     *
     * @return the display positions, indexed by stored edit unit
     */
    long[] displayPositions() {
        merge();
        var out = new long[count];
        for (var unit = 0; unit < count; unit++) {
            out[unit] = unit;
        }
        for (var unit = 0; unit < count; unit++) {
            if (offsets[unit] < 0) {
                continue;
            }
            var stored = unit + temporal[unit];
            if (stored >= 0 && stored < count) {
                out[stored] = unit;
            }
        }
        return out;
    }
}
