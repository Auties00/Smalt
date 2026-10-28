package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.internal.H264Levels;
import com.github.auties00.smalt.video.h264.internal.H264Levels.Level;

/**
 * Selects the level of an encoded stream from the limits of {@link H264Levels}.
 *
 * <p>Level 1b keeps the pseudo {@code level_idc} 9; the sequence parameter set writer signals it
 * as {@code level_idc} 11 with {@code constraint_set3_flag} in the Baseline and Main profiles and
 * as {@code level_idc} 9 in the High profiles. Interlaced streams fit only the levels from 2.1 to
 * 4.1, the levels that allow {@code frame_mbs_only_flag} 0 (ITU-T H.264 Table A-4).
 */
public final class Levels {
    private Levels() {
        throw new AssertionError();
    }

    /**
     * Returns the limits of a level.
     *
     * @param idc the {@code level_idc}, 9 for level 1b
     * @return the limits
     * @throws IllegalArgumentException if Table A-1 has no such level
     */
    public static Level of(int idc) {
        var level = H264Levels.find(idc);
        if (level == null) {
            throw new IllegalArgumentException("unknown level " + idc);
        }
        return level;
    }

    /**
     * Returns the factor by which a profile scales the bitrate and buffer limits of Table A-1
     * ({@code cpbBrVclFactor} of Table A-2).
     *
     * @param profile the stream's profile
     * @return the factor in bits per Table A-1 unit
     */
    public static int bitrateFactor(H264Profile profile) {
        return switch (profile) {
            case CONSTRAINED_BASELINE, MAIN -> 1000;
            case HIGH -> 1250;
            case HIGH_10 -> 3000;
            case HIGH_422, HIGH_444_PREDICTIVE -> 4000;
        };
    }

    /**
     * Returns whether a level allows interlaced coding.
     *
     * @param level the level to check
     * @return whether {@code frame_mbs_only_flag} may be 0
     */
    static boolean allowsInterlace(Level level) {
        return level.idc() >= 21 && level.idc() <= 41;
    }

    /**
     * Returns whether a stream satisfies the limits of a level.
     *
     * @param level the level to check
     * @param factor the bitrate factor of the profile, see {@link #bitrateFactor}
     * @param interlaced whether the stream is interlaced
     * @param mbWidth the picture width in macroblocks
     * @param mbHeight the frame height in macroblocks
     * @param frameRate the frame rate in frames per second, or 0 to leave it unchecked
     * @param bitrate the maximum bitrate in bits per second, or 0 to leave it unchecked
     * @param bufferSize the coded picture buffer size in bits, or 0 to leave it unchecked
     * @return whether the stream fits
     */
    public static boolean fits(Level level, int factor, boolean interlaced, int mbWidth, int mbHeight,
                               double frameRate, long bitrate, long bufferSize) {
        if (interlaced && !allowsInterlace(level)) {
            return false;
        }
        var frameMbs = (long) mbWidth * mbHeight;
        if (frameMbs > level.maxFs() || (long) mbWidth * mbWidth > 8L * level.maxFs()
                || (long) mbHeight * mbHeight > 8L * level.maxFs()) {
            return false;
        }
        if (frameMbs * frameRate > level.maxMbps() * 1.0001) {
            return false;
        }
        return bitrate <= (long) level.maxBr() * factor && bufferSize <= (long) level.maxCpb() * factor;
    }

    /**
     * Returns the lowest level a stream fits, never 1b.
     *
     * <p>When no level satisfies every limit, the result is taken from the levels at or above the
     * lowest one whose frame size and decoded picture buffer fit: the lowest that satisfies the
     * macroblock rate and the bitrate, else the macroblock rate and the buffer size, else the
     * macroblock rate alone; failing all three, the highest level that allows the stream's coding.
     *
     * @param factor the bitrate factor of the profile, see {@link #bitrateFactor}
     * @param interlaced whether the stream is interlaced
     * @param mbWidth the picture width in macroblocks
     * @param mbHeight the frame height in macroblocks
     * @param frameRate the frame rate in frames per second
     * @param bitrate the maximum bitrate in bits per second, or 0 when unknown
     * @param bufferSize the coded picture buffer size in bits, or 0 when unknown
     * @param refs the number of reference frames the stream needs
     * @return the lowest fitting level
     */
    public static Level select(int factor, boolean interlaced, int mbWidth, int mbHeight, double frameRate,
                               long bitrate, long bufferSize, int refs) {
        var frameMbs = mbWidth * mbHeight;
        for (var level : H264Levels.ALL) {
            if (level.idc() != 9 && fits(level, factor, interlaced, mbWidth, mbHeight, frameRate, bitrate, bufferSize)
                    && level.maxDpbFrames(frameMbs) >= refs) {
                return level;
            }
        }
        for (var level : H264Levels.ALL) {
            if (level.idc() != 9 && fits(level, factor, interlaced, mbWidth, mbHeight, 0, 0, 0)
                    && level.maxDpbFrames(frameMbs) >= refs) {
                var fastest = fastestAbove(level, factor, interlaced, mbWidth, mbHeight, frameRate, bitrate, 0);
                if (fastest == null) {
                    fastest = fastestAbove(level, factor, interlaced, mbWidth, mbHeight, frameRate, 0, bufferSize);
                }
                if (fastest == null) {
                    fastest = fastestAbove(level, factor, interlaced, mbWidth, mbHeight, frameRate, 0, 0);
                }
                return fastest != null ? fastest : highest(interlaced);
            }
        }
        return highest(interlaced);
    }

    /**
     * Returns the highest level that allows a stream's coding.
     *
     * @param interlaced whether the stream is interlaced
     * @return level 4.1 for interlaced streams, the highest level otherwise
     */
    private static Level highest(boolean interlaced) {
        return interlaced ? of(41) : H264Levels.HIGHEST;
    }

    /**
     * Returns the lowest level at or above a starting level that a stream fits.
     *
     * @param start the lowest level whose picture size limits fit
     * @param factor the bitrate factor of the profile
     * @param interlaced whether the stream is interlaced
     * @param mbWidth the picture width in macroblocks
     * @param mbHeight the frame height in macroblocks
     * @param frameRate the frame rate in frames per second
     * @param bitrate the maximum bitrate in bits per second, or 0 to leave it unchecked
     * @param bufferSize the coded picture buffer size in bits, or 0 to leave it unchecked
     * @return the level, or {@code null} when none at or above {@code start} fits
     */
    private static Level fastestAbove(Level start, int factor, boolean interlaced, int mbWidth, int mbHeight,
                                      double frameRate, long bitrate, long bufferSize) {
        var found = false;
        for (var level : H264Levels.ALL) {
            found |= level == start;
            if (found && fits(level, factor, interlaced, mbWidth, mbHeight, frameRate, bitrate, bufferSize)) {
                return level;
            }
        }
        return null;
    }
}
