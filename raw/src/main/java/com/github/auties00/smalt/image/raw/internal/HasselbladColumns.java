/* Ports the Adobe DNG SDK 1.4 (source/dng_bad_pixels.cpp: dng_opcode_FixBadPixelsList::FixSingleColumn and
   FixClusteredRect, dng_bad_pixel_list::IsRectIsolated and IsPointValid; source/dng_image.cpp: the edge repeat
   of dng_image::Get). Copyright 2008 Adobe Systems Incorporated; DNG SDK License Agreement. Modified for Smalt. */
package com.github.auties00.smalt.image.raw.internal;

/**
 * Interpolates the defective columns a Hasselblad CCD camera lists in the calibration block of its 3FR files,
 * whose photosites carry no usable signal.
 *
 * <p>The listed columns are interpolated one after the other, in the list's order, each from the photosites as
 * the columns before it left them. A listed column at least two photosites from the plane's left and right edges
 * is interpolated photosite by photosite along seven directions through each photosite, from the 9 by 9
 * photosites around it:
 * <ul>
 * <li>a red or blue photosite has the directions to the photosites of its color two columns away (in its row,
 * and two or four rows up or down) and four columns away (two rows up or down); each direction's estimate is the
 * sum of those two photosites;</li>
 * <li>a green photosite has the direction to the greens of its kind two columns away in its row, whose estimate
 * is their sum, and six directions to the greens of the other kind one column away (one or three rows up or
 * down) and three columns away (one row up or down), whose estimates are the sum of the two greens plus twice
 * the difference between weighted means of the greens of the two kinds around the photosite.</li>
 * </ul>
 * Each direction has a gradient, a sum of absolute differences between photosites around the photosite along the
 * direction. The photosite becomes the rounded mean of the estimates of the directions whose gradient is at most
 * one and a half times the smallest one, held between the two photosites of its color in its row (red or blue)
 * or between the smallest and the largest of the four nearest greens of the other kind plus the difference of
 * the kinds (green). Photosites outside the plane are those of its first or last two rows or columns, repeated
 * with a period of two.
 *
 * <p>A column nearer the edges is interpolated from the photosites of the first of eight sets of positions around
 * each photosite that holds a photosite inside the plane and outside the column: the rounded mean of those
 * photosites. The sets lie one diagonal step away (green photosites only), then two steps along a row or
 * column, two diagonal steps, a knight's move of one and three steps (green only), four steps along a row or
 * column, three diagonal steps (green only), a knight's move of two and four steps, and four diagonal steps.
 *
 * @implNote This implementation is the Adobe DNG SDK's interpolation of the bad rectangles of opcode
 * {@code FixBadPixelsList}, which Adobe DNG Converter applies to each listed column with an opcode of its own:
 * run as a black box on copies of the CC0 H3D and H4D samples whose photosites were replaced by noise and by
 * hashed values, and whose lists held one to four columns, two of them two columns apart, the converter's
 * output equals it on every photosite of those columns and of the H3D sample's.
 */
final class HasselbladColumns {
    /**
     * The side of the window of photosites the interpolation of a column reads around each photosite.
     */
    private static final int WINDOW = 9;

    /**
     * The distance from the plane's left and right edges within which a listed column is interpolated from the
     * nearest usable photosites.
     */
    private static final int EDGE = 2;

    /**
     * The sets of positions of the interpolation of clustered columns, in the order they are tried, each as
     * pairs of row and column offsets; a set whose first row offset is odd is used only for green photosites.
     */
    private static final int[][] CLUSTER_SETS = {
            {-1, 1, -1, -1, 1, -1, 1, 1},
            {-2, 0, 2, 0, 0, -2, 0, 2},
            {-2, -2, -2, 2, 2, -2, 2, 2},
            {-1, -3, -3, -1, 1, -3, 3, -1, -1, 3, -3, 1, 1, 3, 3, 1},
            {-4, 0, 4, 0, 0, -4, 0, 4},
            {-3, -3, -3, 3, 3, -3, 3, 3},
            {-2, -4, -4, -2, 2, -4, 4, -2, -2, 4, -4, 2, 2, 4, 4, 2},
            {-4, -4, -4, 4, 4, -4, 4, 4}};

    /**
     * Prevents instantiation.
     */
    private HasselbladColumns() {
        throw new AssertionError();
    }

    /**
     * Interpolates the defective columns of a plane in place.
     *
     * @param plane the plane, of one sample per photosite, whose filter array is red, green, green, blue from its
     *              top-left photosite
     * @param columns the defective columns, in the order they are fixed, each within the plane
     */
    static void interpolate(SensorPlane plane, int[] columns) {
        interpolate(plane, columns, 0);
    }

    /**
     * Interpolates the defective columns of a plane in place, one after the other in the given order, each from
     * the plane as the ones before it left it, from its photosites' values relative to a black level.
     *
     * <p>The interpolation reads each photosite as its value less the black level, a value below the black level
     * as zero, and writes the result plus the black level, at most 65535. Its bounds and the unsigned arithmetic
     * of its means thus apply to the values relative to the black level.
     *
     * @param plane the plane, of one sample per photosite, whose filter array is red, green, green, blue from its
     *              top-left photosite
     * @param columns the defective columns, in the order they are fixed, each within the plane
     * @param black the black level of the plane's photosites, from 0 to 65535
     */
    static void interpolate(SensorPlane plane, int[] columns, int black) {
        var width = plane.width;
        var height = plane.height;
        if (width < 2 || height < 2) {
            return;
        }
        for (var column : columns) {
            fix(plane.integers, width, height, width, 0, column, black);
        }
    }

    /**
     * Interpolates one defective column of a buffer of a plane's columns in place, from the buffer's photosites
     * relative to a black level, as {@link #interpolate(SensorPlane, int[], int)} does.
     *
     * <p>The buffer must hold the four columns on either side of the column that lie within the plane; the
     * photosites outside the plane are those of its first or last two rows or columns, repeated with a period of
     * two.
     *
     * @param values the buffer's photosites, row by row
     * @param span the number of the buffer's columns
     * @param height the number of the buffer's rows, the plane's height, at least 2
     * @param width the plane's width, at least 2
     * @param from the plane's column at the buffer's first column
     * @param column the defective column, in the plane's columns
     * @param black the black level of the photosites, from 0 to 65535
     */
    static void fix(short[] values, int span, int height, int width, int from, int column, int black) {
        var single = column >= EDGE && column <= width - 1 - EDGE;
        var window = new int[WINDOW * WINDOW];
        var replaced = new short[height];
        for (var y = 0; y < height; y++) {
            var value = single ? single(values, span, height, width, from, column, y, black, window)
                    : clustered(values, span, height, width, from, column, y, black);
            replaced[y] = (short) Math.min(value + black, 65535);
        }
        for (var y = 0; y < height; y++) {
            values[y * span + column - from] = replaced[y];
        }
    }

    /**
     * Writes interpolated columns into a plane.
     *
     * @param plane the plane to write the columns into
     * @param columns the columns, each within the plane
     * @param fixed the photosites of each column, row by row, in the order of {@code columns}; a {@code null}
     *              element leaves its column unchanged
     */
    static void place(SensorPlane plane, int[] columns, short[][] fixed) {
        var width = plane.width;
        for (var index = 0; index < columns.length; index++) {
            var values = fixed[index];
            if (values == null) {
                continue;
            }
            for (var y = 0; y < values.length; y++) {
                plane.integers[y * width + columns[index]] = values[y];
            }
        }
    }

    /**
     * Returns the interpolated value of a photosite of a column at least {@value #EDGE} photosites from the
     * plane's left and right edges.
     *
     * @param values the buffer's photosites, row by row
     * @param span the number of the buffer's columns
     * @param height the plane's height
     * @param width the plane's width
     * @param from the plane's column at the buffer's first column
     * @param x the photosite's column, in the plane's columns
     * @param y the photosite's row
     * @param black the black level the values are read relative to
     * @param window an array of 81 elements the method overwrites with the photosites around the photosite
     * @return the value, relative to the black level
     */
    static int single(short[] values, int span, int height, int width, int from, int x, int y, int black,
                      int[] window) {
        for (var row = 0; row < WINDOW; row++) {
            for (var column = 0; column < WINDOW; column++) {
                window[row * WINDOW + column] = at(values, span, height, width, from, x - 4 + column, y - 4 + row,
                        black);
            }
        }
        return ((x + y) & 1) == 1 ? green(window) : color(window);
    }

    /**
     * Returns the interpolated value of a green photosite of an isolated column from the 9 by 9 photosites
     * around it, whose rows alternate between green-blue and red-green from the top.
     *
     * @param p the photosites, row by row, the photosite at row 4 and column 4
     * @return the value
     */
    private static int green(int[] p) {
        int b03 = p[3], b05 = p[5];
        int g11 = p[10], g13 = p[12], g15 = p[14], g17 = p[16];
        int g22 = p[20], b23 = p[21], b25 = p[23], g26 = p[24];
        int r30 = p[27], g31 = p[28], r32 = p[29], g33 = p[30], g35 = p[32], r36 = p[33], g37 = p[34], r38 = p[35];
        int g40 = p[36], g42 = p[38], b43 = p[39], b45 = p[41], g46 = p[42], g48 = p[44];
        int r50 = p[45], g51 = p[46], r52 = p[47], g53 = p[48], g55 = p[50], r56 = p[51], g57 = p[52], r58 = p[53];
        int g62 = p[56], b63 = p[57], b65 = p[59], g66 = p[60];
        int g71 = p[64], g73 = p[66], g75 = p[68], g77 = p[70];
        int b83 = p[75], b85 = p[77];
        // the difference between the greens of the photosite's kind and of the other kind
        var split = ((g22 + g62 + g26 + g66) * 4 + (g42 + g46) * 8 - (g11 + g13 + g15 + g17)
                - (g31 + g33 + g35 + g37) * 3 - (g51 + g53 + g55 + g57) * 3 - (g71 + g73 + g75 + g77) + 16) >> 5;
        int[] estimates = {g13 + g75 + split * 2, g33 + g55 + split * 2, g31 + g57 + split * 2, g42 + g46,
                g37 + g51 + split * 2, g35 + g53 + split * 2, g15 + g73 + split * 2};
        int[] gradients = {
                Math.abs(g13 - g75) + Math.abs(g15 - g46) + Math.abs(g22 - g53) + Math.abs(g35 - g66)
                        + Math.abs(g42 - g73) + Math.abs(b03 - b65) + Math.abs(b23 - b85),
                Math.abs(g33 - g55) + Math.abs(g22 - g55) + Math.abs(g33 - g66) + Math.abs(g13 - g35)
                        + Math.abs(g53 - g75) + Math.abs(b23 - b45) + Math.abs(b43 - b65),
                Math.abs(g31 - g57) + Math.abs(g33 - g46) + Math.abs(g35 - g48) + Math.abs(g40 - g53)
                        + Math.abs(g42 - g55) + Math.abs(r30 - r56) + Math.abs(r32 - r58),
                Math.abs(g42 - g46) * 2 + Math.abs(g33 - g35) + Math.abs(g53 - g55) + Math.abs(b23 - b25)
                        + Math.abs(b43 - b45) + Math.abs(b63 - b65),
                Math.abs(g37 - g51) + Math.abs(g35 - g42) + Math.abs(g33 - g40) + Math.abs(g48 - g55)
                        + Math.abs(g46 - g53) + Math.abs(r38 - r52) + Math.abs(r36 - r50),
                Math.abs(g35 - g53) + Math.abs(g26 - g53) + Math.abs(g35 - g62) + Math.abs(g15 - g33)
                        + Math.abs(g55 - g73) + Math.abs(b25 - b43) + Math.abs(b45 - b63),
                Math.abs(g15 - g73) + Math.abs(g13 - g42) + Math.abs(g26 - g55) + Math.abs(g33 - g62)
                        + Math.abs(g46 - g75) + Math.abs(b05 - b63) + Math.abs(b25 - b83)};
        var lower = Math.clamp(Math.min(Math.min(g33, g35), Math.min(g53, g55)) + split, 0, 65535);
        var upper = Math.clamp(Math.max(Math.max(g33, g35), Math.max(g53, g55)) + split, 0, 65535);
        return select(estimates, gradients, lower, upper);
    }

    /**
     * Returns the interpolated value of a red or blue photosite of an isolated column from the 9 by 9 photosites
     * around it, whose rows alternate between rows of its color and rows of the other one from the top.
     *
     * @param p the photosites, row by row, the photosite at row 4 and column 4
     * @return the value
     */
    private static int color(int[] p) {
        int b02 = p[2], g03 = p[3], g05 = p[5], b06 = p[6];
        int r13 = p[12], r15 = p[14];
        int b20 = p[18], b22 = p[20], g23 = p[21], g25 = p[23], b26 = p[24], b28 = p[26];
        int r31 = p[28], g32 = p[29], r33 = p[30], r35 = p[32], g36 = p[33], r37 = p[34];
        int g41 = p[37], b42 = p[38], g43 = p[39], g45 = p[41], b46 = p[42], g47 = p[43];
        int r51 = p[46], g52 = p[47], r53 = p[48], r55 = p[50], g56 = p[51], r57 = p[52];
        int b60 = p[54], b62 = p[56], g63 = p[57], g65 = p[59], b66 = p[60], b68 = p[62];
        int r73 = p[66], r75 = p[68];
        int b82 = p[74], g83 = p[75], g85 = p[77], b86 = p[78];
        int[] estimates = {b02 + b86, b22 + b66, b20 + b68, b42 + b46, b28 + b60, b26 + b62, b06 + b82};
        int[] gradients = {
                Math.abs(b02 - b86) + Math.abs(r13 - r55) + Math.abs(r33 - r75) + Math.abs(g03 - g45)
                        + Math.abs(g23 - g65) + Math.abs(g43 - g85),
                Math.abs(b22 - b66) + Math.abs(r13 - r35) + Math.abs(r33 - r55) + Math.abs(r53 - r75)
                        + Math.abs(g23 - g45) + Math.abs(g43 - g65),
                Math.abs(b20 - b68) + Math.abs(r31 - r55) + Math.abs(r33 - r57) + Math.abs(g23 - g47)
                        + Math.abs(g32 - g56) + Math.abs(g41 - g65),
                // the SDK's gradient also adds |g43 - g43|, which is zero
                Math.abs(b42 - b46) + Math.abs(r33 - r35) + Math.abs(r53 - r55) + Math.abs(g32 - g36)
                        + Math.abs(g52 - g56),
                Math.abs(b28 - b60) + Math.abs(r37 - r53) + Math.abs(r35 - r51) + Math.abs(g25 - g41)
                        + Math.abs(g36 - g52) + Math.abs(g47 - g63),
                Math.abs(b26 - b62) + Math.abs(r15 - r33) + Math.abs(r35 - r53) + Math.abs(r55 - r73)
                        + Math.abs(g25 - g43) + Math.abs(g45 - g63),
                Math.abs(b06 - b82) + Math.abs(r15 - r53) + Math.abs(r35 - r73) + Math.abs(g05 - g43)
                        + Math.abs(g25 - g63) + Math.abs(g45 - g83)};
        return select(estimates, gradients, Math.min(b42, b46), Math.max(b42, b46));
    }

    /**
     * Returns the rounded mean of the estimates whose gradient is at most one and a half times the smallest,
     * held between two bounds, in the unsigned 32-bit arithmetic of the SDK: a negative sum of estimates wraps to
     * a large mean, which the upper bound then holds.
     *
     * @param estimates the directions' estimates, each the sum of two photosites
     * @param gradients the directions' gradients
     * @param lower the smallest value, from 0 to 65535
     * @param upper the largest value, from {@code lower} to 65535
     * @return the value
     */
    private static int select(int[] estimates, int[] gradients, int lower, int upper) {
        var minimum = Integer.MAX_VALUE;
        for (var gradient : gradients) {
            minimum = Math.min(minimum, gradient);
        }
        var limit = (minimum * 3) >>> 1;
        var total = 0;
        var count = 0;
        for (var direction = 0; direction < gradients.length; direction++) {
            if (gradients[direction] <= limit) {
                total += estimates[direction];
                count += 2;
            }
        }
        var estimate = Integer.divideUnsigned(total + (count >>> 1), count);
        return Math.max(lower, Integer.compareUnsigned(estimate, upper) > 0 ? upper : estimate);
    }

    /**
     * Returns the interpolated value of a photosite of a column within {@value #EDGE} photosites of the plane's
     * left or right edge.
     *
     * @param values the buffer's photosites, row by row
     * @param span the number of the buffer's columns
     * @param height the plane's height
     * @param width the plane's width
     * @param from the plane's column at the buffer's first column
     * @param x the photosite's column, in the plane's columns
     * @param y the photosite's row
     * @param black the black level the values are read relative to
     * @return the value, or the photosite's own one when no set holds a usable photosite, relative to the black
     *         level
     */
    static int clustered(short[] values, int span, int height, int width, int from, int x, int y, int black) {
        var green = ((x + y) & 1) == 1;
        for (var set : CLUSTER_SETS) {
            if (!green && (set[0] & 1) == 1) {
                continue;
            }
            var total = 0;
            var count = 0;
            for (var entry = 0; entry < set.length; entry += 2) {
                var row = y + set[entry];
                var column = x + set[entry + 1];
                if (row >= 0 && row < height && column >= 0 && column < width && column != x) {
                    total += relative(values[row * span + column - from], black);
                    count++;
                }
            }
            if (count > 0) {
                return (total + (count >> 1)) / count;
            }
        }
        return relative(values[y * span + x - from], black);
    }

    /**
     * Returns a photosite's value less a black level, or zero when it is below the black level.
     *
     * @param value the photosite
     * @param black the black level
     * @return the value relative to the black level
     */
    private static int relative(short value, int black) {
        return Math.max(0, (value & 0xFFFF) - black);
    }

    /**
     * Returns a photosite of a plane from a buffer of its columns, or, outside the plane, the photosite of its
     * first or last two rows or columns that repeating them with a period of two places there.
     *
     * @param values the buffer's photosites, row by row
     * @param span the number of the buffer's columns
     * @param height the plane's height, at least 2
     * @param width the plane's width, at least 2
     * @param from the plane's column at the buffer's first column
     * @param x the column, in the plane's columns
     * @param y the row
     * @param black the black level the value is read relative to
     * @return the photosite's value, relative to the black level
     */
    private static int at(short[] values, int span, int height, int width, int from, int x, int y, int black) {
        return relative(values[repeat(y, height) * span + repeat(x, width) - from], black);
    }

    /**
     * Maps a coordinate outside a range onto the range's first or last two coordinates, repeated with a period
     * of two.
     *
     * @param coordinate the column or row
     * @param size the size of the range, at least 2
     * @return the coordinate within the range
     */
    private static int repeat(int coordinate, int size) {
        if (coordinate < 0) {
            return coordinate & 1;
        }
        return coordinate < size ? coordinate : size - 2 + ((coordinate - size) & 1);
    }
}
