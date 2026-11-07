package com.github.auties00.smalt.image.ico;

/**
 * Describes one displayed step of an animated cursor: which stored frame it shows and for how long.
 *
 * <p>Display times are in jiffies, sixtieths of a second, the unit the {@code anih} and {@code rate}
 * chunks store.
 *
 * @param frame the index of the stored frame shown, from zero
 * @param jiffies the display time in sixtieths of a second, from 0 to {@value #MAX_JIFFIES}
 */
public record AniStep(int frame, long jiffies) {
    /**
     * The longest display time a step stores, in jiffies: the largest unsigned 32-bit value.
     */
    public static final long MAX_JIFFIES = 0xFFFF_FFFFL;

    /**
     * Validates the step.
     *
     * @throws IllegalArgumentException if {@code frame} is negative or {@code jiffies} is outside 0 to
     * {@value #MAX_JIFFIES}
     */
    public AniStep {
        if (frame < 0) {
            throw new IllegalArgumentException("negative frame index " + frame);
        }
        if (jiffies < 0 || jiffies > MAX_JIFFIES) {
            throw new IllegalArgumentException("display time " + jiffies + " is outside 0.." + MAX_JIFFIES);
        }
    }

    /**
     * {@return the display time in microseconds, rounded down}
     */
    public long durationMicros() {
        return jiffies * 1_000_000 / 60;
    }
}
