package com.github.auties00.smalt.video.hevc.encoder;

/**
 * Enumerates the levels of ITU-T H.265 Annex A, the bounds on picture size, sample rate, bitrate and buffer
 * that a stream announces to decoders.
 *
 * <p>An {@link HevcEncoder} chooses the lowest level that fits its stream unless
 * {@link HevcEncoderOptions#level()} names one. Levels 4 and above have a high tier, with higher bitrate
 * and buffer bounds, which the encoder signals when the main tier's bounds are too low for the stream.
 */
public enum HevcLevel {
    /**
     * Level 1.
     */
    LEVEL_1(30, 36_864, 552_960L, 128, 0, 350, 0),

    /**
     * Level 2.
     */
    LEVEL_2(60, 122_880, 3_686_400L, 1_500, 0, 1_500, 0),

    /**
     * Level 2.1.
     */
    LEVEL_2_1(63, 245_760, 7_372_800L, 3_000, 0, 3_000, 0),

    /**
     * Level 3.
     */
    LEVEL_3(90, 552_960, 16_588_800L, 6_000, 0, 6_000, 0),

    /**
     * Level 3.1.
     */
    LEVEL_3_1(93, 983_040, 33_177_600L, 10_000, 0, 10_000, 0),

    /**
     * Level 4.
     */
    LEVEL_4(120, 2_228_224, 66_846_720L, 12_000, 30_000, 12_000, 30_000),

    /**
     * Level 4.1.
     */
    LEVEL_4_1(123, 2_228_224, 133_693_440L, 20_000, 50_000, 20_000, 50_000),

    /**
     * Level 5.
     */
    LEVEL_5(150, 8_912_896, 267_386_880L, 25_000, 100_000, 25_000, 100_000),

    /**
     * Level 5.1.
     */
    LEVEL_5_1(153, 8_912_896, 534_773_760L, 40_000, 160_000, 40_000, 160_000),

    /**
     * Level 5.2.
     */
    LEVEL_5_2(156, 8_912_896, 1_069_547_520L, 60_000, 240_000, 60_000, 240_000),

    /**
     * Level 6.
     */
    LEVEL_6(180, 35_651_584, 1_069_547_520L, 60_000, 240_000, 60_000, 240_000),

    /**
     * Level 6.1.
     */
    LEVEL_6_1(183, 35_651_584, 2_139_095_040L, 120_000, 480_000, 120_000, 480_000),

    /**
     * Level 6.2.
     */
    LEVEL_6_2(186, 35_651_584, 4_278_190_080L, 240_000, 800_000, 240_000, 800_000);

    private final int levelIdc;

    private final int maxLumaPictureSize;

    private final long maxLumaSampleRate;

    private final int maxBitrateMain;

    private final int maxBitrateHigh;

    private final int maxBufferMain;

    private final int maxBufferHigh;

    HevcLevel(int levelIdc, int maxLumaPictureSize, long maxLumaSampleRate, int maxBitrateMain, int maxBitrateHigh,
              int maxBufferMain, int maxBufferHigh) {
        this.levelIdc = levelIdc;
        this.maxLumaPictureSize = maxLumaPictureSize;
        this.maxLumaSampleRate = maxLumaSampleRate;
        this.maxBitrateMain = maxBitrateMain;
        this.maxBitrateHigh = maxBitrateHigh;
        this.maxBufferMain = maxBufferMain;
        this.maxBufferHigh = maxBufferHigh;
    }

    /**
     * {@return the {@code general_level_idc} of the level, thirty times the level number}
     */
    public int levelIdc() {
        return levelIdc;
    }

    /**
     * {@return the largest picture, {@code MaxLumaPs} of Table A.8, in luma samples}
     *
     * <p>Neither side of the picture may exceed {@code sqrt(8 * MaxLumaPs)} samples.
     */
    public int maxLumaPictureSize() {
        return maxLumaPictureSize;
    }

    /**
     * {@return the largest luma sample rate, {@code MaxLumaSr} of Table A.9, in samples per second}
     */
    public long maxLumaSampleRate() {
        return maxLumaSampleRate;
    }

    /**
     * Returns the largest bitrate of a tier, {@code MaxBR} of Table A.9 for the Main and Main 10 profiles.
     *
     * @param highTier whether the bound of the high tier is wanted
     * @return the bitrate in bits per second, or 0 for the high tier of a level below 4, which has none
     */
    public long maxBitrate(boolean highTier) {
        return 1000L * (highTier ? maxBitrateHigh : maxBitrateMain);
    }

    /**
     * Returns the largest coded picture buffer of a tier, {@code MaxCPB} of Table A.8.
     *
     * @param highTier whether the bound of the high tier is wanted
     * @return the size in bits, or 0 for the high tier of a level below 4, which has none
     */
    public long maxBuffer(boolean highTier) {
        return 1000L * (highTier ? maxBufferHigh : maxBufferMain);
    }
}
