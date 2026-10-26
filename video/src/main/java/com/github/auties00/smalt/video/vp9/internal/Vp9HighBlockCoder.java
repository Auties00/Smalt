/* Ports libvpx 1.15.1 (vp9/encoder/vp9_encodemb.c, vp9/encoder/vp9_quantize.c, vpx_dsp/quantize.c,
   vp9/common/vp9_reconintra.c, vp9/common/vp9_reconinter.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.DCT_DCT;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.INTRA_FRAME;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_16X16;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_32X32;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_4X4;
import static com.github.auties00.smalt.video.vp9.internal.Vp9Constants.TX_8X8;

/**
 * Codes the blocks of a 10 or 12-bit frame at full precision for a {@link Vp9RtCoder}, once the
 * coder has chosen their modes on the 8-bit proxies: the predictions, the residual, the forward
 * transform, the quantization with the tables of the bit depth, and the reconstruction, which it
 * then rounds into the proxy of the reconstruction for the decisions of the blocks that follow.
 *
 * <p>The predictions and reconstructions equal those of a decoder. Instances belong to one coder
 * and are not thread-safe.
 *
 * @implNote This implementation follows the high bit depth branches of libvpx's
 * {@code vp9_encode_sb}, {@code vp9_encode_block_intra}, {@code vp9_highbd_quantize_fp},
 * {@code vpx_highbd_quantize_b}, {@code build_intra_predictors_high} and
 * {@code highbd_inter_predictor}; libvpx takes its decisions on the samples themselves, this
 * implementation on their 8-bit rounding, so its decisions equal those of 8-bit coding.
 */
final class Vp9HighBlockCoder {
    /**
     * The index of the above row in {@link #edge}; the top-left sample sits just before it.
     */
    private static final int ABOVE = 80;

    /**
     * The coder whose blocks this codes.
     */
    private final Vp9RtCoder coder;

    /**
     * The frame-level encoder.
     */
    private final Vp9RtEncoder enc;

    /**
     * The gathered intra edge: the left column from index 0, the above row from {@link #ABOVE}.
     */
    private final short[] edge = new short[256];

    /**
     * Scratch memory of the sub-pixel filters.
     */
    private final short[] convolveScratch = new short[Vp9Dsp.CONVOLVE_SCRATCH];

    /**
     * Scratch memory of the inverse transforms.
     */
    private final int[] transformScratch = new int[Vp9Dsp.TRANSFORM_SCRATCH];

    /**
     * The coefficients of one transform block in raster order, the layout of the high bit depth
     * inverse transforms.
     */
    private final int[] rasterCoef = new int[1024];

    /**
     * Creates the high bit depth block coder of a coder.
     *
     * @param coder the coder whose blocks this coder codes
     */
    Vp9HighBlockCoder(Vp9RtCoder coder) {
        this.coder = coder;
        this.enc = coder.enc;
    }

    /**
     * Predicts, codes and reconstructs one plane of an intra block, transform block by transform
     * block ({@code vp9_encode_intra_block_plane}).
     *
     * @param bsize the block size, at least 8x8
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     */
    void encodeIntraPlane(int bsize, int plane) {
        var c = coder;
        var m = c.mi;
        var sx = plane == 0 ? 0 : enc.ssX;
        var sy = plane == 0 ? 0 : enc.ssY;
        var planeBsize = plane == 0 ? bsize : enc.uvBsize(bsize);
        var txSize = plane == 0 ? m.txSize : enc.uvTxSize(m);
        var num4w = Vp9RtCoder.NUM_4X4_WIDE[planeBsize];
        var num4h = Vp9RtCoder.NUM_4X4_HIGH[planeBsize];
        var maxWide = num4w + (c.mbToRightEdge >= 0 ? 0 : c.mbToRightEdge >> (5 + sx));
        var maxHigh = num4h + (c.mbToBottomEdge >= 0 ? 0 : c.mbToBottomEdge >> (5 + sy));
        var step = 1 << txSize;
        var bw = 1 << Vp9RtCoder.B_WIDTH_LOG2[planeBsize];
        var recon = enc.recon;
        var dst = recon.planes16[plane];
        var dstStride = recon.strides[plane];
        var src = enc.source;
        var srcPlane = src.planes16[plane];
        var srcStride = src.strides[plane];
        var size = 4 << txSize;
        var eob = c.eobs[plane];
        var optimize = c.optimizesCoding();
        if (optimize) {
            c.entropyContexts(plane, planeBsize, txSize, c.codingAbove, c.codingLeft);
        }
        for (var r = 0; r < maxHigh; r += step) {
            for (var col = 0; col < maxWide; col += step) {
                var block = r * num4w + (col << txSize);
                var mode = plane == 0 ? (txSize == TX_4X4 ? m.yMode((r << 1) + col) : m.mode) : m.uvMode;
                var dOff = c.dstOff[plane] + 4 * (r * dstStride + col);
                var sOff = c.srcOff[plane] + 4 * (r * srcStride + col);
                var haveTop = r != 0 || c.aboveMi != null;
                var haveLeft = col != 0 || c.leftMi != null;
                var haveRight = col + step < bw;
                var x0 = (-c.mbToLeftEdge >> (3 + sx)) + col * 4;
                var y0 = (-c.mbToTopEdge >> (3 + sy)) + r * 4;
                predictIntra(plane, dst, dOff, dstStride, mode, txSize, haveTop, haveLeft, haveRight, x0, y0);
                var txType = plane == 0 && txSize != TX_32X32 && !enc.lossless ? Vp9Constants.MODE_TO_TX_TYPE[mode]
                        : DCT_DCT;
                subtract(c.srcDiff, 0, size, srcPlane, sOff, srcStride, dst, dOff, dstStride, size, size);
                var off = block * 16;
                forwardTransform(txSize, txType, c.srcDiff, 0, size, c.coeff[plane], off);
                var e = quantize(plane, txSize, txType, off, false);
                if (optimize) {
                    e = c.optimizeBlock(c.highOptimizer, enc.codingQuant, enc.bitDepth, plane, off, e, txSize, txType,
                            c.codingAbove[col] + c.codingLeft[r]);
                    c.codingAbove[col] = c.codingLeft[r] = (byte) (e > 0 ? 1 : 0);
                }
                eob[block] = e;
                if (e > 0) {
                    reconstruct(txSize, txType, e, c.dqcoeff[plane], off, Vp9RtCoder.SCANS[txSize * 4 + txType], dst,
                            dOff, dstStride);
                    m.skip = 0;
                }
            }
        }
    }

    /**
     * Predicts every plane of an inter block from the references' samples and, unless the mode
     * decision skipped its residual, codes and reconstructs the residual ({@code vp9_encode_sb}).
     *
     * @param bsize the block size, at least 8x8
     */
    void encodeInter(int bsize) {
        var c = coder;
        var m = c.mi;
        for (var plane = 0; plane < 3; plane++) {
            predictInter(plane, bsize);
        }
        m.skip = 1;
        if (c.skip) {
            return;
        }
        var fp = enc.sf.useQuantFp;
        var recon = enc.recon;
        var src = enc.source;
        for (var plane = 0; plane < 3; plane++) {
            var sx = plane == 0 ? 0 : enc.ssX;
            var sy = plane == 0 ? 0 : enc.ssY;
            var planeBsize = plane == 0 ? bsize : enc.uvBsize(bsize);
            var bw = Vp9RtCoder.NUM_4X4_WIDE[planeBsize] * 4;
            var bh = Vp9RtCoder.NUM_4X4_HIGH[planeBsize] * 4;
            var dst = recon.planes16[plane];
            var dstStride = recon.strides[plane];
            subtract(c.srcDiff, 0, bw, src.planes16[plane], c.srcOff[plane], src.strides[plane], dst,
                    c.dstOff[plane], dstStride, bw, bh);
            var txSize = plane == 0 ? m.txSize : enc.uvTxSize(m);
            var num4w = Vp9RtCoder.NUM_4X4_WIDE[planeBsize];
            var maxWide = num4w + (c.mbToRightEdge >= 0 ? 0 : c.mbToRightEdge >> (5 + sx));
            var maxHigh = Vp9RtCoder.NUM_4X4_HIGH[planeBsize] + (c.mbToBottomEdge >= 0 ? 0
                    : c.mbToBottomEdge >> (5 + sy));
            var step = 1 << txSize;
            var scan = Vp9RtCoder.SCANS[txSize * 4];
            var eob = c.eobs[plane];
            var optimize = c.optimizesCoding();
            if (optimize) {
                c.entropyContexts(plane, planeBsize, txSize, c.codingAbove, c.codingLeft);
            }
            for (var r = 0; r < maxHigh; r += step) {
                for (var col = 0; col < maxWide; col += step) {
                    var block = r * num4w + (col << txSize);
                    var off = block * 16;
                    if (fp && c.skipTxfm == 1 && plane == 0) {
                        eob[block] = 0;
                        continue;
                    }
                    forwardTransform(txSize, DCT_DCT, c.srcDiff, 4 * (r * bw + col), bw, c.coeff[plane], off);
                    var e = quantize(plane, txSize, DCT_DCT, off, fp);
                    if (optimize) {
                        e = c.optimizeBlock(c.highOptimizer, enc.codingQuant, enc.bitDepth, plane, off, e, txSize,
                                DCT_DCT, c.codingAbove[col] + c.codingLeft[r]);
                        c.codingAbove[col] = c.codingLeft[r] = (byte) (e > 0 ? 1 : 0);
                    }
                    eob[block] = e;
                    if (e > 0) {
                        m.skip = 0;
                        reconstruct(txSize, DCT_DCT, e, c.dqcoeff[plane], off, scan, dst,
                                c.dstOff[plane] + 4 * (r * dstStride + col), dstStride);
                    }
                }
            }
        }
    }

    /**
     * Rounds the reconstruction of the block's area into the 8-bit proxy of the reconstruction.
     *
     * @param bsize the block size, at least 8x8
     */
    void updateProxy(int bsize) {
        var c = coder;
        var recon = enc.recon;
        var shift = enc.bitDepth - 8;
        for (var plane = 0; plane < 3; plane++) {
            var sx = plane == 0 ? 0 : enc.ssX;
            var sy = plane == 0 ? 0 : enc.ssY;
            var w = (Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4) >> sx;
            var h = (Vp9RtCoder.NUM_4X4_HIGH[bsize] * 4) >> sy;
            var p16 = recon.planes16[plane];
            var p8 = recon.planes8[plane];
            var stride = recon.strides[plane];
            for (var y = 0; y < h; y++) {
                var row = c.dstOff[plane] + y * stride;
                for (var x = 0; x < w; x++) {
                    p8[row + x] = (byte) Vp9EncPicture.proxy(p16[row + x], shift);
                }
            }
        }
    }

    /**
     * Predicts one plane of the inter block into the reconstruction from the samples of its
     * references, averaging the second reference into the first ({@code highbd_inter_predictor}).
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param bsize the block size, at least 8x8
     */
    private void predictInter(int plane, int bsize) {
        var c = coder;
        var m = c.mi;
        var sx = plane == 0 ? 0 : enc.ssX;
        var sy = plane == 0 ? 0 : enc.ssY;
        var w = (Vp9RtCoder.NUM_4X4_WIDE[bsize] * 4) >> sx;
        var h = (Vp9RtCoder.NUM_4X4_HIGH[bsize] * 4) >> sy;
        var x0 = (c.miCol * 8) >> sx;
        var y0 = (c.miRow * 8) >> sy;
        var recon = enc.recon;
        if (m.sbType < Vp9Constants.BLOCK_8X8) {
            var stride = recon.strides[plane];
            var n4w = Math.max(1, 2 >> sx);
            var n4h = Math.max(1, 2 >> sy);
            for (var ref = 0; ref < (m.ref1 > INTRA_FRAME ? 2 : 1); ref++) {
                var picture = enc.activeRef(ref == 0 ? m.ref0 : m.ref1);
                var i = 0;
                for (var y = 0; y < n4h; y++) {
                    for (var x = 0; x < n4w; x++) {
                        var mv = Vp9RtCoder.averageSplitMvs(m, plane, ref, i++, sx, sy);
                        predictInterInto(picture, plane, x0 + 4 * x, y0 + 4 * y, mv, m.interpFilter,
                                recon.planes16[plane], c.dstOff[plane] + 4 * (y * stride + x), stride, 4, 4, ref == 1);
                    }
                }
            }
            return;
        }
        predictInterInto(enc.activeRef(m.ref0), plane, x0, y0, m.mv0, m.interpFilter, recon.planes16[plane],
                c.dstOff[plane], recon.strides[plane], w, h, false);
        if (m.ref1 > INTRA_FRAME) {
            predictInterInto(enc.activeRef(m.ref1), plane, x0, y0, m.mv1, m.interpFilter, recon.planes16[plane],
                    c.dstOff[plane], recon.strides[plane], w, h, true);
        }
    }

    /**
     * Predicts a block from the samples of an unscaled bordered reference picture.
     *
     * @param ref the reference picture
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param x0 the block's column in the plane
     * @param y0 the block's row in the plane
     * @param mv the packed motion vector in 1/8 luma pixel
     * @param filter the interpolation filter
     * @param dst the plane receiving the prediction
     * @param dstOff the index of the block's top-left sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param w the block width
     * @param h the block height
     * @param average whether to average into the destination
     */
    void predictInterInto(Vp9EncPicture ref, int plane, int x0, int y0, int mv, int filter, short[] dst, int dstOff,
                          int dstStride, int w, int h, boolean average) {
        var sx = plane == 0 ? 0 : ref.ssX;
        var sy = plane == 0 ? 0 : ref.ssY;
        var mvRow = (mv >> 16) * (1 << (1 - sy));
        var mvCol = (short) mv * (1 << (1 - sx));
        var x = x0 + (mvCol >> 4);
        var y = y0 + (mvRow >> 4);
        var stride = ref.strides[plane];
        Vp9HighDsp.convolve(ref.planes16[plane], ref.origins[plane] + y * stride + x, stride, dst, dstOff, dstStride,
                Vp9Constants.FILTER_KERNELS, filter * 16 * 8, mvCol & 15, 16, mvRow & 15, 16, w, h, average,
                enc.bitDepth, convolveScratch);
    }

    /**
     * Predicts a transform block in place from its reconstructed neighbors, gathering the edge
     * with the decoder's availability rules and edge replication ({@code build_intra_predictors_high}).
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param ref the reconstruction plane, which holds the neighbors and receives the prediction
     * @param dst the index of the transform block's top-left sample
     * @param stride the row stride
     * @param mode the intra mode
     * @param txSize the transform size
     * @param haveTop whether the above row is available
     * @param haveLeft whether the left column is available
     * @param haveRight whether the samples above and to the right belong to the same block
     * @param x0 the transform block's column in the plane
     * @param y0 the transform block's row in the plane
     */
    private void predictIntra(int plane, short[] ref, int dst, int stride, int mode, int txSize, boolean haveTop,
                              boolean haveLeft, boolean haveRight, int x0, int y0) {
        var c = coder;
        var recon = enc.recon;
        var e = edge;
        var bs = 4 << txSize;
        var frameWidth = recon.planeWidths[plane];
        var frameHeight = recon.planeHeights[plane];
        var rightEdge = c.mbToRightEdge < 0;
        var base = 128 << (enc.bitDepth - 8);
        var extend = Vp9Constants.EXTEND_MODES[mode];
        short[] aboveArray = e;
        var aboveIndex = ABOVE;
        if ((extend & Vp9Constants.NEED_LEFT) != 0) {
            if (haveLeft) {
                var count = bs;
                if (c.mbToBottomEdge < 0 && y0 + bs > frameHeight) {
                    count = frameHeight - y0;
                }
                for (var i = 0; i < count; i++) {
                    e[i] = ref[dst + i * stride - 1];
                }
                if (count < bs) {
                    Arrays.fill(e, count, bs, ref[dst + (count - 1) * stride - 1]);
                }
            } else {
                Arrays.fill(e, 0, bs, (short) (base + 1));
            }
        }
        if ((extend & Vp9Constants.NEED_ABOVE) != 0) {
            if (haveTop) {
                var aboveRef = dst - stride;
                if (rightEdge) {
                    if (x0 + bs <= frameWidth) {
                        System.arraycopy(ref, aboveRef, e, ABOVE, bs);
                    } else if (x0 <= frameWidth) {
                        var r = frameWidth - x0;
                        System.arraycopy(ref, aboveRef, e, ABOVE, r);
                        Arrays.fill(e, ABOVE + r, ABOVE + bs, e[ABOVE + r - 1]);
                    }
                } else if (bs == 4 && haveRight && haveLeft) {
                    aboveArray = ref;
                    aboveIndex = aboveRef;
                } else {
                    System.arraycopy(ref, aboveRef, e, ABOVE, bs);
                }
                e[ABOVE - 1] = haveLeft ? ref[aboveRef - 1] : (short) (base + 1);
            } else {
                Arrays.fill(e, ABOVE, ABOVE + bs, (short) (base - 1));
                e[ABOVE - 1] = (short) (base - 1);
            }
        }
        if ((extend & Vp9Constants.NEED_ABOVERIGHT) != 0) {
            if (haveTop) {
                var aboveRef = dst - stride;
                if (rightEdge) {
                    if (x0 + 2 * bs <= frameWidth) {
                        if (haveRight && bs == 4) {
                            System.arraycopy(ref, aboveRef, e, ABOVE, 2 * bs);
                        } else {
                            System.arraycopy(ref, aboveRef, e, ABOVE, bs);
                            Arrays.fill(e, ABOVE + bs, ABOVE + 2 * bs, e[ABOVE + bs - 1]);
                        }
                    } else if (x0 + bs <= frameWidth) {
                        var r = frameWidth - x0;
                        if (haveRight && bs == 4) {
                            System.arraycopy(ref, aboveRef, e, ABOVE, r);
                            Arrays.fill(e, ABOVE + r, ABOVE + 2 * bs, e[ABOVE + r - 1]);
                        } else {
                            System.arraycopy(ref, aboveRef, e, ABOVE, bs);
                            Arrays.fill(e, ABOVE + bs, ABOVE + 2 * bs, e[ABOVE + bs - 1]);
                        }
                    } else if (x0 <= frameWidth) {
                        var r = frameWidth - x0;
                        System.arraycopy(ref, aboveRef, e, ABOVE, r);
                        Arrays.fill(e, ABOVE + r, ABOVE + 2 * bs, e[ABOVE + r - 1]);
                    }
                    e[ABOVE - 1] = haveLeft ? ref[aboveRef - 1] : (short) (base + 1);
                } else if (bs == 4 && haveRight && haveLeft) {
                    aboveArray = ref;
                    aboveIndex = aboveRef;
                } else {
                    System.arraycopy(ref, aboveRef, e, ABOVE, bs);
                    if (bs == 4 && haveRight) {
                        System.arraycopy(ref, aboveRef + bs, e, ABOVE + bs, bs);
                    } else {
                        Arrays.fill(e, ABOVE + bs, ABOVE + 2 * bs, e[ABOVE + bs - 1]);
                    }
                    e[ABOVE - 1] = haveLeft ? ref[aboveRef - 1] : (short) (base + 1);
                }
            } else {
                Arrays.fill(e, ABOVE, ABOVE + 2 * bs, (short) (base - 1));
                e[ABOVE - 1] = (short) (base - 1);
            }
        }
        var dcEdges = (haveTop ? 1 : 0) | (haveLeft ? 2 : 0);
        Vp9HighDsp.intraPredict(mode, txSize, dcEdges, ref, dst, stride, aboveArray, aboveIndex, e, 0, enc.bitDepth);
    }

    /**
     * Subtracts a prediction from the source samples ({@code vpx_highbd_subtract_block}).
     *
     * @param diff receives the residual
     * @param diffOff the index of the first residual
     * @param diffStride the residual row stride
     * @param src the source plane
     * @param srcOff the index of the block in the source
     * @param srcStride the source row stride
     * @param pred the prediction plane
     * @param predOff the index of the block in the prediction
     * @param predStride the prediction row stride
     * @param w the width
     * @param h the height
     */
    static void subtract(short[] diff, int diffOff, int diffStride, short[] src, int srcOff, int srcStride,
                         short[] pred, int predOff, int predStride, int w, int h) {
        for (var y = 0; y < h; y++, diffOff += diffStride, srcOff += srcStride, predOff += predStride) {
            for (var x = 0; x < w; x++) {
                diff[diffOff + x] = (short) (src[srcOff + x] - pred[predOff + x]);
            }
        }
    }

    /**
     * Computes the forward transform of a residual block with the scalar kernels, whose
     * intermediates hold the range of 12-bit residuals.
     *
     * @param txSize the transform size
     * @param txType the transform type
     * @param diff the residuals
     * @param diffOff the index of the first residual
     * @param stride the residual row stride
     * @param out receives the coefficients in raster order
     * @param off the index of the first coefficient
     */
    private void forwardTransform(int txSize, int txType, short[] diff, int diffOff, int stride, int[] out, int off) {
        var tmp = coder.fdctScratch;
        switch (txSize) {
            case TX_4X4 -> {
                if (enc.lossless) {
                    Vp9FwdTxfm.fwht4x4(diff, diffOff, stride, out, off);
                } else if (txType == DCT_DCT) {
                    Vp9FwdTxfm.fdct4x4(diff, diffOff, stride, out, off, tmp);
                } else {
                    Vp9FwdTxfm.fht4x4(diff, diffOff, stride, out, off, txType, tmp);
                }
            }
            case TX_8X8 -> {
                if (txType == DCT_DCT) {
                    Vp9FwdTxfm.fdct8x8(diff, diffOff, stride, out, off, tmp);
                } else {
                    Vp9FwdTxfm.fht8x8(diff, diffOff, stride, out, off, txType, tmp);
                }
            }
            case TX_16X16 -> {
                if (txType == DCT_DCT) {
                    Vp9FwdTxfm.fdct16x16(diff, diffOff, stride, out, off, tmp);
                } else {
                    Vp9FwdTxfm.fht16x16(diff, diffOff, stride, out, off, txType, tmp);
                }
            }
            default -> Vp9FwdTxfm.fdct32x32(diff, diffOff, stride, out, off, true, tmp);
        }
    }

    /**
     * Quantizes the coefficients of one transform block with the bit depth's tables.
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param txSize the transform size
     * @param txType the transform type, which selects the scan
     * @param off the index of the block's first coefficient
     * @param fast whether to use the fast quantizer of real-time inter blocks
     * @return the end of block
     */
    private int quantize(int plane, int txSize, int txType, int off, boolean fast) {
        var c = coder;
        var q = enc.codingQuant;
        var pidx = c.qindex * 2;
        var size = 4 << txSize;
        var n = size * size;
        var scan = Vp9RtCoder.SCANS[txSize * 4 + txType];
        var big = txSize == TX_32X32;
        var y = plane == 0;
        var cf = c.coeff[plane];
        var qc = c.qcoeff[plane];
        var dq = c.dqcoeff[plane];
        if (fast) {
            return y ? Vp9Quantizer.quantizeFpHigh(cf, off, n, qc, dq, scan, q.yRoundFp, q.yQuantFp, q.yDequant, pidx, big)
                    : Vp9Quantizer.quantizeFpHigh(cf, off, n, qc, dq, scan, q.uvRoundFp, q.uvQuantFp, q.uvDequant, pidx,
                    big);
        }
        return y ? Vp9Quantizer.quantizeBHigh(cf, off, n, qc, dq, scan, q.yZbin, q.yRound, q.yQuant, q.yQuantShift,
                q.yDequant, pidx, big)
                : Vp9Quantizer.quantizeBHigh(cf, off, n, qc, dq, scan, q.uvZbin, q.uvRound, q.uvQuant, q.uvQuantShift,
                q.uvDequant, pidx, big);
    }

    /**
     * Adds the inverse transform of dequantized coefficients to the prediction, as the decoder
     * does: the inverse Walsh-Hadamard transform in a lossless frame.
     *
     * @param txSize the transform size
     * @param txType the transform type
     * @param eob the end of block
     * @param dq the dequantized coefficients in raster order
     * @param off the index of the first coefficient
     * @param scan the scan order
     * @param dst the plane holding the prediction
     * @param dOff the index of the block's top-left sample
     * @param stride the plane's row stride
     */
    private void reconstruct(int txSize, int txType, int eob, int[] dq, int off, short[] scan, short[] dst, int dOff,
                             int stride) {
        var lc = rasterCoef;
        for (var i = 0; i < eob; i++) {
            var rc = scan[i];
            lc[rc] = dq[off + rc];
        }
        if (enc.lossless) {
            Vp9HighDsp.inverseWhtAdd(eob, lc, 0, dst, dOff, stride, enc.bitDepth, transformScratch);
        } else {
            Vp9HighDsp.inverseTransformAdd(txSize, txType, eob, lc, 0, dst, dOff, stride, enc.bitDepth,
                    transformScratch);
        }
        for (var i = 0; i < eob; i++) {
            lc[scan[i]] = 0;
        }
    }
}
