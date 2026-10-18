package com.github.auties00.smalt.transcode;

import java.util.OptionalDouble;

/**
 * Reports how far a transcode has got.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param positionMicros the output time written so far, in microseconds
 * @param durationMicros the expected output duration in microseconds, or {@code -1} when the input
 *                       declares none
 */
public record TranscodeProgress(long positionMicros, long durationMicros) {
    /**
     * Returns the completed fraction.
     *
     * @return the position divided by the duration, clamped to {@code [0, 1]}, or empty when the
     *         duration is unknown
     */
    public OptionalDouble fraction() {
        if (durationMicros <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(Math.clamp((double) positionMicros / durationMicros, 0.0, 1.0));
    }
}
