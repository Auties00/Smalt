/* Ports SVT-JPEG-XS 0.9.0 (Pi.c). Copyright 2024 Intel Corporation; BSD-2-Clause-Patent. Modified for Smalt.
   Ports the ISO/IEC 21122-5 ed. 3 reference software (ids.c: the packet bands and the precinct band widths).
   Copyright intoPIX SA, Fraunhofer IIS and Canon Inc; Copyright License for Evaluation and Testing (see
   THIRD-PARTY-NOTICES). Modified for Smalt. */
package com.github.auties00.smalt.image.jxs.internal;

import com.github.auties00.smalt.MediaException;

/**
 * The geometry of a JPEG XS picture (ISO/IEC 21122-1 annex B): the components, their wavelet bands, the global band
 * order, the packets of a precinct and the precinct grid.
 *
 * <p>Instances are immutable.
 */
public final class JxsLayout {
    /**
     * The number of components.
     */
    public final int components;
    /**
     * The width of the sampling grid.
     */
    public final int width;
    /**
     * The height of the sampling grid.
     */
    public final int height;
    /**
     * The number of components with a wavelet decomposition ({@code Nc - Sd}).
     */
    public final int decomposed;
    /**
     * The width of each component.
     */
    public final int[] componentWidth;
    /**
     * The height of each component.
     */
    public final int[] componentHeight;
    /**
     * The horizontal decomposition levels of each component.
     */
    public final int[] levelsX;
    /**
     * The vertical decomposition levels of each component.
     */
    public final int[] levelsY;
    /**
     * The height of a precinct of each component, in lines.
     */
    public final int[] precinctLines;
    /**
     * The number of bands of each component.
     */
    public final int[] bandCount;
    /**
     * The width of each band of each component, indexed {@code [c][b]}.
     */
    public final int[][] bandWidth;
    /**
     * The height of each band of each component.
     */
    public final int[][] bandHeight;
    /**
     * The horizontal position of each band in the component plane laid out with every level's low band at the
     * origin.
     */
    public final int[][] bandX;
    /**
     * The vertical position of each band in the component plane.
     */
    public final int[][] bandY;
    /**
     * The number of lines of each band in a precinct, 1 or 2 for decomposed components and up to 4 otherwise.
     */
    public final int[][] bandLines;
    /**
     * The global index of each band of each component.
     */
    public final int[][] globalIndex;
    /**
     * The number of global band indices, existing or not ({@code NL}).
     */
    public final int globalBands;
    /**
     * The component of each global band, or -1 where the band does not exist.
     */
    public final int[] globalComponent;
    /**
     * The band within its component of each global band, or -1 where the band does not exist.
     */
    public final int[] globalBand;
    /**
     * The number of bands that exist.
     */
    public final int existingBands;
    /**
     * The number of packets of a precinct.
     */
    public final int packets;
    /**
     * The global bands of each packet, in coding order.
     */
    public final int[][] packetBands;
    /**
     * The band line each packet codes.
     */
    public final int[] packetLine;
    /**
     * The precinct height of the sampling grid, {@code 2^NLy}.
     */
    public final int precinctHeight;
    /**
     * The number of precinct rows.
     */
    public final int precinctRows;
    /**
     * The number of precinct columns.
     */
    public final int precinctColumns;
    /**
     * The number of precinct rows of a slice other than the last.
     */
    public final int rowsPerSlice;
    /**
     * The number of slices.
     */
    public final int slices;
    /**
     * Whether packet headers are short.
     */
    public final boolean shortHeaders;
    /**
     * The width of each band in a precinct of a column other than the last, indexed {@code [c][b]}.
     */
    public final int[][] precinctBandWidth;
    /**
     * The width of each band in a precinct of the last column.
     */
    public final int[][] lastPrecinctBandWidth;
    /**
     * The number of lines of each band in a precinct of the last row.
     */
    public final int[][] lastPrecinctBandLines;

    /**
     * Computes the layout of the picture a header describes.
     *
     * @param header the main header
     * @throws MediaException.Malformed if the decomposition does not fit the picture or the weights table does not
     * list one entry per band
     */
    public JxsLayout(JxsHeader header) {
        this(header.components, header.width, header.height, header.horizontalLevels, header.verticalLevels,
                header.suppressedComponents, header.sx, header.sy, header.precinctWidth, header.sliceHeight,
                header.longHeaders);
        if (header.gains.length != existingBands) {
            throw new MediaException.Malformed("the JPEG XS weights table lists " + header.gains.length
                    + " bands, the picture has " + existingBands);
        }
        if (header.refreshGains != null && header.refreshGains.length != existingBands) {
            throw new MediaException.Malformed("the JPEG XS refresh weights table lists " + header.refreshGains.length
                    + " bands, the picture has " + existingBands);
        }
    }

    /**
     * Computes the layout of a picture from its parameters.
     *
     * @param components the number of components
     * @param width the width of the sampling grid
     * @param height the height of the sampling grid
     * @param nlx the number of horizontal decomposition levels
     * @param nly the number of vertical decomposition levels
     * @param sd the number of trailing components without decomposition
     * @param sx the horizontal sampling factor of each component
     * @param sy the vertical sampling factor of each component
     * @param cw the precinct width {@code Cw}, or 0 for the full width
     * @param hsl the slice height in precincts
     * @param longHeaders whether long packet headers are enforced
     * @throws MediaException.Malformed if the decomposition does not fit the picture
     */
    public JxsLayout(int components, int width, int height, int nlx, int nly, int sd, int[] sx, int[] sy, int cw,
                     int hsl, boolean longHeaders) {
        this.components = components;
        this.width = width;
        this.height = height;
        this.decomposed = components - sd;
        if (sd > components || nly > nlx || hsl < 1) {
            throw new MediaException.Malformed("the JPEG XS decomposition parameters are inconsistent");
        }
        precinctHeight = 1 << nly;
        componentWidth = new int[components];
        componentHeight = new int[components];
        levelsX = new int[components];
        levelsY = new int[components];
        precinctLines = new int[components];
        bandCount = new int[components];
        bandWidth = new int[components][];
        bandHeight = new int[components][];
        bandX = new int[components][];
        bandY = new int[components][];
        bandLines = new int[components][];
        globalIndex = new int[components][];
        var existing = 0;
        for (var c = 0; c < components; c++) {
            componentWidth[c] = width / sx[c];
            componentHeight[c] = height / sy[c];
            precinctLines[c] = precinctHeight / sy[c];
            if (componentWidth[c] == 0 || componentHeight[c] == 0 || precinctLines[c] == 0) {
                throw new MediaException.Malformed("JPEG XS component " + c + " is empty or has no precinct lines");
            }
            if (c < decomposed) {
                levelsX[c] = nlx;
                if (nly < sy[c] / 2) {
                    throw new MediaException.Malformed("the JPEG XS vertically subsampled component " + c
                            + " needs a vertical decomposition level");
                }
                levelsY[c] = nly - sy[c] / 2;
            }
            bandCount[c] = 2 * levelsY[c] + levelsX[c] + 1;
            existing += bandCount[c];
        }
        existingBands = existing;
        var perComponent = 2 * nly + nlx + 1;
        globalBands = perComponent * decomposed + sd;
        globalComponent = new int[globalBands];
        globalBand = new int[globalBands];
        java.util.Arrays.fill(globalComponent, -1);
        java.util.Arrays.fill(globalBand, -1);
        for (var c = 0; c < decomposed; c++) {
            var count = bandCount[c];
            globalIndex[c] = new int[count];
            var ignore = perComponent - count;
            var atTheEnd = 3 * levelsY[c];
            var id = c;
            for (var i = 0; i < count; i++) {
                globalIndex[c][i] = id;
                globalComponent[id] = c;
                globalBand[id] = i;
                id += decomposed;
                if (ignore > 0 && i == count - atTheEnd + 1 - ignore) {
                    id += decomposed * ignore;
                }
            }
        }
        for (var c = decomposed; c < components; c++) {
            var id = decomposed * perComponent + c - decomposed;
            globalIndex[c] = new int[]{id};
            globalComponent[id] = c;
            globalBand[id] = 0;
        }
        for (var c = 0; c < components; c++) {
            var count = bandCount[c];
            var w = new int[count];
            var h = new int[count];
            var x = new int[count];
            var y = new int[count];
            var level = count - 1;
            w[level] = componentWidth[c];
            h[level] = componentHeight[c];
            for (var d = 0; d < levelsY[c]; d++) {
                var tw = w[level];
                var th = h[level];
                var w2 = tw / 2;
                var w1 = tw - w2;
                var h2 = th / 2;
                var h1 = th - h2;
                w[level] = w2;
                h[level] = h2;
                x[level] = w1;
                y[level] = h1;
                w[level - 1] = w1;
                h[level - 1] = h2;
                x[level - 1] = 0;
                y[level - 1] = h1;
                w[level - 2] = w2;
                h[level - 2] = h1;
                x[level - 2] = w1;
                y[level - 2] = 0;
                w[level - 3] = w1;
                h[level - 3] = h1;
                x[level - 3] = 0;
                y[level - 3] = 0;
                level -= 3;
            }
            for (var d = levelsY[c]; d < levelsX[c]; d++) {
                var tw = w[level];
                var th = h[level];
                var w2 = tw / 2;
                var w1 = tw - w2;
                w[level] = w2;
                h[level] = th;
                x[level] = w1;
                y[level] = 0;
                w[level - 1] = w1;
                h[level - 1] = th;
                x[level - 1] = 0;
                y[level - 1] = 0;
                level--;
            }
            var lines = new int[count];
            for (var b = 0; b < count; b++) {
                if (w[b] == 0 || h[b] == 0) {
                    throw new MediaException.Malformed("the JPEG XS decomposition of component " + c
                            + " leaves an empty band");
                }
                if (c < decomposed) {
                    lines[b] = levelsY[c] == 2 && b >= count - 3 ? 2 : 1;
                } else {
                    lines[b] = Math.min(precinctHeight >> (sy[c] - 1), componentHeight[c]);
                }
            }
            bandWidth[c] = w;
            bandHeight[c] = h;
            bandX[c] = x;
            bandY[c] = y;
            bandLines[c] = lines;
        }

        var bandsOf = new java.util.ArrayList<int[]>();
        var lineOf = new java.util.ArrayList<Integer>();
        var first = nlx - nly + 1;
        if (decomposed > 0) {
            var list = new java.util.ArrayList<Integer>();
            for (var beta = 0; beta < first; beta++) {
                for (var c = 0; c < decomposed; c++) {
                    var g = beta * decomposed + c;
                    if (globalBand[g] < 0) {
                        throw new MediaException.Malformed("the JPEG XS band layout lacks band " + beta
                                + " of component " + c);
                    }
                    list.add(g);
                }
            }
            bandsOf.add(list.stream().mapToInt(Integer::intValue).toArray());
            lineOf.add(0);
            for (var beta0 = first; beta0 < perComponent; beta0 += 3) {
                var lines = bandLines[0][globalBand[beta0 * decomposed]];
                for (var line = 0; line < lines; line++) {
                    for (var beta = beta0; beta < beta0 + 3; beta++) {
                        list.clear();
                        for (var c = 0; c < decomposed; c++) {
                            var g = beta * decomposed + c;
                            if (globalBand[g] >= 0 && line < bandLines[c][globalBand[g]]) {
                                list.add(g);
                            }
                        }
                        if (!list.isEmpty()) {
                            bandsOf.add(list.stream().mapToInt(Integer::intValue).toArray());
                            lineOf.add(line);
                        }
                    }
                }
            }
        }
        for (var line = 0; line < precinctHeight; line++) {
            for (var c = decomposed; c < components; c++) {
                if (line < bandLines[c][0]) {
                    bandsOf.add(new int[]{globalIndex[c][0]});
                    lineOf.add(line);
                }
            }
        }
        packets = bandsOf.size();
        packetBands = bandsOf.toArray(int[][]::new);
        packetLine = lineOf.stream().mapToInt(Integer::intValue).toArray();

        shortHeaders = !longHeaders && (long) width * components < 32752;
        precinctRows = (height + precinctHeight - 1) / precinctHeight;
        var sliceLines = (long) hsl * precinctHeight;
        rowsPerSlice = hsl;
        slices = (int) ((height + sliceLines - 1) / sliceLines);
        precinctBandWidth = new int[components][];
        lastPrecinctBandWidth = new int[components][];
        lastPrecinctBandLines = new int[components][];
        if (cw == 0) {
            precinctColumns = 1;
        } else {
            var maxSx = 0;
            for (var c = 0; c < components; c++) {
                maxSx = Math.max(maxSx, sx[c]);
            }
            var span = 8L * cw * maxSx * (1L << nlx);
            precinctColumns = (int) ((width + span - 1) / span);
        }
        var columnWidth = width;
        if (cw != 0) {
            var maxSx = 0;
            for (var c = 0; c < components; c++) {
                maxSx = Math.max(maxSx, sx[c]);
            }
            columnWidth = (int) Math.min(Integer.MAX_VALUE, 8L * cw * maxSx * (1L << nlx));
        }
        var lastColumnWidth = (width - 1) % columnWidth + 1;
        for (var c = 0; c < components; c++) {
            var bands = bandCount[c];
            var normal = new int[bands];
            var last = new int[bands];
            var lastLines = new int[bands];
            for (var b = 0; b < bands; b++) {
                normal[b] = precinctBandWidth(c, b, columnWidth, sx[c]);
                last[b] = precinctBandWidth(c, b, lastColumnWidth, sx[c]);
                lastLines[b] = bandHeight[c][b] - (precinctRows - 1) * bandLines[c][b];
                if (last[b] <= 0 || lastLines[b] < 0 || normal[b] <= 0
                        || (long) (precinctColumns - 1) * normal[b] + last[b] != bandWidth[c][b]) {
                    throw new MediaException.Malformed("the JPEG XS precinct grid does not fit band " + b
                            + " of component " + c);
                }
            }
            precinctBandWidth[c] = normal;
            lastPrecinctBandWidth[c] = last;
            lastPrecinctBandLines[c] = lastLines;
        }
    }

    /**
     * Returns the width in a band of a precinct {@code pw} sampling grid points wide: the low-pass bands of
     * horizontal depth {@code d} hold {@code ceil(pw' / 2^d)} coefficients, the high-pass ones
     * {@code floor(ceil(pw' / 2^(d - 1)) / 2)}, {@code pw'} being the width in component samples.
     */
    private int precinctBandWidth(int c, int b, int pw, int sx) {
        var componentWidth = (pw + sx - 1) / sx;
        if (c >= decomposed) {
            return componentWidth;
        }
        var horizontal = levelsX[c] - levelsY[c];
        int depth;
        boolean high;
        if (b == 0) {
            depth = levelsX[c];
            high = false;
        } else if (b <= horizontal) {
            depth = levelsX[c] - b + 1;
            high = true;
        } else {
            var j = (b - horizontal - 1) / 3;
            depth = levelsY[c] - j;
            high = (b - horizontal - 1) % 3 != 1;
        }
        if (high) {
            var d = 1 << (depth - 1);
            return (componentWidth + d - 1) / d / 2;
        }
        var d = 1 << depth;
        return (componentWidth + d - 1) / d;
    }
}
