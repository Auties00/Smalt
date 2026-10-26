/* Ports the Microsoft MPEG-4 Visual reference software of ISO/IEC 14496-5:2001 (version 2 FDAM1-2.3-001213,
 * sys/newpred.cpp and sys/decoder/newpdec.cpp: CNewPred and CNewPredDecoder). Copyright 1998-2000 Oki Electric
 * Industry, Microsoft Corporation and the contributors named in the sources; ISO/IEC free license for products
 * conforming to ISO/IEC 14496-2. Modified for Smalt. */
package com.github.auties00.smalt.video.mpeg4.internal.object;

import java.util.Arrays;

/**
 * The decoder state of NEWPRED (7.12): the NEWPRED segments of a VOP, the reference slices kept per segment and
 * vop_id, and the substitution of the reference of each segment by the slice its vop_id_for_prediction names.
 */
final class NewPred {
    private static final int EXPANDY = ObjectBase.EXPANDY_REF_FRAME;
    private static final int MB = ObjectBase.MB_SIZE;
    private static final int BLOCK = ObjectBase.BLOCK_SIZE;
    private static final int BUFFERS = 5;
    private static final int MAX_BITS_VOP_ID = 15;

    private static final class SliceBuffer {
        final int sizeY;
        final int sizeUV;
        int vopId;
        final int[] y;
        final int[] u;
        final int[] v;

        SliceBuffer(int sizeY, int sizeUV) {
            this.sizeY = sizeY;
            this.sizeUV = sizeUV;
            this.y = new int[sizeY];
            this.u = new int[sizeUV];
            this.v = new int[sizeUV];
        }
    }

    final int width;
    final int height;
    private final int numMBX;
    private final int numMBY;
    private final int maxVopId;
    private final boolean segmentVop;
    private final boolean shapeOnly;
    private final Rct frameY;
    private final Rct frameUV;
    private int vopId;
    private int slice;
    int[] slicePoints;
    int numSlice;
    private SliceBuffer[][] buffers;
    private int[] ref;
    final int[] refY;
    final int[] refU;
    final int[] refV;
    private VopPlanes current;

    /**
     * Builds the state of a VOP with its segment start macroblocks ({@code SetObject}).
     *
     * @param points the first macroblock of each segment, the first being 0, or {@code null} for none
     */
    NewPred(int numBitsTimeIncr, int width, int height, int[] points, int count, boolean segmentVop,
            boolean shapeOnly, Rct frameY, Rct frameUV) {
        var bitsVopId = Math.min(numBitsTimeIncr + 3, MAX_BITS_VOP_ID);
        this.maxVopId = (1 << bitsVopId) - 1;
        this.width = width;
        this.height = height;
        this.numMBX = width / MB;
        this.numMBY = height / MB;
        this.segmentVop = segmentVop;
        this.shapeOnly = shapeOnly;
        this.frameY = new Rct(frameY);
        this.frameUV = new Rct(frameUV);
        slicePoints(points, count);
        var uvWidth = width / 2;
        var uvHeight = height / 2;
        if (!shapeOnly) {
            refY = new int[(2 * EXPANDY + width) * (2 * EXPANDY + height)];
            refU = new int[(2 * ObjectBase.EXPANDUV_REF_FRAME + uvWidth) * (2 * ObjectBase.EXPANDUV_REF_FRAME
                    + uvHeight)];
            refV = new int[refU.length];
        } else {
            refY = refU = refV = new int[0];
        }
        if (numSlice > 0) {
            buffers = new SliceBuffer[numSlice][BUFFERS];
            ref = new int[numSlice];
            for (var i = 0; i < numSlice; i++) {
                var rows = (i + 1 < numSlice ? slicePoints[i + 1] - slicePoints[i] : numMBX * numMBY - slicePoints[i])
                        / numMBX;
                if (rows == 0) {
                    rows = 1;
                }
                var heightUV = MB * rows / 2;
                for (var j = 0; j < BUFFERS; j++) {
                    buffers[i][j] = new SliceBuffer((2 * EXPANDY + width) * (MB * rows),
                            (EXPANDY + uvWidth) * heightUV);
                }
            }
        }
    }

    private void slicePoints(int[] points, int count) {
        if (points == null && !segmentVop) {
            slicePoints = null;
            numSlice = 0;
            return;
        }
        if (segmentVop || points == null) {
            numSlice = 1;
            slicePoints = new int[]{0, -1};
            return;
        }
        numSlice = count;
        slicePoints = Arrays.copyOf(points, count + 1);
        slicePoints[count] = -1;
        if (slicePoints[0] != 0) {
            throw CorruptStream.INSTANCE;
        }
        for (var i = 0; i < numSlice; i++) {
            if (slicePoints[i] % numMBX != 0) {
                var flag = 0;
                var before = slicePoints[i] / numMBX * numMBX;
                var next = before == (numMBY - 1) * numMBX ? -1 : before + numMBX;
                for (var k = 0; k <= numSlice; k++) {
                    if (slicePoints[k] == before) {
                        flag++;
                    }
                    if (slicePoints[k] == next) {
                        flag++;
                    }
                }
                if (flag != 2) {
                    throw CorruptStream.INSTANCE;
                }
            }
        }
    }

    int currentVopId() {
        return vopId;
    }

    void resetVopId(int id) {
        vopId = id;
    }

    void setCurrent(VopPlanes refQ1) {
        current = refQ1;
    }

    /**
     * Records the vop_id of a VOP or video packet header and the reference it names ({@code GetRef}).
     */
    void getRef(boolean vopHeader, int predType, int id, boolean indication, int prediction) {
        vopId = id;
        if (predType != VopMode.IVOP) {
            if (vopHeader) {
                slice = 0;
            } else if (!segmentVop) {
                slice++;
            }
            if (ref == null || slice >= ref.length) {
                throw CorruptStream.INSTANCE;
            }
            ref[slice] = indication ? prediction : 0;
        }
    }

    /**
     * {@return the segment that contains a macroblock ({@code GetSliceNum})}
     */
    int sliceNumber(int mbx, int mby) {
        if (slicePoints == null) {
            return -1;
        }
        var number = mbx + mby * numMBX;
        int i;
        for (i = 0; slicePoints[i] >= 0; i++) {
            if (number < slicePoints[i]) {
                return i - 1;
            }
        }
        return i - 1;
    }

    private int nextSliceHeadMBA(int mbx, int mby) {
        var number = mbx + mby * numMBX;
        for (var i = 0; slicePoints[i] >= 0; i++) {
            if (slicePoints[i] > number) {
                return slicePoints[i];
            }
        }
        return -1;
    }

    /**
     * {@return the first macroblock of a segment ({@code NowMBA})}
     */
    int nowMBA(int vp) {
        var i = 0;
        while (slicePoints[i] >= 0) {
            i++;
        }
        return vp >= i ? -1 : slicePoints[vp];
    }

    /**
     * Copies the reference frame into the working buffers ({@code CopyReftoBuf}).
     */
    void copyRefToBuf(VopPlanes ref) {
        System.arraycopy(ref.y.pixels, 0, refY, 0, frameY.area());
        System.arraycopy(ref.u.pixels, 0, refU, 0, frameUV.area());
        System.arraycopy(ref.v.pixels, 0, refV, 0, frameUV.area());
    }

    /**
     * Copies the slice of the stored reference that a segment names into the working buffers, at the given row
     * offsets ({@code CopyNPtoVM}).
     */
    boolean copyNPtoVM(int sliceNumber, int offY, int offU, int offV) {
        return copySlice(sliceNumber, refY, offY, refU, offU, refV, offV, false);
    }

    /**
     * Restores the previous reference of each segment into the reference VOP ({@code CopyNPtoPrev}).
     */
    boolean copyNPtoPrev(int sliceNumber, int[] y, int offY, int[] u, int offU, int[] v, int offV) {
        var first = buffers[sliceNumber][0].vopId;
        ref[sliceNumber] = first != 1 ? first - 1 : maxVopId;
        return copySlice(sliceNumber, y, offY, u, offU, v, offV, true);
    }

    private boolean copySlice(int sliceNumber, int[] y, int offY, int[] u, int offU, int[] v, int offV,
                              boolean restore) {
        if (slicePoints == null || sliceNumber >= numSlice) {
            return false;
        }
        var cur = slicePoints[sliceNumber];
        var next = slicePoints[sliceNumber + 1];
        if (next == -1) {
            next = numMBX * numMBY;
        }
        var st = cur % numMBX + EXPANDY / MB;
        var ww = next - cur;
        int hh;
        if (ww <= numMBX) {
            hh = 1;
        } else {
            hh = ww / numMBX;
            ww = numMBX;
        }
        for (var i = 0; i < BUFFERS; i++) {
            if (!restore && ref[sliceNumber] == 0) {
                break;
            }
            var buffer = buffers[sliceNumber][i];
            if (buffer.vopId == ref[sliceNumber]) {
                for (var j = 0; j < hh * MB; j++) {
                    var dist = st * MB + j * frameY.width;
                    System.arraycopy(buffer.y, dist, y, offY + dist, ww * MB);
                }
                for (var j = 0; j < hh * BLOCK; j++) {
                    var dist = st * BLOCK + j * frameUV.width;
                    System.arraycopy(buffer.u, dist, u, offU + dist, ww * BLOCK);
                    System.arraycopy(buffer.v, dist, v, offV + dist, ww * BLOCK);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Writes the working buffers into the reference VOP and extends the segment that starts at a macroblock over
     * the rest of the reference ({@code ChangeRefOfSlice}).
     *
     * @param offY the index of the first row of the segment in the reference luminance
     */
    void changeRefOfSlice(VopPlanes reference, int offY, int offUV, int mbx, int mby) {
        changePlane(reference.y.pixels, offY, refY, mbx, mby, frameY, 1);
        changePlane(reference.u.pixels, offUV, refU, mbx, mby, frameUV, MB / BLOCK);
        changePlane(reference.v.pixels, offUV, refV, mbx, mby, frameUV, MB / BLOCK);
    }

    private void changePlane(int[] plane, int rowOff, int[] buffer, int mbx, int mby, Rct size, int magnification) {
        System.arraycopy(buffer, 0, plane, 0, size.width * (size.bottom - size.top));
        var mba = nextSliceHeadMBA(mbx, mby);
        var headX = mbx * MB / magnification;
        var lengthX = (((mba - 1) % numMBX) + 1) * MB / magnification - headX;
        if (lengthX < 0) {
            lengthX = width / magnification - headX;
        }
        var full = headX == 0 && lengthX == width / magnification;
        var refPoint = rowOff + size.left;
        if (!full) {
            for (var p = 0; p < MB / magnification; p++) {
                var left = refPoint - size.left + headX;
                var right = left + lengthX - 1;
                var count = headX - size.left;
                Arrays.fill(plane, refPoint, refPoint + count, plane[left]);
                var after = width / magnification - headX - lengthX - size.left;
                Arrays.fill(plane, right + 1, right + 1 + after, plane[right]);
                refPoint += size.width;
            }
        }
        refPoint = rowOff + size.left;
        while (refPoint > 0) {
            refPoint -= size.width;
            System.arraycopy(plane, rowOff + size.left, plane, refPoint, size.width);
        }
        refPoint = rowOff + size.left;
        if (mba == -1) {
            refPoint += (height / MB - mby) * size.width * MB / magnification;
        } else {
            var rows = full ? mba / numMBX - mby : 1;
            refPoint += rows * size.width * MB / magnification;
        }
        var last = (size.bottom - size.top - 1) * size.width;
        System.arraycopy(plane, refPoint - size.width, plane, refPoint, size.width);
        while (refPoint < last) {
            refPoint += size.width;
            System.arraycopy(plane, refPoint - size.width, plane, refPoint, size.width);
        }
    }

    /**
     * Shifts the stored references of a segment and stores the slice of the current VOP with its vop_id
     * ({@code make_next_decbuf}).
     */
    void makeNextDecBuf(int id, int sliceNumber) {
        var row = buffers[sliceNumber];
        var tmp = row[BUFFERS - 1];
        System.arraycopy(row, 0, row, 1, BUFFERS - 1);
        row[0] = tmp;
        tmp.vopId = id;
        if (!shapeOnly) {
            var sizeY = 0;
            var sizeUV = 0;
            for (var i = 0; i < sliceNumber; i++) {
                if (slicePoints[i + 1] % numMBX != 0) {
                    continue;
                }
                sizeY += buffers[i][0].sizeY;
                sizeUV += buffers[i][0].sizeUV;
            }
            System.arraycopy(current.y.pixels, EXPANDY * frameY.width + sizeY, tmp.y, 0, tmp.sizeY);
            System.arraycopy(current.u.pixels, EXPANDY / 2 * frameUV.width + sizeUV, tmp.u, 0, tmp.sizeUV);
            System.arraycopy(current.v.pixels, EXPANDY / 2 * frameUV.width + sizeUV, tmp.v, 0, tmp.sizeUV);
        }
    }
}
