package com.github.auties00.smalt.container.internal;

import java.util.Arrays;

/**
 * Maps timestamps to the byte positions of the frames that start at them, for elementary streams
 * whose frames carry no index of their own.
 *
 * <p>Entries are appended in increasing timestamp order; each holds a timestamp, a byte offset and
 * two integers of format-specific scan state (for example the reference header an MPEG audio
 * cursor needs to resume). {@link #floor(long)} finds the last entry at or before a timestamp.
 * An instance is not thread-safe.
 */
public final class SeekIndex {
    /**
     * The timestamps, in increasing order.
     */
    private long[] timestamps;

    /**
     * The byte offsets.
     */
    private long[] offsets;

    /**
     * The first word of scan state.
     */
    private int[] first;

    /**
     * The second word of scan state.
     */
    private int[] second;

    /**
     * The number of entries.
     */
    private int size;

    /**
     * Creates an empty index.
     */
    public SeekIndex() {
        this.timestamps = new long[64];
        this.offsets = new long[64];
        this.first = new int[64];
        this.second = new int[64];
    }

    /**
     * Appends an entry.
     *
     * @param timestamp the timestamp of the frame, not below the last entry's
     * @param offset    the byte offset of the frame
     * @param state     the first word of scan state
     * @param extra     the second word of scan state
     */
    public void add(long timestamp, long offset, int state, int extra) {
        if (size == timestamps.length) {
            var capacity = size * 2;
            timestamps = Arrays.copyOf(timestamps, capacity);
            offsets = Arrays.copyOf(offsets, capacity);
            first = Arrays.copyOf(first, capacity);
            second = Arrays.copyOf(second, capacity);
        }
        timestamps[size] = timestamp;
        offsets[size] = offset;
        first[size] = state;
        second[size] = extra;
        size++;
    }

    /**
     * Returns the number of entries.
     *
     * @return the size
     */
    public int size() {
        return size;
    }

    /**
     * Returns the index of the last entry whose timestamp is at or before a time.
     *
     * @param timestamp the time
     * @return the entry index, or {@code -1} when every entry is later or the index is empty
     */
    public int floor(long timestamp) {
        var low = 0;
        var high = size - 1;
        var found = -1;
        while (low <= high) {
            var middle = (low + high) >>> 1;
            if (timestamps[middle] <= timestamp) {
                found = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return found;
    }

    /**
     * Returns the timestamp of an entry.
     *
     * @param index the entry index
     * @return the timestamp
     */
    public long timestamp(int index) {
        return timestamps[index];
    }

    /**
     * Returns the byte offset of an entry.
     *
     * @param index the entry index
     * @return the offset
     */
    public long offset(int index) {
        return offsets[index];
    }

    /**
     * Returns the first word of scan state of an entry.
     *
     * @param index the entry index
     * @return the state
     */
    public int state(int index) {
        return first[index];
    }

    /**
     * Returns the second word of scan state of an entry.
     *
     * @param index the entry index
     * @return the state
     */
    public int extra(int index) {
        return second[index];
    }
}
