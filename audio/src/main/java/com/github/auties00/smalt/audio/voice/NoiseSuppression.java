package com.github.auties00.smalt.audio.voice;

/**
 * Selects how strongly a {@link VoiceProcessor} suppresses stationary background noise.
 *
 * <p>Each level caps the attenuation the noise suppressor applies to a frequency bin dominated
 * by noise; higher levels also over-estimate the noise and correct the overall gain for the
 * energy the filter removed.
 */
public enum NoiseSuppression {
    /**
     * Disables noise suppression.
     */
    OFF(-1),

    /**
     * Suppresses noise by at most 6 dB.
     */
    LOW(0),

    /**
     * Suppresses noise by at most 12 dB.
     */
    MODERATE(1),

    /**
     * Suppresses noise by at most 18 dB, the level calls use.
     */
    HIGH(2),

    /**
     * Suppresses noise by at most about 21 dB.
     */
    VERY_HIGH(3);

    /**
     * The index of the level in the suppressor's tuning table, or {@code -1} for {@link #OFF}.
     */
    private final int level;

    /**
     * Creates a constant.
     *
     * @param level the index of the level in the suppressor's tuning table
     */
    NoiseSuppression(int level) {
        this.level = level;
    }

    /**
     * Returns the index of the level in the suppressor's tuning table.
     *
     * @return the index, or {@code -1} for {@link #OFF}
     */
    int level() {
        return level;
    }
}
