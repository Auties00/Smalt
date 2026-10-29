package com.github.auties00.smalt.internal.check;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;

import java.util.Arrays;

/**
 * Prepares a picture for a lossless request to an encoder that holds fewer bits per sample than the picture has.
 *
 * <p>Such a picture is coded losslessly only when narrowing it to 8 bits loses nothing, that is when its 16-bit
 * RGBA conversion equals the widening ({@code v * 257}) of its 8-bit one, as for a picture decoded from 8-bit
 * samples at high bit depth. This class is thread-safe.
 */
public final class LosslessDepth {
    /**
     * Prevents instantiation.
     */
    private LosslessDepth() {
        throw new AssertionError();
    }

    /**
     * Returns the picture an encoder holding at most {@code maxBits} bits per sample codes losslessly.
     *
     * @param picture the picture of the lossless request
     * @param maxBits the most bits per sample the format holds
     * @param format the format, lowercase, for the message
     * @return {@code picture} when its depth fits, otherwise its exact 8-bit RGBA conversion
     * @throws MediaException.Unsupported if the picture is deeper than {@code maxBits} and not exactly 8-bit
     */
    public static Picture fit(Picture picture, int maxBits, String format) {
        if (picture.bitDepth() <= maxBits) {
            return picture;
        }
        var narrow = ImageOps.toRgba(picture);
        var deep = ImageOps.toRgba16(picture);
        var widened = ImageOps.toSixteenBit(narrow);
        var row = deep.width() * 4;
        for (var y = 0; y < deep.height(); y++) {
            if (!Arrays.equals(deep.pixels(), y * deep.stride(), y * deep.stride() + row,
                    widened.pixels(), y * widened.stride(), y * widened.stride() + row)) {
                throw new MediaException.Unsupported(format, picture.bitDepth()
                        + "-bit samples cannot be coded losslessly: " + format + " holds at most " + maxBits
                        + " bits per sample");
            }
        }
        return narrow;
    }
}
