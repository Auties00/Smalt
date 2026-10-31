package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.video.hevc.internal.HevcFrame;
import com.github.auties00.smalt.video.hevc.internal.HevcSliceHeader;
import com.github.auties00.smalt.video.hevc.internal.HevcSps;
import com.github.auties00.smalt.video.hevc.internal.PictureState;

import java.util.Arrays;

/**
 * Holds one picture being encoded: its source samples, its reconstruction and the coding decisions of every
 * coding tree block, from which the syntax is written.
 *
 * <p>The decisions live in per 4x4 luma block arrays of the decoder's layout ({@link PictureState} for the
 * flags, coding tree depth, luma intra mode and quantizer, the motion field of the {@link HevcFrame}) and in
 * the arrays of this class for the remaining syntax. The quantized levels of every transform block are kept
 * at the block's position in coefficient planes the size of the picture's components.
 */
final class PictureData {
    /**
     * The field of {@link #cuInfo} holding {@code PartMode}, bits 0 to 2.
     */
    static final int CU_PART_MASK = 7;

    /**
     * The shift of the field of {@link #cuInfo} holding {@code intra_chroma_pred_mode}, 0 to 4.
     */
    static final int CU_CHROMA_SHIFT = 3;

    /**
     * The field of {@link #tuInfo} holding the transform tree depth of the leaf, bits 0 and 1.
     */
    static final int TU_DEPTH_MASK = 3;

    /**
     * The {@link #tuInfo} flag of a luma transform block with coefficients.
     */
    static final int TU_CBF_Y = 4;

    /**
     * The {@link #tuInfo} flag of a Cb transform block with coefficients: of the leaf's own chroma block, or
     * for 4x4 luma leaves of 4:2:0 pictures, of the chroma block of their 8x8 parent.
     */
    static final int TU_CBF_CB = 8;

    /**
     * The {@link #tuInfo} flag of a Cr transform block with coefficients, as {@link #TU_CBF_CB}.
     */
    static final int TU_CBF_CR = 16;

    /**
     * The {@link #puInfo} flag of a prediction unit coded in merge mode.
     */
    static final int PU_MERGE = 1;

    /**
     * The shift of the field of {@link #puInfo} holding {@code merge_idx}, 3 bits.
     */
    static final int PU_MERGE_IDX_SHIFT = 1;

    /**
     * The shift of the field of {@link #puInfo} holding {@code inter_pred_idc}, 2 bits: 0 list 0, 1 list 1,
     * 2 both.
     */
    static final int PU_DIR_SHIFT = 4;

    /**
     * The shift of {@code mvp_l0_flag} in {@link #puInfo}; {@code mvp_l1_flag} follows it.
     */
    static final int PU_MVP_SHIFT = 6;

    /**
     * The settings.
     */
    final EncoderSettings s;

    /**
     * The sequence parameter set the decoder components read.
     */
    final HevcSps sps;

    /**
     * The reconstruction, with 16-bit sample planes at every bit depth.
     */
    final HevcFrame frame;

    /**
     * The coding information the decoder components read.
     */
    final PictureState pic;

    /**
     * The source luma samples, {@link EncoderSettings#codedWidth} per row.
     */
    final short[] sourceY;

    /**
     * The source Cb samples, {@link EncoderSettings#chromaWidth} per row.
     */
    final short[] sourceCb;

    /**
     * The source Cr samples, {@link EncoderSettings#chromaWidth} per row.
     */
    final short[] sourceCr;

    /**
     * {@code PartMode} and {@code intra_chroma_pred_mode} of the coding unit covering each 4x4 block.
     */
    final byte[] cuInfo;

    /**
     * The transform tree depth and coded block flags of the transform block covering each 4x4 block.
     */
    final byte[] tuInfo;

    /**
     * The merge flag and index, the prediction direction and the predictor flags of the prediction unit
     * covering each 4x4 block.
     */
    final int[] puInfo;

    /**
     * The list 0 motion vector difference of the prediction unit covering each 4x4 block, packed as
     * {@code (y << 16) | (x & 0xFFFF)}.
     */
    final int[] mvd0;

    /**
     * The list 1 motion vector difference, as {@link #mvd0}.
     */
    final int[] mvd1;

    /**
     * The quantized luma levels.
     */
    final short[] levelY;

    /**
     * The quantized Cb levels.
     */
    final short[] levelCb;

    /**
     * The quantized Cr levels.
     */
    final short[] levelCr;

    /**
     * The sample adaptive offset merge of each coding tree block: 0 none, 1 left, 2 up.
     */
    final byte[] saoMerge;

    /**
     * The slice segment header the decoder components read, set before the picture is analyzed.
     */
    HevcSliceHeader header;

    /**
     * The values the slice segment header is written from.
     */
    final SliceHeaderWriter.SliceParams params = new SliceHeaderWriter.SliceParams();

    /**
     * The reference picture lists of the slice, {@code null} for an intra slice.
     */
    HevcFrame[][] refList;

    /**
     * The padded copy of the reconstructed luma plane of a reference picture, for the motion search; set
     * once the picture is final.
     */
    short[] paddedY;

    /**
     * Allocates a picture.
     *
     * @param s the settings
     * @param sps the parsed sequence parameter set
     */
    PictureData(EncoderSettings s, HevcSps sps) {
        this.s = s;
        this.sps = sps;
        this.frame = new HevcFrame(s.codedWidth, s.codedHeight, 1, false);
        this.pic = new PictureState(sps);
        var luma = s.codedWidth * s.codedHeight;
        var chroma = s.chromaWidth * s.chromaHeight;
        this.sourceY = new short[luma];
        this.sourceCb = new short[chroma];
        this.sourceCr = new short[chroma];
        var blocks = pic.width4 * pic.height4;
        this.cuInfo = new byte[blocks];
        this.tuInfo = new byte[blocks];
        this.puInfo = new int[blocks];
        this.mvd0 = new int[blocks];
        this.mvd1 = new int[blocks];
        this.levelY = new short[luma];
        this.levelCb = new short[chroma];
        this.levelCr = new short[chroma];
        this.saoMerge = new byte[sps.sizeInCtbs];
    }

    /**
     * Clears the decisions for a new picture.
     */
    void reset() {
        pic.reset();
        frame.reset(sps.sizeInCtbs);
        frame.sps = sps;
        Arrays.fill(frame.ctbSlice, 0);
        Arrays.fill(pic.ctbSliceAddr, 0);
        Arrays.fill(pic.depth, (byte) 0);
        Arrays.fill(pic.intraMode, (byte) 0);
        Arrays.fill(cuInfo, (byte) 0);
        Arrays.fill(tuInfo, (byte) 0);
        Arrays.fill(puInfo, 0);
        Arrays.fill(mvd0, 0);
        Arrays.fill(mvd1, 0);
        Arrays.fill(saoMerge, (byte) 0);
    }

    /**
     * Returns the index of the 4x4 block covering a luma location.
     *
     * @param x the luma x
     * @param y the luma y
     * @return the index into the block arrays
     */
    int block(int x, int y) {
        return (y >> 2) * pic.width4 + (x >> 2);
    }

    /**
     * Returns the source plane of a component.
     *
     * @param c the component
     * @return the plane
     */
    short[] source(int c) {
        return c == 0 ? sourceY : c == 1 ? sourceCb : sourceCr;
    }

    /**
     * Returns the reconstruction plane of a component.
     *
     * @param c the component
     * @return the plane
     */
    short[] recon(int c) {
        return c == 0 ? frame.y : c == 1 ? frame.cb : frame.cr;
    }

    /**
     * Returns the level plane of a component.
     *
     * @param c the component
     * @return the plane
     */
    short[] levels(int c) {
        return c == 0 ? levelY : c == 1 ? levelCb : levelCr;
    }

    /**
     * Returns the row stride of a component's planes.
     *
     * @param c the component
     * @return the stride
     */
    int stride(int c) {
        return c == 0 ? s.codedWidth : s.chromaWidth;
    }
}
