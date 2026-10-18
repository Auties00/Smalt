package com.github.auties00.smalt.video.dnxhd.internal;

/**
 * Converts the samples of VC-3 RGB coding units (SMPTE ST 2019-1 compression IDs 1256 and 1270 with
 * the color format flag {@code CLF} set): the Y'CbCr to RGB conversion of macroblocks coded in the
 * alternate color space, and the mapping between the coded sample levels and full-range RGB.
 *
 * <p>Clause 6 and Table 2 put the R, G and B samples of an RGB coding unit at the levels of
 * Recommendation ITU-R BT.709: black at {@code 16 * 2^(n-8)} and white at {@code 235 * 2^(n-8)} for
 * {@code n}-bit samples, so 64 and 940 at 10 bits. A coding unit whose color volume is described
 * out of band ({@code CLV = 11b}) uses the full range of codes instead.
 */
public final class Vc3Rgb {
    /**
     * The luma coefficient of red in Recommendation ITU-R BT.709, Part 2, item 3.2.
     */
    private static final double KR = 0.2126;

    /**
     * The luma coefficient of blue in Recommendation ITU-R BT.709, Part 2, item 3.2.
     */
    private static final double KB = 0.0722;

    /**
     * The luma coefficient of green in Recommendation ITU-R BT.709, Part 2, item 3.2.
     */
    private static final double KG = 1 - KR - KB;

    /**
     * The ratio of the quantized luma excursion (219) to the color-difference excursion (224).
     */
    private static final double EXCURSIONS = 219.0 / 224.0;

    /**
     * The weight of {@code Cr} in red: {@code 2 (1 - Kr)}, 1.5748, at quantized levels.
     */
    private static final float CR_TO_R = (float) (2 * (1 - KR) * EXCURSIONS);

    /**
     * The weight of {@code Cb} in blue: {@code 2 (1 - Kb)}, 1.8556, at quantized levels.
     */
    private static final float CB_TO_B = (float) (2 * (1 - KB) * EXCURSIONS);

    /**
     * The weight of {@code Cb} subtracted from green, at quantized levels.
     */
    private static final float CB_TO_G = (float) (2 * (1 - KB) * KB / KG * EXCURSIONS);

    /**
     * The weight of {@code Cr} subtracted from green, at quantized levels.
     */
    private static final float CR_TO_G = (float) (2 * (1 - KR) * KR / KG * EXCURSIONS);

    /**
     * Prevents instantiation.
     */
    private Vc3Rgb() {
        throw new AssertionError();
    }

    /**
     * Converts one macroblock of Y'CbCr samples to RGB in place, as {@code ConvertMacroblockColor}
     * of ST 2019-1 clause 8.1.1 does for a macroblock whose alternate color space flag
     * {@code ACF} is set.
     *
     * <p>The three planes of the target hold Y', Cb and Cr on entry and R, G and B on return. The
     * conversion inverts the quantized signal equations of Recommendation ITU-R BT.709, Part 2,
     * items 3.2 to 3.5, so that R, G and B keep the quantization levels of Y':
     * {@snippet :
     * R = Y + 1.5748 * 219 / 224 * (Cr - 2^(n-1))
     * B = Y + 1.8556 * 219 / 224 * (Cb - 2^(n-1))
     * G = Y - (1.8556 * Kb / Kg * (Cb - 2^(n-1)) + 1.5748 * Kr / Kg * (Cr - 2^(n-1))) * 219 / 224
     * }
     * rounded to the nearest code and clipped to the {@code n}-bit range.
     *
     * @param target the output description, with 16-bit planes
     * @param x      the first column of the macroblock
     * @param top    the first coding unit row of the macroblock
     * @param depth  the sample bit depth {@code n} of the planes
     */
    public static void toRgb(Vc3Target target, int x, int top, int depth) {
        var planes = target.planes16();
        var first = planes[0];
        var second = planes[1];
        var third = planes[2];
        var stride = target.strides()[0];
        var middle = 1 << (depth - 1);
        var max = (1 << depth) - 1;
        for (var row = 0; row < 16; row++) {
            var at = ((top + row) * target.rowStep() + target.rowOffset()) * stride + x;
            for (var column = 0; column < 16; column++, at++) {
                var y = (float) first[at];
                var cb = second[at] - middle;
                var cr = third[at] - middle;
                first[at] = (short) clamp(y + CR_TO_R * cr, max);
                second[at] = (short) clamp(y - CB_TO_G * cb - CR_TO_G * cr, max);
                third[at] = (short) clamp(y + CB_TO_B * cb, max);
            }
        }
    }

    /**
     * Returns a full-range 16-bit RGB component of a coded sample.
     *
     * @param code       the coded sample
     * @param depth      the sample bit depth
     * @param outOfBand  whether the color volume is described out of band, so that the codes span
     *                   the full range
     * @param outputMax  the largest output value: 65535 or 255
     * @return the component, clipped to {@code [0, outputMax]}
     */
    public static int expand(int code, int depth, boolean outOfBand, int outputMax) {
        if (outOfBand) {
            var max = (1 << depth) - 1;
            return (int) ((code * (long) outputMax + max / 2) / max);
        }
        var black = 16 << (depth - 8);
        var span = 219 << (depth - 8);
        var value = ((code - black) * (long) outputMax * 2 + span) / (2L * span);
        return (int) Math.clamp(code < black ? 0 : value, 0, outputMax);
    }

    /**
     * Maps full-range 16-bit RGB components in place to the coded levels of an RGB coding unit
     * whose color volume is described in band: black at {@code 16 * 2^(n-8)}, white at
     * {@code 235 * 2^(n-8)}.
     *
     * @param plane the components, 16-bit unsigned on entry and {@code depth}-bit codes on return
     * @param depth the sample bit depth
     */
    public static void compress(short[] plane, int depth) {
        var black = 16 << (depth - 8);
        var span = 219L << (depth - 8);
        for (var i = 0; i < plane.length; i++) {
            plane[i] = (short) (black + ((plane[i] & 0xFFFF) * span * 2 + 65535) / (2 * 65535L));
        }
    }

    /**
     * Converts full-range 16-bit RGB components in place to the BT.709 Y'CbCr codes of macroblocks
     * coded in the alternate color space, the inverse of {@link #toRgb(Vc3Target, int, int, int)}.
     *
     * <p>With {@code r}, {@code g} and {@code b} the components scaled to {@code [0, 1]},
     * {@code y = Kr r + Kg g + Kb b} and the codes of {@code n}-bit samples are
     * {@snippet :
     * Y  = 16 * 2^(n-8) + 219 * 2^(n-8) * y
     * Cb = 2^(n-1) + 224 * 2^(n-8) * (b - y) / (2 (1 - Kb))
     * Cr = 2^(n-1) + 224 * 2^(n-8) * (r - y) / (2 (1 - Kr))
     * }
     * rounded to the nearest code, so that the conversion back puts R, G and B at the levels
     * {@link #compress(short[], int)} gives them.
     *
     * @param first  the red components on entry, the Y' codes on return
     * @param second the green components on entry, the Cb codes on return
     * @param third  the blue components on entry, the Cr codes on return
     * @param depth  the sample bit depth {@code n}
     */
    public static void toYcbcr(short[] first, short[] second, short[] third, int depth) {
        var black = 16 << (depth - 8);
        var lumaSpan = (219 << (depth - 8)) / 65535.0;
        var chromaSpan = (224 << (depth - 8)) / 65535.0;
        var middle = 1 << (depth - 1);
        var max = (1 << depth) - 1;
        var cbScale = chromaSpan / (2 * (1 - KB));
        var crScale = chromaSpan / (2 * (1 - KR));
        for (var i = 0; i < first.length; i++) {
            var r = first[i] & 0xFFFF;
            var g = second[i] & 0xFFFF;
            var b = third[i] & 0xFFFF;
            var y = KR * r + KG * g + KB * b;
            first[i] = (short) round(black + lumaSpan * y, max);
            second[i] = (short) round(middle + cbScale * (b - y), max);
            third[i] = (short) round(middle + crScale * (r - y), max);
        }
    }

    /**
     * Rounds a converted sample to the nearest code and clips it.
     *
     * @param value the sample
     * @param max   the largest code
     * @return the code
     */
    private static int round(double value, int max) {
        return Math.clamp((long) Math.floor(value + 0.5), 0, max);
    }

    /**
     * Rounds a converted sample to the nearest code and clips it.
     *
     * @param value the sample
     * @param max   the largest code
     * @return the code
     */
    private static int clamp(float value, int max) {
        return Math.clamp((int) Math.floor(value + 0.5f), 0, max);
    }
}
