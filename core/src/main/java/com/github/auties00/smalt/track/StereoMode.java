package com.github.auties00.smalt.track;

/**
 * Enumerates how the pictures of a stereoscopic video track pack the views of the two eyes, as the
 * Matroska {@code StereoMode} element numbers them.
 *
 * <p>An MP4 {@code st3d} box stores {@link #MONO}, {@link #TOP_BOTTOM_LEFT_FIRST} and
 * {@link #SIDE_BY_SIDE_LEFT_FIRST} only.
 */
public enum StereoMode {
    /**
     * A single view (code 0).
     */
    MONO(0),

    /**
     * The views side by side, the left eye on the left (code 1).
     */
    SIDE_BY_SIDE_LEFT_FIRST(1),

    /**
     * The views one above the other, the right eye on top (code 2).
     */
    TOP_BOTTOM_RIGHT_FIRST(2),

    /**
     * The views one above the other, the left eye on top (code 3).
     */
    TOP_BOTTOM_LEFT_FIRST(3),

    /**
     * The views in a checkerboard, the right eye first (code 4).
     */
    CHECKERBOARD_RIGHT_FIRST(4),

    /**
     * The views in a checkerboard, the left eye first (code 5).
     */
    CHECKERBOARD_LEFT_FIRST(5),

    /**
     * The views in alternate rows, the right eye first (code 6).
     */
    ROW_INTERLEAVED_RIGHT_FIRST(6),

    /**
     * The views in alternate rows, the left eye first (code 7).
     */
    ROW_INTERLEAVED_LEFT_FIRST(7),

    /**
     * The views in alternate columns, the right eye first (code 8).
     */
    COLUMN_INTERLEAVED_RIGHT_FIRST(8),

    /**
     * The views in alternate columns, the left eye first (code 9).
     */
    COLUMN_INTERLEAVED_LEFT_FIRST(9),

    /**
     * A cyan and red anaglyph (code 10).
     */
    ANAGLYPH_CYAN_RED(10),

    /**
     * The views side by side, the right eye on the left (code 11).
     */
    SIDE_BY_SIDE_RIGHT_FIRST(11),

    /**
     * A green and magenta anaglyph (code 12).
     */
    ANAGLYPH_GREEN_MAGENTA(12),

    /**
     * Both views laced in one block, the left eye first (code 13).
     */
    BLOCK_LACED_LEFT_FIRST(13),

    /**
     * Both views laced in one block, the right eye first (code 14).
     */
    BLOCK_LACED_RIGHT_FIRST(14);

    private final int code;

    StereoMode(int code) {
        this.code = code;
    }

    /**
     * {@return the Matroska {@code StereoMode} value, from 0 to 14}
     */
    public int code() {
        return code;
    }

    /**
     * Returns the mode a Matroska {@code StereoMode} value names.
     *
     * @param code the value
     * @return the mode
     * @throws IllegalArgumentException if the value is not from 0 to 14
     */
    public static StereoMode ofCode(long code) {
        var values = values();
        if (code < 0 || code >= values.length) {
            throw new IllegalArgumentException("unknown stereo mode " + code);
        }
        return values[(int) code];
    }
}
