package com.github.auties00.smalt.video.vp9;

/**
 * Enumerates the trades between compression and encoding time of a {@link Vp9Encoder}, slowest
 * first.
 *
 * <p>Each speed is the real-time speed of libvpx's VP9 encoder with the same number, its
 * {@code cpu-used} control {@code -N}: every speed codes the same stream format, and faster speeds
 * test fewer partitions, modes and motion vectors, so they spend more bits for the same quality.
 */
public enum Vp9EncoderSpeed {
    /**
     * The slowest speed, with the fullest partition and mode search.
     */
    SPEED_5(5),

    /**
     * The speed of real-time calls, which {@link Vp9EncoderOptions#forRealtime()} uses.
     */
    SPEED_6(6),

    /**
     * The libvpx real-time speed 7.
     */
    SPEED_7(7),

    /**
     * The libvpx real-time speed 8.
     */
    SPEED_8(8),

    /**
     * The fastest speed.
     */
    SPEED_9(9);

    /**
     * The libvpx {@code cpu-used} magnitude of the speed.
     */
    private final int level;

    /**
     * Creates a speed.
     *
     * @param level the libvpx speed
     */
    Vp9EncoderSpeed(int level) {
        this.level = level;
    }

    /**
     * Returns the speed of a libvpx speed number.
     *
     * @param level the libvpx real-time speed, the magnitude of {@code cpu-used}, from 5 to 9
     * @return the speed
     * @throws IllegalArgumentException if {@code level} is outside 5 to 9
     */
    public static Vp9EncoderSpeed of(int level) {
        if (level < 5 || level > 9) {
            throw new IllegalArgumentException("speed must be between 5 and 9, got " + level);
        }
        return values()[level - 5];
    }

    /**
     * Returns the libvpx real-time speed number, the magnitude of the {@code cpu-used} control.
     *
     * @return the speed number, from 5 to 9
     */
    public int level() {
        return level;
    }
}
