package com.github.auties00.smalt.container.ts;

/**
 * Stamps the units of an audio stream with timestamps in samples of the stream's rate, from the PES times of
 * the packets they start in.
 *
 * <p>PES times are converted to samples as an offset from the first PES time of the stream, so that a unit
 * gets the same timestamp whether the read started at the beginning of the file or after a seek. A unit
 * without a PES time of its own follows the previous unit; a PES time within half a unit of where the
 * previous unit ends is taken as that end, so the jitter of 90 kHz rounding does not reach the packets, and a
 * farther one (lost units, a splice) moves the timeline. Units before the first PES time are not stamped.
 * Instances are not thread-safe.
 */
final class SampleClock {
    /**
     * The time at which the previous unit ends, in samples, or {@link TsClock#NONE}.
     */
    private long next = TsClock.NONE;

    /**
     * The first PES time of the stream, in absolute 90 kHz ticks (before the clock's origin is subtracted),
     * or {@link TsClock#NONE} before the first.
     */
    private long baseTicks = TsClock.NONE;

    /**
     * Copies the first PES time of another clock, which a fresh read pass shares with the probe.
     *
     * @param other the clock of the configured parser
     */
    void inherit(SampleClock other) {
        baseTicks = other.baseTicks;
    }

    /**
     * Stamps one unit.
     *
     * @param anchor the PES time of the unit in 90 kHz ticks relative to {@code origin}, or
     * {@link TsClock#NONE} when it follows the previous unit
     * @param origin the origin of the timeline in absolute 90 kHz ticks
     * @param rate the sampling rate of the timestamps
     * @param samples the duration of the unit in samples
     * @return the timestamp in samples, or {@link TsClock#NONE} when the unit precedes the first PES time
     */
    long stamp(long anchor, long origin, int rate, int samples) {
        var timestamp = next;
        if (anchor != TsClock.NONE) {
            if (baseTicks == TsClock.NONE) {
                baseTicks = anchor + origin;
            }
            var base = baseTicks - origin;
            var anchored = Math.floorDiv(base * rate + 45_000, 90_000)
                    + Math.floorDiv((anchor - base) * rate + 45_000, 90_000);
            if (next == TsClock.NONE || Math.abs(anchored - next) > samples / 2) {
                timestamp = anchored;
            }
        }
        if (timestamp == TsClock.NONE) {
            return TsClock.NONE;
        }
        next = timestamp + samples;
        return timestamp;
    }
}
