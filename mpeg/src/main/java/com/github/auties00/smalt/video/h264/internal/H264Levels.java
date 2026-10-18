package com.github.auties00.smalt.video.h264.internal;

import java.util.List;

/**
 * The level limits of ITU-T H.264 Table A-1, shared by the decoder's buffer sizing and the
 * encoder's level selection.
 *
 * <p>Level 1b is represented by the pseudo {@code level_idc} 9, which {@link #effectiveIdc} maps
 * both of its encodings to: {@code level_idc} 9, and {@code level_idc} 11 with
 * {@code constraint_set3_flag} in the Baseline, Main and Extended profiles.
 */
public final class H264Levels {
    /**
     * The limits of one level.
     *
     * @param idc the {@code level_idc}, 9 for level 1b
     * @param maxMbps the maximum macroblock processing rate, in macroblocks per second
     * @param maxFs the maximum frame size, in macroblocks
     * @param maxDpbMbs the maximum decoded picture buffer size, in macroblocks
     * @param maxBr the maximum video bitrate, in units of 1000 bits per second for the Baseline and
     * Main profiles (1250 for High)
     * @param maxCpb the maximum coded picture buffer size, in the units of {@code maxBr}
     * @param maxVmv the maximum vertical motion vector magnitude, in full luma samples
     * @param minCr the minimum compression ratio
     * @param maxMvsPer2Mb the maximum number of motion vectors per two consecutive macroblocks, or
     * 0 when unlimited
     */
    public record Level(int idc, long maxMbps, int maxFs, int maxDpbMbs, int maxBr, int maxCpb, int maxVmv,
                        int minCr, int maxMvsPer2Mb) {
        /**
         * Returns whether a stream fits this level.
         *
         * @param high whether the stream uses the High profile, whose bitrate factor is 1250
         * instead of 1000
         * @param mbWidth the picture width in macroblocks
         * @param mbHeight the picture height in macroblocks
         * @param frameRate the frame rate in frames per second, or 0 when unknown
         * @param bitrate the maximum bitrate in bits per second, or 0 when unknown
         * @param bufferSize the coded picture buffer size in bits, or 0 when unknown
         * @return whether every limit is respected
         */
        public boolean fits(boolean high, int mbWidth, int mbHeight, double frameRate, long bitrate, long bufferSize) {
            var frameMbs = (long) mbWidth * mbHeight;
            if (frameMbs > maxFs || (long) mbWidth * mbWidth > 8L * maxFs || (long) mbHeight * mbHeight > 8L * maxFs) {
                return false;
            }
            if (frameMbs * frameRate > maxMbps * 1.0001) {
                return false;
            }
            var factor = high ? 1250L : 1000L;
            return bitrate <= maxBr * factor && bufferSize <= maxCpb * factor;
        }

        /**
         * Returns {@code MaxDpbFrames} at a picture size (ITU-T H.264 A.3.1).
         *
         * @param frameMbs the picture size in macroblocks
         * @return the number of frames the decoded picture buffer holds, from 1 to 16
         */
        public int maxDpbFrames(int frameMbs) {
            return Math.clamp(maxDpbMbs / Math.max(1, frameMbs), 1, 16);
        }
    }

    /**
     * The levels of Table A-1 in increasing order; 1b sorts between 1 and 1.1.
     */
    public static final List<Level> ALL = List.of(
            new Level(10, 1485, 99, 396, 64, 175, 64, 2, 0),
            new Level(9, 1485, 99, 396, 128, 350, 64, 2, 0),
            new Level(11, 3000, 396, 900, 192, 500, 128, 2, 0),
            new Level(12, 6000, 396, 2376, 384, 1000, 128, 2, 0),
            new Level(13, 11880, 396, 2376, 768, 2000, 128, 2, 0),
            new Level(20, 11880, 396, 2376, 2000, 2000, 128, 2, 0),
            new Level(21, 19800, 792, 4752, 4000, 4000, 256, 2, 0),
            new Level(22, 20250, 1620, 8100, 4000, 4000, 256, 2, 0),
            new Level(30, 40500, 1620, 8100, 10000, 10000, 256, 2, 32),
            new Level(31, 108000, 3600, 18000, 14000, 14000, 512, 4, 16),
            new Level(32, 216000, 5120, 20480, 20000, 20000, 512, 4, 16),
            new Level(40, 245760, 8192, 32768, 20000, 25000, 512, 4, 16),
            new Level(41, 245760, 8192, 32768, 50000, 62500, 512, 2, 16),
            new Level(42, 522240, 8704, 34816, 50000, 62500, 512, 2, 16),
            new Level(50, 589824, 22080, 110400, 135000, 135000, 512, 2, 16),
            new Level(51, 983040, 36864, 184320, 240000, 240000, 512, 2, 16),
            new Level(52, 2073600, 36864, 184320, 240000, 240000, 512, 2, 16),
            new Level(60, 4177920, 139264, 696320, 240000, 240000, 8192, 2, 16),
            new Level(61, 8355840, 139264, 696320, 480000, 480000, 8192, 2, 16),
            new Level(62, 16711680, 139264, 696320, 800000, 800000, 8192, 2, 16));

    /**
     * The highest level, level 6.2.
     */
    public static final Level HIGHEST = ALL.getLast();

    private H264Levels() {
        throw new AssertionError();
    }

    /**
     * Returns the {@code level_idc} of a sequence with 1b normalized to 9.
     *
     * @param profileIdc the {@code profile_idc}
     * @param constraintFlags the constraint flags byte, {@code constraint_set0_flag} in bit 7
     * @param levelIdc the {@code level_idc}
     * @return {@code levelIdc}, or 9 when it is 11 with {@code constraint_set3_flag} in profile
     * 66, 77 or 88
     */
    public static int effectiveIdc(int profileIdc, int constraintFlags, int levelIdc) {
        if (levelIdc == 11 && (constraintFlags & 0x10) != 0 && (profileIdc == 66 || profileIdc == 77 || profileIdc == 88)) {
            return 9;
        }
        return levelIdc;
    }

    /**
     * Returns the limits of a level.
     *
     * @param idc the {@code level_idc}, 9 for level 1b
     * @return the limits, or {@code null} when Table A-1 has no such level
     */
    public static Level find(int idc) {
        for (var level : ALL) {
            if (level.idc == idc) {
                return level;
            }
        }
        return null;
    }
}
