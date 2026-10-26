package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.video.av1.Av1FilmGrain;
import com.github.auties00.smalt.video.av1.Av1ScreenContent;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

/**
 * Describes the coded pictures of an inter encoder's stream and the sequence-level tools it
 * signals.
 *
 * @param format the chroma format, any but {@link ChromaFormat#YUV440}
 * @param bitDepth the bit depth, 8, 10 or 12
 * @param color the color description signaled in the sequence header
 * @param filmGrain whether frames may signal film grain
 * @param screenContent when frames use the screen content tools
 * @param timing the timing information the sequence header signals
 * @param superresDenominator the super-resolution denominator of the frames the inter encoder
 * codes, 8 for none or 9 to 16
 */
public record StreamFormat(ChromaFormat format, int bitDepth, ColorInfo color, boolean filmGrain,
                           Av1ScreenContent screenContent, StreamTiming timing, int superresDenominator) {
    /**
     * Returns the format of 8-bit 4:2:0 pictures of a color without film grain, screen content
     * tools, timing information or super-resolution.
     *
     * @param color the color description
     * @return the format
     */
    public static StreamFormat of(ColorInfo color) {
        return new StreamFormat(ChromaFormat.YUV420, 8, color, false, Av1ScreenContent.NEVER, StreamTiming.NONE, 8);
    }

    /**
     * Checks that film grain parameters can be coded in a chroma format.
     *
     * @param grain the parameters
     * @param format the chroma format, any but {@link ChromaFormat#YUV440}
     * @throws IllegalArgumentException if they cannot: in 4:2:0 one chroma plane has points and
     * the other none, or there are chroma points but no luma points
     */
    public static void checkFilmGrain(Av1FilmGrain grain, ChromaFormat format) {
        var seq = new SequenceHeader();
        seq.monochrome = format == ChromaFormat.MONOCHROME;
        seq.ssHor = format == ChromaFormat.YUV444 ? 0 : 1;
        seq.ssVer = format == ChromaFormat.YUV420 || format == ChromaFormat.MONOCHROME ? 1 : 0;
        FilmGrains.toData(grain, seq);
    }
}
