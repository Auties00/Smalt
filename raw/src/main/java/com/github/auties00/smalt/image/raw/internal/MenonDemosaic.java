/* Ports colour-demosaicing (colour_demosaicing/bayer/demosaicing/menon2007.py: demosaicing_CFA_Bayer_Menon2007,
   refining_step_Menon2007). Copyright 2015 Colour Developers; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.raw.internal;

/**
 * Demosaics a window of a Bayer image with directional filtering and a posteriori decision
 * (DDFAPD), followed by the refinement step.
 *
 * <p>Green is estimated at red and blue photosites along both directions with a 5-tap filter
 * ({@code [0, 1/2, 0, 1/2, 0]} on green plus {@code [-1/4, 0, 1/2, 0, -1/4]} on the photosite's
 * own color). The direction whose color differences vary less, summed over a 5x5 window, wins.
 * Red and blue are then interpolated from color differences, along the chosen direction at blue
 * and red photosites, and the refinement step re-estimates every channel from smoothed color
 * differences. Convolutions mirror their input at the image edges without repeating the edge
 * sample, so the color filter array's phase is kept; the decision sums treat the outside as
 * zero.
 *
 * <p>The window is computed from a tile that extends {@link #MARGIN} photosites beyond it where the
 * image allows, enough for every step's support, so windows computed separately join without
 * seams and the result equals that of the whole image.
 *
 * @implNote This implementation ports {@code demosaicing_CFA_Bayer_Menon2007} and
 * {@code refining_step_Menon2007} of the colour-demosaicing package, itself an implementation of
 * D. Menon, S. Andriani and G. Calvagno, "Demosaicing With Directional Filtering and a posteriori
 * Decision", IEEE Transactions on Image Processing 16(1), 2007; it computes in single precision.
 */
final class MenonDemosaic {
    /**
     * The photosites a tile extends beyond its window on every side; the steps' supports add up to
     * nine.
     */
    static final int MARGIN = 12;

    /**
     * The tile's photosites, with the CFA values.
     */
    private float[] cfa;

    /**
     * The horizontal green estimate.
     */
    private float[] greenH;

    /**
     * The vertical green estimate.
     */
    private float[] greenV;

    /**
     * Scratch plane.
     */
    private float[] scratchA;

    /**
     * Scratch plane.
     */
    private float[] scratchB;

    /**
     * Scratch plane.
     */
    private float[] scratchC;

    /**
     * Scratch plane.
     */
    private float[] scratchD;

    /**
     * The red estimate.
     */
    private float[] red;

    /**
     * The green estimate.
     */
    private float[] green;

    /**
     * The blue estimate.
     */
    private float[] blue;

    /**
     * The plane of each photosite: 0 red, 1 green, 2 blue.
     */
    private byte[] plane;

    /**
     * Whether horizontal interpolation won at each photosite.
     */
    private boolean[] horizontal;

    /**
     * Whether each tile row holds red photosites.
     */
    private boolean[] redRow;

    /**
     * Whether each tile row holds blue photosites.
     */
    private boolean[] blueRow;

    /**
     * Whether each tile column holds red photosites.
     */
    private boolean[] redColumn;

    /**
     * Whether each tile column holds blue photosites.
     */
    private boolean[] blueColumn;

    /**
     * Column indices of the neighbors at offsets -2, -1, +1 and +2, mirrored at the image edges
     * and clamped to the tile.
     */
    private int[][] columns;

    /**
     * Row indices of the neighbors at offsets -2, -1, +1 and +2.
     */
    private int[][] rows;

    /**
     * Whether the column at offset -2, -1, +1 or +2 lies in the image.
     */
    private boolean[][] columnInside;

    /**
     * Whether the row at offset -2, -1, +1 or +2 lies in the image.
     */
    private boolean[][] rowInside;

    /**
     * The tile width.
     */
    private int tileWidth;

    /**
     * The tile height.
     */
    private int tileHeight;

    /**
     * Demosaics a window of a Bayer image.
     *
     * @param image the image, one sample per pixel with a Bayer CFA
     * @param x0 the first column of the window
     * @param y0 the first row of the window
     * @param width the window width
     * @param height the window height
     * @param r receives the red values, row by row
     * @param g receives the green values
     * @param b receives the blue values
     */
    void run(LinearImage image, int x0, int y0, int width, int height, float[] r, float[] g, float[] b) {
        var tx0 = Math.max(0, x0 - MARGIN);
        var ty0 = Math.max(0, y0 - MARGIN);
        var tx1 = Math.min(image.width, x0 + width + MARGIN);
        var ty1 = Math.min(image.height, y0 + height + MARGIN);
        prepare(image, tx0, ty0, tx1 - tx0, ty1 - ty0);
        interpolate();
        var w = tileWidth;
        for (var row = 0; row < height; row++) {
            var source = (y0 - ty0 + row) * w + (x0 - tx0);
            System.arraycopy(red, source, r, row * width, width);
            System.arraycopy(green, source, g, row * width, width);
            System.arraycopy(blue, source, b, row * width, width);
        }
    }

    /**
     * Loads a tile and its neighbor tables.
     *
     * @param image the linear image being demosaiced
     * @param tx0 the first column of the tile
     * @param ty0 the first row of the tile
     * @param tw the tile width
     * @param th the tile height
     */
    private void prepare(LinearImage image, int tx0, int ty0, int tw, int th) {
        tileWidth = tw;
        tileHeight = th;
        var cells = tw * th;
        if (cfa == null || cfa.length < cells) {
            cfa = new float[cells];
            greenH = new float[cells];
            greenV = new float[cells];
            scratchA = new float[cells];
            scratchB = new float[cells];
            scratchC = new float[cells];
            scratchD = new float[cells];
            red = new float[cells];
            green = new float[cells];
            blue = new float[cells];
            plane = new byte[cells];
            horizontal = new boolean[cells];
        }
        redRow = new boolean[th];
        blueRow = new boolean[th];
        redColumn = new boolean[tw];
        blueColumn = new boolean[tw];
        var patternWidth = image.cfa.width();
        var patternHeight = image.cfa.height();
        var planes = image.planes;
        for (var y = 0; y < th; y++) {
            var source = (ty0 + y) * image.width + tx0;
            System.arraycopy(image.data, source, cfa, y * tw, tw);
            var patternRow = (ty0 + y) % patternHeight * patternWidth;
            var patternColumn = tx0 % patternWidth;
            for (var x = 0; x < tw; x++) {
                var p = planes[patternRow + patternColumn];
                if (++patternColumn == patternWidth) {
                    patternColumn = 0;
                }
                plane[y * tw + x] = (byte) p;
                if (p == 0) {
                    redRow[y] = true;
                    redColumn[x] = true;
                } else if (p == 2) {
                    blueRow[y] = true;
                    blueColumn[x] = true;
                }
            }
        }
        columns = neighbors(tx0, tw, image.width);
        rows = neighbors(ty0, th, image.height);
        columnInside = inside(tx0, tw, image.width);
        rowInside = inside(ty0, th, image.height);
    }

    /**
     * Builds the neighbor index tables of one axis.
     *
     * @param start the first coordinate of the tile
     * @param count the tile's extent
     * @param size the image's extent
     * @return for offsets -2, -1, +1 and +2, the tile index of each neighbor
     */
    private static int[][] neighbors(int start, int count, int size) {
        int[] offsets = {-2, -1, 1, 2};
        var result = new int[4][count];
        for (var k = 0; k < 4; k++) {
            for (var i = 0; i < count; i++) {
                var position = start + i + offsets[k];
                if (position < 0) {
                    position = -position;
                } else if (position >= size) {
                    position = 2 * size - 2 - position;
                }
                position = Math.clamp(position, 0, size - 1);
                result[k][i] = Math.clamp(position - start, 0, count - 1);
            }
        }
        return result;
    }

    /**
     * Builds the inside flags of one axis.
     *
     * @param start the first coordinate of the tile
     * @param count the tile's extent
     * @param size the image's extent
     * @return for offsets -2, -1, +1 and +2, whether each neighbor lies in the image
     */
    private static boolean[][] inside(int start, int count, int size) {
        int[] offsets = {-2, -1, 1, 2};
        var result = new boolean[4][count];
        for (var k = 0; k < 4; k++) {
            for (var i = 0; i < count; i++) {
                var position = start + i + offsets[k];
                result[k][i] = position >= 0 && position < size;
            }
        }
        return result;
    }

    /**
     * Runs the algorithm over the loaded tile.
     */
    private void interpolate() {
        var w = tileWidth;
        var h = tileHeight;
        var cl2 = columns[0];
        var cl1 = columns[1];
        var cr1 = columns[2];
        var cr2 = columns[3];
        var ru2 = rows[0];
        var ru1 = rows[1];
        var rd1 = rows[2];
        var rd2 = rows[3];
        for (var y = 0; y < h; y++) {
            var line = y * w;
            var up2 = ru2[y] * w;
            var up1 = ru1[y] * w;
            var down1 = rd1[y] * w;
            var down2 = rd2[y] * w;
            for (var x = 0; x < w; x++) {
                var at = line + x;
                var value = cfa[at];
                if (plane[at] == 1) {
                    greenH[at] = value;
                    greenV[at] = value;
                } else {
                    greenH[at] = 0.5f * (cfa[line + cl1[x]] + cfa[line + cr1[x]])
                            + (0.5f * value - 0.25f * (cfa[line + cl2[x]] + cfa[line + cr2[x]]));
                    greenV[at] = 0.5f * (cfa[up1 + x] + cfa[down1 + x])
                            + (0.5f * value - 0.25f * (cfa[up2 + x] + cfa[down2 + x]));
                }
            }
        }
        var chromaH = scratchA;
        var chromaV = scratchB;
        for (var at = 0; at < w * h; at++) {
            if (plane[at] == 1) {
                chromaH[at] = 0;
                chromaV[at] = 0;
            } else {
                chromaH[at] = cfa[at] - greenH[at];
                chromaV[at] = cfa[at] - greenV[at];
            }
        }
        var gradientH = scratchC;
        var gradientV = scratchD;
        for (var y = 0; y < h; y++) {
            var line = y * w;
            var down2 = rd2[y] * w;
            for (var x = 0; x < w; x++) {
                gradientH[line + x] = Math.abs(chromaH[line + x] - chromaH[line + cr2[x]]);
                gradientV[line + x] = Math.abs(chromaV[line + x] - chromaV[down2 + x]);
            }
        }
        var outsideL1 = columnInside[1];
        var outsideL2 = columnInside[0];
        var outsideU1 = rowInside[1];
        var outsideU2 = rowInside[0];
        var outsideD1 = rowInside[2];
        var outsideD2 = rowInside[3];
        var outsideR1 = columnInside[2];
        var outsideR2 = columnInside[3];
        var from = 2;
        while (from < w - 2 && !(outsideL1[from] && outsideL2[from] && outsideR1[from] && outsideR2[from])) {
            from++;
        }
        var to = from;
        while (to < w - 2 && outsideL1[to] && outsideL2[to] && outsideR1[to] && outsideR2[to]) {
            to++;
        }
        for (var y = 0; y < h; y++) {
            var interior = y >= 2 && y < h - 2 && outsideU1[y] && outsideU2[y] && outsideD1[y] && outsideD2[y];
            for (var x = 0; x < w; x++) {
                if (interior && x == from && from < to) {
                    decideInterior(gradientH, gradientV, y, from, to);
                    x = to - 1;
                    continue;
                }
                var sumH = horizontalSum(gradientH, x, y, w, h, outsideL1, outsideL2, outsideU1, outsideU2, outsideD1, outsideD2);
                var sumV = verticalSum(gradientV, x, y, w, h, outsideU1, outsideU2, outsideL1, outsideL2, outsideR1, outsideR2);
                horizontal[y * w + x] = sumV >= sumH;
            }
        }
        for (var at = 0; at < w * h; at++) {
            green[at] = horizontal[at] ? greenH[at] : greenV[at];
            var p = plane[at];
            red[at] = p == 0 ? cfa[at] : 0;
            blue[at] = p == 2 ? cfa[at] : 0;
        }
        redBlueAtGreen();
        redBlueAtOpposite();
        refine();
    }

    /**
     * Decides the interpolation direction of a run of photosites of one row whose decision windows
     * lie inside both the tile and the image: the result of {@link #horizontalSum} and
     * {@link #verticalSum} with every neighbor inside, summed in the same order.
     *
     * @implNote This implementation exists for speed: without the edge tests the loop has no
     * branch and a fixed stencil, which the JIT compiles to SIMD code.
     *
     * @param gradientH the horizontal gradients
     * @param gradientV the vertical gradients
     * @param y the row, at least 2 rows from the tile's top and bottom
     * @param from the first column, at least 2
     * @param to one past the last column, at most the tile width minus 2
     */
    private void decideInterior(float[] gradientH, float[] gradientV, int y, int from, int to) {
        var w = tileWidth;
        var r0 = y * w;
        var rm1 = r0 - w;
        var rm2 = rm1 - w;
        var rp1 = r0 + w;
        var rp2 = rp1 + w;
        var d = gradientH;
        var e = gradientV;
        for (var x = from; x < to; x++) {
            var sumH = 3 * d[r0 + x] + 3 * d[r0 + x - 2];
            sumH += d[rm1 + x - 1];
            sumH += d[rp1 + x - 1];
            sumH += d[rm2 + x] + d[rm2 + x - 2];
            sumH += d[rp2 + x] + d[rp2 + x - 2];
            var sumV = 3 * e[r0 + x] + 3 * e[rm2 + x];
            sumV += e[rm1 + x - 1];
            sumV += e[rm1 + x + 1];
            sumV += e[r0 + x - 2] + e[rm2 + x - 2];
            sumV += e[r0 + x + 2] + e[rm2 + x + 2];
            horizontal[r0 + x] = sumV >= sumH;
        }
    }

    /**
     * Sums horizontal gradients over the decision window of a photosite: rows -2 to +2 and
     * columns -2 to 0, with the weights of the DDFAPD kernel, the outside counting as zero.
     *
     * @param d the gradients
     * @param x the column
     * @param y the row
     * @param w the tile width
     * @param h the tile height
     * @param l1 whether column -1 lies inside
     * @param l2 whether column -2 lies inside
     * @param u1 whether row -1 lies inside
     * @param u2 whether row -2 lies inside
     * @param d1 whether row +1 lies inside
     * @param d2 whether row +2 lies inside
     * @return the weighted sum
     */
    private float horizontalSum(float[] d, int x, int y, int w, int h, boolean[] l1, boolean[] l2, boolean[] u1,
                                boolean[] u2, boolean[] d1, boolean[] d2) {
        var c0 = x;
        var cm1 = Math.max(0, x - 1);
        var cm2 = Math.max(0, x - 2);
        var r0 = y * w;
        var rm1 = Math.max(0, y - 1) * w;
        var rm2 = Math.max(0, y - 2) * w;
        var rp1 = Math.min(h - 1, y + 1) * w;
        var rp2 = Math.min(h - 1, y + 2) * w;
        var left1 = l1[x];
        var left2 = l2[x];
        var sum = 3 * d[r0 + c0] + (left2 ? 3 * d[r0 + cm2] : 0);
        if (u1[y]) {
            sum += left1 ? d[rm1 + cm1] : 0;
        }
        if (d1[y]) {
            sum += left1 ? d[rp1 + cm1] : 0;
        }
        if (u2[y]) {
            sum += d[rm2 + c0] + (left2 ? d[rm2 + cm2] : 0);
        }
        if (d2[y]) {
            sum += d[rp2 + c0] + (left2 ? d[rp2 + cm2] : 0);
        }
        return sum;
    }

    /**
     * Sums vertical gradients over the decision window of a photosite: the transposed kernel of
     * {@link #horizontalSum}, rows -2 to 0 and columns -2 to +2.
     *
     * @param d the gradients
     * @param x the column
     * @param y the row
     * @param w the tile width
     * @param h the tile height
     * @param u1 whether row -1 lies inside
     * @param u2 whether row -2 lies inside
     * @param l1 whether column -1 lies inside
     * @param l2 whether column -2 lies inside
     * @param r1 whether column +1 lies inside
     * @param r2 whether column +2 lies inside
     * @return the weighted sum
     */
    private float verticalSum(float[] d, int x, int y, int w, int h, boolean[] u1, boolean[] u2, boolean[] l1,
                              boolean[] l2, boolean[] r1, boolean[] r2) {
        var r0 = y * w;
        var rm1 = Math.max(0, y - 1) * w;
        var rm2 = Math.max(0, y - 2) * w;
        var cm1 = Math.max(0, x - 1);
        var cm2 = Math.max(0, x - 2);
        var cp1 = Math.min(w - 1, x + 1);
        var cp2 = Math.min(w - 1, x + 2);
        var up1 = u1[y];
        var up2 = u2[y];
        var sum = 3 * d[r0 + x] + (up2 ? 3 * d[rm2 + x] : 0);
        if (l1[x]) {
            sum += up1 ? d[rm1 + cm1] : 0;
        }
        if (r1[x]) {
            sum += up1 ? d[rm1 + cp1] : 0;
        }
        if (l2[x]) {
            sum += d[r0 + cm2] + (up2 ? d[rm2 + cm2] : 0);
        }
        if (r2[x]) {
            sum += d[r0 + cp2] + (up2 ? d[rm2 + cp2] : 0);
        }
        return sum;
    }

    /**
     * Interpolates red and blue at green photosites from the color differences of their two
     * neighbors of that color: horizontal in rows of that color, vertical otherwise.
     */
    private void redBlueAtGreen() {
        var w = tileWidth;
        var h = tileHeight;
        var cl1 = columns[1];
        var cr1 = columns[2];
        var ru1 = rows[1];
        var rd1 = rows[2];
        var newRed = scratchA;
        var newBlue = scratchB;
        System.arraycopy(red, 0, newRed, 0, w * h);
        System.arraycopy(blue, 0, newBlue, 0, w * h);
        for (var y = 0; y < h; y++) {
            var line = y * w;
            var up = ru1[y] * w;
            var down = rd1[y] * w;
            for (var x = 0; x < w; x++) {
                var at = line + x;
                if (plane[at] != 1) {
                    continue;
                }
                var horizontalRed = 0.5f * (red[line + cl1[x]] + red[line + cr1[x]]);
                var horizontalGreen = 0.5f * (green[line + cl1[x]] + green[line + cr1[x]]);
                var verticalRed = 0.5f * (red[up + x] + red[down + x]);
                var verticalGreen = 0.5f * (green[up + x] + green[down + x]);
                var horizontalBlue = 0.5f * (blue[line + cl1[x]] + blue[line + cr1[x]]);
                var verticalBlue = 0.5f * (blue[up + x] + blue[down + x]);
                if (redRow[y]) {
                    newRed[at] = green[at] + horizontalRed - horizontalGreen;
                }
                if (blueRow[y]) {
                    newRed[at] = green[at] + verticalRed - verticalGreen;
                    newBlue[at] = green[at] + horizontalBlue - horizontalGreen;
                }
                if (redRow[y]) {
                    newBlue[at] = green[at] + verticalBlue - verticalGreen;
                }
            }
        }
        System.arraycopy(newRed, 0, red, 0, w * h);
        System.arraycopy(newBlue, 0, blue, 0, w * h);
    }

    /**
     * Interpolates red at blue photosites and blue at red ones from color differences, along the
     * direction the decision chose.
     */
    private void redBlueAtOpposite() {
        var w = tileWidth;
        var h = tileHeight;
        var cl1 = columns[1];
        var cr1 = columns[2];
        var ru1 = rows[1];
        var rd1 = rows[2];
        var newRed = scratchA;
        System.arraycopy(red, 0, newRed, 0, w * h);
        for (var y = 0; y < h; y++) {
            if (!blueRow[y]) {
                continue;
            }
            var line = y * w;
            var up = ru1[y] * w;
            var down = rd1[y] * w;
            for (var x = 0; x < w; x++) {
                var at = line + x;
                if (plane[at] != 2) {
                    continue;
                }
                if (horizontal[at]) {
                    newRed[at] = blue[at] + 0.5f * (red[line + cl1[x]] + red[line + cr1[x]])
                            - 0.5f * (blue[line + cl1[x]] + blue[line + cr1[x]]);
                } else {
                    newRed[at] = blue[at] + 0.5f * (red[up + x] + red[down + x]) - 0.5f * (blue[up + x] + blue[down + x]);
                }
            }
        }
        System.arraycopy(newRed, 0, red, 0, w * h);
        var newBlue = scratchB;
        System.arraycopy(blue, 0, newBlue, 0, w * h);
        for (var y = 0; y < h; y++) {
            if (!redRow[y]) {
                continue;
            }
            var line = y * w;
            var up = ru1[y] * w;
            var down = rd1[y] * w;
            for (var x = 0; x < w; x++) {
                var at = line + x;
                if (plane[at] != 0) {
                    continue;
                }
                if (horizontal[at]) {
                    newBlue[at] = red[at] + 0.5f * (blue[line + cl1[x]] + blue[line + cr1[x]])
                            - 0.5f * (red[line + cl1[x]] + red[line + cr1[x]]);
                } else {
                    newBlue[at] = red[at] + 0.5f * (blue[up + x] + blue[down + x]) - 0.5f * (red[up + x] + red[down + x]);
                }
            }
        }
        System.arraycopy(newBlue, 0, blue, 0, w * h);
    }

    /**
     * Runs the refinement step: green at red and blue photosites from smoothed color differences,
     * then red and blue at green photosites, then red at blue photosites and blue at red ones.
     */
    private void refine() {
        var w = tileWidth;
        var h = tileHeight;
        var cells = w * h;
        var cl1 = columns[1];
        var cr1 = columns[2];
        var ru1 = rows[1];
        var rd1 = rows[2];
        var redGreen = scratchA;
        var blueGreen = scratchB;
        var redGreenSmooth = scratchC;
        var blueGreenSmooth = scratchD;
        for (var at = 0; at < cells; at++) {
            redGreen[at] = red[at] - green[at];
            blueGreen[at] = blue[at] - green[at];
        }
        for (var y = 0; y < h; y++) {
            var line = y * w;
            var up = ru1[y] * w;
            var down = rd1[y] * w;
            for (var x = 0; x < w; x++) {
                var at = line + x;
                var p = plane[at];
                if (p == 1) {
                    redGreenSmooth[at] = 0;
                    blueGreenSmooth[at] = 0;
                    continue;
                }
                var source = p == 0 ? redGreen : blueGreen;
                var smooth = horizontal[at]
                        ? (source[line + cl1[x]] + source[at] + source[line + cr1[x]]) / 3f
                        : (source[up + x] + source[at] + source[down + x]) / 3f;
                if (p == 0) {
                    redGreenSmooth[at] = smooth;
                    blueGreenSmooth[at] = 0;
                } else {
                    blueGreenSmooth[at] = smooth;
                    redGreenSmooth[at] = 0;
                }
            }
        }
        for (var at = 0; at < cells; at++) {
            var p = plane[at];
            if (p == 0) {
                green[at] = red[at] - redGreenSmooth[at];
            } else if (p == 2) {
                green[at] = blue[at] - blueGreenSmooth[at];
            }
        }
        for (var at = 0; at < cells; at++) {
            redGreen[at] = red[at] - green[at];
            blueGreen[at] = blue[at] - green[at];
        }
        for (var y = 0; y < h; y++) {
            var line = y * w;
            var up = ru1[y] * w;
            var down = rd1[y] * w;
            for (var x = 0; x < w; x++) {
                var at = line + x;
                if (plane[at] != 1) {
                    continue;
                }
                if (blueRow[y]) {
                    redGreenSmooth[at] = 0.5f * (redGreen[up + x] + redGreen[down + x]);
                    red[at] = green[at] + redGreenSmooth[at];
                }
                if (blueColumn[x]) {
                    redGreenSmooth[at] = 0.5f * (redGreen[line + cl1[x]] + redGreen[line + cr1[x]]);
                    red[at] = green[at] + redGreenSmooth[at];
                }
                if (redRow[y]) {
                    blueGreenSmooth[at] = 0.5f * (blueGreen[up + x] + blueGreen[down + x]);
                    blue[at] = green[at] + blueGreenSmooth[at];
                }
                if (redColumn[x]) {
                    blueGreenSmooth[at] = 0.5f * (blueGreen[line + cl1[x]] + blueGreen[line + cr1[x]]);
                    blue[at] = green[at] + blueGreenSmooth[at];
                }
            }
        }
        var redBlue = scratchA;
        for (var at = 0; at < cells; at++) {
            redBlue[at] = red[at] - blue[at];
        }
        var smoothed = scratchB;
        for (var y = 0; y < h; y++) {
            var line = y * w;
            var up = ru1[y] * w;
            var down = rd1[y] * w;
            for (var x = 0; x < w; x++) {
                var at = line + x;
                var p = plane[at];
                if (p == 1) {
                    continue;
                }
                smoothed[at] = horizontal[at]
                        ? (redBlue[line + cl1[x]] + redBlue[at] + redBlue[line + cr1[x]]) / 3f
                        : (redBlue[up + x] + redBlue[at] + redBlue[down + x]) / 3f;
            }
        }
        for (var at = 0; at < cells; at++) {
            var p = plane[at];
            if (p == 2) {
                red[at] = blue[at] + smoothed[at];
            } else if (p == 0) {
                blue[at] = red[at] - smoothed[at];
            }
        }
    }
}
