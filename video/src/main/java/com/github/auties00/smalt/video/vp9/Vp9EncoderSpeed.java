package com.github.auties00.smalt.video.vp9;

/**
 * Enumerates the trades between compression and encoding time of a {@link Vp9Encoder}, slowest
 * first.
 *
 * <p>Each speed is the speed of libvpx's VP9 encoder with the same number, its {@code cpu-used}
 * control: {@link #SPEED_0} to {@link #SPEED_4} are the good-quality speeds, which
 * {@link Vp9EncoderUsage#GOOD_QUALITY} codes with a rate-distortion search of partitions, modes,
 * transforms and filters; {@link #SPEED_5} to {@link #SPEED_9} are the real-time speeds, which
 * {@link Vp9EncoderUsage#REALTIME} codes with a variance-based partitioning and a model-based mode
 * decision. {@link Vp9EncoderUsage#BEST_QUALITY} searches more than {@link #SPEED_0} whatever
 * the speed. Every speed codes the same stream format; faster speeds test fewer partitions, modes
 * and motion vectors, so they spend more bits for the same quality.
 */
public enum Vp9EncoderSpeed {
    /**
     * The slowest good-quality speed, with the fullest partition, mode and motion search.
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
     * The libvpx good-quality speed 3.
     */
    SPEED_3(3),

    /**
     * The fastest good-quality speed.
     */
    SPEED_4(4),

    /**
     * The slowest real-time speed, with the fullest partition and mode search.
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
     * The fastest real-time speed.
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
     * @param level the libvpx speed, the magnitude of {@code cpu-used}, from 0 to 9
     * @return the speed
     * @throws IllegalArgumentException if {@code level} is outside 0 to 9
     */
    public static Vp9EncoderSpeed of(int level) {
        if (level < 0 || level > 9) {
            throw new IllegalArgumentException("speed must be between 0 and 9, got " + level);
        }
        return values()[level];
    }

    /**
     * Returns the libvpx speed number, the magnitude of the {@code cpu-used} control.
     *
     * @return the speed number, from 0 to 9
     */
    public int level() {
        return level;
    }

    /**
     * {@return whether the speed is a real-time speed, {@link #SPEED_5} or faster}
     */
    public boolean realtime() {
        return level >= 5;
    }
}
