package com.github.auties00.smalt.audio.loudness;

/**
 * Holds one stretch of silence found by a {@link SilenceDetector}.
 *
 * @param startFrame the index of the first silent sample (per channel)
 * @param endFrame   the index after the last silent sample: the first sound, or the end of the
 *                   audio
 * @param sampleRate the sampling rate the indexes count in, in hertz
 */
public record SilenceRange(long startFrame, long endFrame, int sampleRate) {
    /**
     * Validates the range.
     *
     * @throws IllegalArgumentException if the range is reversed or negative, or the rate is not
     *                                  positive
     */
    public SilenceRange {
        if (startFrame < 0 || endFrame < startFrame || sampleRate <= 0) {
            throw new IllegalArgumentException("invalid silence range " + startFrame + ".." + endFrame + " at " + sampleRate + " Hz");
        }
    }

    /**
     * Returns when the silence starts.
     *
     * @return the start in microseconds, rounded down
     */
    public long startMicros() {
        return startFrame * 1_000_000L / sampleRate;
    }

    /**
     * Returns when the silence ends.
     *
     * @return the end in microseconds, rounded down
     */
    public long endMicros() {
        return endFrame * 1_000_000L / sampleRate;
    }

    /**
     * Returns how long the silence lasts.
     *
     * @return the duration in microseconds, rounded down
     */
    public long durationMicros() {
        return (endFrame - startFrame) * 1_000_000L / sampleRate;
    }
}
