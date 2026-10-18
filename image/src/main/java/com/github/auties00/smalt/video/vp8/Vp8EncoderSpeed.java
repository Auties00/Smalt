package com.github.auties00.smalt.video.vp8;

/**
 * Enumerates the trades between compression and encoding time of a {@link Vp8Encoder}, slowest
 * first.
 *
 * <p>Each speed is the real-time speed of libvpx's VP8 encoder with the same number, its
 * {@code cpu-used} control {@code -N}: every speed codes the same stream format, and faster speeds
 * test fewer modes and motion vectors, so they spend more bits for the same quality. From
 * {@link #SPEED_14} on, the loop filter is the simple filter.
 */
public enum Vp8EncoderSpeed {
    /**
     * The slowest speed: a full loop filter level search and the diamond motion search.
     */
    SPEED_4(4),

    /**
     * The libvpx real-time speed 5.
     */
    SPEED_5(5),

    /**
     * The speed of real-time calls, which {@link Vp8EncoderOptions#forRealtime()} uses.
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
     * The libvpx real-time speed 9.
     */
    SPEED_9(9),

    /**
     * The libvpx real-time speed 10.
     */
    SPEED_10(10),

    /**
     * The libvpx real-time speed 11.
     */
    SPEED_11(11),

    /**
     * The libvpx real-time speed 12.
     */
    SPEED_12(12),

    /**
     * The libvpx real-time speed 13.
     */
    SPEED_13(13),

    /**
     * The first speed that codes with the simple loop filter.
     */
    SPEED_14(14),

    /**
     * The libvpx real-time speed 15.
     */
    SPEED_15(15),

    /**
     * The fastest speed.
     */
    SPEED_16(16);

    /**
     * The libvpx {@code cpu-used} magnitude of the speed.
     */
    private final int level;

    /**
     * Creates a speed.
     *
     * @param level the libvpx speed
     */
    Vp8EncoderSpeed(int level) {
        this.level = level;
    }

    /**
     * Returns the speed of a libvpx speed number.
     *
     * @param level the libvpx real-time speed, the magnitude of {@code cpu-used}, from 4 to 16
     * @return the speed
     * @throws IllegalArgumentException if {@code level} is outside 4 to 16
     */
    public static Vp8EncoderSpeed of(int level) {
        if (level < 4 || level > 16) {
            throw new IllegalArgumentException("speed must be between 4 and 16, got " + level);
        }
        return values()[level - 4];
    }

    /**
     * Returns the libvpx real-time speed number, the magnitude of the {@code cpu-used} control.
     *
     * @return the speed number, from 4 to 16
     */
    public int level() {
        return level;
    }
}
