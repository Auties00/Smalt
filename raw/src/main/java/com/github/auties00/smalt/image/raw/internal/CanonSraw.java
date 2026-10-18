package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jpeg.internal.LosslessJpeg;

/**
 * Converts the subsampled YCbCr data of Canon mRAW and sRAW files to red, green and blue.
 *
 * <p>The lossless JPEG stream ({@link LosslessJpeg#decodeUnits}) codes units of two (sRAW, 4:2:2)
 * or four (mRAW, 4:2:0) luma samples followed by one blue-difference and one red-difference
 * sample; the units fill the slices of the CR2 file like the samples of a Bayer file. In a unit of
 * four luma samples the first two belong to the upper row of its 2x2 block. Each photosite's
 * chroma is the unit's own for the photosite where the unit's chroma is sited (the first sRAW
 * photosite; the lower-left mRAW photosite) and otherwise the rounded mean of the two nearest
 * sited values, {@code (a + b + 1) >> 1}: the unit to the right for the other photosites of a row,
 * the unit above for the upper mRAW row, and for the upper-right mRAW photosite the rounded mean
 * of those vertical means of the unit and the one to its right. Units past the image edge are
 * replaced by the nearest one.
 *
 * <p>Three conversions exist, chosen by the version of the maker note's color data:
 * <ul>
 * <li>{@link Kind#SCALED} (color data versions 6, 7 and 9): with {@code d} the doubled chroma
 * minus {@code 2 * 16383}, plus one for mRAW, red is {@code Y + ((200 * dCb + 22929 * dCr) >> 13)},
 * green {@code Y - ((5460 * dCb + 11751 * dCr) >> 13)} and blue
 * {@code Y + ((29040 * dCb - 101 * dCr) >> 13)}.</li>
 * <li>{@link Kind#DIFFERENCE} (versions 3, 10 to 13 and 15): with {@code c} the chroma minus 16384,
 * red is {@code Y + cCr}, blue {@code Y + cCb} and green
 * {@code Y - ((778 * cCb) >> 12) - (cCr >> 1)}; version 3 first lowers luma by 512.</li>
 * </ul>
 * Results are clamped to the range 0 to 16383.
 *
 * @implNote This implementation's layout, siting, interpolation and conversions were worked out
 * from CC0 sample files against the values Adobe DNG Converter, run as a black box,
 * stores for them (linear red, green and blue), which it reproduces exactly; the
 * conversion of color data versions it has no sample of is not known, so those files
 * are reported unsupported.
 */
final class CanonSraw {
    /**
     * The conversions from luma and chroma to red, green and blue.
     */
    enum Kind {
        /**
         * Scaled chroma, centered on 16383, with a full 3x2 matrix.
         */
        SCALED,

        /**
         * Plain differences, centered on 16384.
         */
        DIFFERENCE
    }

    /**
     * The largest value a converted sample takes.
     */
    static final int MAXIMUM = 16383;

    /**
     * Prevents instantiation.
     */
    private CanonSraw() {
        throw new AssertionError();
    }

    /**
     * Returns the conversion of a color data version.
     *
     * @param version the {@code ColorDataVersion} of the maker note
     * @return the conversion, or {@code null} when it is not known
     */
    static Kind kind(int version) {
        return switch (version) {
            case 6, 7, 9 -> Kind.SCALED;
            case 3, 10, 11, 12, 13, 15 -> Kind.DIFFERENCE;
            default -> null;
        };
    }

    /**
     * Converts the units of a whole image, laid out by the CR2 slices, to interleaved red, green
     * and blue samples.
     *
     * @param units the units of each image row of units, {@code rows x (columns * perUnit)}
     * @param unitColumns the units across the image
     * @param unitRows the rows of units
     * @param mraw whether units cover 2x2 photosites rather than 2x1
     * @param kind the conversion
     * @param lumaOffset the value subtracted from luma
     * @param width the image width in photosites
     * @param height the image height in photosites
     * @param out receives {@code width * height * 3} samples
     */
    static void convert(short[] units, int unitColumns, int unitRows, boolean mraw, Kind kind, int lumaOffset, int width,
                        int height, short[] out) {
        var perUnit = mraw ? 6 : 4;
        var lumas = mraw ? 4 : 2;
        var stride = unitColumns * perUnit;
        var chroma = new int[2];
        for (var y = 0; y < height; y++) {
            var unitRow = mraw ? y >> 1 : y;
            if (unitRow >= unitRows) {
                break;
            }
            var upper = mraw && (y & 1) == 0;
            var above = Math.max(unitRow - 1, 0);
            for (var x = 0; x < width; x++) {
                var unit = x >> 1;
                if (unit >= unitColumns) {
                    break;
                }
                var right = Math.min(unit + 1, unitColumns - 1);
                var odd = (x & 1) == 1;
                var luma = units[unitRow * stride + unit * perUnit + (mraw && !upper ? 2 : 0) + (x & 1)] & 0xFFFF;
                for (var plane = 0; plane < 2; plane++) {
                    var at = lumas + plane;
                    var own = units[unitRow * stride + unit * perUnit + at] & 0xFFFF;
                    int value;
                    if (!upper) {
                        value = odd ? (own + (units[unitRow * stride + right * perUnit + at] & 0xFFFF) + 1) >> 1 : own;
                    } else {
                        var vertical = (own + (units[above * stride + unit * perUnit + at] & 0xFFFF) + 1) >> 1;
                        if (odd) {
                            var next = ((units[unitRow * stride + right * perUnit + at] & 0xFFFF)
                                    + (units[above * stride + right * perUnit + at] & 0xFFFF) + 1) >> 1;
                            value = (vertical + next + 1) >> 1;
                        } else {
                            value = vertical;
                        }
                    }
                    chroma[plane] = value;
                }
                var target = (y * width + x) * 3;
                if (kind == Kind.SCALED) {
                    var cb = 2 * chroma[0] - 2 * 16383 + (mraw ? 1 : 0);
                    var cr = 2 * chroma[1] - 2 * 16383 + (mraw ? 1 : 0);
                    out[target] = clamp(luma + ((200 * cb + 22929 * cr) >> 13));
                    out[target + 1] = clamp(luma - ((5460 * cb + 11751 * cr) >> 13));
                    out[target + 2] = clamp(luma + ((29040 * cb - 101 * cr) >> 13));
                } else {
                    var y0 = luma - lumaOffset;
                    var cb = chroma[0] - 16384;
                    var cr = chroma[1] - 16384;
                    out[target] = clamp(y0 + cr);
                    out[target + 1] = clamp(y0 - ((778 * cb) >> 12) - (cr >> 1));
                    out[target + 2] = clamp(y0 + cb);
                }
            }
        }
    }

    /**
     * Clamps a value to the range of the converted samples.
     *
     * @param value the converted sample, of any range
     * @return the clamped value as a sample
     */
    private static short clamp(int value) {
        return (short) Math.clamp(value, 0, MAXIMUM);
    }

    /**
     * Checks that a stream is a Canon mRAW or sRAW stream: three components, the first sampled
     * 2x1 or 2x2 and the others 1x1.
     *
     * @param jpeg the stream
     * @return whether the units cover 2x2 photosites
     * @throws MediaException.Unsupported if the stream has another structure
     */
    static boolean mraw(LosslessJpeg jpeg) {
        var luma = jpeg.sampling(0);
        if (jpeg.components() != 3 || luma[0] != 2 || (luma[1] != 1 && luma[1] != 2)
                || jpeg.sampling(1)[0] != 1 || jpeg.sampling(1)[1] != 1 || jpeg.sampling(2)[0] != 1 || jpeg.sampling(2)[1] != 1) {
            throw new MediaException.Unsupported("raw", "this subsampled CR2 layout is not supported");
        }
        return luma[1] == 2;
    }
}
