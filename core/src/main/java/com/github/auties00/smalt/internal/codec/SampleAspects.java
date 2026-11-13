package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.track.Rational;

/**
 * Fits sample aspect ratios to the fields a bitstream codes them in.
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
public final class SampleAspects {
    /**
     * The pixel aspect ratios of codes 1 to 5 of MPEG-4 Visual's {@code aspect_ratio_info} (ISO/IEC 14496-2 table
     * 6-12) and of H.263's PAR code (ITU-T H.263 Table 5), as numerator and denominator; code 15 is an extended pixel
     * aspect ratio of two 8-bit terms in both.
     */
    private static final int[][] PIXEL_ASPECTS = {{1, 1}, {12, 11}, {10, 11}, {16, 11}, {40, 33}};

    /**
     * The MPEG-4 Visual and H.263 code of an extended pixel aspect ratio.
     */
    public static final int EXTENDED_PIXEL_ASPECT = 15;

    private SampleAspects() {
    }

    /**
     * {@return the MPEG-4 Visual or H.263 code of a pixel aspect ratio, from 1 to 5, or
     * {@link #EXTENDED_PIXEL_ASPECT} for a ratio the codes do not list}
     *
     * @param ratio the ratio, in lowest terms
     */
    public static int pixelAspectCode(Rational ratio) {
        var index = indexOf(ratio, PIXEL_ASPECTS);
        return index >= 0 ? index + 1 : EXTENDED_PIXEL_ASPECT;
    }

    /**
     * {@return the pixel aspect ratio of an MPEG-4 Visual or H.263 code from 1 to 5, or {@code null} for any other
     * code}
     *
     * @param code the code read from the bitstream, from 0 to 15
     */
    public static Rational pixelAspectOf(int code) {
        return code >= 1 && code <= PIXEL_ASPECTS.length
                ? new Rational(PIXEL_ASPECTS[code - 1][0], PIXEL_ASPECTS[code - 1][1]) : null;
    }

    /**
     * Returns a sample aspect ratio in lowest terms whose terms do not exceed a bound: the ratio itself when its
     * reduced terms fit, else the closest fraction whose terms fit.
     *
     * @param ratio the ratio, with positive terms
     * @param max the largest term the field holds, positive
     * @return the ratio in lowest terms, each term from 1 to {@code max}
     */
    public static Rational fit(Rational ratio, long max) {
        var reduced = ratio.reduce();
        if (reduced.numerator() <= max && reduced.denominator() <= max) {
            return reduced;
        }
        // Outside [1/max, max] the closest fraction is a bound; inside, the first two convergents always fit, so the
        // approximation never has a zero term.
        if (reduced.numerator() / reduced.denominator() >= max) {
            return new Rational(max, 1);
        }
        if (reduced.denominator() / reduced.numerator() >= max) {
            return new Rational(1, max);
        }
        return MpegVideoHeaders.approximate(reduced.numerator(), reduced.denominator(), max);
    }

    /**
     * Returns the position of a ratio in a table of the ratios a codec codes by index.
     *
     * @param ratio the ratio, in lowest terms
     * @param table the ratios, each a numerator and a denominator in lowest terms
     * @return the index of the matching entry, or -1 when none matches
     */
    public static int indexOf(Rational ratio, int[][] table) {
        for (var i = 0; i < table.length; i++) {
            if (table[i][0] == ratio.numerator() && table[i][1] == ratio.denominator()) {
                return i;
            }
        }
        return -1;
    }
}
