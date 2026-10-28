package com.github.auties00.smalt.image.metadata;

import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * A set of disjoint, non-adjacent byte ranges of a file, kept sorted.
 *
 * <p>Instances are not thread-safe.
 */
final class TiffRanges {
    /**
     * The ranges, from their start to their exclusive end.
     */
    private final TreeMap<Long, Long> ranges = new TreeMap<>();

    /**
     * Adds a range, merging it with the ranges it overlaps or touches; an empty range is ignored.
     *
     * @param start the first byte
     * @param end the byte after the last one
     */
    void add(long start, long end) {
        if (end <= start) {
            return;
        }
        var before = ranges.floorEntry(start);
        if (before != null && before.getValue() >= start) {
            start = before.getKey();
            end = Math.max(end, before.getValue());
        }
        var next = ranges.ceilingEntry(start);
        while (next != null && next.getKey() <= end) {
            end = Math.max(end, next.getValue());
            ranges.remove(next.getKey());
            next = ranges.ceilingEntry(start);
        }
        ranges.put(start, end);
    }

    /**
     * Removes a range from the set.
     *
     * @param start the first byte
     * @param end the byte after the last one
     */
    void subtract(long start, long end) {
        if (end <= start || ranges.isEmpty()) {
            return;
        }
        var before = ranges.lowerEntry(start);
        if (before != null && before.getValue() > start) {
            ranges.put(before.getKey(), start);
            if (before.getValue() > end) {
                ranges.put(end, before.getValue());
                return;
            }
        }
        var next = ranges.ceilingEntry(start);
        while (next != null && next.getKey() < end) {
            ranges.remove(next.getKey());
            if (next.getValue() > end) {
                ranges.put(end, next.getValue());
                return;
            }
            next = ranges.ceilingEntry(start);
        }
    }

    /**
     * Returns whether a range lies entirely inside one range of the set.
     *
     * @param start the first byte
     * @param end the byte after the last one
     * @return {@code true} when the set covers the range
     */
    boolean covers(long start, long end) {
        var range = ranges.floorEntry(start);
        return range != null && range.getValue() >= end;
    }

    /**
     * {@return whether the set holds no range}
     */
    boolean isEmpty() {
        return ranges.isEmpty();
    }

    /**
     * {@return the last range, or {@code null} when the set is empty}
     */
    Map.Entry<Long, Long> last() {
        return ranges.lastEntry();
    }

    /**
     * {@return the ranges, from start to exclusive end, in ascending order; a live view}
     */
    NavigableMap<Long, Long> asMap() {
        return ranges;
    }
}
