package com.github.auties00.smalt.video.mpeg4.internal;

/**
 * Forms motion compensated prediction blocks from a reference {@link Frame}: half sample
 * prediction (ISO/IEC 14496-2 7.6.2.1, 7.6.9.1), quarter sample prediction (7.6.2.2), field
 * prediction of interlaced macroblocks (7.7.2) and overlapped block motion compensation (7.6.6,
 * ITU-T H.263 F.3).
 *
 * <p>A motion vector may point anywhere: samples outside the reference read the nearest sample of
 * its edge-extended area (7.6.4). A block whose source area leaves the extended border is first
 * copied, with clamped coordinates, into a private buffer and predicted from there, which gives the
 * same samples.
 *
 * <p>An instance holds scratch buffers and is used by one thread at a time.
 */
public final class MotionCompensator {
    /**
     * The row stride of the edge emulation buffer.
     */
    private static final int EDGE_STRIDE = 32;

    /**
     * The weights of the current vector in overlapped motion compensation (Figure 7-21).
     */
    private static final int[] OBMC_CURRENT = {
            4, 5, 5, 5, 5, 5, 5, 4,
            5, 5, 5, 5, 5, 5, 5, 5,
            5, 5, 6, 6, 6, 6, 5, 5,
            5, 5, 6, 6, 6, 6, 5, 5,
            5, 5, 6, 6, 6, 6, 5, 5,
            5, 5, 6, 6, 6, 6, 5, 5,
            5, 5, 5, 5, 5, 5, 5, 5,
            4, 5, 5, 5, 5, 5, 5, 4
    };

    /**
     * The weights of the vector above or below in overlapped motion compensation (Figure 7-22).
     */
    private static final int[] OBMC_VERTICAL = {
            2, 2, 2, 2, 2, 2, 2, 2,
            1, 1, 2, 2, 2, 2, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 2, 2, 2, 2, 1, 1,
            2, 2, 2, 2, 2, 2, 2, 2
    };

    /**
     * The weights of the vector left or right in overlapped motion compensation (Figure 7-23).
     */
    private static final int[] OBMC_HORIZONTAL = {
            2, 1, 1, 1, 1, 1, 1, 2,
            2, 2, 1, 1, 1, 1, 2, 2,
            2, 2, 1, 1, 1, 1, 2, 2,
            2, 2, 1, 1, 1, 1, 2, 2,
            2, 2, 1, 1, 1, 1, 2, 2,
            2, 2, 1, 1, 1, 1, 2, 2,
            2, 2, 1, 1, 1, 1, 2, 2,
            2, 1, 1, 1, 1, 1, 1, 2
    };

    /**
     * The edge emulation buffer: up to 17 rows (or 33 rows of a field area) of
     * {@link #EDGE_STRIDE} samples.
     */
    private final byte[] edge = new byte[EDGE_STRIDE * 40];

    /**
     * The quarter sample scratch buffer.
     */
    private final int[] scratch = new int[Mpeg4Dsp.SCRATCH_INTS];

    /**
     * The overlapped motion compensation predictions: current, above, below, left and right, each
     * 8x8.
     */
    private final byte[] obmc = new byte[5 * 64];

    /**
     * The kernels.
     */
    private Mpeg4Dsp dsp;

    /**
     * Creates a compensator.
     */
    public MotionCompensator() {
    }

    /**
     * Selects the kernels of the picture being reconstructed.
     *
     * @param dsp the kernels
     */
    public void setDsp(Mpeg4Dsp dsp) {
        this.dsp = dsp;
    }

    /**
     * Predicts a luminance block of a frame-predicted macroblock.
     *
     * @param ref the reference picture
     * @param dst the destination plane
     * @param dstOffset the index of the block's top-left sample in the destination
     * @param dstStride the destination row stride
     * @param x the block's left column in the picture
     * @param y the block's top row in the picture
     * @param size the block size, 8 or 16
     * @param mvx the horizontal vector
     * @param mvy the vertical vector
     * @param quarter whether the vector is in quarter samples
     * @param rounding the rounding control
     */
    public void luma(Frame ref, byte[] dst, int dstOffset, int dstStride, int x, int y, int size, int mvx, int mvy,
                     boolean quarter, int rounding) {
        var shift = quarter ? 2 : 1;
        var mask = quarter ? 3 : 1;
        var ix = x + (mvx >> shift);
        var iy = y + (mvy >> shift);
        var fx = mvx & mask;
        var fy = mvy & mask;
        var stride = ref.lumaStride;
        var plane = ref.y;
        var offset = ref.lumaOrigin + iy * stride + ix;
        var maxX = ref.mbWidth * 16 + Frame.LUMA_BORDER - size - 1;
        var maxY = ref.mbHeight * 16 + Frame.LUMA_BORDER - size - 1;
        if (ix < -Frame.LUMA_BORDER || iy < -Frame.LUMA_BORDER || ix > maxX || iy > maxY) {
            emulate(plane, ref.lumaOrigin, stride, ref.mbWidth * 16, ref.mbHeight * 16, Frame.LUMA_BORDER, ix, iy,
                    size + 1, size + 1, 1);
            plane = edge;
            offset = 0;
            stride = EDGE_STRIDE;
        }
        if (quarter) {
            dsp.quarterPel(plane, offset, stride, dst, dstOffset, dstStride, size, size, fx, fy, rounding, scratch);
        } else {
            dsp.halfPel(plane, offset, stride, dst, dstOffset, dstStride, size, size, fx, fy, rounding);
        }
    }

    /**
     * Predicts the two chrominance blocks of a macroblock with a chrominance vector in half
     * samples.
     *
     * @param ref the reference picture
     * @param dstU the blue-difference destination plane
     * @param dstV the red-difference destination plane
     * @param dstOffset the index of the blocks' top-left sample in the destination planes
     * @param dstStride the destination row stride
     * @param x the blocks' left column in the chrominance planes
     * @param y the blocks' top row in the chrominance planes
     * @param mvx the horizontal vector in half samples
     * @param mvy the vertical vector in half samples
     * @param rounding the rounding control
     */
    public void chroma(Frame ref, byte[] dstU, byte[] dstV, int dstOffset, int dstStride, int x, int y, int mvx,
                       int mvy, int rounding) {
        chromaPlane(ref, ref.u, dstU, dstOffset, dstStride, x, y, mvx, mvy, rounding, 8, 1);
        chromaPlane(ref, ref.v, dstV, dstOffset, dstStride, x, y, mvx, mvy, rounding, 8, 1);
    }

    /**
     * Predicts one field of a chrominance block of a field-predicted macroblock.
     *
     * @param ref the reference picture
     * @param dstU the blue-difference destination plane
     * @param dstV the red-difference destination plane
     * @param dstOffset the index of the blocks' top-left sample in the destination planes
     * @param dstStride the destination frame row stride
     * @param x the blocks' left column in the chrominance planes
     * @param y the blocks' top row in the chrominance planes
     * @param mvx the horizontal vector in half samples
     * @param mvy the vertical vector in half samples, in frame lines
     * @param rounding the rounding control
     * @param destField the field of the destination, 0 for top or 1 for bottom
     * @param refField the field of the reference, 0 for top or 1 for bottom
     */
    public void chromaField(Frame ref, byte[] dstU, byte[] dstV, int dstOffset, int dstStride, int x, int y,
                            int mvx, int mvy, int rounding, int destField, int refField) {
        chromaPlane(ref, ref.u, dstU, dstOffset + destField * dstStride, dstStride * 2, x, y + refField, mvx, mvy,
                rounding, 4, 2);
        chromaPlane(ref, ref.v, dstV, dstOffset + destField * dstStride, dstStride * 2, x, y + refField, mvx, mvy,
                rounding, 4, 2);
    }

    /**
     * Predicts a chrominance block of one plane.
     *
     * @param ref the reference picture
     * @param plane the reference plane
     * @param dst the destination plane
     * @param dstOffset the index of the block's top-left sample in the destination
     * @param dstStride the destination row stride
     * @param x the block's left column
     * @param y the block's top row (of its field when {@code step} is 2)
     * @param mvx the horizontal vector in half samples
     * @param mvy the vertical vector in half samples (frame lines when {@code step} is 2)
     * @param rounding the rounding control
     * @param rows the number of rows to predict
     * @param step the line step, 1 for frames and 2 for fields
     */
    private void chromaPlane(Frame ref, byte[] plane, byte[] dst, int dstOffset, int dstStride, int x, int y,
                             int mvx, int mvy, int rounding, int rows, int step) {
        var ix = x + (mvx >> 1);
        var fx = mvx & 1;
        int iy;
        int fy;
        if (step == 1) {
            iy = y + (mvy >> 1);
            fy = mvy & 1;
        } else {
            iy = y + 2 * (mvy >> 2);
            fy = (mvy >> 1) & 1;
        }
        var stride = ref.chromaStride;
        var offset = ref.chromaOrigin + iy * stride + ix;
        var src = plane;
        var srcStride = stride * step;
        var maxX = ref.mbWidth * 8 + Frame.CHROMA_BORDER - 9;
        var lastRow = iy + step * rows;
        if (ix < -Frame.CHROMA_BORDER || iy < -Frame.CHROMA_BORDER || ix > maxX
                || lastRow >= ref.mbHeight * 8 + Frame.CHROMA_BORDER) {
            emulate(plane, ref.chromaOrigin, stride, ref.mbWidth * 8, ref.mbHeight * 8, Frame.CHROMA_BORDER, ix, iy,
                    9, rows + 1, step);
            src = edge;
            offset = 0;
            srcStride = EDGE_STRIDE;
        }
        dsp.halfPel(src, offset, srcStride, dst, dstOffset, dstStride, 8, rows, fx, fy, rounding);
    }

    /**
     * Predicts one field of a field-predicted luminance macroblock (7.7.2.1): 16 columns by 8 rows
     * of the destination field from a field of the reference.
     *
     * @param ref the reference picture
     * @param dst the destination plane
     * @param dstOffset the index of the macroblock's top-left sample in the destination
     * @param dstStride the destination frame row stride
     * @param x the macroblock's left column
     * @param y the macroblock's top row
     * @param mvx the horizontal vector
     * @param mvy the vertical vector in frame lines, even
     * @param quarter whether the vector is in quarter samples
     * @param rounding the rounding control
     * @param destField the field of the destination, 0 for top or 1 for bottom
     * @param refField the field of the reference, 0 for top or 1 for bottom
     */
    public void lumaField(Frame ref, byte[] dst, int dstOffset, int dstStride, int x, int y, int mvx, int mvy,
                          boolean quarter, int rounding, int destField, int refField) {
        var shift = quarter ? 2 : 1;
        var mask = quarter ? 3 : 1;
        var ix = x + (mvx >> shift);
        var fx = mvx & mask;
        // The vertical vector counts frame lines; within the field it counts half as many lines.
        var fieldVector = mvy >> 1;
        var iy = y + refField + 2 * (fieldVector >> shift);
        var fy = fieldVector & mask;
        var stride = ref.lumaStride;
        var src = ref.y;
        var offset = ref.lumaOrigin + iy * stride + ix;
        var srcStride = stride * 2;
        var maxX = ref.mbWidth * 16 + Frame.LUMA_BORDER - 17;
        if (ix < -Frame.LUMA_BORDER || iy < -Frame.LUMA_BORDER || ix > maxX
                || iy + 2 * 9 >= ref.mbHeight * 16 + Frame.LUMA_BORDER) {
            emulate(ref.y, ref.lumaOrigin, stride, ref.mbWidth * 16, ref.mbHeight * 16, Frame.LUMA_BORDER, ix, iy,
                    17, 9, 2);
            src = edge;
            offset = 0;
            srcStride = EDGE_STRIDE;
        }
        var out = dstOffset + destField * dstStride;
        if (quarter) {
            dsp.quarterPel(src, offset, srcStride, dst, out, dstStride * 2, 16, 8, fx, fy, rounding, scratch);
        } else {
            dsp.halfPel(src, offset, srcStride, dst, out, dstStride * 2, 16, 8, fx, fy, rounding);
        }
    }

    /**
     * Averages a block of a second prediction into a first one.
     *
     * @param src the second prediction
     * @param srcOffset the index of its top-left sample
     * @param srcStride its row stride
     * @param dst the first prediction
     * @param dstOffset the index of its top-left sample
     * @param dstStride its row stride
     * @param width the block width
     * @param height the block height
     */
    public void average(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                        int width, int height) {
        dsp.average(src, srcOffset, srcStride, dst, dstOffset, dstStride, width, height);
    }

    /**
     * Predicts an 8x8 luminance block with overlapped motion compensation (7.6.6): each sample is
     * the weighted sum of the predictions of the block's own vector and of the remote vectors of
     * the nearest block above or below and left or right.
     *
     * @param ref the reference picture
     * @param dst the destination plane
     * @param dstOffset the index of the block's top-left sample
     * @param dstStride the destination row stride
     * @param x the block's left column
     * @param y the block's top row
     * @param vectors the vectors as {@code x, y} pairs: current, above, below, left, right
     * @param quarter whether the vectors are in quarter samples
     * @param rounding the rounding control
     */
    public void overlapped(Frame ref, byte[] dst, int dstOffset, int dstStride, int x, int y, int[] vectors,
                           boolean quarter, int rounding) {
        for (var k = 0; k < 5; k++) {
            luma(ref, obmc, k * 64, 8, x, y, 8, vectors[2 * k], vectors[2 * k + 1], quarter, rounding);
        }
        for (var row = 0; row < 8; row++) {
            var vertical = row < 4 ? 64 : 128;
            var d = dstOffset + row * dstStride;
            for (var col = 0; col < 8; col++) {
                var horizontal = col < 4 ? 192 : 256;
                var w = row * 8 + col;
                var value = (obmc[w] & 0xFF) * OBMC_CURRENT[w] + (obmc[vertical + w] & 0xFF) * OBMC_VERTICAL[w]
                        + (obmc[horizontal + w] & 0xFF) * OBMC_HORIZONTAL[w];
                dst[d + col] = (byte) ((value + 4) >> 3);
            }
        }
    }

    /**
     * Copies an area of a plane into the edge emulation buffer, reading every sample outside the
     * plane's extended border from the nearest sample inside it, which equals the clipped reference
     * sample of 7.6.9.1 because the border replicates the edge samples.
     *
     * @param plane the reference plane
     * @param origin the index of its top-left coded sample
     * @param stride its row stride
     * @param width the coded width
     * @param height the coded height
     * @param border the border width
     * @param x the area's left column
     * @param y the area's top row
     * @param cols the number of columns
     * @param rows the number of rows
     * @param step the row step in the plane, 1 or 2
     */
    private void emulate(byte[] plane, int origin, int stride, int width, int height, int border, int x, int y,
                         int cols, int rows, int step) {
        for (var row = 0; row < rows; row++) {
            var sy = y + row * step;
            sy = sy < -border ? -border : Math.min(sy, height + border - 1);
            var src = origin + sy * stride;
            var dst = row * EDGE_STRIDE;
            for (var col = 0; col < cols; col++) {
                var sx = x + col;
                sx = sx < -border ? -border : Math.min(sx, width + border - 1);
                edge[dst + col] = plane[src + sx];
            }
        }
    }
}
