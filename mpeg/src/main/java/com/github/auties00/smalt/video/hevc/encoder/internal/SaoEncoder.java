/* Ports HM-16.21, the ITU-T / ISO/IEC H.265 reference software (source/Lib/TLibEncoder/TEncSampleAdaptiveOffset.cpp:
   the offset derivation, the iterative offset reduction and the rate-distortion choice of the sample adaptive offset
   parameters). Copyright (c) 2010-2017, ITU/ISO/IEC; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.video.hevc.internal.PictureState;

/**
 * Chooses the sample adaptive offset parameters (ITU-T H.265 7.4.9.3) of every coding tree block of a
 * deblocked picture.
 *
 * <p>For each block and component the encoder gathers, over the deblocked samples, the number of samples and
 * the sum of their errors against the source in each edge offset category of the four edge classes and in
 * each of the 32 bands; offsets are the rounded mean errors, reduced while that lowers the estimated
 * distortion plus lambda times their bits. The best of off, the four edge classes and the band offset, or the
 * parameters of the block to the left or above, is kept. Luma and chroma are decided apart; Cb and Cr share
 * the type and the edge class. Instances are not thread-safe.
 */
final class SaoEncoder {
    /**
     * The neighbor offsets of the edge classes, {@code hPos} then {@code vPos} of Table 8-14.
     */
    private static final int[][] EDGE = {{-1, 1, 0, 0}, {0, 0, -1, 1}, {-1, 1, -1, 1}, {1, -1, -1, 1}};

    /**
     * The sums of errors of each edge class and category, indexed {@code [component][class][category]}, the
     * categories ordered full valley, half valley, plain, half peak, full peak.
     */
    private final long[][][] edgeDiff = new long[3][4][5];

    /**
     * The sample counts of each edge class and category.
     */
    private final long[][][] edgeCount = new long[3][4][5];

    /**
     * The sums of errors of each band, indexed {@code [component][band]}.
     */
    private final long[][] bandDiff = new long[3][32];

    /**
     * The sample counts of each band.
     */
    private final long[][] bandCount = new long[3][32];

    /**
     * The candidate offsets of one component, {@code SaoOffsetVal[1..4]}.
     */
    private final int[] offsets = new int[4];

    /**
     * The offsets of the band candidates of every band.
     */
    private final int[] bandOffsets = new int[32];

    /**
     * The cost of the band candidates of every band.
     */
    private final double[] bandCosts = new double[32];

    /**
     * The best parameters of each component: type, class, then four offsets.
     */
    private final int[][] best = new int[3][6];

    /**
     * Candidate parameters of each component, as {@link #best}.
     */
    private final int[][] trial = new int[3][6];

    /**
     * Constructs an encoder.
     */
    SaoEncoder() {
    }

    /**
     * Decides the parameters of every coding tree block and writes them to the picture's coding information,
     * then returns which components use the offsets anywhere.
     *
     * @param d the deblocked picture
     * @param lambda the Lagrange multiplier of luma squared errors
     * @param chromaLambda the Lagrange multiplier of chroma squared errors
     * @return bit 0 when some block uses luma offsets, bit 1 when some block uses chroma offsets
     */
    int decide(PictureData d, double lambda, double chromaLambda) {
        var pic = d.pic;
        var sps = d.sps;
        var size = sps.sizeInCtbs;
        var used = 0;
        for (var ctb = 0; ctb < size; ctb++) {
            var rx = ctb % sps.widthInCtbs;
            var ry = ctb / sps.widthInCtbs;
            for (var c = 0; c < 3; c++) {
                statistics(d, c, rx, ry);
            }
            var newCost = decideLuma(d, lambda) + lambda / chromaLambda * decideChroma(d, chromaLambda);
            var merge = 0;
            var bestCost = newCost;
            if (rx > 0) {
                var cost = mergeCost(pic, size, ctb - 1, lambda, chromaLambda, d);
                if (cost < bestCost) {
                    bestCost = cost;
                    merge = 1;
                }
            }
            if (ry > 0) {
                var cost = mergeCost(pic, size, ctb - sps.widthInCtbs, lambda, chromaLambda, d);
                if (cost < bestCost) {
                    merge = 2;
                }
            }
            d.saoMerge[ctb] = (byte) merge;
            if (merge != 0) {
                var source = merge == 1 ? ctb - 1 : ctb - sps.widthInCtbs;
                for (var c = 0; c < 3; c++) {
                    pic.saoType[c * size + ctb] = pic.saoType[c * size + source];
                    pic.saoClass[c * size + ctb] = pic.saoClass[c * size + source];
                    System.arraycopy(pic.saoOffset, (c * size + source) * 4, pic.saoOffset, (c * size + ctb) * 4, 4);
                }
            } else {
                for (var c = 0; c < 3; c++) {
                    pic.saoType[c * size + ctb] = (byte) best[c][0];
                    pic.saoClass[c * size + ctb] = (byte) best[c][1];
                    for (var i = 0; i < 4; i++) {
                        pic.saoOffset[(c * size + ctb) * 4 + i] = (short) best[c][2 + i];
                    }
                }
            }
            if (pic.saoType[ctb] != 0) {
                used |= 1;
            }
            if (pic.saoType[size + ctb] != 0) {
                used |= 2;
            }
        }
        return used;
    }

    /**
     * Gathers the error statistics of one component of a coding tree block.
     *
     * @param d the deblocked picture
     * @param c the component
     * @param rx the coding tree block column
     * @param ry the coding tree block row
     */
    private void statistics(PictureData d, int c, int rx, int ry) {
        var shift = c == 0 ? 0 : 1;
        var ctbSize = d.sps.ctbSize >> shift;
        var width = c == 0 ? d.s.codedWidth : d.s.chromaWidth;
        var height = c == 0 ? d.s.codedHeight : d.s.chromaHeight;
        var x0 = rx * ctbSize;
        var y0 = ry * ctbSize;
        var x1 = Math.min(x0 + ctbSize, width);
        var y1 = Math.min(y0 + ctbSize, height);
        var rec = d.recon(c);
        var src = d.source(c);
        var bandShift = d.s.bitDepth - 5;
        for (var k = 0; k < 4; k++) {
            java.util.Arrays.fill(edgeDiff[c][k], 0);
            java.util.Arrays.fill(edgeCount[c][k], 0);
        }
        java.util.Arrays.fill(bandDiff[c], 0);
        java.util.Arrays.fill(bandCount[c], 0);
        for (var y = y0; y < y1; y++) {
            var row = y * width;
            for (var x = x0; x < x1; x++) {
                var value = rec[row + x];
                var error = src[row + x] - value;
                var band = value >> bandShift;
                bandDiff[c][band] += error;
                bandCount[c][band]++;
                for (var k = 0; k < 4; k++) {
                    var ax = x + EDGE[k][0];
                    var bx = x + EDGE[k][1];
                    var ay = y + EDGE[k][2];
                    var by = y + EDGE[k][3];
                    if (ax < 0 || bx < 0 || ay < 0 || by < 0 || ax >= width || bx >= width || ay >= height
                            || by >= height) {
                        continue;
                    }
                    var category = 2 + Integer.signum(value - rec[ay * width + ax])
                            + Integer.signum(value - rec[by * width + bx]);
                    edgeDiff[c][k][category] += error;
                    edgeCount[c][k][category]++;
                }
            }
        }
    }

    /**
     * Decides the luma parameters of a block into {@link #best}.
     *
     * @param d the picture
     * @param lambda the Lagrange multiplier
     * @return the cost of the decision, the distortion change plus lambda times its bits
     */
    private double decideLuma(PictureData d, double lambda) {
        var bestCost = lambda;
        java.util.Arrays.fill(best[0], 0);
        var maxOffset = maxOffset(d.s.bitDepth);
        for (var k = 0; k < 4; k++) {
            var cost = edgeOffsets(0, k, lambda, maxOffset, trial[0]) + lambda * 4;
            if (cost < bestCost) {
                bestCost = cost;
                System.arraycopy(trial[0], 0, best[0], 0, 6);
            }
        }
        var cost = bandOffsets(0, lambda, maxOffset, trial[0]) + lambda * 7;
        if (cost < bestCost) {
            bestCost = cost;
            System.arraycopy(trial[0], 0, best[0], 0, 6);
        }
        return bestCost;
    }

    /**
     * Decides the chroma parameters of a block into {@link #best}.
     *
     * @param d the picture
     * @param lambda the Lagrange multiplier
     * @return the cost of the decision, in units of chroma squared errors
     */
    private double decideChroma(PictureData d, double lambda) {
        var bestCost = lambda;
        java.util.Arrays.fill(best[1], 0);
        java.util.Arrays.fill(best[2], 0);
        var maxOffset = maxOffset(d.s.bitDepth);
        for (var k = 0; k < 4; k++) {
            var cost = edgeOffsets(1, k, lambda, maxOffset, trial[1])
                    + edgeOffsets(2, k, lambda, maxOffset, trial[2]) + lambda * 4;
            if (cost < bestCost) {
                bestCost = cost;
                System.arraycopy(trial[1], 0, best[1], 0, 6);
                System.arraycopy(trial[2], 0, best[2], 0, 6);
            }
        }
        var cost = bandOffsets(1, lambda, maxOffset, trial[1]) + bandOffsets(2, lambda, maxOffset, trial[2])
                + lambda * 12;
        if (cost < bestCost) {
            bestCost = cost;
            System.arraycopy(trial[1], 0, best[1], 0, 6);
            System.arraycopy(trial[2], 0, best[2], 0, 6);
        }
        return bestCost;
    }

    /**
     * Returns the largest offset magnitude, {@code (1 << (Min(bitDepth, 10) - 5)) - 1}.
     *
     * @param bitDepth the bit depth
     * @return the magnitude
     */
    private static int maxOffset(int bitDepth) {
        return (1 << (Math.min(bitDepth, 10) - 5)) - 1;
    }

    /**
     * Derives the offsets of one edge class of one component.
     *
     * @param c the component
     * @param k the edge class
     * @param lambda the Lagrange multiplier
     * @param maxOffset the largest offset magnitude
     * @param out receives type 2, the class and the four offsets
     * @return the distortion change plus lambda times the offset bits
     */
    private double edgeOffsets(int c, int k, double lambda, int maxOffset, int[] out) {
        out[0] = 2;
        out[1] = k;
        var cost = 0.0;
        for (var i = 0; i < 4; i++) {
            var category = i < 2 ? i : i + 1;
            var count = edgeCount[c][k][category];
            var diff = edgeDiff[c][k][category];
            var offset = count == 0 ? 0 : (int) Math.clamp(Math.round((double) diff / count), -maxOffset, maxOffset);
            if (i < 2 && offset < 0 || i >= 2 && offset > 0) {
                offset = 0;
            }
            offset = reduce(offset, count, diff, lambda, maxOffset, false);
            out[2 + i] = offset;
            cost += distortion(count, offset, diff) + lambda * bits(offset, maxOffset, false);
        }
        return cost;
    }

    /**
     * Derives the band offset parameters of one component.
     *
     * @param c the component
     * @param lambda the Lagrange multiplier
     * @param maxOffset the largest offset magnitude
     * @param out receives type 1, the band position and the four offsets
     * @return the distortion change plus lambda times the offset bits
     */
    private double bandOffsets(int c, double lambda, int maxOffset, int[] out) {
        for (var band = 0; band < 32; band++) {
            var count = bandCount[c][band];
            var diff = bandDiff[c][band];
            var offset = count == 0 ? 0 : (int) Math.clamp(Math.round((double) diff / count), -maxOffset, maxOffset);
            offset = reduce(offset, count, diff, lambda, maxOffset, true);
            bandOffsets[band] = offset;
            bandCosts[band] = distortion(count, offset, diff) + lambda * bits(offset, maxOffset, true);
        }
        var bestBand = 0;
        var bestCost = Double.MAX_VALUE;
        for (var band = 0; band <= 28; band++) {
            var cost = bandCosts[band] + bandCosts[band + 1] + bandCosts[band + 2] + bandCosts[band + 3];
            if (cost < bestCost) {
                bestCost = cost;
                bestBand = band;
            }
        }
        out[0] = 1;
        out[1] = bestBand;
        for (var i = 0; i < 4; i++) {
            out[2 + i] = bandOffsets[bestBand + i];
        }
        return bestCost;
    }

    /**
     * Lowers the magnitude of an offset while that lowers the estimated cost (HM {@code estIterOffset}).
     *
     * @param offset the initial offset
     * @param count the number of samples
     * @param diff the sum of their errors
     * @param lambda the Lagrange multiplier
     * @param maxOffset the largest offset magnitude
     * @param band whether the offset is a band offset, which carries a sign bit
     * @return the offset
     */
    private static int reduce(int offset, long count, long diff, double lambda, int maxOffset, boolean band) {
        var bestOffset = 0;
        var bestCost = lambda;
        for (var value = offset; value != 0; value = value > 0 ? value - 1 : value + 1) {
            var cost = distortion(count, value, diff) + lambda * bits(value, maxOffset, band);
            if (cost < bestCost) {
                bestCost = cost;
                bestOffset = value;
            }
        }
        return bestOffset;
    }

    /**
     * Returns the change in squared error from adding an offset to samples.
     *
     * @param count the number of samples
     * @param offset the offset added to every sample
     * @param diff the sum of the errors of the samples
     * @return the change
     */
    private static double distortion(long count, int offset, long diff) {
        return count * (double) offset * offset - 2.0 * diff * offset;
    }

    /**
     * Returns the bits of an offset: its truncated unary magnitude and, for band offsets, its sign.
     *
     * @param offset the offset value
     * @param maxOffset the largest magnitude
     * @param band whether the offset carries a sign bit
     * @return the bits
     */
    private static int bits(int offset, int maxOffset, boolean band) {
        var magnitude = Math.abs(offset);
        var bits = magnitude == maxOffset ? magnitude : magnitude + 1;
        return bits + (band && offset != 0 ? 1 : 0);
    }

    /**
     * Returns the cost of copying the parameters of a neighboring block.
     *
     * @param pic the coding information holding the neighbor's parameters
     * @param size {@code PicSizeInCtbsY}
     * @param source the raster address of the neighbor
     * @param lambda the Lagrange multiplier of luma
     * @param chromaLambda the Lagrange multiplier of chroma
     * @param d the picture
     * @return the distortion change of the copied parameters plus lambda times one merge flag
     */
    private double mergeCost(PictureState pic, int size, int source, double lambda, double chromaLambda,
                             PictureData d) {
        var cost = lambda;
        for (var c = 0; c < 3; c++) {
            var index = c * size + source;
            var type = pic.saoType[index];
            if (type == 0) {
                continue;
            }
            var cls = pic.saoClass[index];
            var weight = c == 0 ? 1.0 : lambda / chromaLambda;
            for (var i = 0; i < 4; i++) {
                var offset = pic.saoOffset[index * 4 + i];
                if (type == 1) {
                    var band = (cls + i) & 31;
                    cost += weight * distortion(bandCount[c][band], offset, bandDiff[c][band]);
                } else {
                    var category = i < 2 ? i : i + 1;
                    cost += weight * distortion(edgeCount[c][cls][category], offset, edgeDiff[c][cls][category]);
                }
            }
        }
        return cost;
    }
}
