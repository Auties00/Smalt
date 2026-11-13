package com.github.auties00.smalt.internal.caption;

import com.github.auties00.smalt.internal.subtitle.StyledText;

/**
 * Packs the character cells of caption screens into {@code int}s and turns rows of cells into styled text.
 *
 * <p>A cell holds a code point in bits 0 to 20 (0 for an empty cell), the italic flag in bit 21, the underline flag
 * in bit 22 and a color in bits 24 to 31: 0 for the default (white) or 1 plus a color of two bits per component,
 * red in bits 4 and 5, green in bits 2 and 3, blue in bits 0 and 1.
 */
public final class CaptionCells {
    /**
     * The empty cell.
     */
    public static final int EMPTY = 0;

    /**
     * The italic flag.
     */
    public static final int ITALIC = 1 << 21;

    /**
     * The underline flag.
     */
    public static final int UNDERLINE = 1 << 22;

    /**
     * The mask of the code point.
     */
    private static final int CODE_POINT = (1 << 21) - 1;

    private CaptionCells() {
        throw new AssertionError();
    }

    /**
     * Packs a cell.
     *
     * @param codePoint the character, positive
     * @param attributes the italic and underline flags and the color, as {@link #attributes(int, boolean, boolean)}
     * makes them
     * @return the cell
     */
    public static int cell(int codePoint, int attributes) {
        return attributes | (codePoint & CODE_POINT);
    }

    /**
     * Packs the attributes of a cell.
     *
     * @param rgb222 the color as two bits per component, {@code 0x3F} (white) for the default
     * @param italic whether the character is italic
     * @param underline whether the character is underlined
     * @return the attributes, without a code point
     */
    public static int attributes(int rgb222, boolean italic, boolean underline) {
        var color = (rgb222 & 0x3F) == 0x3F ? 0 : (rgb222 & 0x3F) + 1;
        return color << 24 | (italic ? ITALIC : 0) | (underline ? UNDERLINE : 0);
    }

    /**
     * Returns the code point of a cell.
     *
     * @param cell the packed cell: code point and attributes
     * @return the code point, 0 when the cell is empty
     */
    public static int codePoint(int cell) {
        return cell & CODE_POINT;
    }

    /**
     * Replaces the code point of a cell, keeping its attributes.
     *
     * @param cell the packed cell: code point and attributes
     * @param codePoint the new character, positive
     * @return the cell with the new character
     */
    public static int withCodePoint(int cell, int codePoint) {
        return (cell & ~CODE_POINT) | (codePoint & CODE_POINT);
    }

    /**
     * Returns whether a row shows any character other than a space.
     *
     * @param row the cells
     * @return {@code true} when a cell holds a visible character
     */
    public static boolean visible(int[] row) {
        for (var cell : row) {
            var codePoint = codePoint(cell);
            if (codePoint != 0 && !Character.isWhitespace(codePoint) && codePoint != 0xA0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds the styled text of a screen: its visible rows from top to bottom, each without its leading and
     * trailing spaces, separated by line feeds. A blank cell inside a row takes the format of the cell before it, so
     * the space between two words of one style does not split their run.
     *
     * @param rows the rows of cells, from the top
     * @param alignment the numpad alignment of the text, or {@code 0} for the default
     * @return the text, {@link StyledText#EMPTY} when no row is visible
     */
    public static StyledText text(int[][] rows, int alignment) {
        var builder = new StyledText.Builder();
        var first = true;
        var any = false;
        for (var row : rows) {
            if (row == null || !visible(row)) {
                continue;
            }
            var start = 0;
            var end = row.length;
            while (start < end && blank(row[start])) {
                start++;
            }
            while (end > start && blank(row[end - 1])) {
                end--;
            }
            if (!first) {
                builder.format(StyledText.Run.of("", 0));
                builder.append('\n');
            }
            first = false;
            any = true;
            StyledText.Run run = null;
            for (var index = start; index < end; index++) {
                var cell = row[index];
                var codePoint = codePoint(cell);
                if (run == null || !blank(cell)) {
                    var style = ((cell & ITALIC) != 0 ? StyledText.ITALIC : 0)
                            | ((cell & UNDERLINE) != 0 ? StyledText.UNDERLINE : 0);
                    var color = cell >>> 24;
                    var argb = color == 0 ? StyledText.NO_COLOR : 0xFF000000L | rgb(color - 1);
                    run = new StyledText.Run("", style, argb, StyledText.NO_COLOR, "", Double.NaN,
                            StyledText.NO_KARAOKE);
                }
                builder.format(run);
                builder.appendCodePoint(codePoint == 0 || codePoint == 0xA0 ? ' ' : codePoint);
            }
        }
        if (!any) {
            return StyledText.EMPTY;
        }
        if (alignment != 0) {
            builder.alignment(alignment);
        }
        return builder.build();
    }

    /**
     * Returns whether a cell shows nothing: empty, a space or a transparent space.
     *
     * @param cell the packed cell: code point and attributes
     * @return {@code true} for a blank cell
     */
    private static boolean blank(int cell) {
        var codePoint = codePoint(cell);
        return codePoint == 0 || codePoint == ' ' || codePoint == 0xA0;
    }

    /**
     * Expands a color of two bits per component.
     *
     * @param rgb222 the color
     * @return the color as {@code 0xRRGGBB}
     */
    public static int rgb(int rgb222) {
        return ((rgb222 >> 4) & 3) * 85 << 16 | ((rgb222 >> 2) & 3) * 85 << 8 | (rgb222 & 3) * 85;
    }
}
