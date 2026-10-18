/* Ports jxl-oxide 0.12.6 (crates/jxl-vardct: write_hf_coeff). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.vardct;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;

/**
 * Decodes the quantized HF coefficients of one group in one pass (ISO/IEC 18181-1 section I.3.3).
 *
 * <p>For every varblock, and for each of the Y, X and B channels, the decoder reads the number of
 * non-zero coefficients, predicted from the neighboring blocks, and then the coefficients in the
 * order of the pass, each with a context derived from the count still to come and its frequency.
 * Coefficients are added to the group's integer coefficient buffers, so passes accumulate.
 *
 * <p>The luma block grid decides which subsampled chroma blocks are coded.
 */
public final class CoefficientDecoder {
    /**
     * The frequency context of each coefficient position (ISO/IEC 18181-1 table I.5).
     */
    private static final int[] FREQ_CONTEXT = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 15, 16, 16, 17, 17, 18, 18, 19, 19,
            20, 20, 21, 21, 22, 22, 23, 23, 23, 23, 24, 24, 24, 24, 25, 25, 25, 25, 26, 26, 26, 26, 27,
            27, 27, 27, 28, 28, 28, 28, 29, 29, 29, 29, 30, 30, 30, 30,
    };

    /**
     * The non-zero count context of each count (ISO/IEC 18181-1 table I.6).
     */
    private static final int[] NONZERO_CONTEXT = {
            0, 31, 62, 62, 93, 93, 93, 93, 123, 123, 123, 123, 152, 152, 152, 152, 152, 152, 152, 152,
            180, 180, 180, 180, 180, 180, 180, 180, 180, 180, 180, 180, 206, 206, 206, 206, 206, 206,
            206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206, 206,
            206, 206, 206, 206, 206, 206, 206,
    };

    /**
     * The decoding channel order (Y, X, B) as channel indices (X = 0, Y = 1, B = 2).
     */
    private static final int[] CHANNEL_ORDER = {1, 0, 2};

    /**
     * Prevents instantiation.
     */
    private CoefficientDecoder() {
        throw new AssertionError();
    }

    /**
     * Decodes the coefficients of a group.
     *
     * @param br the bitstream of the pass group
     * @param pass the HF pass
     * @param numPresets the number of HF presets
     * @param globals the VarDCT globals
     * @param blocks the block grid of the LF group
     * @param blockStride the width of the block grid
     * @param left the first block column of the group in the LF group
     * @param top the first block row of the group in the LF group
     * @param width the number of block columns of the group
     * @param height the number of block rows of the group
     * @param hshift the horizontal chroma shift of each channel (X, Y, B)
     * @param vshift the vertical chroma shift of each channel
     * @param lfQuant the quantized LF samples of the LF group (X, Y, B), or {@code null} when
     * the LF comes from an LF frame
     * @param lfStride the width of each quantized LF plane
     * @param shift the coefficient shift of the pass
     * @param coeff the coefficient buffers of the group (X, Y, B), accumulated into
     * @param coeffStride the width of each coefficient buffer
     * @throws MediaException.Malformed if the coefficients are invalid
     */
    public static void decode(JxlBitReader br, HfPass pass, int numPresets, LfGlobalVarDct globals, int[] blocks,
                              int blockStride, int left, int top, int width, int height, int[] hshift, int[] vshift,
                              int[][] lfQuant, int[] lfStride, int shift, int[][] coeff, int[] coeffStride) {
        var decoder = pass.decoder().copy();
        var qfThresholds = globals.qfThresholds();
        var lfThresholds = globals.lfThresholds();
        var contextMap = globals.blockContextMap();
        var numClusters = globals.numBlockClusters();
        var lfIdxMul = (lfThresholds[0].length + 1) * (lfThresholds[1].length + 1) * (lfThresholds[2].length + 1);
        var hfIdxMul = qfThresholds.length + 1;
        var presetBits = 32 - Integer.numberOfLeadingZeros(numPresets - 1);
        if (numPresets == 1) {
            presetBits = 0;
        }
        var preset = br.readBits(presetBits);
        if (preset >= numPresets) {
            throw new MediaException.Malformed("HF preset out of range");
        }
        var contextSize = 495 * numClusters;
        var allClusters = decoder.clusterMap();
        var clusterBase = contextSize * preset;
        if (clusterBase + contextSize > allClusters.length) {
            throw new MediaException.Malformed("HF context map too small");
        }
        decoder.begin(br);
        var nonZeroRows = new int[3][];
        for (var c = 0; c < 3; c++) {
            nonZeroRows[c] = new int[(width + (1 << hshift[c]) - 1) >> hshift[c]];
        }
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var info = blocks[(top + y) * blockStride + left + x];
                if (!HfMetadata.isData(info)) {
                    continue;
                }
                var type = HfMetadata.type(info);
                var qf = HfMetadata.hfMul(info);
                var w8 = TransformType.width(type);
                var h8 = TransformType.height(type);
                var numBlocks = w8 * h8;
                var numBlocksLog = Integer.numberOfTrailingZeros(numBlocks);
                var orderId = TransformType.order(type);
                var transpose = TransformType.needsTranspose(type);
                var lfIdx = 0;
                if (lfQuant != null) {
                    for (var c : new int[]{0, 2, 1}) {
                        var thresholds = lfThresholds[c];
                        lfIdx *= thresholds.length + 1;
                        var lx = (left + x) >> hshift[c];
                        var ly = (top + y) >> vshift[c];
                        var q = lfQuant[c][ly * lfStride[c] + lx];
                        for (var threshold : thresholds) {
                            if (q > threshold) {
                                lfIdx++;
                            }
                        }
                    }
                }
                var hfIdx = 0;
                for (var threshold : qfThresholds) {
                    if (qf > threshold) {
                        hfIdx++;
                    }
                }
                for (var ci = 0; ci < 3; ci++) {
                    var chIdx = ci * 13 + orderId;
                    var c = CHANNEL_ORDER[ci];
                    var hs = hshift[c];
                    var vs = vshift[c];
                    var sx = x >> hs;
                    var sy = y >> vs;
                    if (hs != 0 || vs != 0) {
                        if ((sx << hs) != x || (sy << vs) != y) {
                            continue;
                        }
                        if (!HfMetadata.isData(blocks[(top + sy) * blockStride + left + sx])) {
                            continue;
                        }
                    }
                    var idx = (chIdx * hfIdxMul + hfIdx) * lfIdxMul + lfIdx;
                    var blockCtx = contextMap[idx];
                    var row = nonZeroRows[c];
                    int predicted;
                    if (sy == 0) {
                        predicted = sx == 0 ? 32 : row[sx - 1];
                    } else if (sx == 0) {
                        predicted = row[sx];
                    } else {
                        predicted = (row[sx] + row[sx - 1] + 1) >> 1;
                    }
                    var nzIdx = predicted >= 8 ? 4 + predicted / 2 : predicted;
                    var nonZeroCtx = blockCtx + nzIdx * numClusters;
                    var nonZeros = decoder.readUintClustered(br, allClusters[clusterBase + nonZeroCtx], 0);
                    if (Integer.compareUnsigned(nonZeros, 63 << numBlocksLog) > 0) {
                        throw new MediaException.Malformed("too many non-zero coefficients");
                    }
                    var nzValue = (nonZeros + numBlocks - 1) >> numBlocksLog;
                    if (sx + w8 > row.length) {
                        throw new MediaException.Malformed("varblock outside its group");
                    }
                    for (var dx = 0; dx < w8; dx++) {
                        row[sx + dx] = nzValue;
                    }
                    if (nonZeros == 0) {
                        continue;
                    }
                    var prevNonZero = nonZeros <= numBlocks * 4 ? 1 : 0;
                    var order = pass.order(orderId, c);
                    var ctxBase = clusterBase + blockCtx * 458 + 37 * numClusters;
                    var target = coeff[c];
                    var stride = coeffStride[c];
                    var size = order.length;
                    for (var k = numBlocks; k < size; k++) {
                        var i = k - numBlocks;
                        var ctx = (NONZERO_CONTEXT[(nonZeros - 1) >> numBlocksLog] + FREQ_CONTEXT[i >> numBlocksLog]) * 2
                                + prevNonZero;
                        if (ctx >= 458 || ctxBase + ctx >= allClusters.length) {
                            throw new MediaException.Malformed("too many zeros in a varblock");
                        }
                        var u = decoder.readUintClustered(br, allClusters[ctxBase + ctx], 0);
                        if (u == 0) {
                            prevNonZero = 0;
                            continue;
                        }
                        var value = ((u >>> 1) ^ -(u & 1)) << shift;
                        var position = order[k];
                        var dx = position & 0xFFFF;
                        var dy = position >>> 16;
                        if (transpose) {
                            var t = dx;
                            dx = dy;
                            dy = t;
                        }
                        target[(sy * 8 + dy) * stride + sx * 8 + dx] += value;
                        prevNonZero = 1;
                        nonZeros--;
                        if (nonZeros == 0) {
                            break;
                        }
                    }
                }
            }
        }
        decoder.checkFinalState();
    }
}
