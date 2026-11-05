package com.github.auties00.smalt.video.h264.encoder.internal;

import java.util.Arrays;

/**
 * Holds the coded parameters of every macroblock of the picture the extended engine is coding,
 * which later macroblocks read for prediction and entropy coding contexts, and locates the
 * neighbors of the current macroblock (ITU-T H.264 6.4.12).
 *
 * <p>Tables are indexed by the macroblock's position in the picture's grid,
 * {@code mbY * mbWidth + mbX}: frame rows for frames, field rows for field pictures, and for
 * frames of macroblock pairs the frame row {@code 2 * pairY + bottom}, whether the pair is coded
 * as frame or field macroblocks. Instances are not thread-safe.
 */
final class WideMbTable {
    /**
     * The macroblock type of a position not coded yet in the picture.
     */
    static final byte NONE = -1;

    /**
     * The picture width in macroblocks.
     */
    final int mbWidth;

    /**
     * The picture height in macroblocks of the current picture.
     */
    int mbRows;

    /**
     * Whether the picture is coded in macroblock pairs.
     */
    boolean mbaff;

    /**
     * The {@link WideMb} type of each macroblock, or {@link #NONE}.
     */
    final byte[] type;

    /**
     * Whether each macroblock is a field macroblock of a frame of macroblock pairs.
     */
    final boolean[] field;

    /**
     * The quantizer {@code QPY} of each macroblock.
     */
    final byte[] qp;

    /**
     * The intra prediction mode of each 4x4 luma block, in raster order, 16 per macroblock: the
     * Intra_4x4 mode, or the Intra_8x8 mode of the 8x8 block holding it.
     */
    final byte[] intraModes;

    /**
     * The {@code TotalCoeff} of each 4x4 block of each component, 16 per component in raster order
     * of the blocks (the chroma blocks of 4:2:0 and 4:2:2 in {@code chroma4x4BlkIdx} order), 48 per
     * macroblock.
     */
    final byte[] totalCoeff;

    /**
     * The coded block flags of each macroblock and component: bits 0 to 15 for the 4x4 blocks in
     * raster order (all four bits of an 8x8 block coded with the 8x8 transform), bit 16 for the DC
     * block; for the chroma of 4:2:0 and 4:2:2, bits 0 to 7 hold the AC blocks.
     */
    final int[] cbf;

    /**
     * The {@code coded_block_pattern} of each macroblock, chroma in bits 4 and 5.
     */
    final byte[] cbp;

    /**
     * The {@code intra_chroma_pred_mode} of each intra macroblock.
     */
    final byte[] chromaMode;

    /**
     * Whether each macroblock uses the 8x8 transform.
     */
    final boolean[] transform8x8;

    /**
     * The horizontal motion vector of each inter macroblock.
     */
    final short[] mvX;

    /**
     * The vertical motion vector of each inter macroblock.
     */
    final short[] mvY;

    /**
     * The reference index of each inter macroblock, -1 for intra macroblocks.
     */
    final byte[] ref;

    /**
     * The absolute horizontal motion vector difference of each macroblock, for CABAC contexts.
     */
    final short[] mvdX;

    /**
     * The absolute vertical motion vector difference of each macroblock, for CABAC contexts.
     */
    final short[] mvdY;

    /**
     * The position of the current macroblock in the grid.
     */
    int index;

    /**
     * The column of the current macroblock.
     */
    int mbX;

    /**
     * The grid row of the current macroblock.
     */
    int mbY;

    /**
     * Whether the current macroblock is the bottom macroblock of its pair.
     */
    boolean bottom;

    /**
     * Whether the current macroblock is a field macroblock of a pair.
     */
    boolean fieldMb;

    /**
     * The address of the current macroblock in decoding order.
     */
    int address;

    /**
     * The address of the first macroblock of the current slice.
     */
    int sliceStart;

    /**
     * The position in the grid of the macroblock {@link #locate} found, or -1 when it is not
     * available.
     */
    int found;

    /**
     * The horizontal position of the located sample inside {@link #found}.
     */
    int foundX;

    /**
     * The vertical position of the located sample inside {@link #found}.
     */
    int foundY;

    /**
     * Allocates the tables of pictures of a size.
     *
     * @param mbWidth the picture width in macroblocks
     * @param mbHeight the frame height in macroblocks
     */
    WideMbTable(int mbWidth, int mbHeight) {
        this.mbWidth = mbWidth;
        this.mbRows = mbHeight;
        var count = mbWidth * mbHeight;
        type = new byte[count];
        field = new boolean[count];
        qp = new byte[count];
        intraModes = new byte[count * 16];
        totalCoeff = new byte[count * 48];
        cbf = new int[count * 3];
        cbp = new byte[count];
        chromaMode = new byte[count];
        transform8x8 = new boolean[count];
        mvX = new short[count];
        mvY = new short[count];
        ref = new byte[count];
        mvdX = new short[count];
        mvdY = new short[count];
    }

    /**
     * Prepares the tables for a new picture.
     *
     * @param rows the picture height in macroblocks
     * @param pairs whether the picture is coded in macroblock pairs
     */
    void startPicture(int rows, boolean pairs) {
        this.mbRows = rows;
        this.mbaff = pairs;
        Arrays.fill(type, 0, mbWidth * rows, NONE);
    }

    /**
     * Returns the decoding-order address of a grid position.
     *
     * @param position the grid position
     * @return the address
     */
    int addressOf(int position) {
        if (!mbaff) {
            return position;
        }
        var x = position % mbWidth;
        var y = position / mbWidth;
        return 2 * ((y >> 1) * mbWidth + x) + (y & 1);
    }

    /**
     * Returns the grid position of a decoding-order address.
     *
     * @param address the decoding-order address
     * @return the grid position
     */
    int positionOf(int address) {
        if (!mbaff) {
            return address;
        }
        var pair = address >> 1;
        return (2 * (pair / mbWidth) + (address & 1)) * mbWidth + pair % mbWidth;
    }

    /**
     * Makes a macroblock the current one, for {@link #locate}.
     *
     * @param address the macroblock's decoding-order address
     * @param field whether it is a field macroblock of a pair
     * @param sliceStart the address of the first macroblock of its slice
     */
    void moveTo(int address, boolean field, int sliceStart) {
        this.address = address;
        this.index = positionOf(address);
        this.mbX = index % mbWidth;
        this.mbY = index / mbWidth;
        this.bottom = mbaff && (address & 1) == 1;
        this.fieldMb = mbaff && field;
        this.sliceStart = sliceStart;
    }

    /**
     * Locates the macroblock and the position inside it of a sample outside the current
     * macroblock, given relative to the current macroblock's top-left sample (ITU-T H.264 6.4.12),
     * and stores the result in {@link #found}, {@link #foundX} and {@link #foundY}.
     *
     * @param xN the horizontal position, from -1 to {@code maxW}
     * @param yN the vertical position, from -1 to {@code maxH - 1}
     * @param maxW the width of the component's macroblock area
     * @param maxH the height of the component's macroblock area
     */
    void locate(int xN, int yN, int maxW, int maxH) {
        found = -1;
        foundX = xN < 0 ? xN + maxW : xN >= maxW ? xN - maxW : xN;
        if (!mbaff) {
            var x = mbX + (xN < 0 ? -1 : xN >= maxW ? 1 : 0);
            var y = mbY + (yN < 0 ? -1 : 0);
            if (x < 0 || x >= mbWidth || y < 0) {
                return;
            }
            var position = y * mbWidth + x;
            if (position < address && position >= sliceStart) {
                found = position;
                foundY = yN < 0 ? yN + maxH : yN;
            }
            return;
        }
        var row = fieldMb ? (bottom ? 1 : 0) + 2 * yN : (bottom ? maxH : 0) + yN;
        var pairX = mbX + (xN < 0 ? -1 : xN >= maxW ? 1 : 0);
        var pairY = (mbY >> 1) + (row < 0 ? -1 : 0);
        if (row < 0) {
            row += 2 * maxH;
        }
        if (pairX < 0 || pairX >= mbWidth || pairY < 0) {
            return;
        }
        var pair = pairY * mbWidth + pairX;
        var currentPair = address >> 1;
        if (pair > currentPair || pair == currentPair && (fieldMb || !bottom || row >= maxH)) {
            return;
        }
        if (2 * pair < sliceStart) {
            return;
        }
        var top = 2 * pairY * mbWidth + pairX;
        if (field[top] && pair != currentPair) {
            found = top + (row & 1) * mbWidth;
            foundY = row >> 1;
        } else {
            found = top + (row >= maxH ? mbWidth : 0);
            foundY = row % maxH;
        }
    }

    /**
     * Returns whether a located macroblock is a field macroblock where the current one is a frame
     * macroblock, or the reverse, so that its vertical motion and reference index need scaling.
     *
     * @param position the located macroblock
     * @return 1 when the neighbor is a field macroblock of a frame macroblock, -1 for the reverse,
     * 0 when both match
     */
    int fieldScaling(int position) {
        if (!mbaff || field[position] == fieldMb) {
            return 0;
        }
        return fieldMb ? -1 : 1;
    }
}
