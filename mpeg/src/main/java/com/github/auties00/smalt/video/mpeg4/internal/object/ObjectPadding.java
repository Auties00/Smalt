/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/mcpad.cpp). Copyright 1996-2000 Microsoft Corporation, Hyundai Electronics and the contributors named in the
 * sources; ISO/IEC free license for products conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.util.Arrays;

/**
 * The padding of reference VOPs with arbitrary shape (7.6.1): repetitive padding of boundary macroblocks, extended
 * padding of transparent ones from their neighbors, and the field-based padding of interlaced VOPs.
 */
abstract class ObjectPadding extends ObjectMotion {
    private static final int INVALID_COLOUR = -1;
    static final int MB_FIELDY = 1;
    static final int MB_FIELDC = 3;

    /**
     * Pads the transparent samples of a boundary macroblock from its opaque ones ({@code mcPadCurrMB}).
     *
     * @param offsets the index of the macroblock in each alpha plane, or {@code null}
     */
    final void mcPadCurrMB(VopPlanes vop, int offY, int offUV, int[] offsets) {
        mcPadCurr(vop.y.pixels, offY, currMBBY, MB_SIZE, frameWidthY);
        mcPadCurr(vop.u.pixels, offUV, currMBBUV, BLOCK_SIZE, frameWidthUV);
        mcPadCurr(vop.v.pixels, offUV, currMBBUV, BLOCK_SIZE, frameWidthUV);
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            for (var i = 0; i < volmd.auxCompCount; i++) {
                mcPadCurr(vop.a[i].pixels, offsets[i], currMBBY, MB_SIZE, frameWidthY);
            }
        }
    }

    /**
     * Pads one block by repetition: each transparent run takes the average of its opaque ends, then transparent rows
     * take their opaque neighbor rows ({@code mcPadCurr}).
     */
    final void mcPadCurr(int[] texture, int base, int[] alpha, int blkSize, int stride) {
        var emptyRow = false;
        var t = base;
        var a = 0;
        var leftX = 0;
        for (var y = 0; y < blkSize; y++, t += stride) {
            var inVop = true;
            var leftColour = INVALID_COLOUR;
            emptyRowArray[y] = false;
            int x;
            for (x = 0; x < blkSize; x++, a++) {
                if (inVop && alpha[a] == 0) {
                    inVop = false;
                    leftX = x;
                    if (x > 0) {
                        leftColour = texture[t + leftX - 1];
                    }
                } else if (!inVop && alpha[a] != 0) {
                    inVop = true;
                    if (leftColour == INVALID_COLOUR) {
                        leftColour = texture[t + x];
                    } else {
                        leftColour = (leftColour + texture[t + x] + 1) >> 1;
                    }
                    Arrays.fill(texture, t + leftX, t + x, leftColour);
                }
            }
            if (!inVop) {
                if (leftX == 0) {
                    emptyRowArray[y] = true;
                    emptyRow = true;
                } else {
                    Arrays.fill(texture, t + leftX, t + x, leftColour);
                }
            }
        }
        if (emptyRow) {
            fillEmptyRows(texture, base, blkSize, blkSize, stride);
        }
    }

    /**
     * Fills the rows marked empty from the rows above and below them.
     */
    private void fillEmptyRows(int[] texture, int base, int width, int rows, int step) {
        var t = base;
        var upper = -1;
        for (var y = 0; y < rows; y++, t += step) {
            if (!emptyRowArray[y]) {
                upper = t;
                continue;
            }
            var lower = t + step;
            int yy;
            for (yy = y + 1; yy < rows; yy++, lower += step) {
                if (!emptyRowArray[yy]) {
                    break;
                }
            }
            if (yy < rows) {
                if (upper < 0) {
                    for (; t < lower; t += step) {
                        System.arraycopy(texture, lower, texture, t, width);
                    }
                } else {
                    for (; t < lower; t += step) {
                        for (var x = 0; x < width; x++) {
                            texture[t + x] = (texture[upper + x] + texture[lower + x] + 1) >> 1;
                        }
                    }
                }
            } else {
                if (upper < 0) {
                    throw CorruptStream.INSTANCE;
                }
                for (yy = y; yy < rows; yy++, t += step) {
                    System.arraycopy(texture, upper, texture, t, width);
                }
            }
            y = yy - 1;
            t -= step;
        }
    }

    /**
     * Pads the transparent left and top neighbors of a macroblock from it ({@code padNeighborTranspMBs}).
     */
    final void padNeighborTranspMBs(int xb, int yb, int mbIndex, VopPlanes vop, int offY, int offUV,
                                    int[] offsets) {
        if (xb > 0) {
            var left = mbmd[mbIndex - 1];
            if (left.transpStatus[0] == MbMode.ALL && !left.padded) {
                mcPadLeftMB(vop, offY, offUV, offsets);
                left.padded = true;
            }
        }
        if (yb > 0) {
            var top = mbmd[mbIndex - numMBX];
            if (top.transpStatus[0] == MbMode.ALL && !top.padded) {
                mcPadTopMB(vop, offY, offUV, offsets);
                top.padded = true;
            }
        }
    }

    /**
     * Pads a transparent macroblock, and its transparent top neighbor, from its left or top neighbor
     * ({@code padCurrAndTopTranspMBFromNeighbor}).
     */
    final void padCurrAndTopTranspMBFromNeighbor(int xb, int yb, int mbIndex, VopPlanes vop, int offY, int offUV,
                                                 int[] offsets) {
        var mode = mbmd[mbIndex];
        if (xb > 0 && mbmd[mbIndex - 1].transpStatus[0] != MbMode.ALL) {
            mcPadCurrMBFromLeft(vop, offY, offUV, offsets);
            mode.padded = true;
        }
        if (yb > 0) {
            var top = mbmd[mbIndex - numMBX];
            if (top.transpStatus[0] != MbMode.ALL) {
                if (!mode.padded) {
                    mcPadCurrMBFromTop(vop, offY, offUV, offsets);
                    mode.padded = true;
                }
            } else if (!top.padded) {
                mcSetTopMBGray(vop, offY, offUV, offsets);
            }
        }
        if (yb == numMBY - 1) {
            if (xb > 0 && mbmd[mbIndex - 1].transpStatus[0] == MbMode.ALL && !mbmd[mbIndex - 1].padded) {
                mcSetLeftMBGray(vop, offY, offUV, offsets);
            }
            if (xb == numMBX - 1 && !mode.padded) {
                mcSetCurrMBGray(vop, offY, offUV, offsets);
            }
        }
    }

    private final int[] leftOffsets = new int[VolMode.MAX_MAC];

    /**
     * Fills the macroblock left of a position with the gray value ({@code mcSetLeftMBGray}).
     */
    final void mcSetLeftMBGray(VopPlanes vop, int offY, int offUV, int[] offsets) {
        int[] shifted = null;
        if (offsets != null && volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            shifted = leftOffsets;
            for (var i = 0; i < volmd.auxCompCount; i++) {
                shifted[i] = offsets[i] - MB_SIZE;
            }
        }
        mcSetCurrMBGray(vop, offY - MB_SIZE, offUV - BLOCK_SIZE, shifted);
    }

    private void mcPadLeftMB(VopPlanes vop, int offY, int offUV, int[] offsets) {
        var y = vop.y.pixels;
        var u = vop.u.pixels;
        var v = vop.v.pixels;
        for (var iy = 0; iy < BLOCK_SIZE; iy++) {
            Arrays.fill(y, offY - MB_SIZE, offY, y[offY]);
            Arrays.fill(u, offUV - BLOCK_SIZE, offUV, u[offUV]);
            Arrays.fill(v, offUV - BLOCK_SIZE, offUV, v[offUV]);
            offY += frameWidthY;
            offUV += frameWidthUV;
            Arrays.fill(y, offY - MB_SIZE, offY, y[offY]);
            offY += frameWidthY;
        }
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            for (var i = 0; i < volmd.auxCompCount; i++) {
                var a = vop.a[i].pixels;
                var off = offsets[i];
                for (var iy = 0; iy < MB_SIZE; iy++) {
                    Arrays.fill(a, off - MB_SIZE, off, a[off]);
                    off += frameWidthY;
                }
            }
        }
    }

    private void mcPadTopMB(VopPlanes vop, int offY, int offUV, int[] offsets) {
        padTop(vop.y.pixels, offY, MB_SIZE, frameWidthY);
        padTop(vop.u.pixels, offUV, BLOCK_SIZE, frameWidthUV);
        padTop(vop.v.pixels, offUV, BLOCK_SIZE, frameWidthUV);
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            for (var i = 0; i < volmd.auxCompCount; i++) {
                padTop(vop.a[i].pixels, offsets[i], MB_SIZE, frameWidthY);
            }
        }
    }

    private static void padTop(int[] plane, int off, int size, int stride) {
        for (var x = 0; x < size; x++) {
            var value = plane[off + x];
            var col = off + x;
            for (var iy = 0; iy < size; iy++) {
                col -= stride;
                plane[col] = value;
            }
        }
    }

    final void mcPadCurrMBFromLeft(VopPlanes vop, int offY, int offUV, int[] offsets) {
        var y = vop.y.pixels;
        var u = vop.u.pixels;
        var v = vop.v.pixels;
        for (var iy = 0; iy < BLOCK_SIZE; iy++) {
            Arrays.fill(y, offY, offY + MB_SIZE, y[offY - 1]);
            Arrays.fill(u, offUV, offUV + BLOCK_SIZE, u[offUV - 1]);
            Arrays.fill(v, offUV, offUV + BLOCK_SIZE, v[offUV - 1]);
            offY += frameWidthY;
            offUV += frameWidthUV;
            Arrays.fill(y, offY, offY + MB_SIZE, y[offY - 1]);
            offY += frameWidthY;
        }
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            for (var i = 0; i < volmd.auxCompCount; i++) {
                var a = vop.a[i].pixels;
                var off = offsets[i];
                for (var iy = 0; iy < MB_SIZE; iy++) {
                    Arrays.fill(a, off, off + MB_SIZE, a[off - 1]);
                    off += frameWidthY;
                }
            }
        }
    }

    final void mcPadCurrMBFromTop(VopPlanes vop, int offY, int offUV, int[] offsets) {
        padFromTop(vop.y.pixels, offY, MB_SIZE, frameWidthY);
        padFromTop(vop.u.pixels, offUV, BLOCK_SIZE, frameWidthUV);
        padFromTop(vop.v.pixels, offUV, BLOCK_SIZE, frameWidthUV);
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            for (var i = 0; i < volmd.auxCompCount; i++) {
                padFromTop(vop.a[i].pixels, offsets[i], MB_SIZE, frameWidthY);
            }
        }
    }

    private static void padFromTop(int[] plane, int off, int size, int stride) {
        for (var x = 0; x < size; x++) {
            var value = plane[off + x - stride];
            var col = off + x;
            for (var iy = 0; iy < size; iy++) {
                plane[col] = value;
                col += stride;
            }
        }
    }

    private int grayValue() {
        return volmd.not8Bit ? 1 << (volmd.nBits - 1) : 128;
    }

    final void mcSetTopMBGray(VopPlanes vop, int offY, int offUV, int[] offsets) {
        var gray = grayValue();
        var y = vop.y.pixels;
        var u = vop.u.pixels;
        var v = vop.v.pixels;
        for (var iy = 0; iy < BLOCK_SIZE; iy++) {
            offY -= frameWidthY;
            offUV -= frameWidthUV;
            Arrays.fill(y, offY, offY + MB_SIZE, gray);
            Arrays.fill(u, offUV, offUV + BLOCK_SIZE, gray);
            Arrays.fill(v, offUV, offUV + BLOCK_SIZE, gray);
            offY -= frameWidthY;
            Arrays.fill(y, offY, offY + MB_SIZE, gray);
        }
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT) {
            for (var i = 0; i < volmd.auxCompCount; i++) {
                var a = vop.a[i].pixels;
                var off = offsets[i];
                for (var iy = 0; iy < MB_SIZE; iy++) {
                    off -= frameWidthY;
                    Arrays.fill(a, off, off + MB_SIZE, gray);
                }
            }
        }
    }

    final void mcSetCurrMBGray(VopPlanes vop, int offY, int offUV, int[] offsets) {
        var gray = grayValue();
        var y = vop.y.pixels;
        var u = vop.u.pixels;
        var v = vop.v.pixels;
        for (var iy = 0; iy < BLOCK_SIZE; iy++) {
            Arrays.fill(y, offY, offY + MB_SIZE, gray);
            Arrays.fill(u, offUV, offUV + BLOCK_SIZE, gray);
            Arrays.fill(v, offUV, offUV + BLOCK_SIZE, gray);
            offY += frameWidthY;
            offUV += frameWidthUV;
            Arrays.fill(y, offY, offY + MB_SIZE, gray);
            offY += frameWidthY;
        }
        if (volmd.alphaUsage == VopPlanes.EIGHT_BIT && offsets != null) {
            for (var i = 0; i < volmd.auxCompCount; i++) {
                var a = vop.a[i].pixels;
                var off = offsets[i];
                for (var iy = 0; iy < MB_SIZE; iy++) {
                    Arrays.fill(a, off, off + MB_SIZE, gray);
                    off += frameWidthY;
                }
            }
        }
    }

    abstract void fieldBasedDownSampleBY(int[] by, int byOff, int[] buv, int buvOff);

    abstract void decideFieldTransparencyStatus(MbMode mode, int[] by, int byOff, int[] buv, int buvOff);

    /**
     * Pads an interlaced VOP field by field after its decoding ({@code fieldBasedMCPadding}).
     */
    final void fieldBasedMCPadding(int firstMb, VopPlanes vop) {
        var y = startInRefToCurrRctY;
        var uv = startInRefToCurrRctUV;
        var offsets = volmd.alphaUsage == VopPlanes.EIGHT_BIT ? new int[volmd.auxCompCount] : null;
        var mbIndex = firstMb;
        for (var mby = 0; mby < numMBY; mby++) {
            var mbY = y;
            var mbUV = uv;
            for (var mbx = 0; mbx < numMBX; mbx++) {
                var mode = mbmd[mbIndex];
                Arrays.fill(mode.fieldPadded, false);
                fieldBasedDownSampleBY(vop.by.pixels, mbY, vop.buv.pixels, mbUV);
                decideFieldTransparencyStatus(mode, vop.by.pixels, mbY, vop.buv.pixels, mbUV);
                if (offsets != null) {
                    Arrays.fill(offsets, startInRefToCurrRctY + MB_SIZE * mbx + frameWidthYxMBSize * mby);
                }
                if (offsets != null) {
                    for (var i = 0; i < volmd.auxCompCount; i++) {
                        mcPadFieldsCurr(mbx, mby, mbIndex, MB_FIELDY, vop.y.pixels, vop.a[i].pixels, mbY, offsets[i],
                                vop.by.pixels, mbY, MB_SIZE, frameWidthY);
                    }
                } else {
                    mcPadFieldsCurr(mbx, mby, mbIndex, MB_FIELDY, vop.y.pixels, null, mbY, 0, vop.by.pixels, mbY,
                            MB_SIZE, frameWidthY);
                }
                mcPadFieldsCurr(mbx, mby, mbIndex, MB_FIELDC, vop.u.pixels, vop.v.pixels, mbUV, mbUV, vop.buv.pixels,
                        mbUV, BLOCK_SIZE, frameWidthUV);
                mbY += MB_SIZE;
                mbUV += BLOCK_SIZE;
                mbIndex++;
            }
            y += frameWidthYxMBSize;
            uv += frameWidthUVxBlkSize;
        }
    }

    private void mcPadFieldsCurr(int mbx, int mby, int mbIndex, int field, int[] plane1, int[] plane2, int off1,
                                 int off2, int[] shape, int shapeOff, int blkXSize, int stride) {
        var mode = mbmd[mbIndex];
        for (var f = 0; f < 2; f++) {
            var fieldMode = field + f;
            var o1 = off1 + f * stride;
            var o2 = off2 + f * stride;
            var os = shapeOff + f * stride;
            if (mode.fieldTranspStatus[fieldMode] != MbMode.ALL) {
                if (mode.fieldTranspStatus[fieldMode] == MbMode.PARTIAL) {
                    mcPadOneField(plane1, o1, shape, os, blkXSize, stride);
                    if (plane2 != null) {
                        mcPadOneField(plane2, o2, shape, os, blkXSize, stride);
                    }
                }
                padNeighborTranspMBFields(mbx, mby, mbIndex, fieldMode, plane1, o1, plane2, o2, blkXSize, stride);
            } else {
                padCurrAndTopTranspMBFieldsFromNeighbor(mbx, mby, mbIndex, fieldMode, plane1, o1, plane2, o2,
                        blkXSize, stride);
            }
        }
    }

    private void mcPadOneField(int[] texture, int base, int[] alpha, int alphaBase, int blkXSize, int stride) {
        var blkYSize = blkXSize / 2;
        var fieldSkip = 2 * stride;
        var emptyRow = false;
        var t = base;
        var leftX = 0;
        for (var y = 0; y < blkYSize; y++, t += fieldSkip) {
            var inVop = true;
            var leftColour = INVALID_COLOUR;
            emptyRowArray[y] = false;
            var a = alphaBase;
            int x;
            for (x = 0; x < blkXSize; x++, a++) {
                if (inVop && alpha[a] == 0) {
                    inVop = false;
                    leftX = x;
                    if (x > 0) {
                        leftColour = texture[t + leftX - 1];
                    }
                } else if (!inVop && alpha[a] != 0) {
                    inVop = true;
                    leftColour = leftColour == INVALID_COLOUR ? texture[t + x] : (leftColour + texture[t + x] + 1)
                            >> 1;
                    Arrays.fill(texture, t + leftX, t + x, leftColour);
                }
            }
            alphaBase += fieldSkip;
            if (!inVop) {
                if (leftX == 0) {
                    emptyRowArray[y] = true;
                    emptyRow = true;
                } else {
                    Arrays.fill(texture, t + leftX, t + x, leftColour);
                }
            }
        }
        if (emptyRow) {
            fillEmptyRows(texture, base, blkXSize, blkYSize, fieldSkip);
        }
    }

    private void padNeighborTranspMBFields(int xb, int yb, int mbIndex, int field, int[] plane1, int off1,
                                           int[] plane2, int off2, int blkXSize, int stride) {
        if (xb > 0) {
            var left = mbmd[mbIndex - 1];
            if (left.fieldTranspStatus[field] == MbMode.ALL && !left.fieldPadded[field]) {
                fieldFill(plane1, off1, plane2, off2, blkXSize, stride, FieldFill.LEFT, 0);
                left.fieldPadded[field] = true;
            }
        }
        if (yb > 0) {
            var top = mbmd[mbIndex - numMBX];
            if (top.fieldTranspStatus[field] == MbMode.ALL && !top.fieldPadded[field]) {
                fieldFill(plane1, off1, plane2, off2, blkXSize, stride, FieldFill.TOP, 0);
                top.fieldPadded[field] = true;
            }
        }
    }

    private void padCurrAndTopTranspMBFieldsFromNeighbor(int xb, int yb, int mbIndex, int field, int[] plane1,
                                                         int off1, int[] plane2, int off2, int blkXSize,
                                                         int stride) {
        var mode = mbmd[mbIndex];
        if (xb > 0 && mbmd[mbIndex - 1].fieldTranspStatus[field] != MbMode.ALL) {
            fieldFill(plane1, off1, plane2, off2, blkXSize, stride, FieldFill.FROM_LEFT, 0);
            mode.fieldPadded[field] = true;
        }
        if (yb > 0) {
            var top = mbmd[mbIndex - numMBX];
            if (top.fieldTranspStatus[field] != MbMode.ALL) {
                if (!mode.fieldPadded[field]) {
                    fieldFill(plane1, off1, plane2, off2, blkXSize, stride, FieldFill.FROM_TOP, 0);
                    mode.fieldPadded[field] = true;
                }
            } else if (!top.fieldPadded[field]) {
                fieldFill(plane1, off1, plane2, off2, blkXSize, stride, FieldFill.TOP_GRAY, grayValue());
            }
        }
        if (yb == numMBY - 1) {
            var left = xb > 0 ? mbmd[mbIndex - 1] : null;
            if (left != null && left.fieldTranspStatus[field] == MbMode.ALL && !left.fieldPadded[field]) {
                fieldFill(plane1, off1, plane2, off2, blkXSize, stride, FieldFill.LEFT_GRAY, grayValue());
            }
            if (xb == numMBX - 1 && !mode.fieldPadded[field]) {
                fieldFill(plane1, off1, plane2, off2, blkXSize, stride, FieldFill.CURR_GRAY, grayValue());
            }
        }
    }

    private enum FieldFill {
        LEFT, TOP, FROM_LEFT, FROM_TOP, TOP_GRAY, LEFT_GRAY, CURR_GRAY
    }

    /**
     * Applies one of the field padding operations ({@code mcPadLeftMBFields}, {@code mcPadTopMBFields},
     * {@code mcPadCurrMBFieldsFromLeft}, {@code mcPadCurrMBFieldsFromTop}, {@code mcSetTopMBFieldsGray},
     * {@code mcSetLeftMBFieldsGray} and {@code mcSetCurrMBFieldsGray}) to the first plane, then to the second.
     */
    private static void fieldFill(int[] plane1, int off1, int[] plane2, int off2, int blkXSize, int stride,
                                  FieldFill operation, int gray) {
        fieldFillOne(plane1, off1, blkXSize, stride, operation, gray);
        if (plane2 != null) {
            fieldFillOne(plane2, off2, blkXSize, stride, operation, gray);
        }
    }

    private static void fieldFillOne(int[] plane, int off, int blkXSize, int stride, FieldFill operation,
                                     int gray) {
        var blkYSize = blkXSize / 2;
        var step = 2 * stride;
        switch (operation) {
            case LEFT -> {
                for (var iy = 0; iy < blkYSize; iy++) {
                    Arrays.fill(plane, off - blkXSize, off, plane[off]);
                    off += step;
                }
            }
            case TOP -> {
                for (var x = 0; x < blkXSize; x++) {
                    var value = plane[off + x];
                    var col = off + x;
                    for (var iy = 0; iy < blkYSize; iy++) {
                        col -= step;
                        plane[col] = value;
                    }
                }
            }
            case FROM_LEFT -> {
                for (var iy = 0; iy < blkYSize; iy++) {
                    Arrays.fill(plane, off, off + blkXSize, plane[off - 1]);
                    off += step;
                }
            }
            case FROM_TOP -> {
                var src = off - step;
                for (var x = 0; x < blkXSize; x++) {
                    var dst = src + x;
                    var value = plane[src + x];
                    for (var iy = 0; iy < blkYSize; iy++) {
                        dst += step;
                        plane[dst] = value;
                    }
                }
            }
            case TOP_GRAY -> {
                off -= step;
                for (var iy = 0; iy < blkYSize; iy++) {
                    Arrays.fill(plane, off, off + blkXSize, gray);
                    off -= step;
                }
            }
            case LEFT_GRAY -> {
                off -= blkXSize;
                for (var iy = 0; iy < blkYSize; iy++) {
                    Arrays.fill(plane, off, off + blkXSize, gray);
                    off += step;
                }
            }
            default -> {
                for (var iy = 0; iy < blkYSize; iy++) {
                    Arrays.fill(plane, off, off + blkXSize, gray);
                    off += step;
                }
            }
        }
    }
}
