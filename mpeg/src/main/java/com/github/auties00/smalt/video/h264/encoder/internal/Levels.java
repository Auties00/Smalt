package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.internal.H264Levels;
import com.github.auties00.smalt.video.h264.internal.H264Levels.Level;

/**
 * Selects the level of an encoded stream from the limits of {@link H264Levels}.
 *
 * <p>Level 1b keeps the pseudo {@code level_idc} 9; the sequence parameter set writer signals it
 * as {@code level_idc} 11 with {@code constraint_set3_flag} in the Baseline and Main profiles and
 * as {@code level_idc} 9 in the High profile.
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
     * Returns the lowest level a stream fits, never 1b.
     *
     * <p>When no level satisfies every limit, the result is taken from the levels at or above the
     * lowest one whose frame size and decoded picture buffer fit: the lowest that satisfies the
     * macroblock rate and the bitrate, else the macroblock rate and the buffer size, else the
     * macroblock rate alone; failing all three, the highest level.
     *
     * @param high whether the stream uses the High profile
     * @param mbWidth the picture width in macroblocks
     * @param mbHeight the picture height in macroblocks
     * @param frameRate the frame rate in frames per second
     * @param bitrate the maximum bitrate in bits per second, or 0 when unknown
     * @param bufferSize the coded picture buffer size in bits, or 0 when unknown
     * @param refs the number of reference frames the stream needs
     * @return the lowest fitting level
     */
    public static Level select(boolean high, int mbWidth, int mbHeight, double frameRate, long bitrate,
                               long bufferSize, int refs) {
        var frameMbs = mbWidth * mbHeight;
        for (var level : H264Levels.ALL) {
            if (level.idc() != 9 && level.fits(high, mbWidth, mbHeight, frameRate, bitrate, bufferSize)
                    && level.maxDpbFrames(frameMbs) >= refs) {
                return level;
            }
        }
        for (var level : H264Levels.ALL) {
            if (level.idc() != 9 && level.fits(high, mbWidth, mbHeight, 0, 0, 0)
                    && level.maxDpbFrames(frameMbs) >= refs) {
                var fastest = fastestAbove(level, high, mbWidth, mbHeight, frameRate, bitrate, 0);
                if (fastest == null) {
                    fastest = fastestAbove(level, high, mbWidth, mbHeight, frameRate, 0, bufferSize);
                }
                if (fastest == null) {
                    fastest = fastestAbove(level, high, mbWidth, mbHeight, frameRate, 0, 0);
                }
                return fastest != null ? fastest : H264Levels.HIGHEST;
            }
        }
        return H264Levels.HIGHEST;
    }

    /**
     * Returns the lowest level at or above a starting level that a stream fits.
     *
     * @param start the lowest level whose picture size limits fit
     * @param high whether the stream uses the High profile
     * @param mbWidth the picture width in macroblocks
     * @param mbHeight the picture height in macroblocks
     * @param frameRate the frame rate in frames per second
     * @param bitrate the maximum bitrate in bits per second, or 0 to leave it unchecked
     * @param bufferSize the coded picture buffer size in bits, or 0 to leave it unchecked
     * @return the level, or {@code null} when none at or above {@code start} fits
     */
    private static Level fastestAbove(Level start, boolean high, int mbWidth, int mbHeight, double frameRate,
                                      long bitrate, long bufferSize) {
        var found = false;
        for (var level : H264Levels.ALL) {
            found |= level == start;
            if (found && level.fits(high, mbWidth, mbHeight, frameRate, bitrate, bufferSize)) {
                return level;
            }
        }
        return null;
    }
}
