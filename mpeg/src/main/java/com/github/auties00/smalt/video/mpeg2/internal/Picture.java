package com.github.auties00.smalt.video.mpeg2.internal;

import com.github.auties00.smalt.internal.codec.MpegVideoHeaders;

/**
 * Holds the coding parameters of the picture being decoded, as its headers set them, and the frames it is decoded
 * into and predicted from; the slices of the picture read it, possibly from several threads, and never write it.
 */
final class Picture {
    /**
     * The {@code picture_coding_type} of an I-picture.
     */
    static final int I = 1;

    /**
     * The {@code picture_coding_type} of a P-picture.
     */
    static final int P = 2;

    /**
     * The {@code picture_coding_type} of a B-picture.
     */
    static final int B = 3;

    /**
     * The {@code picture_coding_type} of an MPEG-1 D-picture.
     */
    static final int D = 4;

    /**
     * The {@code picture_structure} of a top field picture.
     */
    static final int TOP_FIELD = 1;

    /**
     * The {@code picture_structure} of a bottom field picture.
     */
    static final int BOTTOM_FIELD = 2;

    /**
     * The {@code picture_structure} of a frame picture.
     */
    static final int FRAME = 3;

    /**
     * The alternate scan (ISO/IEC 13818-2 Figure 7-3): the raster index of the coefficient at each scan position.
     */
    private static final int[] ALTERNATE = {
            0, 8, 16, 24, 1, 9, 2, 10, 17, 25, 32, 40, 48, 56, 57, 49,
            41, 33, 26, 18, 3, 11, 4, 12, 19, 27, 34, 42, 50, 58, 35, 43,
            51, 59, 20, 28, 5, 13, 6, 14, 21, 29, 36, 44, 52, 60, 37, 45,
            53, 61, 22, 30, 7, 15, 23, 31, 38, 46, 54, 62, 39, 47, 55, 63};

    /**
     * The zigzag scan, as {@link #ALTERNATE}.
     */
    static final int[] ZIGZAG = MpegVideoHeaders.zigzag();

    /**
     * Whether the stream is MPEG-2.
     */
    boolean mpeg2;

    /**
     * {@code picture_coding_type}.
     */
    int codingType;

    /**
     * {@code picture_structure}.
     */
    int structure;

    /**
     * Whether the picture is the second field of its frame.
     */
    boolean secondField;

    /**
     * {@code chroma_format}.
     */
    int chromaFormat;

    /**
     * The number of blocks of a macroblock: 6, 8 or 12.
     */
    int blockCount;

    /**
     * The macroblock column count.
     */
    int mbWidth;

    /**
     * The macroblock row count of the picture (half the frame's for a field picture).
     */
    int mbRows;

    /**
     * {@code vertical_size}, which decides whether slices carry {@code slice_vertical_position_extension}.
     */
    int verticalSize;

    /**
     * The {@code f_code} values, indexed by direction (0 forward, 1 backward) and component (0 horizontal,
     * 1 vertical); the MPEG-1 {@code forward_f_code} and {@code backward_f_code} fill both components.
     */
    final int[][] fCode = new int[2][2];

    /**
     * The MPEG-1 {@code full_pel_forward_vector} and {@code full_pel_backward_vector}, by direction.
     */
    final boolean[] fullPel = new boolean[2];

    /**
     * {@code intra_dc_precision}, 0 for MPEG-1.
     */
    int intraDcPrecision;

    /**
     * {@code top_field_first}.
     */
    boolean topFieldFirst;

    /**
     * {@code frame_pred_frame_dct}; {@code true} for MPEG-1.
     */
    boolean framePredFrameDct;

    /**
     * {@code concealment_motion_vectors}.
     */
    boolean concealmentVectors;

    /**
     * {@code q_scale_type}.
     */
    boolean qScaleType;

    /**
     * {@code intra_vlc_format}.
     */
    boolean intraVlcFormat;

    /**
     * {@code alternate_scan}.
     */
    boolean alternateScan;

    /**
     * {@code repeat_first_field}.
     */
    boolean repeatFirstField;

    /**
     * {@code progressive_frame}; {@code true} for MPEG-1.
     */
    boolean progressiveFrame;

    /**
     * The coefficient index of each scan position in the transposed block layout of the inverse DCT
     * (coefficient {@code column} of row {@code row} at {@code column * 8 + row}).
     */
    final int[] scan = new int[64];

    /**
     * The intra weights of luma blocks, by scan position.
     */
    final int[] intraLuma = new int[64];

    /**
     * The intra weights of chroma blocks, by scan position.
     */
    final int[] intraChroma = new int[64];

    /**
     * The non-intra weights of luma blocks, by scan position.
     */
    final int[] interLuma = new int[64];

    /**
     * The non-intra weights of chroma blocks, by scan position.
     */
    final int[] interChroma = new int[64];

    /**
     * The frame the picture is decoded into.
     */
    Frame current;

    /**
     * The forward reference frame, or {@code null} when it is missing.
     */
    Frame forward;

    /**
     * The backward reference frame, or {@code null} when it is missing; for the second field of a P frame this is
     * the frame being decoded, whose first field it may predict from.
     */
    Frame backward;

    /**
     * Returns the macroblock count of the picture.
     *
     * @return the count
     */
    int mbCount() {
        return mbWidth * mbRows;
    }

    /**
     * Returns the field parity of a field picture.
     *
     * @return 0 for a top field, 1 for a bottom field and for frame pictures
     */
    int parity() {
        return structure == BOTTOM_FIELD ? 1 : 0;
    }

    /**
     * Sets the scan and the weights by scan position from the quantizer matrices in force.
     *
     * @param intra the intra matrix, raster order
     * @param nonIntra the non-intra matrix, raster order
     * @param chromaIntra the chroma intra matrix, raster order
     * @param chromaNonIntra the chroma non-intra matrix, raster order
     */
    void setWeights(int[] intra, int[] nonIntra, int[] chromaIntra, int[] chromaNonIntra) {
        var order = alternateScan ? ALTERNATE : ZIGZAG;
        var chromaMatrices = chromaFormat != 1;
        for (var position = 0; position < 64; position++) {
            var raster = order[position];
            scan[position] = (raster & 7) << 3 | raster >> 3;
            intraLuma[position] = intra[raster];
            interLuma[position] = nonIntra[raster];
            intraChroma[position] = chromaMatrices ? chromaIntra[raster] : intra[raster];
            interChroma[position] = chromaMatrices ? chromaNonIntra[raster] : nonIntra[raster];
        }
    }
}
