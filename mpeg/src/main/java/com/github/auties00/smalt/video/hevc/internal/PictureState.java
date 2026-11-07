package com.github.auties00.smalt.video.hevc.internal;

import java.util.Arrays;

/**
 * Holds the coding information of the picture being decoded that the loop filters, the parsing of
 * later blocks and the motion vector prediction read back: per 4x4 luma block flags, quantization
 * parameters, intra modes and transform edges, and per coding tree block the slice and the sample
 * adaptive offset parameters.
 */
public final class PictureState {
    /**
     * The flag of a 4x4 block coded in intra prediction mode.
     */
    public static final int INTRA = 1;

    /**
     * The flag of a 4x4 block of a coding unit with {@code cu_skip_flag} set.
     */
    public static final int SKIP = 2;

    /**
     * The flag of a 4x4 block of a PCM coding unit.
     */
    public static final int PCM = 4;

    /**
     * The flag of a 4x4 block of a coding unit with {@code cu_transquant_bypass_flag} set.
     */
    public static final int BYPASS = 8;

    /**
     * The flag of a 4x4 block whose luma transform block has non-zero coefficients.
     */
    public static final int CODED = 16;

    /**
     * The flag of a 4x4 block of a coding unit coded in palette mode.
     */
    public static final int PALETTE = 32;

    /**
     * The flags of the blocks whose samples the deblocking filter never modifies:
     * {@link #BYPASS} and {@link #PALETTE}.
     */
    public static final int NO_DEBLOCK = BYPASS | PALETTE;

    /**
     * The edge flag of a transform block boundary on the left of a 4x4 block.
     */
    public static final int EDGE_TU_V = 1;

    /**
     * The edge flag of a prediction block boundary on the left of a 4x4 block.
     */
    public static final int EDGE_PU_V = 2;

    /**
     * The edge flag of a transform block boundary above a 4x4 block.
     */
    public static final int EDGE_TU_H = 4;

    /**
     * The edge flag of a prediction block boundary above a 4x4 block.
     */
    public static final int EDGE_PU_H = 8;

    /**
     * The sequence parameter set the arrays are sized for.
     */
    public final HevcSps sps;

    /**
     * The width in 4x4 blocks, the stride of the block arrays.
     */
    public final int width4;

    /**
     * The height in 4x4 blocks.
     */
    public final int height4;

    /**
     * The {@link #INTRA}, {@link #SKIP}, {@link #PCM}, {@link #BYPASS}, {@link #CODED} and
     * {@link #PALETTE} flags of each 4x4 block.
     */
    public final byte[] flags;

    /**
     * {@code CtDepth} of each 4x4 block.
     */
    public final byte[] depth;

    /**
     * {@code IntraPredModeY} of each 4x4 block.
     */
    public final byte[] intraMode;

    /**
     * {@code QpY} of each 4x4 block.
     */
    public final byte[] qp;

    /**
     * The {@link #EDGE_TU_V}, {@link #EDGE_PU_V}, {@link #EDGE_TU_H} and {@link #EDGE_PU_H} flags of
     * each 4x4 block.
     */
    public final byte[] edges;

    /**
     * {@code SliceAddrRs} of the slice of each coding tree block, by raster address, or -1 for a
     * coding tree block not decoded yet.
     */
    public final int[] ctbSliceAddr;

    /**
     * The slice segment header of each coding tree block, by raster address.
     */
    public final HevcSliceHeader[] ctbHeader;

    /**
     * {@code SaoTypeIdx} of each component of each coding tree block, indexed
     * {@code cIdx * sizeInCtbs + ctbAddrRs}: 0 off, 1 band offset, 2 edge offset.
     */
    public final byte[] saoType;

    /**
     * The band position or the edge offset class of each component of each coding tree block,
     * indexed like {@link #saoType}.
     */
    public final byte[] saoClass;

    /**
     * {@code SaoOffsetVal[1..4]} of each component of each coding tree block, indexed
     * {@code (cIdx * sizeInCtbs + ctbAddrRs) * 4 + i}.
     */
    public final short[] saoOffset;

    /**
     * The context variables stored after the second coding tree block of each row for wavefront
     * parallel processing, {@code TableStateIdxWpp}, {@link CabacTables#COUNT} entries per row: a
     * row reads the states of the row above, so rows may be decoded concurrently.
     */
    public final byte[] wppStates;

    /**
     * The context variables stored at the end of a slice segment for a dependent slice segment,
     * {@code TableStateIdxDs}.
     */
    public final byte[] dependentStates = new byte[CabacTables.COUNT];

    /**
     * The Rice parameter statistics {@code StatCoeff} stored with {@link #wppStates}, four per row
     * of coding tree blocks.
     */
    public final int[] wppStatCoeff;

    /**
     * The Rice parameter statistics {@code StatCoeff} stored with {@link #dependentStates}.
     */
    public final int[] dependentStatCoeff = new int[4];

    /**
     * The {@code QpY} of the last coding unit of the previous slice segment, for a dependent slice
     * segment that continues it.
     */
    public int dependentQp;

    /**
     * {@code PredictorPaletteSizeWpp} of each row of coding tree blocks, stored with
     * {@link #wppStates}; empty without palette mode.
     */
    public final int[] wppPaletteSize;

    /**
     * {@code PredictorPaletteEntriesWpp} of each row of coding tree blocks,
     * {@link #paletteStride} entries per row: {@code PaletteMaxPredictorSize} entries of each of
     * the three components; empty without palette mode.
     */
    public final int[] wppPalette;

    /**
     * The entries one row of {@link #wppPalette} holds, three components of
     * {@code PaletteMaxPredictorSize} entries.
     */
    public final int paletteStride;

    /**
     * {@code PredictorPaletteSizeDs}, stored with {@link #dependentStates}.
     */
    public int dependentPaletteSize;

    /**
     * {@code PredictorPaletteEntriesDs}, laid out like one row of {@link #wppPalette}.
     */
    public final int[] dependentPalette;

    /**
     * The kernels the picture is reconstructed with, selected by {@link #reset()}.
     */
    public HevcDsp dsp = HevcDsp.SCALAR;

    /**
     * Allocates the arrays for a sequence parameter set.
     *
     * @param sps the sequence parameter set
     */
    public PictureState(HevcSps sps) {
        this.sps = sps;
        this.width4 = sps.width >> 2;
        this.height4 = sps.height >> 2;
        var blocks = width4 * height4;
        this.flags = new byte[blocks];
        this.depth = new byte[blocks];
        this.intraMode = new byte[blocks];
        this.qp = new byte[blocks];
        this.edges = new byte[blocks];
        this.ctbSliceAddr = new int[sps.sizeInCtbs];
        this.ctbHeader = new HevcSliceHeader[sps.sizeInCtbs];
        this.saoType = new byte[3 * sps.sizeInCtbs];
        this.saoClass = new byte[3 * sps.sizeInCtbs];
        this.saoOffset = new short[3 * sps.sizeInCtbs * 4];
        this.wppStates = new byte[sps.heightInCtbs * CabacTables.COUNT];
        this.wppStatCoeff = new int[sps.heightInCtbs * 4];
        this.paletteStride = sps.paletteModeEnabled ? 3 * sps.paletteMaxPredictorSize : 0;
        this.wppPaletteSize = new int[sps.paletteModeEnabled ? sps.heightInCtbs : 0];
        this.wppPalette = new int[sps.heightInCtbs * paletteStride];
        this.dependentPalette = new int[paletteStride];
    }

    /**
     * Clears the per-picture state for a new picture and selects its kernels.
     */
    public void reset() {
        dsp = HevcDsp.select(Math.max(sps.bitDepthLuma, sps.bitDepthChroma));
        Arrays.fill(ctbSliceAddr, -1);
        Arrays.fill(ctbHeader, null);
        Arrays.fill(edges, (byte) 0);
        Arrays.fill(flags, (byte) 0);
        Arrays.fill(saoType, (byte) 0);
    }
}
