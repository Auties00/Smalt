package com.github.auties00.smalt.image.raw;

/**
 * A color of the filters of a color filter array, with the code TIFF/EP and DNG give it in the
 * {@code CFAPattern} tag.
 */
public enum CfaColor {
    /**
     * Red, code 0.
     */
    RED,

    /**
     * Green, code 1.
     */
    GREEN,

    /**
     * Blue, code 2.
     */
    BLUE,

    /**
     * Cyan, code 3.
     */
    CYAN,

    /**
     * Magenta, code 4.
     */
    MAGENTA,

    /**
     * Yellow, code 5.
     */
    YELLOW,

    /**
     * White (unfiltered), code 6.
     */
    WHITE;

    /**
     * {@return the TIFF/EP {@code CFAPattern} code of the color, from 0 to 6}
     */
    public int code() {
        return ordinal();
    }

    /**
     * Returns the color of a TIFF/EP {@code CFAPattern} code, the inverse of {@link #code()}.
     *
     * @param code the code, from 0 to 6
     * @return the color
     * @throws IllegalArgumentException if the code is not from 0 to 6
     */
    public static CfaColor of(int code) {
        var values = values();
        if (code < 0 || code >= values.length) {
            throw new IllegalArgumentException("invalid CFA color code " + code);
        }
        return values[code];
    }

    /**
     * {@return the letter of the color, as pattern names spell it: {@code R}, {@code G}, {@code B},
     * {@code C}, {@code M}, {@code Y} or {@code W}}
     */
    public char letter() {
        return "RGBCMYW".charAt(ordinal());
    }
}
