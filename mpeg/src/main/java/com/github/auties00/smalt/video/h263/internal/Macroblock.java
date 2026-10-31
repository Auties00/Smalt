package com.github.auties00.smalt.video.h263.internal;

import java.util.Arrays;

/**
 * Holds what the macroblock layer of ITU-T H.263 coded for one macroblock, between parsing and reconstruction.
 *
 * <p>Vectors are in half samples; in the Reduced-Resolution Update mode they are the actual vectors of the 16 by 16
 * blocks, not the pseudo vectors that were coded (Q.4). Coefficients are dequantized, stored column by column as the
 * inverse transform reads them.
 */
final class Macroblock {
    /**
     * Not coded: predicted with a zero vector (P- and Improved PB-frames), the direct mode (B-pictures), forward
     * prediction (EP-pictures) or upward prediction (EI-pictures).
     */
    static final int SKIPPED = 0;

    /**
     * Predicted with one vector, or from the picture a non-zero PR0 selects with a zero vector.
     */
    static final int INTER = 1;

    /**
     * Predicted with four vectors.
     */
    static final int INTER4V = 2;

    /**
     * Coded without prediction.
     */
    static final int INTRA = 3;

    /**
     * Predicted in the direct mode of B-pictures.
     */
    static final int DIRECT = 4;

    /**
     * Predicted forward (B- and EP-pictures).
     */
    static final int FORWARD = 5;

    /**
     * Predicted backward (B-pictures).
     */
    static final int BACKWARD = 6;

    /**
     * Predicted bidirectionally (B-pictures), or from the forward and upward references (EP-pictures).
     */
    static final int BIDIRECTIONAL = 7;

    /**
     * Predicted upward from the reference layer (EI- and EP-pictures).
     */
    static final int UPWARD = 8;

    /**
     * The B-part of a PB- or Improved PB-frame predicted bidirectionally (G.5).
     */
    static final int PB_BIDIRECTIONAL = 0;

    /**
     * The B-part of an Improved PB-frame predicted forward (M.2.2).
     */
    static final int PB_FORWARD = 1;

    /**
     * The B-part of an Improved PB-frame predicted backward (M.2.3).
     */
    static final int PB_BACKWARD = 2;

    /**
     * The macroblock index in the picture's coding grid.
     */
    int index;

    /**
     * The column in the coding grid.
     */
    int column;

    /**
     * The row in the coding grid.
     */
    int row;

    /**
     * The prediction kind, one of the kind constants.
     */
    int kind;

    /**
     * Whether the macroblock was coded: COD 0.
     */
    boolean coded;

    /**
     * The forward or P vectors of the four luminance blocks, horizontal components.
     */
    final int[] forwardX = new int[4];

    /**
     * The forward or P vectors, vertical components.
     */
    final int[] forwardY = new int[4];

    /**
     * The backward vectors of the four luminance blocks, horizontal components.
     */
    final int[] backwardX = new int[4];

    /**
     * The backward vectors, vertical components.
     */
    final int[] backwardY = new int[4];

    /**
     * The forward reference picture of each luminance block, an index into the picture's forward references.
     */
    final int[] forwardReference = new int[4];

    /**
     * The backward reference picture: 0, or 1 for the second backward reference of two-picture backward prediction.
     */
    int backwardReference;

    /**
     * The coded block pattern of the six blocks, block 0 in bit 5.
     */
    int pattern;

    /**
     * The quantizer.
     */
    int quant;

    /**
     * The Advanced INTRA Coding prediction mode.
     */
    int intraMode;

    /**
     * The dequantized coefficients of the six blocks.
     */
    final short[] coefficients = new short[6 * 64];

    /**
     * The prediction mode of the B-part of a PB- or Improved PB-frame, one of the {@code PB_} constants.
     */
    int partMode;

    /**
     * The coded block pattern of the B-part.
     */
    int partPattern;

    /**
     * The forward vector of the B-part of an Improved PB-frame in the forward mode, or the delta vector of a PB-frame
     * (MVDB), horizontal component.
     */
    int partX;

    /**
     * The vertical component of {@link #partX}.
     */
    int partY;

    /**
     * Whether MVDB was coded.
     */
    boolean partVector;

    /**
     * The forward reference picture of the B-part.
     */
    int partReference;

    /**
     * The dequantized coefficients of the B-part.
     */
    final short[] partCoefficients = new short[6 * 64];

    /**
     * The header code of the macroblock in the Data Partitioned Slice mode: the MCBPC or MBTYPE symbol of the header
     * partition, which the coefficient partition continues from.
     */
    int symbol;

    /**
     * Whether the B-part has coefficients: CBPB is present (MODB).
     */
    boolean partCoded;

    /**
     * Creates an empty macroblock.
     */
    Macroblock() {
    }

    /**
     * Resets the macroblock for parsing.
     *
     * @param index the index in the coding grid
     * @param column the macroblock column, from 0
     * @param row the macroblock row, from 0
     */
    void reset(int index, int column, int row) {
        this.index = index;
        this.column = column;
        this.row = row;
        kind = SKIPPED;
        coded = false;
        Arrays.fill(forwardX, 0);
        Arrays.fill(forwardY, 0);
        Arrays.fill(backwardX, 0);
        Arrays.fill(backwardY, 0);
        Arrays.fill(forwardReference, 0);
        backwardReference = 0;
        pattern = 0;
        intraMode = 0;
        partMode = PB_BIDIRECTIONAL;
        partPattern = 0;
        partX = 0;
        partY = 0;
        partVector = false;
        partReference = 0;
        symbol = 0;
        partCoded = false;
    }

    /**
     * Returns whether the macroblock is INTRA coded.
     *
     * @return whether it is
     */
    boolean intra() {
        return kind == INTRA;
    }
}
