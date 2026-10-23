package com.github.auties00.smalt.video.h264.internal;

import java.util.Arrays;

/**
 * Holds the arrays of the scalable decoding process that ITU-T H.264 F.8.1.2.1 collectively
 * calls {@code currentVars}, for one layer picture size, together with the syntax values the
 * parsing of neighboring macroblocks reads.
 *
 * <p>Motion vectors are stored per 4x4 block in raster order inside the macroblock, which holds
 * the same information as the partition-indexed arrays of the standard; reference indices are
 * stored per 8x8 quadrant, a value below 0 meaning the list is not used. Transform coefficients
 * are stored in the {@code tCoeffIdx} layout of the standard: 16 values per 4x4 block (64 per 8x8
 * block) in raster order of the block, luma at 0, Cb at 256 and Cr at 320. Constructed samples
 * ({@code cS}) and residual samples ({@code rS}) are full planes without borders.
 */
final class SvcLayer {
    /**
     * The transform type of an unspecified macroblock.
     */
    static final int T_NONE = 0;

    /**
     * The 4x4 transform type {@code T_4x4}.
     */
    static final int T_4X4 = 1;

    /**
     * The 8x8 transform type {@code T_8x8}.
     */
    static final int T_8X8 = 2;

    /**
     * The Intra_16x16 transform type {@code T_16x16}.
     */
    static final int T_16X16 = 3;

    /**
     * The I_PCM transform type {@code T_PCM}.
     */
    static final int T_PCM = 4;

    /**
     * The number of coefficients of a 4:2:0 macroblock.
     */
    static final int COEFFS = 384;

    /**
     * The columns of padding left and right of each row of the constructed sample planes, which
     * the deblocking kernels may read beyond the samples they filter.
     */
    static final int PAD = 16;

    /**
     * The parsing category of a skipped macroblock.
     */
    static final int SYNTAX_SKIP = 1;

    /**
     * The parsing category of an I_PCM macroblock.
     */
    static final int SYNTAX_PCM = 2;

    /**
     * The parsing category of an Intra_16x16 macroblock.
     */
    static final int SYNTAX_I16 = 3;

    /**
     * The parsing category of an I_NxN macroblock.
     */
    static final int SYNTAX_INXN = 4;

    /**
     * The parsing category of an inter macroblock, one with {@code base_mode_flag} equal to 1
     * included.
     */
    static final int SYNTAX_INTER = 5;

    /**
     * The flag of {@link #syntax} marking a B_Skip or B_Direct_16x16 macroblock.
     */
    static final int SYNTAX_DIRECT = 0x10;

    /**
     * The flag of {@link #syntax} marking a macroblock with {@code transform_size_8x8_flag} 1.
     */
    static final int SYNTAX_T8X8 = 0x20;

    /**
     * The flag of {@link #syntax} marking a macroblock with {@code base_mode_flag} 1.
     */
    static final int SYNTAX_BASE = 0x40;

    /**
     * The picture width in macroblocks.
     */
    final int widthMbs;

    /**
     * The picture height in macroblocks.
     */
    final int heightMbs;

    /**
     * The number of macroblocks.
     */
    final int mbCount;

    /**
     * The luma width in samples.
     */
    final int width;

    /**
     * The luma height in samples.
     */
    final int height;

    /**
     * The chroma width in samples.
     */
    final int chromaWidth;

    /**
     * The chroma height in samples.
     */
    final int chromaHeight;

    /**
     * The row stride of the constructed luma plane {@link #cY}.
     */
    final int stride;

    /**
     * The row stride of the constructed chroma planes {@link #cCb} and {@link #cCr}.
     */
    final int chromaStride;

    /**
     * The {@code sliceIdc} of each macroblock, {@code (first_mb_in_slice << 7) + DQId}, or -1 when
     * unspecified.
     */
    final int[] sliceIdc;

    /**
     * The {@code cTrafo} of each macroblock, one of the {@code T_*} constants.
     */
    final byte[] cTrafo;

    /**
     * The {@code baseModeFlag} of each macroblock.
     */
    final boolean[] baseMode;

    /**
     * The {@code mbType} of each macroblock, an {@link SvcMb} type.
     */
    final byte[] mbType;

    /**
     * The {@code subMbType} of each macroblock, four entries per macroblock.
     */
    final byte[] subMbType;

    /**
     * The {@code tQPY} of each macroblock.
     */
    final byte[] qpY;

    /**
     * The {@code tQPCb} of each macroblock.
     */
    final byte[] qpCb;

    /**
     * The {@code tQPCr} of each macroblock.
     */
    final byte[] qpCr;

    /**
     * The Intra_4x4 prediction modes, sixteen per macroblock indexed by {@code luma4x4BlkIdx}.
     */
    final byte[] ipred4x4;

    /**
     * The Intra_8x8 prediction modes, four per macroblock.
     */
    final byte[] ipred8x8;

    /**
     * The Intra_16x16 prediction mode of each macroblock.
     */
    final byte[] ipred16x16;

    /**
     * The intra chroma prediction mode of each macroblock.
     */
    final byte[] ipredChroma;

    /**
     * The list 0 reference indices, four per macroblock indexed by 8x8 quadrant, -1 when the
     * list is not used.
     */
    final byte[] refIdx0;

    /**
     * The list 1 reference indices, laid out as {@link #refIdx0}.
     */
    final byte[] refIdx1;

    /**
     * The list 0 motion vectors, 32 components per macroblock: horizontal then vertical
     * component of each 4x4 block in raster order.
     */
    final short[] mv0;

    /**
     * The list 1 motion vectors, laid out as {@link #mv0}.
     */
    final short[] mv1;

    /**
     * The transform coefficient levels {@code tCoeffLevel}, {@link #COEFFS} per macroblock.
     */
    final int[] tCoeff;

    /**
     * The scaled transform coefficients {@code sTCoeff}, {@link #COEFFS} per macroblock.
     */
    final int[] sTCoeff;

    /**
     * The residual luma samples {@code rSL}.
     */
    final short[] rY;

    /**
     * The residual Cb samples {@code rSCb}.
     */
    final short[] rCb;

    /**
     * The residual Cr samples {@code rSCr}.
     */
    final short[] rCr;

    /**
     * The constructed luma samples {@code cSL}, row {@code y} starting at index
     * {@code y * stride + PAD}.
     */
    final byte[] cY;

    /**
     * The constructed Cb samples {@code cSCb}, laid out as {@link #cY} with
     * {@link #chromaStride}.
     */
    final byte[] cCb;

    /**
     * The constructed Cr samples {@code cSCr}, laid out as {@link #cCb}.
     */
    final byte[] cCr;

    /**
     * The parsing category of each macroblock in the layer that coded it last
     * ({@code SYNTAX_*} with its flags).
     */
    final byte[] syntax;

    /**
     * The coded block pattern of each macroblock, {@code CodedBlockPatternLuma} in the low four
     * bits and {@code CodedBlockPatternChroma} above.
     */
    final byte[] cbp;

    /**
     * The {@code TotalCoeff} of each 4x4 block: sixteen luma blocks, then four Cb and four Cr AC
     * blocks, per macroblock; for CABAC a non-zero value means {@code coded_block_flag} 1.
     */
    final byte[] totalCoeff;

    /**
     * The {@code coded_block_flag} of the DC blocks of each macroblock: bit 0 luma, bit 1 Cb,
     * bit 2 Cr.
     */
    final byte[] dcFlags;

    /**
     * The absolute list 0 motion vector differences of each 4x4 block, horizontal then vertical,
     * capped at 127, for CABAC contexts.
     */
    final byte[] mvd0;

    /**
     * The absolute list 1 motion vector differences, laid out as {@link #mvd0}.
     */
    final byte[] mvd1;

    /**
     * Whether each 8x8 quadrant contributes 1 to the CABAC {@code ref_idx_l0} context of its
     * neighbors.
     */
    final boolean[] refContext0;

    /**
     * Whether each 8x8 quadrant contributes 1 to the CABAC {@code ref_idx_l1} context of its
     * neighbors.
     */
    final boolean[] refContext1;

    /**
     * The luma 4x4 blocks (bit {@code luma4x4BlkIdx}) whose transform coefficient levels the layer
     * that coded the macroblock transmitted as non-zero.
     */
    final short[] codedLuma;

    /**
     * Allocates the arrays of a layer picture.
     *
     * @param widthMbs the width in macroblocks
     * @param heightMbs the height in macroblocks
     */
    SvcLayer(int widthMbs, int heightMbs) {
        this.widthMbs = widthMbs;
        this.heightMbs = heightMbs;
        this.mbCount = widthMbs * heightMbs;
        this.width = widthMbs * 16;
        this.height = heightMbs * 16;
        this.chromaWidth = width / 2;
        this.chromaHeight = height / 2;
        this.stride = width + 2 * PAD;
        this.chromaStride = chromaWidth + 2 * PAD;
        sliceIdc = new int[mbCount];
        cTrafo = new byte[mbCount];
        baseMode = new boolean[mbCount];
        mbType = new byte[mbCount];
        subMbType = new byte[mbCount * 4];
        qpY = new byte[mbCount];
        qpCb = new byte[mbCount];
        qpCr = new byte[mbCount];
        ipred4x4 = new byte[mbCount * 16];
        ipred8x8 = new byte[mbCount * 4];
        ipred16x16 = new byte[mbCount];
        ipredChroma = new byte[mbCount];
        refIdx0 = new byte[mbCount * 4];
        refIdx1 = new byte[mbCount * 4];
        mv0 = new short[mbCount * 32];
        mv1 = new short[mbCount * 32];
        tCoeff = new int[mbCount * COEFFS];
        sTCoeff = new int[mbCount * COEFFS];
        rY = new short[width * height];
        rCb = new short[chromaWidth * chromaHeight];
        rCr = new short[chromaWidth * chromaHeight];
        cY = new byte[stride * height];
        cCb = new byte[chromaStride * chromaHeight];
        cCr = new byte[chromaStride * chromaHeight];

        syntax = new byte[mbCount];
        cbp = new byte[mbCount];
        totalCoeff = new byte[mbCount * 24];
        dcFlags = new byte[mbCount];
        mvd0 = new byte[mbCount * 32];
        mvd1 = new byte[mbCount * 32];
        refContext0 = new boolean[mbCount * 4];
        refContext1 = new boolean[mbCount * 4];
        codedLuma = new short[mbCount];
        reset();
    }

    /**
     * Returns whether this layer has a picture size.
     *
     * @param widthMbs the width in macroblocks
     * @param heightMbs the height in macroblocks
     * @return {@code true} when the sizes match
     */
    boolean fits(int widthMbs, int heightMbs) {
        return this.widthMbs == widthMbs && this.heightMbs == heightMbs;
    }

    /**
     * Sets every array to its initial value (ITU-T H.264 F.8.1.2.1): unspecified slices and
     * types, reference indices -1, everything else 0.
     */
    void reset() {
        Arrays.fill(sliceIdc, -1);
        Arrays.fill(cTrafo, (byte) T_NONE);
        Arrays.fill(baseMode, false);
        Arrays.fill(mbType, (byte) SvcMb.NONE);
        Arrays.fill(subMbType, (byte) 0);
        Arrays.fill(qpY, (byte) 0);
        Arrays.fill(qpCb, (byte) 0);
        Arrays.fill(qpCr, (byte) 0);
        Arrays.fill(refIdx0, (byte) -1);
        Arrays.fill(refIdx1, (byte) -1);
        Arrays.fill(mv0, (short) 0);
        Arrays.fill(mv1, (short) 0);
        Arrays.fill(tCoeff, 0);
        Arrays.fill(sTCoeff, 0);
        Arrays.fill(rY, (short) 0);
        Arrays.fill(rCb, (short) 0);
        Arrays.fill(rCr, (short) 0);
        Arrays.fill(cY, (byte) 0);
        Arrays.fill(cCb, (byte) 0);
        Arrays.fill(cCr, (byte) 0);
        Arrays.fill(syntax, (byte) 0);
        Arrays.fill(codedLuma, (short) 0);
    }

    /**
     * Copies every array of a layer of the same size into this one.
     *
     * @param source the layer to copy
     */
    void copyFrom(SvcLayer source) {
        System.arraycopy(source.sliceIdc, 0, sliceIdc, 0, mbCount);
        System.arraycopy(source.cTrafo, 0, cTrafo, 0, mbCount);
        System.arraycopy(source.baseMode, 0, baseMode, 0, mbCount);
        System.arraycopy(source.mbType, 0, mbType, 0, mbCount);
        System.arraycopy(source.subMbType, 0, subMbType, 0, subMbType.length);
        System.arraycopy(source.qpY, 0, qpY, 0, mbCount);
        System.arraycopy(source.qpCb, 0, qpCb, 0, mbCount);
        System.arraycopy(source.qpCr, 0, qpCr, 0, mbCount);
        System.arraycopy(source.ipred4x4, 0, ipred4x4, 0, ipred4x4.length);
        System.arraycopy(source.ipred8x8, 0, ipred8x8, 0, ipred8x8.length);
        System.arraycopy(source.ipred16x16, 0, ipred16x16, 0, mbCount);
        System.arraycopy(source.ipredChroma, 0, ipredChroma, 0, mbCount);
        System.arraycopy(source.refIdx0, 0, refIdx0, 0, refIdx0.length);
        System.arraycopy(source.refIdx1, 0, refIdx1, 0, refIdx1.length);
        System.arraycopy(source.mv0, 0, mv0, 0, mv0.length);
        System.arraycopy(source.mv1, 0, mv1, 0, mv1.length);
        System.arraycopy(source.tCoeff, 0, tCoeff, 0, tCoeff.length);
        System.arraycopy(source.sTCoeff, 0, sTCoeff, 0, sTCoeff.length);
        System.arraycopy(source.rY, 0, rY, 0, rY.length);
        System.arraycopy(source.rCb, 0, rCb, 0, rCb.length);
        System.arraycopy(source.rCr, 0, rCr, 0, rCr.length);
        System.arraycopy(source.cY, 0, cY, 0, cY.length);
        System.arraycopy(source.cCb, 0, cCb, 0, cCb.length);
        System.arraycopy(source.cCr, 0, cCr, 0, cCr.length);
        System.arraycopy(source.syntax, 0, syntax, 0, mbCount);
        System.arraycopy(source.cbp, 0, cbp, 0, mbCount);
        System.arraycopy(source.codedLuma, 0, codedLuma, 0, mbCount);
    }
}
