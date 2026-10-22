package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Describes one component of a tile: its area on the component's sample grid, its resolution
 * levels, sub-bands, precincts and code-blocks (ISO/IEC 15444-1 B.5 to B.7).
 *
 * @implNote This implementation follows OpenJPEG's {@code opj_tcd_init_tile}, including its
 * choice of a unit gain for every sub-band of an irreversible decoder, which its inverse 9-7
 * transform compensates.
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
    final int x0;

    /**
     * The top edge on the component grid.
     */
    final int y0;

    /**
     * The right edge on the component grid (exclusive).
     */
    final int x1;

    /**
     * The bottom edge on the component grid (exclusive).
     */
    final int y1;

    /**
     * The coding style.
     */
    final ComponentCoding coding;

    /**
     * The resolution levels, lowest first.
     */
    final Resolution[] resolutions;

    /**
     * The number of resolution levels decoded, all of them unless the decode is reduced.
     */
    final int decodedResolutions;

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
     * @param limits the limits bounding the structure's size
     * @throws MediaException.LimitExceeded if the structure would exceed the limits
     */
    TileComponent(int x0, int y0, int x1, int y1, ComponentCoding coding, Quantization quantization, int precision,
                  int decodedResolutions, boolean encoder, MediaLimits limits) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.coding = coding;
        this.decodedResolutions = decodedResolutions;
        var count = coding.resolutions();
        this.resolutions = new Resolution[count];
        var blocks = 0L;
        var bandIndex = 0;
        for (var r = 0; r < count; r++) {
            var level = count - 1 - r;
            var rx0 = ceilDivPow2(x0, level);
            var ry0 = ceilDivPow2(y0, level);
            var rx1 = ceilDivPow2(x1, level);
            var ry1 = ceilDivPow2(y1, level);
            var pdx = coding.precinctWidth(r);
            var pdy = coding.precinctHeight(r);
            var precinctX0 = (rx0 >> pdx) << pdx;
            var precinctY0 = (ry0 >> pdy) << pdy;
            var precinctX1 = ceilDivPow2(rx1, pdx) << pdx;
            var precinctY1 = ceilDivPow2(ry1, pdy) << pdy;
            var precinctsWide = rx0 == rx1 ? 0 : (int) ((precinctX1 - precinctX0) >> pdx);
            var precinctsHigh = ry0 == ry1 ? 0 : (int) ((precinctY1 - precinctY0) >> pdy);
            var precinctCount = (long) precinctsWide * precinctsHigh;
            LimitChecks.checkAllocation(limits, precinctCount * 3 * 8);
            long groupX0;
            long groupY0;
            int groupWidth;
            int groupHeight;
            if (r == 0) {
                groupX0 = precinctX0;
                groupY0 = precinctY0;
                groupWidth = pdx;
                groupHeight = pdy;
            } else {
                groupX0 = ceilDivPow2(precinctX0, 1);
                groupY0 = ceilDivPow2(precinctY0, 1);
                groupWidth = pdx - 1;
                groupHeight = pdy - 1;
            }
            var blockWidth = Math.min(coding.codeBlockWidth(), groupWidth);
            var blockHeight = Math.min(coding.codeBlockHeight(), groupHeight);
            var bandCount = r == 0 ? 1 : 3;
            var bands = new Band[bandCount];
            for (var b = 0; b < bandCount; b++, bandIndex++) {
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
                var gain = (!encoder && !coding.reversible()) || orientation == 0 ? 0 : orientation == 3 ? 2 : 1;
                var exponent = quantization.exponent(bandIndex);
                var mantissa = quantization.mantissa(bandIndex);
                var stepSize = (float) ((1.0 + mantissa / 2048.0) * StrictMath.pow(2.0, precision + gain - exponent));
                var bitPlanes = exponent + quantization.guardBits() - 1;
                var empty = bx1 <= bx0 || by1 <= by0;
                var precincts = new Precinct[(int) precinctCount];
                if (!(encoder && empty)) {
                    for (var p = 0; p < precincts.length; p++) {
                        var groupStartX = groupX0 + (long) (p % precinctsWide) * (1L << groupWidth);
                        var groupStartY = groupY0 + (long) (p / precinctsWide) * (1L << groupHeight);
                        var px0 = (int) Math.max(groupStartX, bx0);
                        var py0 = (int) Math.max(groupStartY, by0);
                        var px1 = (int) Math.min(groupStartX + (1L << groupWidth), bx1);
                        var py1 = (int) Math.min(groupStartY + (1L << groupHeight), by1);
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
                            var startX = ((px0 >> blockWidth) << blockWidth) + (k % wide) * (1 << blockWidth);
                            var startY = ((py0 >> blockHeight) << blockHeight) + (k / wide) * (1 << blockHeight);
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
                bands[b] = new Band(orientation, (int) bx0, (int) by0, (int) bx1, (int) by1, stepSize, bitPlanes,
                        bandIndex, precincts);
            }
            resolutions[r] = new Resolution((int) rx0, (int) ry0, (int) rx1, (int) ry1, pdx, pdy, precinctsWide,
                    precinctsHigh, bands);
        }
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
