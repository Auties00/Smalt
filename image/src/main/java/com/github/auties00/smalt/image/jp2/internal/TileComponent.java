package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Describes one component of a tile: its area on the component's sample grid, its resolution
 * levels, sub-bands, precincts and code-blocks (ISO/IEC 15444-1 B.5 to B.7, and the arbitrary
 * decompositions of ISO/IEC 15444-2 F.2).
 *
 * @implNote This implementation follows OpenJPEG's {@code opj_tcd_init_tile}, including its
 * choice of a unit gain for every sub-band of an irreversible decoder using the 9-7 wavelet, which
 * its inverse 9-7 transform compensates. Sub-bands of an {@code ATK} kernel carry their gain in their
 * step size, as ISO/IEC 15444-1 equation E-3 states it.
 */
final class TileComponent {
    /**
     * The approximate heap cost of one code-block, used to bound the structure's size.
     */
    private static final long BLOCK_COST = 160;

    /**
     * The decoder code-blocks of a precinct built for encoding.
     */
    private static final CodeBlock[] NO_CODE_BLOCKS = {};

    /**
     * The encoder code-blocks of a precinct built for decoding.
     */
    private static final EncodedBlock[] NO_ENCODED_BLOCKS = {};

    /**
     * The left edge on the component grid.
     */
    final long x0;

    /**
     * The top edge on the component grid.
     */
    final long y0;

    /**
     * The right edge on the component grid (exclusive).
     */
    final long x1;

    /**
     * The bottom edge on the component grid (exclusive).
     */
    final long y1;

    /**
     * The coding style.
     */
    final ComponentCoding coding;

    /**
     * The {@code ATK} kernel of the wavelet, or {@code null} for a wavelet of ISO/IEC 15444-1.
     */
    final LiftingKernel kernel;

    /**
     * The resolution levels, lowest first.
     */
    final Resolution[] resolutions;

    /**
     * The number of resolution levels decoded, all of them unless the decode is reduced.
     */
    final int decodedResolutions;

    /**
     * The precision of the component in bits.
     */
    final int precision;

    /**
     * The total number of code-blocks, bounded by the limits.
     */
    private long blocks;

    /**
     * The index of the next sub-band in codestream order.
     */
    private int bandIndex;

    /**
     * Constructs a tile-component of the dyadic decomposition with a wavelet of ISO/IEC 15444-1 and its whole
     * structure.
     *
     * @param x0 the left edge on the component grid
     * @param y0 the top edge on the component grid
     * @param x1 the right edge on the component grid
     * @param y1 the bottom edge on the component grid
     * @param coding the coding style
     * @param quantization the step sizes and quantization style of the component
     * @param precision the component precision in bits
     * @param decodedResolutions the number of resolution levels to decode
     * @param encoder whether the structure serves an encoder
     * @param limits the limits bounding the structure's size
     * @throws MediaException.LimitExceeded if the structure would exceed the limits
     */
    TileComponent(long x0, long y0, long x1, long y1, ComponentCoding coding, Quantization quantization,
                  int precision, int decodedResolutions, boolean encoder, MediaLimits limits) {
        this(x0, y0, x1, y1, coding, quantization, precision, decodedResolutions, encoder, null, null, limits);
    }

    /**
     * Constructs a tile-component and its whole structure.
     *
     * @param x0 the left edge on the component grid
     * @param y0 the top edge on the component grid
     * @param x1 the right edge on the component grid
     * @param y1 the bottom edge on the component grid
     * @param coding the coding style
     * @param quantization the step sizes and quantization style of the component
     * @param precision the component precision in bits
     * @param decodedResolutions the number of resolution levels to decode
     * @param encoder whether the structure serves an encoder
     * @param kernel the {@code ATK} kernel of the wavelet, or {@code null}
     * @param decomposition the arbitrary decomposition, or {@code null} for the dyadic one
     * @param limits the limits bounding the structure's size
     * @throws MediaException.Malformed if a precinct of an arbitrary decomposition is smaller than its sub-band
     * splits allow
     * @throws MediaException.Unsupported if derived quantization meets a sub-band whose number of decomposition
     * levels is not an integer
     * @throws MediaException.LimitExceeded if the structure would exceed the limits
     */
    TileComponent(long x0, long y0, long x1, long y1, ComponentCoding coding, Quantization quantization,
                  int precision, int decodedResolutions, boolean encoder, LiftingKernel kernel,
                  Decomposition decomposition, MediaLimits limits) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.coding = coding;
        this.kernel = kernel;
        this.precision = precision;
        this.decodedResolutions = decodedResolutions;
        this.resolutions = new Resolution[coding.resolutions()];
        if (decomposition == null) {
            dyadic(quantization, encoder, limits);
        } else {
            arbitrary(quantization, decomposition, encoder, limits);
        }
    }

    /**
     * Returns whether the wavelet is reversible.
     *
     * @return {@code true} for the 5-3 wavelet or a reversible {@code ATK} kernel
     */
    boolean reversible() {
        return kernel == null ? coding.reversible() : kernel.reversible;
    }

    /**
     * Builds the resolutions of the dyadic decomposition (B.5 to B.7).
     *
     * @param quantization the step sizes and guard bits of the sub-bands
     * @param encoder whether the structure serves an encoder
     * @param limits the resource bounds
     */
    private void dyadic(Quantization quantization, boolean encoder, MediaLimits limits) {
        var count = coding.resolutions();
        for (var r = 0; r < count; r++) {
            var level = count - 1 - r;
            var rx0 = ceilDivPow2(x0, level);
            var ry0 = ceilDivPow2(y0, level);
            var rx1 = ceilDivPow2(x1, level);
            var ry1 = ceilDivPow2(y1, level);
            var pdx = coding.precinctWidth(r);
            var pdy = coding.precinctHeight(r);
            var grid = PrecinctGrid.of(rx0, ry0, rx1, ry1, pdx, pdy, limits);
            var bandCount = r == 0 ? 1 : 3;
            var bands = new Band[bandCount];
            var lower = r == 0 ? null : resolutions[r - 1];
            for (var b = 0; b < bandCount; b++) {
                var orientation = r == 0 ? 0 : b + 1;
                long bx0;
                long by0;
                long bx1;
                long by1;
                if (r == 0) {
                    bx0 = rx0;
                    by0 = ry0;
                    bx1 = rx1;
                    by1 = ry1;
                } else {
                    var xOffset = (long) (orientation & 1) << level;
                    var yOffset = (long) (orientation >> 1) << level;
                    bx0 = ceilDivPow2(x0 - xOffset, level + 1);
                    by0 = ceilDivPow2(y0 - yOffset, level + 1);
                    bx1 = ceilDivPow2(x1 - xOffset, level + 1);
                    by1 = ceilDivPow2(y1 - yOffset, level + 1);
                }
                var reversibleOrEncoder = encoder || reversible() || kernel != null;
                var gain = !reversibleOrEncoder || orientation == 0 ? 0 : orientation == 3 ? 2 : 1;
                var exponent = quantization.exponent(bandIndex);
                var mantissa = quantization.mantissa(bandIndex);
                var shift = r == 0 ? 0 : 1;
                var offsetX = (orientation & 1) != 0 ? lower.width() : 0;
                var offsetY = (orientation & 2) != 0 ? lower.height() : 0;
                bands[b] = band(orientation, bx0, by0, bx1, by1, exponent, mantissa, gain, grid, pdx - shift,
                        pdy - shift, shift, shift, offsetX, offsetY, quantization.guardBits(), encoder, limits);
            }
            resolutions[r] = new Resolution(rx0, ry0, rx1, ry1, pdx, pdy, grid.wide, grid.high, bands, level, level,
                    null);
        }
    }

    /**
     * Builds the resolutions of an arbitrary decomposition (F.2).
     *
     * @param quantization the step sizes and guard bits of the sub-bands
     * @param decomposition the shape of the arbitrary decomposition
     * @param encoder whether the structure serves an encoder
     * @param limits the resource bounds
     */
    private void arbitrary(Quantization quantization, Decomposition decomposition, boolean encoder,
                           MediaLimits limits) {
        var levels = coding.resolutions() - 1;
        var shape = decomposition.shape(levels);
        var placed = new IdentityHashMap<Decomposition.Node, SubbandNode>();
        place(shape.root(), x0, y0, x1, y1, 0, 0, placed);
        var depthX = 0;
        var depthY = 0;
        var depthsX = new int[levels + 1];
        var depthsY = new int[levels + 1];
        for (var lev = 1; lev <= levels; lev++) {
            var split = shape.resolutionNodes()[lev - 1].split;
            depthX += split == Decomposition.VERTICAL ? 0 : 1;
            depthY += split == Decomposition.HORIZONTAL ? 0 : 1;
            depthsX[lev] = depthX;
            depthsY[lev] = depthY;
        }
        var derived = quantization.style() == Quantization.SCALAR_DERIVED;
        var irreversibleKernel = kernel != null && !kernel.reversible;
        for (var r = 0; r <= levels; r++) {
            var node = placed.get(shape.resolutionNodes()[levels - r]);
            var pdx = coding.precinctWidth(r);
            var pdy = coding.precinctHeight(r);
            var grid = PrecinctGrid.of(node.x0(), node.y0(), node.x1(), node.y1(), pdx, pdy, limits);
            var members = shape.bands(r);
            var bands = new Band[members.size()];
            for (var b = 0; b < bands.length; b++) {
                var member = members.get(b);
                var area = placed.get(member);
                var exponent = derived ? quantization.derivedExponent(levels, member.doubledDepth)
                        : quantization.exponent(bandIndex);
                var mantissa = quantization.mantissa(bandIndex);
                var gain = encoder || reversible() || irreversibleKernel ? member.gain : 0;
                var splitsX = r == 0 ? 0 : member.splitsX;
                var splitsY = r == 0 ? 0 : member.splitsY;
                if (splitsX > pdx || splitsY > pdy) {
                    throw new MediaException.Malformed("JPEG 2000 precinct of 2^" + pdx + "x2^" + pdy
                            + " smaller than its sub-band splits");
                }
                bands[b] = band(member.primary, area.x0(), area.y0(), area.x1(), area.y1(), exponent, mantissa, gain,
                        grid, pdx - splitsX, pdy - splitsY, splitsX, splitsY, area.offsetX(), area.offsetY(),
                        quantization.guardBits(), encoder, limits);
            }
            resolutions[r] = new Resolution(node.x0(), node.y0(), node.x1(), node.y1(), pdx, pdy, grid.wide,
                    grid.high, bands, depthsX[levels - r], depthsY[levels - r], node);
        }
    }

    /**
     * Places a decomposition node and its descendants, computing their areas with Table F.2 and their buffer
     * positions.
     *
     * @param node the node to place, with its descendants
     * @param nx0 the left edge of the node
     * @param ny0 the top edge of the node
     * @param nx1 the right edge of the node
     * @param ny1 the bottom edge of the node
     * @param offsetX the buffer column of the node's first sample
     * @param offsetY the buffer row of the node's first sample
     * @param placed receives the placement of every node
     * @return the placed node
     */
    private static SubbandNode place(Decomposition.Node node, long nx0, long ny0, long nx1, long ny1, int offsetX,
                                     int offsetY, Map<Decomposition.Node, SubbandNode> placed) {
        SubbandNode[] children = null;
        if (node.children != null) {
            children = new SubbandNode[node.children.length];
            var lowWidth = (int) (ceilDivPow2(nx1, 1) - ceilDivPow2(nx0, 1));
            var lowHeight = (int) (ceilDivPow2(ny1, 1) - ceilDivPow2(ny0, 1));
            for (var j = 0; j < children.length; j++) {
                var child = node.children[j];
                var cx0 = edge(nx0, child.horizontal);
                var cx1 = edge(nx1, child.horizontal);
                var cy0 = edge(ny0, child.vertical);
                var cy1 = edge(ny1, child.vertical);
                var ox = offsetX + (child.horizontal == Decomposition.HIGH ? lowWidth : 0);
                var oy = offsetY + (child.vertical == Decomposition.HIGH ? lowHeight : 0);
                children[j] = place(child, cx0, cy0, cx1, cy1, ox, oy, placed);
            }
        }
        var result = new SubbandNode(node.split, nx0, ny0, nx1, ny1, offsetX, offsetY, children);
        placed.put(node, result);
        return result;
    }

    /**
     * Maps an edge of a node to its child (Table F.2).
     *
     * @param edge the parent's edge
     * @param filtering {@link Decomposition#LOW}, {@link Decomposition#HIGH} or {@link Decomposition#NONE}
     * @return {@code ceil(edge / 2)}, {@code floor(edge / 2)} or {@code edge}
     */
    private static long edge(long edge, int filtering) {
        return switch (filtering) {
            case Decomposition.LOW -> ceilDivPow2(edge, 1);
            case Decomposition.HIGH -> Math.floorDiv(edge, 2);
            default -> edge;
        };
    }

    /**
     * Holds the precinct partition of a resolution.
     *
     * @param precinctX0 the left edge of the first precinct column
     * @param precinctY0 the top edge of the first precinct row
     * @param wide the number of precinct columns
     * @param high the number of precinct rows
     */
    private record PrecinctGrid(long precinctX0, long precinctY0, int wide, int high) {
        /**
         * Computes the precinct partition of a resolution (B.6).
         *
         * @param rx0 the resolution's left edge
         * @param ry0 the resolution's top edge
         * @param rx1 the resolution's right edge
         * @param ry1 the resolution's bottom edge
         * @param pdx the base-2 logarithm of the precinct width
         * @param pdy the base-2 logarithm of the precinct height
         * @param limits the resource bounds
         * @return the partition
         */
        static PrecinctGrid of(long rx0, long ry0, long rx1, long ry1, int pdx, int pdy, MediaLimits limits) {
            var precinctX0 = (rx0 >> pdx) << pdx;
            var precinctY0 = (ry0 >> pdy) << pdy;
            var precinctX1 = ceilDivPow2(rx1, pdx) << pdx;
            var precinctY1 = ceilDivPow2(ry1, pdy) << pdy;
            var wide = rx0 == rx1 ? 0 : (int) ((precinctX1 - precinctX0) >> pdx);
            var high = ry0 == ry1 ? 0 : (int) ((precinctY1 - precinctY0) >> pdy);
            LimitChecks.checkAllocation(limits, (long) wide * high * 3 * 8);
            return new PrecinctGrid(precinctX0, precinctY0, wide, high);
        }
    }

    /**
     * Builds one sub-band with its precincts and code-blocks.
     *
     * @param orientation the context orientation
     * @param bx0 the left edge
     * @param by0 the top edge
     * @param bx1 the right edge
     * @param by1 the bottom edge
     * @param exponent the step size exponent
     * @param mantissa the step size mantissa
     * @param gain the base-2 logarithm of the sub-band gain
     * @param grid the resolution's precinct partition
     * @param groupWidth the base-2 logarithm of the precinct width in the sub-band
     * @param groupHeight the base-2 logarithm of the precinct height in the sub-band
     * @param splitsX the number of horizontal splits from the resolution to the sub-band
     * @param splitsY the number of vertical splits from the resolution to the sub-band
     * @param offsetX the buffer column of the first sample
     * @param offsetY the buffer row of the first sample
     * @param guardBits the number of guard bits
     * @param encoder whether the structure serves an encoder
     * @param limits the resource bounds
     * @return the sub-band
     */
    private Band band(int orientation, long bx0, long by0, long bx1, long by1, int exponent, int mantissa, int gain,
                      PrecinctGrid grid, int groupWidth, int groupHeight, int splitsX, int splitsY, int offsetX,
                      int offsetY, int guardBits, boolean encoder, MediaLimits limits) {
        var stepSize = (float) ((1.0 + mantissa / 2048.0) * StrictMath.pow(2.0, precision + gain - exponent));
        var bitPlanes = exponent + guardBits - 1;
        var empty = bx1 <= bx0 || by1 <= by0;
        var groupX0 = ceilDivPow2(grid.precinctX0, splitsX);
        var groupY0 = ceilDivPow2(grid.precinctY0, splitsY);
        var blockWidth = Math.min(coding.codeBlockWidth(), groupWidth);
        var blockHeight = Math.min(coding.codeBlockHeight(), groupHeight);
        var precincts = new Precinct[grid.wide * grid.high];
        if (!(encoder && empty)) {
            for (var p = 0; p < precincts.length; p++) {
                var groupStartX = groupX0 + (long) (p % grid.wide) * (1L << groupWidth);
                var groupStartY = groupY0 + (long) (p / grid.wide) * (1L << groupHeight);
                var px0 = Math.max(groupStartX, bx0);
                var py0 = Math.max(groupStartY, by0);
                var px1 = Math.min(groupStartX + (1L << groupWidth), bx1);
                var py1 = Math.min(groupStartY + (1L << groupHeight), by1);
                var wide = 0;
                var high = 0;
                if (px1 > px0 && py1 > py0) {
                    var blockX0 = (px0 >> blockWidth) << blockWidth;
                    var blockY0 = (py0 >> blockHeight) << blockHeight;
                    wide = (int) ((ceilDivPow2(px1, blockWidth) << blockWidth) - blockX0 >> blockWidth);
                    high = (int) ((ceilDivPow2(py1, blockHeight) << blockHeight) - blockY0 >> blockHeight);
                }
                blocks += (long) wide * high;
                LimitChecks.checkAllocation(limits, blocks * BLOCK_COST);
                var blockCount = wide * high;
                var codeBlocks = encoder ? NO_CODE_BLOCKS : new CodeBlock[blockCount];
                var encodedBlocks = encoder ? new EncodedBlock[blockCount] : NO_ENCODED_BLOCKS;
                for (var k = 0; k < blockCount; k++) {
                    var startX = ((px0 >> blockWidth) << blockWidth) + (long) (k % wide) * (1 << blockWidth);
                    var startY = ((py0 >> blockHeight) << blockHeight) + (long) (k / wide) * (1 << blockHeight);
                    var cx0 = Math.max(startX, px0);
                    var cy0 = Math.max(startY, py0);
                    var cx1 = Math.min(startX + (1 << blockWidth), px1);
                    var cy1 = Math.min(startY + (1 << blockHeight), py1);
                    if (encoder) {
                        encodedBlocks[k] = new EncodedBlock(cx0, cy0, cx1, cy1);
                    } else {
                        codeBlocks[k] = new CodeBlock(cx0, cy0, cx1, cy1);
                    }
                }
                precincts[p] = new Precinct(wide, high, codeBlocks, encodedBlocks);
            }
        }
        return new Band(orientation, bx0, by0, bx1, by1, stepSize, bitPlanes, bandIndex++, precincts, offsetX,
                offsetY);
    }

    /**
     * Divides by a power of two, rounding up.
     *
     * @param value the dividend, possibly negative
     * @param shift the base-2 logarithm of the divisor
     * @return {@code ceil(value / 2^shift)}
     */
    static long ceilDivPow2(long value, int shift) {
        return (value + (1L << shift) - 1) >> shift;
    }

    /**
     * Returns the resolution level the decoded samples are at.
     *
     * @return the highest decoded resolution
     */
    Resolution top() {
        return resolutions[decodedResolutions - 1];
    }
}
