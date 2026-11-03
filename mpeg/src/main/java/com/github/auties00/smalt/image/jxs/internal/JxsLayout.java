/* Ports SVT-JPEG-XS 0.9.0 (Pi.c). Copyright 2024 Intel Corporation; BSD-2-Clause-Patent. Modified for Smalt. */
package com.github.auties00.smalt.image.jxs.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * The geometry of a JPEG XS picture (ISO/IEC 21122-1 annex B): the components, their wavelet bands, the global band
 * order, the packets of a precinct and the precinct grid.
 *
 * <p>Within a component, band 0 is the lowest-pass band, followed by the high-pass bands of the horizontal-only levels
 * from the deepest to the finest, then, for every level that is both horizontal and vertical from the deepest to the
 * finest, the bands that are high-pass horizontally, vertically and in both directions. Instances are immutable.
 *
 * @implNote This implementation derives the precinct band widths from the precinct width in component samples, which
 * is a multiple of {@code 2^NLx}, shifted right by the horizontal depth of the band, rather than by scaling the band
 * width, so that every precinct but the last covers the same coefficients in every band.
 */
public final class JxsLayout {
    private static final int SHORT_HEADER_LIMIT = 32752;

    /**
     * The number of components {@code Nc}.
     */
    public final int components;
    /**
     * The number of components with a wavelet decomposition, {@code Nc - Sd}.
     */
    public final int decomposed;
    /**
     * The width in samples of each component.
     */
    public final int[] componentWidth;
    /**
     * The height in samples of each component.
     */
    public final int[] componentHeight;
    /**
     * The number of horizontal decomposition levels of each component.
     */
    public final int[] levelsX;
    /**
     * The number of vertical decomposition levels of each component.
     */
    public final int[] levelsY;
    /**
     * The number of existing bands of each component.
     */
    public final int[] bandCount;
    /**
     * The width in coefficients of band {@code b} of component {@code c}, indexed {@code [c][b]}.
     */
    public final int[][] bandWidth;
    /**
     * The height in coefficients of band {@code b} of component {@code c}, indexed {@code [c][b]}.
     */
    public final int[][] bandHeight;
    /**
     * The horizontal position of band {@code b} of component {@code c} in the coefficient plane of the component,
     * indexed {@code [c][b]}.
     */
    public final int[][] bandX;
    /**
     * The vertical position of band {@code b} of component {@code c} in the coefficient plane of the component,
     * indexed {@code [c][b]}.
     */
    public final int[][] bandY;
    /**
     * The number of lines of band {@code b} of component {@code c} in a precinct other than the last row, indexed
     * {@code [c][b]}.
     */
    public final int[][] bandLines;
    /**
     * The number {@code NL} of global band indices, existing or not.
     */
    public final int globalBands;
    /**
     * The component of each global band index, or -1 where the band does not exist.
     */
    public final int[] globalComponent;
    /**
     * The band within its component of each global band index, or -1 where the band does not exist.
     */
    public final int[] globalBand;
    /**
     * The number of existing bands over all components.
     */
    public final int existingBands;
    /**
     * The number of packets of a precinct.
     */
    public final int packets;
    /**
     * The global band indices each packet carries, indexed by packet in coding order.
     */
    public final int[][] packetBands;
    /**
     * The band line within the precinct each packet carries, indexed by packet.
     */
    public final int[] packetLine;
    /**
     * The number of precinct rows of the picture.
     */
    public final int precinctRows;
    /**
     * The number of precinct columns of the picture.
     */
    public final int precinctColumns;
    /**
     * The number of precinct rows of every slice but the last.
     */
    public final int rowsPerSlice;
    /**
     * The number of slices of the picture.
     */
    public final int slices;
    /**
     * Whether the packet headers of the codestream are short.
     */
    public final boolean shortHeaders;
    /**
     * The width in coefficients of band {@code b} of component {@code c} in a precinct of any column but the last,
     * indexed {@code [c][b]}.
     */
    public final int[][] precinctBandWidth;
    /**
     * The width in coefficients of band {@code b} of component {@code c} in a precinct of the last column, indexed
     * {@code [c][b]}.
     */
    public final int[][] lastPrecinctBandWidth;
    /**
     * The number of lines of band {@code b} of component {@code c} in a precinct of the last row, indexed
     * {@code [c][b]}.
     */
    public final int[][] lastPrecinctBandLines;

    /**
     * Builds the layout of the picture a main header describes.
     *
     * @param header the main header
     * @throws MediaException.Malformed if the decomposition does not fit the picture or the weights table does not
     * list one entry per existing band
     */
    public JxsLayout(JxsHeader header) {
        this(header.components, header.width, header.height, header.horizontalLevels, header.verticalLevels,
                header.suppressedComponents, header.sx, header.sy, header.precinctWidth, header.sliceHeight,
                header.longHeaders);
        if (header.gains == null || header.gains.length != existingBands) {
            throw new MediaException.Malformed("the JPEG XS weights table lists "
                    + (header.gains == null ? 0 : header.gains.length) + " bands, not " + existingBands);
        }
    }

    /**
     * Builds the layout of a picture from its coding parameters.
     *
     * @param components the number of components {@code Nc}
     * @param width the width {@code Wf} of the sampling grid
     * @param height the height {@code Hf} of the sampling grid
     * @param nlx the number of horizontal decomposition levels {@code NL,x}
     * @param nly the number of vertical decomposition levels {@code NL,y}
     * @param sd the number {@code Sd} of trailing components without decomposition
     * @param sx the horizontal sampling factor of each component
     * @param sy the vertical sampling factor of each component
     * @param cw the precinct width {@code Cw}, or 0 for precincts as wide as the picture
     * @param hsl the slice height {@code Hsl} in precincts
     * @param longHeaders whether long packet headers are enforced ({@code Lh})
     * @throws MediaException.Malformed if the decomposition does not fit the picture
     */
    public JxsLayout(int components, int width, int height, int nlx, int nly, int sd, int[] sx, int[] sy, int cw,
                     int hsl, boolean longHeaders) {
        if (components < 1 || sd < 0 || sd > components || nlx < 0 || nlx > 15 || nly < 0 || nly > 2 || nly > nlx
                || hsl < 1 || cw < 0 || width < 1 || height < 1 || sx.length < components || sy.length < components) {
            throw new MediaException.Malformed("the JPEG XS coding parameters are inconsistent: " + components
                    + " components, " + sd + " without decomposition, " + nlx + " horizontal and " + nly
                    + " vertical levels, slice height " + hsl);
        }
        this.components = components;
        this.decomposed = components - sd;
        componentWidth = new int[components];
        componentHeight = new int[components];
        levelsX = new int[components];
        levelsY = new int[components];
        bandCount = new int[components];
        bandWidth = new int[components][];
        bandHeight = new int[components][];
        bandX = new int[components][];
        bandY = new int[components][];
        bandLines = new int[components][];
        var depth = new int[components][];
        var beta = new int[components][];
        var betas = 2 * nly + nlx + 1;
        var precinctHeight = 1 << nly;
        var sxMax = 1;
        for (var c = 0; c < components; c++) {
            if (sx[c] < 1 || sx[c] > 2 || sy[c] < 1 || sy[c] > 2) {
                throw new MediaException.Malformed("JPEG XS component " + c + " has sampling factors " + sx[c] + "x"
                        + sy[c]);
            }
            sxMax = Math.max(sxMax, sx[c]);
            componentWidth[c] = width / sx[c];
            componentHeight[c] = height / sy[c];
            if (componentWidth[c] < 1 || componentHeight[c] < 1 || precinctHeight / sy[c] < 1) {
                throw new MediaException.Malformed("JPEG XS component " + c + " has no samples or no precinct lines");
            }
            if (c < decomposed) {
                var reduction = sy[c] == 2 ? 1 : 0;
                if (nly < reduction) {
                    throw new MediaException.Malformed("JPEG XS component " + c + " is vertically sub-sampled "
                            + "without a vertical decomposition level");
                }
                levelsX[c] = nlx;
                levelsY[c] = nly - reduction;
                decompose(c, nly, nlx, depth, beta);
            } else {
                bandCount[c] = 1;
                bandWidth[c] = new int[]{componentWidth[c]};
                bandHeight[c] = new int[]{componentHeight[c]};
                bandX[c] = new int[1];
                bandY[c] = new int[1];
                bandLines[c] = new int[]{Math.min(precinctHeight / sy[c], componentHeight[c])};
                depth[c] = new int[1];
                beta[c] = new int[]{-1};
            }
        }

        globalBands = betas * decomposed + sd;
        globalComponent = new int[globalBands];
        globalBand = new int[globalBands];
        Arrays.fill(globalComponent, -1);
        Arrays.fill(globalBand, -1);
        var existing = 0;
        for (var c = 0; c < components; c++) {
            for (var b = 0; b < bandCount[c]; b++) {
                var g = c < decomposed ? beta[c][b] * decomposed + c : betas * decomposed + c - decomposed;
                globalComponent[g] = c;
                globalBand[g] = b;
                existing++;
            }
        }
        existingBands = existing;

        var bandsOfPacket = new int[globalBands * 4 + 1][];
        var lineOfPacket = new int[globalBands * 4 + 1];
        var count = 0;
        var horizontalBetas = nlx - nly + 1;
        count = addPacket(bandsOfPacket, lineOfPacket, count, 0, horizontalBetas, 0);
        for (var b = horizontalBetas; b < betas; b++) {
            count = addPacket(bandsOfPacket, lineOfPacket, count, b, b + 1, 0);
        }
        for (var b = horizontalBetas; b < betas; b++) {
            count = addPacket(bandsOfPacket, lineOfPacket, count, b, b + 1, 1);
        }
        for (var c = decomposed; c < components; c++) {
            var g = betas * decomposed + c - decomposed;
            for (var line = 0; line < bandLines[c][0]; line++) {
                bandsOfPacket[count] = new int[]{g};
                lineOfPacket[count] = line;
                count++;
            }
        }
        packets = count;
        packetBands = Arrays.copyOf(bandsOfPacket, count);
        packetLine = Arrays.copyOf(lineOfPacket, count);

        precinctRows = (height + precinctHeight - 1) / precinctHeight;
        rowsPerSlice = hsl;
        slices = (precinctRows + hsl - 1) / hsl;
        shortHeaders = !longHeaders && (long) width * components < SHORT_HEADER_LIMIT;

        var precinctSamples = cw == 0 ? Long.MAX_VALUE : 8L * cw * sxMax << nlx;
        precinctColumns = precinctSamples >= width ? 1 : (int) ((width + precinctSamples - 1) / precinctSamples);
        precinctBandWidth = new int[components][];
        lastPrecinctBandWidth = new int[components][];
        lastPrecinctBandLines = new int[components][];
        for (var c = 0; c < components; c++) {
            var bands = bandCount[c];
            precinctBandWidth[c] = new int[bands];
            lastPrecinctBandWidth[c] = new int[bands];
            lastPrecinctBandLines[c] = new int[bands];
            for (var b = 0; b < bands; b++) {
                var full = bandWidth[c][b];
                var normal = precinctColumns == 1 ? full : (int) (precinctSamples / sx[c] >> depth[c][b]);
                var last = full - (precinctColumns - 1) * normal;
                if (normal < 1 || last < 1) {
                    throw new MediaException.Malformed("the JPEG XS precinct grid does not tile band " + b
                            + " of component " + c);
                }
                precinctBandWidth[c][b] = normal;
                lastPrecinctBandWidth[c][b] = last;
                var lastLines = bandHeight[c][b] - (precinctRows - 1) * bandLines[c][b];
                if (lastLines < 0 || lastLines > bandLines[c][b]) {
                    throw new MediaException.Malformed("the JPEG XS precinct rows do not tile band " + b
                            + " of component " + c);
                }
                lastPrecinctBandLines[c][b] = lastLines;
            }
        }
    }

    /**
     * Splits decomposed component {@code c} into its bands, recording the horizontal depth and the filter type index
     * (beta) of each band.
     */
    private void decompose(int c, int nly, int nlx, int[][] depth, int[][] beta) {
        var lx = levelsX[c];
        var ly = levelsY[c];
        var bands = 2 * ly + lx + 1;
        bandCount[c] = bands;
        var w = new int[bands];
        var h = new int[bands];
        var x = new int[bands];
        var y = new int[bands];
        var lines = new int[bands];
        var d = new int[bands];
        var t = new int[bands];
        var regionWidth = componentWidth[c];
        var regionHeight = componentHeight[c];
        var index = bands - 1;
        for (var split = 0; split < lx; split++) {
            var lowWidth = (regionWidth + 1) >>> 1;
            var highWidth = regionWidth >>> 1;
            if (split < ly) {
                var lowHeight = (regionHeight + 1) >>> 1;
                var highHeight = regionHeight >>> 1;
                var bandLinesOfLevel = 1 << (ly - split - 1);
                for (var kind = 2; kind >= 0; kind--) {
                    var i = index - (2 - kind);
                    var right = kind != 1;
                    var below = kind != 0;
                    x[i] = right ? lowWidth : 0;
                    y[i] = below ? lowHeight : 0;
                    w[i] = right ? highWidth : lowWidth;
                    h[i] = below ? highHeight : lowHeight;
                    lines[i] = bandLinesOfLevel;
                    d[i] = split + 1;
                    t[i] = nlx - nly + 1 + 3 * (nly - 1 - split) + kind;
                }
                index -= 3;
                regionHeight = lowHeight;
            } else {
                x[index] = lowWidth;
                w[index] = highWidth;
                h[index] = regionHeight;
                lines[index] = 1;
                d[index] = split + 1;
                t[index] = split < nly ? nlx - nly + 1 + 3 * (nly - 1 - split) : nlx - split;
                index--;
            }
            regionWidth = lowWidth;
        }
        w[0] = regionWidth;
        h[0] = regionHeight;
        lines[0] = 1;
        d[0] = lx;
        t[0] = 0;
        for (var b = 0; b < bands; b++) {
            if (w[b] < 1 || h[b] < 1) {
                throw new MediaException.Malformed("the JPEG XS decomposition leaves band " + b + " of component " + c
                        + " empty");
            }
        }
        bandWidth[c] = w;
        bandHeight[c] = h;
        bandX[c] = x;
        bandY[c] = y;
        bandLines[c] = lines;
        depth[c] = d;
        beta[c] = t;
    }

    /**
     * Appends the packet of line {@code line} carrying the existing bands of filter types {@code from} (inclusive) to
     * {@code to} (exclusive) of the decomposed components that have that line, unless it would be empty.
     *
     * @return the new packet count
     */
    private int addPacket(int[][] bands, int[] lines, int count, int from, int to, int line) {
        var list = new int[(to - from) * decomposed];
        var size = 0;
        for (var g = from * decomposed; g < to * decomposed; g++) {
            var c = globalComponent[g];
            if (c >= 0 && bandLines[c][globalBand[g]] > line) {
                list[size++] = g;
            }
        }
        if (size == 0) {
            return count;
        }
        bands[count] = Arrays.copyOf(list, size);
        lines[count] = line;
        return count + 1;
    }
}
