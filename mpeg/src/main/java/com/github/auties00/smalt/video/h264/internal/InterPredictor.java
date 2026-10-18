package com.github.auties00.smalt.video.h264.internal;

/**
 * Forms the inter prediction of macroblocks (ITU-T H.264 8.4.2): fractional-sample
 * interpolation from the reference pictures and default, explicit or implicit weighted
 * combination, written straight into the frame being decoded.
 *
 * <p>Reference blocks that reach beyond the border of a reference frame's padding are first
 * copied into a scratch block with coordinates clamped to the picture, which reproduces the
 * sample repetition of ITU-T H.264 equations 8-228 and 8-229 for any motion vector.
 */
final class InterPredictor {
    /**
     * The row stride of the luma edge-emulation block.
     */
    private static final int EDGE_STRIDE = 24;

    /**
     * The kernels of the picture being predicted, selected by {@link #startPicture}.
     */
    private H264Dsp dsp = H264Dsp.SCALAR;

    /**
     * The luma interpolation integer scratch.
     */
    private final short[] sums = new short[H264Dsp.LUMA_SCRATCH_SHORTS];

    /**
     * The luma interpolation byte scratch.
     */
    private final byte[] scratch = new byte[H264Dsp.LUMA_SCRATCH_BYTES];

    /**
     * The list 1 luma prediction of a bi-predicted block, stride 16.
     */
    private final byte[] lumaTemp = new byte[16 * 16];

    /**
     * The list 1 Cb prediction of a bi-predicted block, stride 8.
     */
    private final byte[] cbTemp = new byte[8 * 8];

    /**
     * The list 1 Cr prediction of a bi-predicted block, stride 8.
     */
    private final byte[] crTemp = new byte[8 * 8];

    /**
     * The luma edge-emulation block.
     */
    private final byte[] edge = new byte[EDGE_STRIDE * 21];

    /**
     * The Cb edge-emulation block.
     */
    private final byte[] chromaEdge = new byte[16 * 9];

    /**
     * The Cr edge-emulation block.
     */
    private final byte[] chromaEdgeCr = new byte[16 * 9];

    /**
     * The frame being decoded.
     */
    private DecoderFrame frame;

    /**
     * The luma width of the frames in samples.
     */
    private int width;

    /**
     * The luma height of the frames in samples.
     */
    private int height;

    /**
     * The reference picture lists of the slice.
     */
    private DecoderFrame[][] lists;

    /**
     * The slice header, for explicit weights.
     */
    private H264SliceHeader header;

    /**
     * The weighted prediction mode: 0 default, 1 explicit, 2 implicit.
     */
    private int weightMode;

    /**
     * The implicit list 0 weights, indexed {@code refIdxL0 * 32 + refIdxL1}.
     */
    private int[] implicitWeight;

    /**
     * Constructs a predictor.
     */
    InterPredictor() {
    }

    /**
     * Prepares prediction into a new frame.
     *
     * @param frame the frame being decoded
     * @param dsp the kernels the frame is predicted with
     */
    void startPicture(DecoderFrame frame, H264Dsp dsp) {
        this.frame = frame;
        this.dsp = dsp;
        this.width = frame.mbWidth * 16;
        this.height = frame.mbHeight * 16;
    }

    /**
     * Prepares prediction for a slice.
     *
     * @param header the slice header
     * @param lists the reference picture lists
     * @param weightMode the weighted prediction mode
     * @param implicitWeight the implicit list 0 weights
     */
    void startSlice(H264SliceHeader header, DecoderFrame[][] lists, int weightMode, int[] implicitWeight) {
        this.header = header;
        this.lists = lists;
        this.weightMode = weightMode;
        this.implicitWeight = implicitWeight;
    }

    /**
     * Predicts every partition of the current macroblock of a slice decoder from its motion
     * caches.
     *
     * @param decoder the slice decoder positioned on the macroblock
     */
    void predictMacroblock(SliceDecoder decoder) {
        var refs0 = decoder.refCache(0);
        var refs1 = decoder.refCache(1);
        var mvs0 = decoder.mvCache(0);
        var mvs1 = decoder.mvCache(1);
        var mbX = decoder.mbX();
        var mbY = decoder.mbY();
        switch (decoder.partShape()) {
            case 0 -> predict(mbX, mbY, 0, 0, 4, 4, refs0, refs1, mvs0, mvs1);
            case 1 -> {
                predict(mbX, mbY, 0, 0, 4, 2, refs0, refs1, mvs0, mvs1);
                predict(mbX, mbY, 0, 2, 4, 2, refs0, refs1, mvs0, mvs1);
            }
            case 2 -> {
                predict(mbX, mbY, 0, 0, 2, 4, refs0, refs1, mvs0, mvs1);
                predict(mbX, mbY, 2, 0, 2, 4, refs0, refs1, mvs0, mvs1);
            }
            default -> {
                if (decoder.uniformMotion()) {
                    predict(mbX, mbY, 0, 0, 4, 4, refs0, refs1, mvs0, mvs1);
                    return;
                }
                for (var i = 0; i < 4; i++) {
                    var x = (i & 1) * 2;
                    var y = (i >> 1) * 2;
                    if (decoder.directPer4x4(i)) {
                        predictQuad(mbX, mbY, x, y, refs0, refs1, mvs0, mvs1);
                        continue;
                    }
                    switch (decoder.subShape(i)) {
                        case 0 -> predict(mbX, mbY, x, y, 2, 2, refs0, refs1, mvs0, mvs1);
                        case 1 -> {
                            predict(mbX, mbY, x, y, 2, 1, refs0, refs1, mvs0, mvs1);
                            predict(mbX, mbY, x, y + 1, 2, 1, refs0, refs1, mvs0, mvs1);
                        }
                        case 2 -> {
                            predict(mbX, mbY, x, y, 1, 2, refs0, refs1, mvs0, mvs1);
                            predict(mbX, mbY, x + 1, y, 1, 2, refs0, refs1, mvs0, mvs1);
                        }
                        default -> predictQuad(mbX, mbY, x, y, refs0, refs1, mvs0, mvs1);
                    }
                }
            }
        }
    }

    /**
     * Predicts the four 4x4 blocks of an 8x8 partition separately.
     *
     * @param mbX the macroblock column
     * @param mbY the macroblock row
     * @param x the partition column in 4x4 units
     * @param y the partition row in 4x4 units
     * @param refs0 the list 0 reference cache
     * @param refs1 the list 1 reference cache
     * @param mvs0 the list 0 motion vector cache
     * @param mvs1 the list 1 motion vector cache
     */
    private void predictQuad(int mbX, int mbY, int x, int y, int[] refs0, int[] refs1, int[] mvs0, int[] mvs1) {
        predict(mbX, mbY, x, y, 1, 1, refs0, refs1, mvs0, mvs1);
        predict(mbX, mbY, x + 1, y, 1, 1, refs0, refs1, mvs0, mvs1);
        predict(mbX, mbY, x, y + 1, 1, 1, refs0, refs1, mvs0, mvs1);
        predict(mbX, mbY, x + 1, y + 1, 1, 1, refs0, refs1, mvs0, mvs1);
    }

    /**
     * Predicts one block with the motion of its top-left 4x4 block.
     *
     * @param mbX the macroblock column
     * @param mbY the macroblock row
     * @param x4 the block column in 4x4 units within the macroblock
     * @param y4 the block row in 4x4 units within the macroblock
     * @param w4 the block width in 4x4 units
     * @param h4 the block height in 4x4 units
     * @param refs0 the list 0 reference cache
     * @param refs1 the list 1 reference cache
     * @param mvs0 the list 0 motion vector cache
     * @param mvs1 the list 1 motion vector cache
     */
    private void predict(int mbX, int mbY, int x4, int y4, int w4, int h4, int[] refs0, int[] refs1,
                         int[] mvs0, int[] mvs1) {
        var c = 9 + y4 * 8 + x4;
        var r0 = refs0[c];
        var r1 = refs1[c];
        var px = mbX * 16 + x4 * 4;
        var py = mbY * 16 + y4 * 4;
        var w = w4 * 4;
        var h = h4 * 4;
        var f = frame;
        var lumaDst = f.lumaOrigin + py * f.lumaStride + px;
        var chromaDst = f.chromaOrigin + (py >> 1) * f.chromaStride + (px >> 1);
        if (r0 >= 0 && r1 >= 0) {
            var ref0 = lists[0][r0];
            var ref1 = lists[1][r1];
            if (weightMode == 0 || (weightMode == 2 && implicitWeight[r0 * 32 + r1] == 32)) {
                averageLuma(ref0, ref1, px, py, mvs0[c], mvs1[c], lumaDst, w, h);
                if (f.hasChroma) {
                    averageChroma(ref0, ref1, px >> 1, py >> 1, mvs0[c], mvs1[c], chromaDst, w >> 1, h >> 1);
                }
                return;
            }
            lumaBlock(ref0, px, py, mvs0[c], f.luma, lumaDst, f.lumaStride, w, h);
            lumaBlock(ref1, px, py, mvs1[c], lumaTemp, 0, 16, w, h);
            if (f.hasChroma) {
                chromaBlock(ref0, px >> 1, py >> 1, mvs0[c], f.cb, f.cr, chromaDst, f.chromaStride, w >> 1, h >> 1);
                chromaBlock(ref1, px >> 1, py >> 1, mvs1[c], cbTemp, crTemp, 0, 8, w >> 1, h >> 1);
            }
            combine(r0, r1, lumaDst, chromaDst, w, h);
            return;
        }
        var list = r0 >= 0 ? 0 : 1;
        var r = r0 >= 0 ? r0 : r1;
        if (r < 0) {
            return;
        }
        var ref = lists[list][r];
        var mv = list == 0 ? mvs0[c] : mvs1[c];
        lumaBlock(ref, px, py, mv, f.luma, lumaDst, f.lumaStride, w, h);
        if (f.hasChroma) {
            chromaBlock(ref, px >> 1, py >> 1, mv, f.cb, f.cr, chromaDst, f.chromaStride, w >> 1, h >> 1);
        }
        if (weightMode == 1) {
            var hdr = header;
            if (hdr.lumaWeightFlag[list][r]) {
                dsp.weight(f.luma, lumaDst, f.lumaStride, w, h, hdr.lumaLog2WeightDenom,
                        hdr.lumaWeight[list][r], hdr.lumaOffset[list][r]);
            }
            if (f.hasChroma && hdr.chromaWeightFlag[list][r]) {
                var denom = hdr.chromaLog2WeightDenom;
                dsp.weight(f.cb, chromaDst, f.chromaStride, w >> 1, h >> 1, denom,
                        hdr.chromaWeight[list][r * 2], hdr.chromaOffset[list][r * 2]);
                dsp.weight(f.cr, chromaDst, f.chromaStride, w >> 1, h >> 1, denom,
                        hdr.chromaWeight[list][r * 2 + 1], hdr.chromaOffset[list][r * 2 + 1]);
            }
        }
    }

    /**
     * Combines the list 0 prediction in the frame with the list 1 prediction in the scratch
     * buffers.
     *
     * @param r0 the list 0 reference index
     * @param r1 the list 1 reference index
     * @param lumaDst the index of the block's top-left luma sample
     * @param chromaDst the index of the block's top-left chroma sample
     * @param w the luma block width
     * @param h the luma block height
     */
    private void combine(int r0, int r1, int lumaDst, int chromaDst, int w, int h) {
        var f = frame;
        var chroma = f.hasChroma;
        switch (weightMode) {
            case 1 -> {
                var hdr = header;
                if (hdr.lumaWeightFlag[0][r0] || hdr.lumaWeightFlag[1][r1]) {
                    dsp.biWeight(f.luma, lumaDst, f.lumaStride, lumaTemp, 0, 16, w, h, hdr.lumaLog2WeightDenom,
                            hdr.lumaWeight[0][r0], hdr.lumaWeight[1][r1],
                            (hdr.lumaOffset[0][r0] + hdr.lumaOffset[1][r1] + 1) >> 1);
                } else {
                    dsp.average(f.luma, lumaDst, f.lumaStride, lumaTemp, 0, 16, w, h);
                }
                if (chroma) {
                    if (hdr.chromaWeightFlag[0][r0] || hdr.chromaWeightFlag[1][r1]) {
                        var denom = hdr.chromaLog2WeightDenom;
                        dsp.biWeight(f.cb, chromaDst, f.chromaStride, cbTemp, 0, 8, w >> 1, h >> 1, denom,
                                hdr.chromaWeight[0][r0 * 2], hdr.chromaWeight[1][r1 * 2],
                                (hdr.chromaOffset[0][r0 * 2] + hdr.chromaOffset[1][r1 * 2] + 1) >> 1);
                        dsp.biWeight(f.cr, chromaDst, f.chromaStride, crTemp, 0, 8, w >> 1, h >> 1, denom,
                                hdr.chromaWeight[0][r0 * 2 + 1], hdr.chromaWeight[1][r1 * 2 + 1],
                                (hdr.chromaOffset[0][r0 * 2 + 1] + hdr.chromaOffset[1][r1 * 2 + 1] + 1) >> 1);
                    } else {
                        dsp.average(f.cb, chromaDst, f.chromaStride, cbTemp, 0, 8, w >> 1, h >> 1);
                        dsp.average(f.cr, chromaDst, f.chromaStride, crTemp, 0, 8, w >> 1, h >> 1);
                    }
                }
            }
            case 2 -> {
                var w0 = implicitWeight[r0 * 32 + r1];
                var w1 = 64 - w0;
                if (w0 == 32) {
                    dsp.average(f.luma, lumaDst, f.lumaStride, lumaTemp, 0, 16, w, h);
                    if (chroma) {
                        dsp.average(f.cb, chromaDst, f.chromaStride, cbTemp, 0, 8, w >> 1, h >> 1);
                        dsp.average(f.cr, chromaDst, f.chromaStride, crTemp, 0, 8, w >> 1, h >> 1);
                    }
                } else {
                    dsp.biWeight(f.luma, lumaDst, f.lumaStride, lumaTemp, 0, 16, w, h, 5, w0, w1, 0);
                    if (chroma) {
                        dsp.biWeight(f.cb, chromaDst, f.chromaStride, cbTemp, 0, 8, w >> 1, h >> 1, 5, w0, w1, 0);
                        dsp.biWeight(f.cr, chromaDst, f.chromaStride, crTemp, 0, 8, w >> 1, h >> 1, 5, w0, w1, 0);
                    }
                }
            }
            default -> {
                dsp.average(f.luma, lumaDst, f.lumaStride, lumaTemp, 0, 16, w, h);
                if (chroma) {
                    dsp.average(f.cb, chromaDst, f.chromaStride, cbTemp, 0, 8, w >> 1, h >> 1);
                    dsp.average(f.cr, chromaDst, f.chromaStride, crTemp, 0, 8, w >> 1, h >> 1);
                }
            }
        }
    }

    /**
     * Predicts a luma block from two references combined by plain averaging, reading a
     * full-sample prediction straight from its reference instead of copying it first.
     *
     * <p>With both motion vectors at full-sample positions the two reference blocks are averaged
     * into the picture; with one, the other prediction is made in the picture and averaged with
     * the full-sample block in place; otherwise the second prediction goes through
     * {@link #lumaTemp}. A block that needs edge emulation is always predicted through
     * {@link #lumaBlock}.
     *
     * @param ref0 the list 0 reference
     * @param ref1 the list 1 reference
     * @param px the block's horizontal position in luma samples
     * @param py the block's vertical position in luma samples
     * @param mv0 the packed list 0 motion vector
     * @param mv1 the packed list 1 motion vector
     * @param dstOffset the index of the block's top-left sample in the picture
     * @param w the block width
     * @param h the block height
     */
    private void averageLuma(DecoderFrame ref0, DecoderFrame ref1, int px, int py, int mv0, int mv1, int dstOffset,
                             int w, int h) {
        var f = frame;
        var full0 = fullLuma(ref0, px, py, mv0, w, h);
        var full1 = fullLuma(ref1, px, py, mv1, w, h);
        if (full0 >= 0 && full1 >= 0) {
            dsp.averageBlocks(ref0.luma, full0, ref0.lumaStride, ref1.luma, full1, ref1.lumaStride, f.luma, dstOffset,
                    f.lumaStride, w, h);
        } else if (full1 >= 0) {
            lumaBlock(ref0, px, py, mv0, f.luma, dstOffset, f.lumaStride, w, h);
            dsp.average(f.luma, dstOffset, f.lumaStride, ref1.luma, full1, ref1.lumaStride, w, h);
        } else if (full0 >= 0) {
            lumaBlock(ref1, px, py, mv1, f.luma, dstOffset, f.lumaStride, w, h);
            dsp.average(f.luma, dstOffset, f.lumaStride, ref0.luma, full0, ref0.lumaStride, w, h);
        } else {
            lumaBlock(ref0, px, py, mv0, f.luma, dstOffset, f.lumaStride, w, h);
            lumaBlock(ref1, px, py, mv1, lumaTemp, 0, 16, w, h);
            dsp.average(f.luma, dstOffset, f.lumaStride, lumaTemp, 0, 16, w, h);
        }
    }

    /**
     * Returns the index of a full-sample luma prediction inside its reference, after waiting for
     * the rows it reads, or -1 when the motion vector has a fractional part or the block needs
     * edge emulation.
     *
     * @param ref the reference picture
     * @param px the block's horizontal position in luma samples
     * @param py the block's vertical position in luma samples
     * @param mv the packed motion vector
     * @param w the block width
     * @param h the block height
     * @return the index of the block's top-left reference sample, or -1
     */
    private int fullLuma(DecoderFrame ref, int px, int py, int mv, int w, int h) {
        if ((mv & 0x30003) != 0) {
            return -1;
        }
        var x = px + ((short) mv >> 2);
        var y = py + (mv >> 18);
        var pad = DecoderFrame.LUMA_PAD;
        if (x - 2 < -pad || y - 2 < -pad || x + w + 3 > width + pad || y + h + 3 > height + pad) {
            return -1;
        }
        ref.awaitLumaRows(Math.clamp(y + h - 1, 0, height - 1) + 1);
        return ref.lumaOrigin + y * ref.lumaStride + x;
    }

    /**
     * Predicts the chroma blocks of both planes from two references combined by plain averaging,
     * as {@link #averageLuma} does for luma.
     *
     * @param ref0 the list 0 reference
     * @param ref1 the list 1 reference
     * @param cx the block's horizontal position in chroma samples
     * @param cy the block's vertical position in chroma samples
     * @param mv0 the packed list 0 motion vector, in luma quarter samples
     * @param mv1 the packed list 1 motion vector, in luma quarter samples
     * @param dstOffset the index of the block's top-left sample in both chroma planes
     * @param w the block width
     * @param h the block height
     */
    private void averageChroma(DecoderFrame ref0, DecoderFrame ref1, int cx, int cy, int mv0, int mv1, int dstOffset,
                               int w, int h) {
        var f = frame;
        var stride = f.chromaStride;
        var full0 = fullChroma(ref0, cx, cy, mv0, w, h);
        var full1 = fullChroma(ref1, cx, cy, mv1, w, h);
        if (full0 >= 0 && full1 >= 0) {
            dsp.averageBlocks(ref0.cb, full0, ref0.chromaStride, ref1.cb, full1, ref1.chromaStride, f.cb, dstOffset,
                    stride, w, h);
            dsp.averageBlocks(ref0.cr, full0, ref0.chromaStride, ref1.cr, full1, ref1.chromaStride, f.cr, dstOffset,
                    stride, w, h);
        } else if (full1 >= 0) {
            chromaBlock(ref0, cx, cy, mv0, f.cb, f.cr, dstOffset, stride, w, h);
            dsp.average(f.cb, dstOffset, stride, ref1.cb, full1, ref1.chromaStride, w, h);
            dsp.average(f.cr, dstOffset, stride, ref1.cr, full1, ref1.chromaStride, w, h);
        } else if (full0 >= 0) {
            chromaBlock(ref1, cx, cy, mv1, f.cb, f.cr, dstOffset, stride, w, h);
            dsp.average(f.cb, dstOffset, stride, ref0.cb, full0, ref0.chromaStride, w, h);
            dsp.average(f.cr, dstOffset, stride, ref0.cr, full0, ref0.chromaStride, w, h);
        } else {
            chromaBlock(ref0, cx, cy, mv0, f.cb, f.cr, dstOffset, stride, w, h);
            chromaBlock(ref1, cx, cy, mv1, cbTemp, crTemp, 0, 8, w, h);
            dsp.average(f.cb, dstOffset, stride, cbTemp, 0, 8, w, h);
            dsp.average(f.cr, dstOffset, stride, crTemp, 0, 8, w, h);
        }
    }

    /**
     * Returns the index of a full-sample chroma prediction inside both chroma planes of its
     * reference, after waiting for the rows it reads, or -1 when the chroma motion vector has a
     * fractional part or the block needs edge emulation.
     *
     * @param ref the reference picture
     * @param cx the block's horizontal position in chroma samples
     * @param cy the block's vertical position in chroma samples
     * @param mv the packed motion vector, in luma quarter samples
     * @param w the block width
     * @param h the block height
     * @return the index of the block's top-left reference sample, or -1
     */
    private int fullChroma(DecoderFrame ref, int cx, int cy, int mv, int w, int h) {
        if ((mv & 0x70007) != 0) {
            return -1;
        }
        var x = cx + ((short) mv >> 3);
        var y = cy + (mv >> 19);
        var pad = DecoderFrame.CHROMA_PAD;
        var chromaWidth = width >> 1;
        var chromaHeight = height >> 1;
        if (x < -pad || y < -pad || x + w + 1 > chromaWidth + pad || y + h + 1 > chromaHeight + pad) {
            return -1;
        }
        ref.awaitChromaRows(Math.clamp(y + h - 1, 0, chromaHeight - 1) + 1);
        return ref.chromaOrigin + y * ref.chromaStride + x;
    }

    /**
     * Interpolates a luma block of a reference frame.
     *
     * @param ref the reference frame
     * @param px the block's horizontal position in the picture
     * @param py the block's vertical position in the picture
     * @param mv the packed motion vector in quarter samples
     * @param dst the destination plane
     * @param dstOffset the destination top-left index
     * @param dstStride the destination stride
     * @param w the block width
     * @param h the block height
     */
    private void lumaBlock(DecoderFrame ref, int px, int py, int mv, byte[] dst, int dstOffset, int dstStride,
                           int w, int h) {
        int mvx = (short) mv;
        var mvy = mv >> 16;
        var x = px + (mvx >> 2);
        var y = py + (mvy >> 2);
        var fx = mvx & 3;
        var fy = mvy & 3;
        // Only vertical interpolation reads rows below the block, up to 3 of them (H.264 8.4.2.2.1).
        var lastRow = fy != 0 ? y + h + 2 : y + h - 1;
        ref.awaitLumaRows(Math.clamp(lastRow, 0, height - 1) + 1);
        var pad = DecoderFrame.LUMA_PAD;
        if (x - 2 >= -pad && y - 2 >= -pad && x + w + 3 <= width + pad && y + h + 3 <= height + pad) {
            dsp.lumaMc(ref.luma, ref.lumaOrigin + y * ref.lumaStride + x, ref.lumaStride, dst, dstOffset, dstStride,
                    w, h, fx, fy, sums, scratch);
            return;
        }
        var source = ref.luma;
        var stride = ref.lumaStride;
        var origin = ref.lumaOrigin;
        var maxX = width - 1;
        var maxY = height - 1;
        for (var r = 0; r < h + 5; r++) {
            var sy = Math.clamp(y - 2 + r, 0, maxY);
            var row = origin + sy * stride;
            var e = r * EDGE_STRIDE;
            for (var col = 0; col < w + 5; col++) {
                edge[e + col] = source[row + Math.clamp(x - 2 + col, 0, maxX)];
            }
        }
        dsp.lumaMc(edge, 2 * EDGE_STRIDE + 2, EDGE_STRIDE, dst, dstOffset, dstStride, w, h, fx, fy, sums, scratch);
    }

    /**
     * Interpolates the two chroma blocks of a reference frame.
     *
     * @param ref the reference frame
     * @param cx the block's horizontal chroma position
     * @param cy the block's vertical chroma position
     * @param mv the packed luma motion vector (eighth chroma samples)
     * @param dstCb the Cb destination plane
     * @param dstCr the Cr destination plane
     * @param dstOffset the destination top-left index
     * @param dstStride the destination stride
     * @param w the chroma block width
     * @param h the chroma block height
     */
    private void chromaBlock(DecoderFrame ref, int cx, int cy, int mv, byte[] dstCb, byte[] dstCr, int dstOffset,
                             int dstStride, int w, int h) {
        int mvx = (short) mv;
        var mvy = mv >> 16;
        var x = cx + (mvx >> 3);
        var y = cy + (mvy >> 3);
        var fx = mvx & 7;
        var fy = mvy & 7;
        var lastRow = fy != 0 ? y + h : y + h - 1;
        ref.awaitChromaRows(Math.clamp(lastRow, 0, (height >> 1) - 1) + 1);
        var pad = DecoderFrame.CHROMA_PAD;
        var chromaWidth = width >> 1;
        var chromaHeight = height >> 1;
        if (x >= -pad && y >= -pad && x + w + 1 <= chromaWidth + pad && y + h + 1 <= chromaHeight + pad) {
            var offset = ref.chromaOrigin + y * ref.chromaStride + x;
            dsp.chromaMc(ref.cb, ref.cr, offset, ref.chromaStride, dstCb, dstCr, dstOffset, dstStride, w, h, fx, fy);
            return;
        }
        emulateChroma(ref.cb, chromaEdge, ref, x, y, w, h, chromaWidth, chromaHeight);
        emulateChroma(ref.cr, chromaEdgeCr, ref, x, y, w, h, chromaWidth, chromaHeight);
        dsp.chromaMc(chromaEdge, chromaEdgeCr, 0, 16, dstCb, dstCr, dstOffset, dstStride, w, h, fx, fy);
    }

    /**
     * Copies a chroma reference block with clamped coordinates into an edge-emulation block.
     *
     * @param plane the chroma plane
     * @param target the edge-emulation block, 16 samples per row
     * @param ref the reference frame
     * @param x the block's horizontal position
     * @param y the block's vertical position
     * @param w the block width
     * @param h the block height
     * @param chromaWidth the chroma plane width
     * @param chromaHeight the chroma plane height
     */
    private static void emulateChroma(byte[] plane, byte[] target, DecoderFrame ref, int x, int y, int w, int h,
                                      int chromaWidth, int chromaHeight) {
        for (var r = 0; r < h + 1; r++) {
            var sy = Math.clamp(y + r, 0, chromaHeight - 1);
            var row = ref.chromaOrigin + sy * ref.chromaStride;
            for (var col = 0; col < w + 1; col++) {
                target[r * 16 + col] = plane[row + Math.clamp(x + col, 0, chromaWidth - 1)];
            }
        }
    }
}
