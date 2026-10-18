package com.github.auties00.smalt.video.h264.encoder;

/**
 * Enumerates the levels of ITU-T H.264 Annex A, the bounds on picture size, frame rate, bitrate
 * and buffer that a stream announces to decoders.
 *
 * <p>An {@link H264Encoder} chooses the lowest level that fits its stream unless
 * {@link H264EncoderOptions#level()} names one.
 */
public enum H264Level {
    /**
     * Level 1.
     */
    LEVEL_1(10),

    /**
     * Level 1b: {@code level_idc} 11 with {@code constraint_set3_flag} in the Constrained
     * Baseline and Main profiles, {@code level_idc} 9 in the High profile.
     */
    LEVEL_1B(9),

    /**
     * Level 1.1.
     */
    LEVEL_1_1(11),

    /**
     * Level 1.2.
     */
    LEVEL_1_2(12),

    /**
     * Level 1.3.
     */
    LEVEL_1_3(13),

    /**
     * Level 2.
     */
    LEVEL_2(20),

    /**
     * Level 2.1.
     */
    LEVEL_2_1(21),

    /**
     * Level 2.2.
     */
    LEVEL_2_2(22),

    /**
     * Level 3.
     */
    LEVEL_3(30),

    /**
     * Level 3.1.
     */
    LEVEL_3_1(31),

    /**
     * Level 3.2.
     */
    LEVEL_3_2(32),

    /**
     * Level 4.
     */
    LEVEL_4(40),

    /**
     * Level 4.1.
     */
    LEVEL_4_1(41),

    /**
     * Level 4.2.
     */
    LEVEL_4_2(42),

    /**
     * Level 5.
     */
    LEVEL_5(50),

    /**
     * Level 5.1.
     */
    LEVEL_5_1(51),

    /**
     * Level 5.2.
     */
    LEVEL_5_2(52),

    /**
     * Level 6.
     */
    LEVEL_6(60),

    /**
     * Level 6.1.
     */
    LEVEL_6_1(61),

    /**
     * Level 6.2.
     */
    LEVEL_6_2(62);

    private final int levelIdc;

    H264Level(int levelIdc) {
        this.levelIdc = levelIdc;
    }

    /**
     * {@return the {@code level_idc} of the level: ten times the level number, or 9 for level 1b,
     * which the Constrained Baseline and Main profiles signal as 11 with
     * {@code constraint_set3_flag}}
     */
    public int levelIdc() {
        return levelIdc;
    }
}
