package com.github.auties00.smalt.video.av1.internal.encoder;

/**
 * The limits of a defined AV1 level (Annex A.3 of the AV1 specification) that the encoders choose
 * levels by and that the decoder model of Annex E takes its bitrate, buffer size and decoding speed
 * from.
 *
 * @param index the {@code seq_level_idx}
 * @param maxPicSize the largest picture, in luma samples
 * @param maxHSize the largest picture width, in luma samples
 * @param maxVSize the largest picture height, in luma samples
 * @param maxDisplayRate the largest displayed luma sample rate, in samples per second
 * @param maxDecodeRate the largest decoded luma sample rate, in samples per second
 * @param maxHeaderRate the most frame headers per second
 * @param mainMbps the largest bitrate of the main tier, in megabits per second
 * @param mainCr the smallest compression ratio basis of the main tier
 */
record LevelLimits(int index, long maxPicSize, int maxHSize, int maxVSize, long maxDisplayRate,
                   long maxDecodeRate, int maxHeaderRate, double mainMbps, int mainCr) {
    /**
     * The {@code seq_level_idx} of the level without limits.
     */
    static final int MAX_PARAMETERS = 31;

    /**
     * The defined levels, in increasing order.
     */
    private static final LevelLimits[] LEVELS = {
            new LevelLimits(0, 147_456, 2048, 1152, 4_423_680L, 5_529_600L, 150, 1.5, 2),
            new LevelLimits(1, 278_784, 2816, 1584, 8_363_520L, 10_454_400L, 150, 3.0, 2),
            new LevelLimits(4, 665_856, 4352, 2448, 19_975_680L, 24_969_600L, 150, 6.0, 2),
            new LevelLimits(5, 1_065_024, 5504, 3096, 31_950_720L, 39_938_400L, 150, 10.0, 2),
            new LevelLimits(8, 2_359_296, 6144, 3456, 70_778_880L, 77_856_768L, 300, 12.0, 4),
            new LevelLimits(9, 2_359_296, 6144, 3456, 141_557_760L, 155_713_536L, 300, 20.0, 4),
            new LevelLimits(12, 8_912_896, 8192, 4352, 267_386_880L, 273_715_200L, 300, 30.0, 6),
            new LevelLimits(13, 8_912_896, 8192, 4352, 534_773_760L, 547_430_400L, 300, 40.0, 8),
            new LevelLimits(14, 8_912_896, 8192, 4352, 1_069_547_520L, 1_094_860_800L, 300, 60.0, 8),
            new LevelLimits(15, 8_912_896, 8192, 4352, 1_069_547_520L, 1_176_502_272L, 300, 60.0, 8),
            new LevelLimits(16, 35_651_584, 16384, 8704, 1_069_547_520L, 1_176_502_272L, 300, 60.0, 8),
            new LevelLimits(17, 35_651_584, 16384, 8704, 2_139_095_040L, 2_189_721_600L, 300, 100.0, 8),
            new LevelLimits(18, 35_651_584, 16384, 8704, 4_278_190_080L, 4_379_443_200L, 300, 160.0, 8),
            new LevelLimits(19, 35_651_584, 16384, 8704, 4_278_190_080L, 4_706_009_088L, 300, 160.0, 8)
    };

    /**
     * The headroom of the level's bitrate over a rate-controlled stream's target, which leaves room
     * for the frame groups and key frames rate control spends above the average.
     */
    private static final double BITRATE_HEADROOM = 1.5;

    /**
     * Returns the limits of a level.
     *
     * @param index the {@code seq_level_idx}
     * @return the limits, or {@code null} for the level without limits and the undefined levels
     */
    static LevelLimits of(int index) {
        for (var l : LEVELS) {
            if (l.index == index) {
                return l;
            }
        }
        return null;
    }

    /**
     * Returns the lowest level whose picture size limits admit a picture size.
     *
     * @param width the largest picture width
     * @param height the largest picture height
     * @return the {@code seq_level_idx}, {@value #MAX_PARAMETERS} when no defined level admits the size
     */
    static int level(int width, int height) {
        for (var l : LEVELS) {
            if (l.admits(width, height)) {
                return l.index;
            }
        }
        return MAX_PARAMETERS;
    }

    /**
     * Returns the lowest level that admits a picture size shown at a frame rate and decoded at a
     * decoded frame rate, whose shortest presentation interval ({@code MinFrameTime}) is at most a
     * frame interval, and whose main tier bitrate covers a target bitrate with headroom.
     *
     * @param width the largest picture width
     * @param height the largest picture height
     * @param frameRate the shown frames per second
     * @param decodeRate the decoded frames per second, hidden frames included, at least
     * {@code frameRate}
     * @param bitrate the target bitrate in bits per second, or 0 for a stream whose bitrate rate
     * control does not bound
     * @param profile the {@code seq_profile}
     * @return the {@code seq_level_idx}, {@value #MAX_PARAMETERS} when no defined level admits the stream
     */
    static int level(int width, int height, double frameRate, double decodeRate, long bitrate, int profile) {
        var luma = (double) width * height;
        for (var l : LEVELS) {
            if (l.admits(width, height) && luma * frameRate <= l.maxDisplayRate
                    && luma * decodeRate <= l.maxDecodeRate && decodeRate <= l.maxHeaderRate
                    && frameRate * l.maxDecodeRate <= (double) l.maxHeaderRate * l.maxDisplayRate
                    && bitrate * BITRATE_HEADROOM <= l.bitrate(profile)) {
                return l.index;
            }
        }
        return MAX_PARAMETERS;
    }

    /**
     * Returns whether the level's picture size limits admit a picture size.
     *
     * @param width the picture width
     * @param height the picture height
     * @return whether they do
     */
    private boolean admits(int width, int height) {
        return (long) width * height <= maxPicSize && width <= maxHSize && height <= maxVSize;
    }

    /**
     * Returns the bitrate of the smoothing buffer of the decoder model, {@code MaxBitrate} times
     * {@code BitrateProfileFactor}, of the main tier.
     *
     * @param profile the {@code seq_profile}
     * @return the bitrate, in bits per second
     */
    double bitrate(int profile) {
        return mainMbps * 1_000_000 * profileFactor(profile);
    }

    /**
     * Returns the size of the smoothing buffer of the decoder model, {@code MaxBufferSize} times
     * {@code BitrateProfileFactor}, of the main tier: one second of the bitrate.
     *
     * @param profile the {@code seq_profile}
     * @return the size, in bits
     */
    double bufferSize(int profile) {
        return bitrate(profile);
    }

    /**
     * Returns the largest coded size of a frame the level's compression ratio limit admits.
     *
     * @param width the picture width
     * @param height the picture height
     * @param frameRate the frames per second the stream decodes
     * @param profile the {@code seq_profile}
     * @return the size, in bits, including the 128 bytes of overhead the limit allows for
     */
    double largestFrameBits(int width, int height, double frameRate, int profile) {
        var luma = (double) width * height;
        var factor = profile == 0 ? 15 : profile == 1 ? 30 : 36;
        var uncompressed = Math.floor(luma * factor / 8);
        var speedAdj = luma * frameRate / maxDisplayRate;
        var ratio = Math.max(0.8, mainCr * speedAdj);
        return (uncompressed / ratio + 128) * 8;
    }

    /**
     * Returns the {@code BitrateProfileFactor} of a profile.
     *
     * @param profile the {@code seq_profile}
     * @return 1, 2 or 3
     */
    private static double profileFactor(int profile) {
        return profile == 0 ? 1.0 : profile == 1 ? 2.0 : 3.0;
    }
}
