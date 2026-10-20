package com.github.auties00.smalt.video.h264.internal;

/**
 * Forms the inter prediction of the macroblocks of pictures deeper than 8 bits (ITU-T H.264
 * 8.4.2), on planes of 16-bit samples: fractional-sample interpolation from the reference
 * pictures and default, explicit or implicit weighted combination with offsets scaled to the bit
 * depth, written straight into the frame being decoded.
 *
 * <p>A reference is a frame or one field of a frame; a field is read as every second row of its
 * frame. Reference blocks that reach beyond the border of a reference frame's padding, or above
 * the first or below the last row of a reference field, are first copied into a scratch block
 * with coordinates clamped to the picture, which reproduces the sample repetition of ITU-T H.264
 * equations 8-228 and 8-229 for any motion vector.
 */
final class WideInterPredictor {
    /**
     * The row stride of the luma edge-emulation block.
     */
    private static final int EDGE_STRIDE = 24;

    /**
     * The kernels of pictures deeper than 8 bits.
     */
    private final H264WideDsp wideDsp = H264WideDsp.INSTANCE;

    /**
     * The luma interpolation integer scratch.
     */
    private final int[] sums = new int[H264WideDsp.LUMA_SCRATCH_INTS];

    /**
     * The luma interpolation sample scratch.
     */
    private final short[] scratch = new short[H264WideDsp.LUMA_SCRATCH_SHORTS];

    /**
     * The largest luma sample value of the frame being decoded.
     */
    private int lumaMax;

    /**
     * The largest chroma sample value of the frame being decoded.
     */
    private int chromaMax;

    /**
     * The luma bit depth of the frame being decoded minus 8, the scale of explicit luma weight
     * offsets.
     */
    private int lumaShift;

    /**
     * The chroma bit depth of the frame being decoded minus 8, the scale of explicit chroma
     * weight offsets.
     */
    private int chromaShift;

    /**
     * The list 1 luma prediction of a bi-predicted block, stride 16.
     */
    private final short[] lumaTemp = new short[16 * 16];

    /**
     * The list 1 Cb prediction of a bi-predicted block, stride 8.
     */
    private final short[] cbTemp = new short[8 * 8];

    /**
     * The list 1 Cr prediction of a bi-predicted block, stride 8.
     */
    private final short[] crTemp = new short[8 * 8];

    /**
     * The luma edge-emulation block.
     */
    private final short[] edge = new short[EDGE_STRIDE * 21];

    /**
     * The Cb edge-emulation block.
     */
    private final short[] chromaEdge = new short[16 * 9];

    /**
     * The Cr edge-emulation block.
     */
    private final short[] chromaEdgeCr = new short[16 * 9];

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
     * The frames holding the references of the current macroblock, indexed {@code [list][refIdx]}.
     */
    private DecoderFrame[][] refFrames;

    /**
     * The parity of the field each reference of the current macroblock reads (0 top, 1 bottom),
     * or -1 for a frame reference, indexed {@code [list][refIdx]}.
     */
    private int[][] refParity;

    /**
     * The parity of the current macroblock's field (0 top, 1 bottom), or -1 for a frame
     * macroblock.
     */
    private int currentParity = -1;

    /**
     * The shift from a reference index of the current macroblock to the index of its explicit
     * prediction weights: 1 for field macroblocks of MBAFF frames, 0 otherwise.
     */
    private int weightShift;

    /**
     * The slice header, for explicit weights.
     */
    private H264SliceHeader header;

    /**
     * The weighted prediction mode: 0 default, 1 explicit, 2 implicit.
     */
    private int weightMode;

    /**
     * The implicit list 0 weights of the current macroblock, indexed
     * {@code refIdxL0 * implicitStride + refIdxL1}.
     */
    private int[] implicitWeight;

    /**
     * The row length of {@link #implicitWeight}.
     */
    private int implicitStride = 32;

    /**
     * The index of the top-left luma sample of the current macroblock.
     */
    private int lumaBase;

    /**
     * The luma row distance of the current macroblock: twice the frame stride for a field
     * macroblock.
     */
    private int lumaPitch;

    /**
     * The index of the top-left chroma sample of the current macroblock.
     */
    private int chromaBase;

    /**
     * The chroma row distance of the current macroblock.
     */
    private int chromaPitch;

    /**
     * The horizontal luma position of the current macroblock in its picture.
     */
    private int mbLumaX;

    /**
     * The vertical luma position of the current macroblock in the frame or field it is predicted
     * in: frame rows for a frame macroblock, field rows for a field macroblock.
     */
    private int mbLumaY;

    /**
     * Constructs a predictor.
     */
    WideInterPredictor() {
    }

    /**
     * Prepares prediction into a new frame.
     *
     * @param frame the frame being decoded, a {@linkplain DecoderFrame#wide wide} frame
     */
    void startPicture(DecoderFrame frame) {
        this.frame = frame;
        this.lumaMax = (1 << frame.bitDepthLuma) - 1;
        this.chromaMax = (1 << frame.bitDepthChroma) - 1;
        this.lumaShift = frame.bitDepthLuma - 8;
        this.chromaShift = frame.bitDepthChroma - 8;
        this.width = frame.mbWidth * 16;
        this.height = frame.mbHeight * 16;
    }

    /**
     * Prepares prediction for a slice.
     *
     * @param header the slice header
     * @param weightMode the weighted prediction mode
     */
    void startSlice(H264SliceHeader header, int weightMode) {
        this.header = header;
        this.weightMode = weightMode;
    }

    /**
     * Selects the references and the weights of the macroblocks that follow: the reference
     * frames and fields their reference indices name, and their implicit weights.
     *
     * @param frames the frames holding the references, indexed {@code [list][refIdx]}
     * @param parity the field each reference reads, or -1 for frames, indexed like
     * {@code frames}
     * @param currentParity the parity of the macroblocks' field, or -1 for frame macroblocks
     * @param weightShift 1 for field macroblocks of MBAFF frames, whose reference indices count
     * fields, 0 otherwise
     * @param implicitWeight the implicit list 0 weights, indexed
     * {@code refIdxL0 * implicitStride + refIdxL1}
     * @param implicitStride the row length of {@code implicitWeight}
     */
    void selectReferences(DecoderFrame[][] frames, int[][] parity, int currentParity, int weightShift,
                          int[] implicitWeight, int implicitStride) {
        this.refFrames = frames;
        this.refParity = parity;
        this.currentParity = currentParity;
        this.weightShift = weightShift;
        this.implicitWeight = implicitWeight;
        this.implicitStride = implicitStride;
    }

    /**
     * Sets the position of the macroblock the next {@link #predictMacroblock} predicts.
     *
     * @param lumaBase the index of its top-left luma sample
     * @param lumaPitch its luma row distance
     * @param chromaBase the index of its top-left chroma sample
     * @param chromaPitch its chroma row distance
     * @param x its horizontal luma position
     * @param y its vertical luma position in the frame or field it is predicted in
     */
    void position(int lumaBase, int lumaPitch, int chromaBase, int chromaPitch, int x, int y) {
        this.lumaBase = lumaBase;
        this.lumaPitch = lumaPitch;
        this.chromaBase = chromaBase;
        this.chromaPitch = chromaPitch;
        this.mbLumaX = x;
        this.mbLumaY = y;
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
        switch (decoder.partShape()) {
            case 0 -> predict(0, 0, 4, 4, refs0, refs1, mvs0, mvs1);
            case 1 -> {
                predict(0, 0, 4, 2, refs0, refs1, mvs0, mvs1);
                predict(0, 2, 4, 2, refs0, refs1, mvs0, mvs1);
            }
            case 2 -> {
                predict(0, 0, 2, 4, refs0, refs1, mvs0, mvs1);
                predict(2, 0, 2, 4, refs0, refs1, mvs0, mvs1);
            }
            default -> {
                if (decoder.uniformMotion()) {
                    predict(0, 0, 4, 4, refs0, refs1, mvs0, mvs1);
                    return;
                }
                for (var i = 0; i < 4; i++) {
                    var x = (i & 1) * 2;
                    var y = (i >> 1) * 2;
                    if (decoder.directPer4x4(i)) {
                        predictQuad(x, y, refs0, refs1, mvs0, mvs1);
                        continue;
                    }
                    switch (decoder.subShape(i)) {
                        case 0 -> predict(x, y, 2, 2, refs0, refs1, mvs0, mvs1);
                        case 1 -> {
                            predict(x, y, 2, 1, refs0, refs1, mvs0, mvs1);
                            predict(x, y + 1, 2, 1, refs0, refs1, mvs0, mvs1);
                        }
                        case 2 -> {
                            predict(x, y, 1, 2, refs0, refs1, mvs0, mvs1);
                            predict(x + 1, y, 1, 2, refs0, refs1, mvs0, mvs1);
                        }
                        default -> predictQuad(x, y, refs0, refs1, mvs0, mvs1);
                    }
                }
            }
        }
    }

    /**
     * Predicts the four 4x4 blocks of an 8x8 partition separately.
     *
     * @param x the partition column in 4x4 units
     * @param y the partition row in 4x4 units
     * @param refs0 the list 0 reference cache
     * @param refs1 the list 1 reference cache
     * @param mvs0 the list 0 motion vector cache
     * @param mvs1 the list 1 motion vector cache
     */
    private void predictQuad(int x, int y, int[] refs0, int[] refs1, int[] mvs0, int[] mvs1) {
        predict(x, y, 1, 1, refs0, refs1, mvs0, mvs1);
        predict(x + 1, y, 1, 1, refs0, refs1, mvs0, mvs1);
        predict(x, y + 1, 1, 1, refs0, refs1, mvs0, mvs1);
        predict(x + 1, y + 1, 1, 1, refs0, refs1, mvs0, mvs1);
    }

    /**
     * Predicts one block with the motion of its top-left 4x4 block.
     *
     * @param x4 the block column in 4x4 units within the macroblock
     * @param y4 the block row in 4x4 units within the macroblock
     * @param w4 the block width in 4x4 units
     * @param h4 the block height in 4x4 units
     * @param refs0 the list 0 reference cache
     * @param refs1 the list 1 reference cache
     * @param mvs0 the list 0 motion vector cache
     * @param mvs1 the list 1 motion vector cache
     */
    private void predict(int x4, int y4, int w4, int h4, int[] refs0, int[] refs1, int[] mvs0, int[] mvs1) {
        var c = 9 + y4 * 8 + x4;
        var r0 = refs0[c];
        var r1 = refs1[c];
        var px = mbLumaX + x4 * 4;
        var py = mbLumaY + y4 * 4;
        var w = w4 * 4;
        var h = h4 * 4;
        var f = frame;
        var lumaDst = lumaBase + y4 * 4 * lumaPitch + x4 * 4;
        var chromaDst = chromaBase + y4 * 2 * chromaPitch + x4 * 2;
        var lumaStride = lumaPitch;
        var chromaStride = chromaPitch;
        if (r0 >= 0 && r1 >= 0) {
            var ref0 = refFrames[0][r0];
            var ref1 = refFrames[1][r1];
            var parity0 = refParity[0][r0];
            var parity1 = refParity[1][r1];
            if (weightMode == 0 || (weightMode == 2 && implicitWeight[r0 * implicitStride + r1] == 32)) {
                averageLuma(ref0, parity0, ref1, parity1, px, py, mvs0[c], mvs1[c], lumaDst, w, h);
                if (f.hasChroma) {
                    averageChroma(ref0, parity0, ref1, parity1, px >> 1, py >> 1, mvs0[c], mvs1[c], chromaDst,
                            w >> 1, h >> 1);
                }
                return;
            }
            lumaBlock(ref0, parity0, px, py, mvs0[c], f.luma16, lumaDst, lumaStride, w, h);
            lumaBlock(ref1, parity1, px, py, mvs1[c], lumaTemp, 0, 16, w, h);
            if (f.hasChroma) {
                chromaBlock(ref0, parity0, px >> 1, py >> 1, mvs0[c], f.cb16, f.cr16, chromaDst, chromaStride, w >> 1,
                        h >> 1);
                chromaBlock(ref1, parity1, px >> 1, py >> 1, mvs1[c], cbTemp, crTemp, 0, 8, w >> 1, h >> 1);
            }
            combine(r0, r1, lumaDst, chromaDst, w, h);
            return;
        }
        var list = r0 >= 0 ? 0 : 1;
        var r = r0 >= 0 ? r0 : r1;
        if (r < 0) {
            return;
        }
        var ref = refFrames[list][r];
        var parity = refParity[list][r];
        var mv = list == 0 ? mvs0[c] : mvs1[c];
        lumaBlock(ref, parity, px, py, mv, f.luma16, lumaDst, lumaStride, w, h);
        if (f.hasChroma) {
            chromaBlock(ref, parity, px >> 1, py >> 1, mv, f.cb16, f.cr16, chromaDst, chromaStride, w >> 1, h >> 1);
        }
        if (weightMode == 1) {
            var hdr = header;
            var index = r >> weightShift;
            if (hdr.lumaWeightFlag[list][index]) {
                wideDsp.weight(f.luma16, lumaDst, lumaStride, w, h, hdr.lumaLog2WeightDenom,
                        hdr.lumaWeight[list][index], hdr.lumaOffset[list][index] << lumaShift, lumaMax);
            }
            if (f.hasChroma && hdr.chromaWeightFlag[list][index]) {
                var denom = hdr.chromaLog2WeightDenom;
                wideDsp.weight(f.cb16, chromaDst, chromaStride, w >> 1, h >> 1, denom,
                        hdr.chromaWeight[list][index * 2], hdr.chromaOffset[list][index * 2] << chromaShift,
                        chromaMax);
                wideDsp.weight(f.cr16, chromaDst, chromaStride, w >> 1, h >> 1, denom,
                        hdr.chromaWeight[list][index * 2 + 1], hdr.chromaOffset[list][index * 2 + 1] << chromaShift,
                        chromaMax);
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
        var lumaStride = lumaPitch;
        var chromaStride = chromaPitch;
        switch (weightMode) {
            case 1 -> {
                var hdr = header;
                var i0 = r0 >> weightShift;
                var i1 = r1 >> weightShift;
                if (hdr.lumaWeightFlag[0][i0] || hdr.lumaWeightFlag[1][i1]) {
                    wideDsp.biWeight(f.luma16, lumaDst, lumaStride, lumaTemp, 0, 16, w, h,
                            hdr.lumaLog2WeightDenom, hdr.lumaWeight[0][i0], hdr.lumaWeight[1][i1],
                            ((hdr.lumaOffset[0][i0] << lumaShift) + (hdr.lumaOffset[1][i1] << lumaShift) + 1) >> 1,
                            lumaMax);
                } else {
                    wideDsp.average(f.luma16, lumaDst, lumaStride, lumaTemp, 0, 16, w, h);
                }
                if (chroma) {
                    if (hdr.chromaWeightFlag[0][i0] || hdr.chromaWeightFlag[1][i1]) {
                        var denom = hdr.chromaLog2WeightDenom;
                        wideDsp.biWeight(f.cb16, chromaDst, chromaStride, cbTemp, 0, 8, w >> 1, h >> 1, denom,
                                hdr.chromaWeight[0][i0 * 2], hdr.chromaWeight[1][i1 * 2],
                                ((hdr.chromaOffset[0][i0 * 2] << chromaShift)
                                        + (hdr.chromaOffset[1][i1 * 2] << chromaShift) + 1) >> 1, chromaMax);
                        wideDsp.biWeight(f.cr16, chromaDst, chromaStride, crTemp, 0, 8, w >> 1, h >> 1, denom,
                                hdr.chromaWeight[0][i0 * 2 + 1], hdr.chromaWeight[1][i1 * 2 + 1],
                                ((hdr.chromaOffset[0][i0 * 2 + 1] << chromaShift)
                                        + (hdr.chromaOffset[1][i1 * 2 + 1] << chromaShift) + 1) >> 1, chromaMax);
                    } else {
                        wideDsp.average(f.cb16, chromaDst, chromaStride, cbTemp, 0, 8, w >> 1, h >> 1);
                        wideDsp.average(f.cr16, chromaDst, chromaStride, crTemp, 0, 8, w >> 1, h >> 1);
                    }
                }
            }
            case 2 -> {
                var w0 = implicitWeight[r0 * implicitStride + r1];
                var w1 = 64 - w0;
                if (w0 == 32) {
                    wideDsp.average(f.luma16, lumaDst, lumaStride, lumaTemp, 0, 16, w, h);
                    if (chroma) {
                        wideDsp.average(f.cb16, chromaDst, chromaStride, cbTemp, 0, 8, w >> 1, h >> 1);
                        wideDsp.average(f.cr16, chromaDst, chromaStride, crTemp, 0, 8, w >> 1, h >> 1);
                    }
                } else {
                    wideDsp.biWeight(f.luma16, lumaDst, lumaStride, lumaTemp, 0, 16, w, h, 5, w0, w1, 0, lumaMax);
                    if (chroma) {
                        wideDsp.biWeight(f.cb16, chromaDst, chromaStride, cbTemp, 0, 8, w >> 1, h >> 1, 5, w0, w1,
                                0, chromaMax);
                        wideDsp.biWeight(f.cr16, chromaDst, chromaStride, crTemp, 0, 8, w >> 1, h >> 1, 5, w0, w1,
                                0, chromaMax);
                    }
                }
            }
            default -> {
                wideDsp.average(f.luma16, lumaDst, lumaStride, lumaTemp, 0, 16, w, h);
                if (chroma) {
                    wideDsp.average(f.cb16, chromaDst, chromaStride, cbTemp, 0, 8, w >> 1, h >> 1);
                    wideDsp.average(f.cr16, chromaDst, chromaStride, crTemp, 0, 8, w >> 1, h >> 1);
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
     * @param parity0 the field of {@code ref0} read, or -1 for the frame
     * @param ref1 the list 1 reference
     * @param parity1 the field of {@code ref1} read, or -1 for the frame
     * @param px the block's horizontal position in luma samples
     * @param py the block's vertical position in luma samples
     * @param mv0 the packed list 0 motion vector
     * @param mv1 the packed list 1 motion vector
     * @param dstOffset the index of the block's top-left sample in the picture
     * @param w the block width
     * @param h the block height
     */
    private void averageLuma(DecoderFrame ref0, int parity0, DecoderFrame ref1, int parity1, int px, int py, int mv0,
                             int mv1, int dstOffset, int w, int h) {
        var f = frame;
        var stride = lumaPitch;
        var full0 = fullLuma(ref0, parity0, px, py, mv0, w, h);
        var full1 = fullLuma(ref1, parity1, px, py, mv1, w, h);
        if (full0 >= 0 && full1 >= 0) {
            wideDsp.averageBlocks(ref0.luma16, full0, refStride(ref0.lumaStride, parity0), ref1.luma16, full1,
                    refStride(ref1.lumaStride, parity1), f.luma16, dstOffset, stride, w, h);
        } else if (full1 >= 0) {
            lumaBlock(ref0, parity0, px, py, mv0, f.luma16, dstOffset, stride, w, h);
            wideDsp.average(f.luma16, dstOffset, stride, ref1.luma16, full1, refStride(ref1.lumaStride, parity1), w,
                    h);
        } else if (full0 >= 0) {
            lumaBlock(ref1, parity1, px, py, mv1, f.luma16, dstOffset, stride, w, h);
            wideDsp.average(f.luma16, dstOffset, stride, ref0.luma16, full0, refStride(ref0.lumaStride, parity0), w,
                    h);
        } else {
            lumaBlock(ref0, parity0, px, py, mv0, f.luma16, dstOffset, stride, w, h);
            lumaBlock(ref1, parity1, px, py, mv1, lumaTemp, 0, 16, w, h);
            wideDsp.average(f.luma16, dstOffset, stride, lumaTemp, 0, 16, w, h);
        }
    }

    /**
     * Returns the row distance of a reference: the frame stride, or twice it for a field.
     *
     * @param stride the frame stride
     * @param parity the field read, or -1 for the frame
     * @return the row distance
     */
    private static int refStride(int stride, int parity) {
        return parity < 0 ? stride : 2 * stride;
    }

    /**
     * Returns the index of a full-sample luma prediction inside its reference, after waiting for
     * the rows it reads, or -1 when the motion vector has a fractional part or the block needs
     * edge emulation.
     *
     * @param ref the reference picture
     * @param parity the field read, or -1 for the frame
     * @param px the block's horizontal position in luma samples
     * @param py the block's vertical position in luma samples
     * @param mv the packed motion vector
     * @param w the block width
     * @param h the block height
     * @return the index of the block's top-left reference sample, or -1
     */
    private int fullLuma(DecoderFrame ref, int parity, int px, int py, int mv, int w, int h) {
        if ((mv & 0x30003) != 0) {
            return -1;
        }
        var x = px + ((short) mv >> 2);
        var y = py + (mv >> 18);
        var pad = DecoderFrame.LUMA_PAD;
        if (parity < 0) {
            if (x - 2 < -pad || y - 2 < -pad || x + w + 3 > width + pad || y + h + 3 > height + pad) {
                return -1;
            }
            ref.awaitLumaRows(Math.clamp(y + h - 1, 0, height - 1) + 1);
            return ref.lumaOrigin + y * ref.lumaStride + x;
        }
        var fieldHeight = height >> 1;
        if (x - 2 < -pad || y - 2 < 0 || x + w + 3 > width + pad || y + h + 3 > fieldHeight) {
            return -1;
        }
        ref.awaitFieldLumaRows(parity, y + h);
        return ref.lumaOrigin + parity * ref.lumaStride + y * 2 * ref.lumaStride + x;
    }

    /**
     * Predicts the chroma blocks of both planes from two references combined by plain averaging,
     * as {@link #averageLuma} does for luma.
     *
     * @param ref0 the list 0 reference
     * @param parity0 the field of {@code ref0} read, or -1 for the frame
     * @param ref1 the list 1 reference
     * @param parity1 the field of {@code ref1} read, or -1 for the frame
     * @param cx the block's horizontal position in chroma samples
     * @param cy the block's vertical position in chroma samples
     * @param mv0 the packed list 0 motion vector, in luma quarter samples
     * @param mv1 the packed list 1 motion vector, in luma quarter samples
     * @param dstOffset the index of the block's top-left sample in both chroma planes
     * @param w the block width
     * @param h the block height
     */
    private void averageChroma(DecoderFrame ref0, int parity0, DecoderFrame ref1, int parity1, int cx, int cy,
                               int mv0, int mv1, int dstOffset, int w, int h) {
        var f = frame;
        var stride = chromaPitch;
        var full0 = fullChroma(ref0, parity0, cx, cy, mv0, w, h);
        var full1 = fullChroma(ref1, parity1, cx, cy, mv1, w, h);
        var stride0 = refStride(ref0.chromaStride, parity0);
        var stride1 = refStride(ref1.chromaStride, parity1);
        if (full0 >= 0 && full1 >= 0) {
            wideDsp.averageBlocks(ref0.cb16, full0, stride0, ref1.cb16, full1, stride1, f.cb16, dstOffset, stride, w,
                    h);
            wideDsp.averageBlocks(ref0.cr16, full0, stride0, ref1.cr16, full1, stride1, f.cr16, dstOffset, stride, w,
                    h);
        } else if (full1 >= 0) {
            chromaBlock(ref0, parity0, cx, cy, mv0, f.cb16, f.cr16, dstOffset, stride, w, h);
            wideDsp.average(f.cb16, dstOffset, stride, ref1.cb16, full1, stride1, w, h);
            wideDsp.average(f.cr16, dstOffset, stride, ref1.cr16, full1, stride1, w, h);
        } else if (full0 >= 0) {
            chromaBlock(ref1, parity1, cx, cy, mv1, f.cb16, f.cr16, dstOffset, stride, w, h);
            wideDsp.average(f.cb16, dstOffset, stride, ref0.cb16, full0, stride0, w, h);
            wideDsp.average(f.cr16, dstOffset, stride, ref0.cr16, full0, stride0, w, h);
        } else {
            chromaBlock(ref0, parity0, cx, cy, mv0, f.cb16, f.cr16, dstOffset, stride, w, h);
            chromaBlock(ref1, parity1, cx, cy, mv1, cbTemp, crTemp, 0, 8, w, h);
            wideDsp.average(f.cb16, dstOffset, stride, cbTemp, 0, 8, w, h);
            wideDsp.average(f.cr16, dstOffset, stride, crTemp, 0, 8, w, h);
        }
    }

    /**
     * Returns the vertical chroma motion vector of a luma motion vector (ITU-T H.264 8.4.1.4):
     * between fields of opposite parity the vector shifts by a quarter chroma sample.
     *
     * @param mvy the vertical luma motion vector component
     * @param parity the field of the reference read, or -1 for the frame
     * @return the vertical chroma vector, in eighth chroma samples
     */
    private int chromaMvy(int mvy, int parity) {
        var current = currentParity;
        if (parity < 0 || current < 0 || parity == current) {
            return mvy;
        }
        return current == 1 ? mvy + 2 : mvy - 2;
    }

    /**
     * Returns the index of a full-sample chroma prediction inside both chroma planes of its
     * reference, after waiting for the rows it reads, or -1 when the chroma motion vector has a
     * fractional part or the block needs edge emulation.
     *
     * @param ref the reference picture
     * @param parity the field read, or -1 for the frame
     * @param cx the block's horizontal position in chroma samples
     * @param cy the block's vertical position in chroma samples
     * @param mv the packed motion vector, in luma quarter samples
     * @param w the block width
     * @param h the block height
     * @return the index of the block's top-left reference sample, or -1
     */
    private int fullChroma(DecoderFrame ref, int parity, int cx, int cy, int mv, int w, int h) {
        var mvy = chromaMvy(mv >> 16, parity);
        if (((short) mv & 7) != 0 || (mvy & 7) != 0) {
            return -1;
        }
        var x = cx + ((short) mv >> 3);
        var y = cy + (mvy >> 3);
        var pad = DecoderFrame.CHROMA_PAD;
        var chromaWidth = width >> 1;
        var chromaHeight = height >> 1;
        if (parity < 0) {
            if (x < -pad || y < -pad || x + w + 1 > chromaWidth + pad || y + h + 1 > chromaHeight + pad) {
                return -1;
            }
            ref.awaitChromaRows(Math.clamp(y + h - 1, 0, chromaHeight - 1) + 1);
            return ref.chromaOrigin + y * ref.chromaStride + x;
        }
        var fieldHeight = chromaHeight >> 1;
        if (x < -pad || y < 0 || x + w + 1 > chromaWidth + pad || y + h + 1 > fieldHeight) {
            return -1;
        }
        ref.awaitFieldChromaRows(parity, y + h);
        return ref.chromaOrigin + parity * ref.chromaStride + y * 2 * ref.chromaStride + x;
    }

    /**
     * Interpolates a luma block of a reference frame or field.
     *
     * @param ref the reference frame
     * @param parity the field read, or -1 for the frame
     * @param px the block's horizontal position in the picture
     * @param py the block's vertical position in the frame or field
     * @param mv the packed motion vector in quarter samples
     * @param dst the destination plane
     * @param dstOffset the destination top-left index
     * @param dstStride the destination stride
     * @param w the block width
     * @param h the block height
     */
    private void lumaBlock(DecoderFrame ref, int parity, int px, int py, int mv, short[] dst, int dstOffset,
                           int dstStride, int w, int h) {
        int mvx = (short) mv;
        var mvy = mv >> 16;
        var x = px + (mvx >> 2);
        var y = py + (mvy >> 2);
        var fx = mvx & 3;
        var fy = mvy & 3;
        // Only vertical interpolation reads rows below the block, up to 3 of them (H.264 8.4.2.2.1).
        var lastRow = fy != 0 ? y + h + 2 : y + h - 1;
        var pad = DecoderFrame.LUMA_PAD;
        int origin;
        int stride;
        int rows;
        if (parity < 0) {
            ref.awaitLumaRows(Math.clamp(lastRow, 0, height - 1) + 1);
            if (x - 2 >= -pad && y - 2 >= -pad && x + w + 3 <= width + pad && y + h + 3 <= height + pad) {
                wideDsp.lumaMc(ref.luma16, ref.lumaOrigin + y * ref.lumaStride + x, ref.lumaStride, dst, dstOffset,
                        dstStride, w, h, fx, fy, sums, scratch, lumaMax);
                return;
            }
            origin = ref.lumaOrigin;
            stride = ref.lumaStride;
            rows = height;
        } else {
            rows = height >> 1;
            ref.awaitFieldLumaRows(parity, Math.clamp(lastRow, 0, rows - 1) + 1);
            origin = ref.lumaOrigin + parity * ref.lumaStride;
            stride = 2 * ref.lumaStride;
            if (x - 2 >= -pad && y - 2 >= 0 && x + w + 3 <= width + pad && y + h + 3 <= rows) {
                wideDsp.lumaMc(ref.luma16, origin + y * stride + x, stride, dst, dstOffset, dstStride, w, h, fx, fy,
                        sums, scratch, lumaMax);
                return;
            }
        }
        var source = ref.luma16;
        var maxX = width - 1;
        var maxY = rows - 1;
        for (var r = 0; r < h + 5; r++) {
            var sy = Math.clamp(y - 2 + r, 0, maxY);
            var row = origin + sy * stride;
            var e = r * EDGE_STRIDE;
            for (var col = 0; col < w + 5; col++) {
                edge[e + col] = source[row + Math.clamp(x - 2 + col, 0, maxX)];
            }
        }
        wideDsp.lumaMc(edge, 2 * EDGE_STRIDE + 2, EDGE_STRIDE, dst, dstOffset, dstStride, w, h, fx, fy, sums,
                scratch, lumaMax);
    }

    /**
     * Interpolates the two chroma blocks of a reference frame or field.
     *
     * @param ref the reference frame
     * @param parity the field read, or -1 for the frame
     * @param cx the block's horizontal chroma position
     * @param cy the block's vertical chroma position in the frame or field
     * @param mv the packed luma motion vector (eighth chroma samples)
     * @param dstCb the Cb destination plane
     * @param dstCr the Cr destination plane
     * @param dstOffset the destination top-left index
     * @param dstStride the destination stride
     * @param w the chroma block width
     * @param h the chroma block height
     */
    private void chromaBlock(DecoderFrame ref, int parity, int cx, int cy, int mv, short[] dstCb, short[] dstCr,
                             int dstOffset, int dstStride, int w, int h) {
        int mvx = (short) mv;
        var mvy = chromaMvy(mv >> 16, parity);
        var x = cx + (mvx >> 3);
        var y = cy + (mvy >> 3);
        var fx = mvx & 7;
        var fy = mvy & 7;
        var lastRow = fy != 0 ? y + h : y + h - 1;
        var pad = DecoderFrame.CHROMA_PAD;
        var chromaWidth = width >> 1;
        int origin;
        int stride;
        int rows;
        if (parity < 0) {
            rows = height >> 1;
            ref.awaitChromaRows(Math.clamp(lastRow, 0, rows - 1) + 1);
            if (x >= -pad && y >= -pad && x + w + 1 <= chromaWidth + pad && y + h + 1 <= rows + pad) {
                var offset = ref.chromaOrigin + y * ref.chromaStride + x;
                wideDsp.chromaMc(ref.cb16, ref.cr16, offset, ref.chromaStride, dstCb, dstCr, dstOffset, dstStride, w,
                        h, fx, fy);
                return;
            }
            origin = ref.chromaOrigin;
            stride = ref.chromaStride;
        } else {
            rows = height >> 2;
            ref.awaitFieldChromaRows(parity, Math.clamp(lastRow, 0, rows - 1) + 1);
            origin = ref.chromaOrigin + parity * ref.chromaStride;
            stride = 2 * ref.chromaStride;
            if (x >= -pad && y >= 0 && x + w + 1 <= chromaWidth + pad && y + h + 1 <= rows) {
                wideDsp.chromaMc(ref.cb16, ref.cr16, origin + y * stride + x, stride, dstCb, dstCr, dstOffset,
                        dstStride, w, h, fx, fy);
                return;
            }
        }
        emulateChroma(ref.cb16, chromaEdge, origin, stride, x, y, w, h, chromaWidth, rows);
        emulateChroma(ref.cr16, chromaEdgeCr, origin, stride, x, y, w, h, chromaWidth, rows);
        wideDsp.chromaMc(chromaEdge, chromaEdgeCr, 0, 16, dstCb, dstCr, dstOffset, dstStride, w, h, fx, fy);
    }

    /**
     * Copies a chroma reference block with clamped coordinates into an edge-emulation block.
     *
     * @param plane the chroma plane
     * @param target the edge-emulation block, 16 samples per row
     * @param origin the index of the frame's or field's sample {@code (0, 0)}
     * @param stride the row distance of the frame or field
     * @param x the block's horizontal position
     * @param y the block's vertical position
     * @param w the block width
     * @param h the block height
     * @param chromaWidth the chroma plane width
     * @param chromaHeight the chroma height of the frame or field
     */
    private static void emulateChroma(short[] plane, short[] target, int origin, int stride, int x, int y, int w, int h,
                                      int chromaWidth, int chromaHeight) {
        for (var r = 0; r < h + 1; r++) {
            var sy = Math.clamp(y + r, 0, chromaHeight - 1);
            var row = origin + sy * stride;
            for (var col = 0; col < w + 1; col++) {
                target[r * 16 + col] = plane[row + Math.clamp(x + col, 0, chromaWidth - 1)];
            }
        }
    }
}
