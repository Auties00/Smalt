package com.github.auties00.smalt.video.vp8;

/**
 * Enumerates the trades between compression and encoding time of a {@link Vp8Encoder}, slowest
 * first.
 *
 * <p>Each speed is the speed of libvpx's VP8 encoder with the same number, the magnitude of its
 * {@code cpu-used} control: {@link #SPEED_0} to {@link #SPEED_4} are the good-quality speeds of
 * {@link Vp8EncoderUsage#GOOD_QUALITY}, which choose modes by their rate and distortion up to
 * {@link #SPEED_3}; {@link #SPEED_4} to {@link #SPEED_16} are the real-time speeds of
 * {@link Vp8EncoderUsage#REALTIME}, {@link #SPEED_4} belonging to both.
 * {@link Vp8EncoderUsage#BEST_QUALITY} searches more than {@link #SPEED_0} whatever the speed.
 * Every speed codes the same
 * stream format, and faster speeds test fewer modes and motion vectors, so they spend more bits
 * for the same quality. From {@link #SPEED_14} on, the loop filter is the simple filter.
 */
public enum Vp8EncoderSpeed {
    /**
     * The slowest good-quality speed, with the fullest mode and motion search and the trellis
     * optimization of the quantized coefficients.
     */
    SPEED_0(0),

    /**
     * The libvpx good-quality speed 1.
     */
    SPEED_1(1),

    /**
     * The libvpx good-quality speed 2.
     */
    SPEED_2(2),

    /**
     * The fastest good-quality speed that chooses modes by their rate and distortion.
     */
    SPEED_3(3),

    /**
     * The fastest good-quality speed and the slowest real-time speed: a full loop filter level
     * search and the diamond motion search.
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
     * @param level the libvpx speed, the magnitude of {@code cpu-used}, from 0 to 16
     * @return the speed
     * @throws IllegalArgumentException if {@code level} is outside 0 to 16
     */
    public static Vp8EncoderSpeed of(int level) {
        if (level < 0 || level > 16) {
            throw new IllegalArgumentException("speed must be between 0 and 16, got " + level);
        }
        return values()[level];
    }

    /**
     * Returns the libvpx speed number, the magnitude of the {@code cpu-used} control.
     *
     * @return the speed number, from 0 to 16
     */
    public int level() {
        return level;
    }

    /**
     * Returns whether real-time coding accepts the speed.
     *
     * @return {@code true} for {@link #SPEED_4} to {@link #SPEED_16}
     */
    public boolean realtime() {
        return level >= 4;
    }

    /**
     * Returns whether good-quality coding accepts the speed.
     *
     * @return {@code true} for {@link #SPEED_0} to {@link #SPEED_4}
     */
    public boolean goodQuality() {
        return level <= 4;
    }
}
