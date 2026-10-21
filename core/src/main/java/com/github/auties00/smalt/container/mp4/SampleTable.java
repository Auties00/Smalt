package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;

import com.github.auties00.smalt.internal.limits.LimitChecks;
import java.util.Arrays;

/**
 * Stores the expanded sample index of one track: where each sample lies in the file, how large
 * it is, and when it is decoded and presented.
 *
 * <p>Samples are appended in decoding order, either all at once from the sample table boxes of a
 * {@code moov} or run by run from the {@code trun} boxes of movie fragments. The arrays grow
 * geometrically; every growth is checked against the {@link MediaLimits} the table was created
 * with, so a hostile sample count raises {@link MediaException.LimitExceeded} before the
 * allocation. Fields are package-private and read directly by the demuxer's packet loop.
 */
final class SampleTable {
    /**
     * The flag bit marking a sync sample.
     */
    static final byte KEYFRAME = 1;

    /**
     * The flag bit marking a sample that is decoded but not presented, which the edit list
     * expansion of {@link Mp4Track} sets on decoding pre-roll.
     */
    static final byte DISCARD = 2;

    /**
     * The number of bytes the table stores per sample, used for allocation checks.
     */
    private static final int BYTES_PER_SAMPLE = 8 + 4 + 8 + 4 + 4 + 1 + 2;

    /**
     * The limits bounding the number of samples.
     */
    private final MediaLimits limits;

    /**
     * The absolute file offset of each sample.
     */
    long[] offsets;

    /**
     * The size of each sample in bytes.
     */
    int[] sizes;

    /**
     * The decoding timestamp of each sample, in media timescale units.
     */
    long[] dts;

    /**
     * The duration of each sample, as an unsigned 32-bit value in media timescale units.
     */
    int[] durations;

    /**
     * The composition offset of each sample, added to the decoding timestamp to obtain the
     * presentation timestamp.
     */
    int[] ctsOffsets;

    /**
     * The flags of each sample; bit {@link #KEYFRAME} marks sync samples.
     */
    byte[] flags;

    /**
     * The zero-based sample description index of each sample, or {@code null} while every sample
     * uses the first description.
     */
    short[] descriptions;

    /**
     * The number of samples stored.
     */
    int count;

    /**
     * Constructs an empty table.
     *
     * @param limits the limits bounding the sample count
     * @param capacity the initial capacity; non-negative
     * @throws MediaException.LimitExceeded if {@code capacity} exceeds the limits
     */
    SampleTable(MediaLimits limits, int capacity) {
        this.limits = limits;
        checkCapacity(capacity);
        this.offsets = new long[capacity];
        this.sizes = new int[capacity];
        this.dts = new long[capacity];
        this.durations = new int[capacity];
        this.ctsOffsets = new int[capacity];
        this.flags = new byte[capacity];
    }

    /**
     * Checks that a table of the given capacity is within the limits.
     *
     * @param capacity the number of samples
     * @throws MediaException.LimitExceeded if the count or the memory it needs exceeds the limits
     */
    private void checkCapacity(long capacity) {
        LimitChecks.checkFrames(limits, capacity);
        LimitChecks.checkAllocation(limits, capacity * 8);
        if (capacity * BYTES_PER_SAMPLE > limits.maxBytes()) {
            throw new MediaException.LimitExceeded("sample table of " + capacity + " samples exceeds the "
                    + limits.maxBytes() + " byte limit");
        }
    }

    /**
     * Ensures room for {@code extra} more samples.
     *
     * @param extra the number of samples about to be appended
     * @throws MediaException.LimitExceeded if the resulting count exceeds the limits
     */
    void ensureCapacity(long extra) {
        var needed = count + extra;
        if (needed <= offsets.length) {
            return;
        }
        checkCapacity(needed);
        var capacity = (int) Math.min(Math.max(needed, (long) offsets.length * 2), Math.max(needed, limits.maxFrames()));
        offsets = Arrays.copyOf(offsets, capacity);
        sizes = Arrays.copyOf(sizes, capacity);
        dts = Arrays.copyOf(dts, capacity);
        durations = Arrays.copyOf(durations, capacity);
        ctsOffsets = Arrays.copyOf(ctsOffsets, capacity);
        flags = Arrays.copyOf(flags, capacity);
        if (descriptions != null) {
            descriptions = Arrays.copyOf(descriptions, capacity);
        }
    }

    /**
     * Appends one sample.
     *
     * @param offset the absolute file offset
     * @param size the size in bytes
     * @param dts the decoding timestamp
     * @param duration the duration as an unsigned 32-bit value
     * @param ctsOffset the composition offset
     * @param keyframe whether the sample is a sync sample
     * @throws MediaException.LimitExceeded if the table is full
     */
    void add(long offset, int size, long dts, int duration, int ctsOffset, boolean keyframe) {
        if (count == offsets.length) {
            ensureCapacity(1);
        }
        var index = count++;
        offsets[index] = offset;
        sizes[index] = size;
        this.dts[index] = dts;
        durations[index] = duration;
        ctsOffsets[index] = ctsOffset;
        flags[index] = keyframe ? KEYFRAME : 0;
    }

    /**
     * Records the sample description of a sample.
     *
     * @param index the sample index, below the capacity
     * @param description the zero-based description index, below {@value Short#MAX_VALUE}
     */
    void setDescription(int index, int description) {
        if (descriptions == null) {
            if (description == 0) {
                return;
            }
            descriptions = new short[offsets.length];
        }
        descriptions[index] = (short) description;
    }

    /**
     * Returns the sample description of a sample.
     *
     * @param index the sample index
     * @return the zero-based description index
     */
    int description(int index) {
        return descriptions == null ? 0 : descriptions[index];
    }

    /**
     * Returns whether a sample is decoded but not presented.
     *
     * @param index the sample index
     * @return {@code true} for a decoding pre-roll sample
     */
    boolean isDiscarded(int index) {
        return (flags[index] & DISCARD) != 0;
    }

    /**
     * Returns whether a sample is a sync sample.
     *
     * @param index the sample index
     * @return {@code true} for a sync sample
     */
    boolean isKeyframe(int index) {
        return (flags[index] & KEYFRAME) != 0;
    }

    /**
     * Returns the presentation timestamp of a sample in media timescale units, before any edit
     * list shift.
     *
     * @param index the sample index
     * @return the presentation timestamp
     */
    long pts(int index) {
        return dts[index] + ctsOffsets[index];
    }

    /**
     * Returns the sum of all sample durations.
     *
     * @return the total duration in media timescale units
     */
    long totalDuration() {
        var total = 0L;
        for (var index = 0; index < count; index++) {
            total += Integer.toUnsignedLong(durations[index]);
        }
        return total;
    }
}
