package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.internal.plane.Planes;

/**
 * Holds one 4:2:0 picture in padded planes: a source picture, a reconstruction being built, or a
 * reference picture with its half-sample interpolation planes.
 *
 * <p>The coded area covers whole macroblocks. Around it, {@link #LUMA_PAD} luma and
 * {@link #CHROMA_PAD} chroma samples replicate the edges once {@link #padBand} ran for every
 * macroblock row, which makes every motion-compensated read inside the padding equal to the
 * decoder's clamped read (clause 8.4.2.2). Sample {@code (x, y)} of a plane lives at
 * {@code origin + y * stride + x}.
 */
final class Frame {
    /**
     * The luma padding on each side, in samples.
     */
    static final int LUMA_PAD = 32;

    /**
     * The chroma padding on each side, in samples.
     */
    static final int CHROMA_PAD = 16;

    /**
     * The coded luma width, a multiple of 16.
     */
    final int width;

    /**
     * The coded luma height, a multiple of 16.
     */
    final int height;

    /**
     * The luma row stride.
     */
    final int lumaStride;

    /**
     * The chroma row stride.
     */
    final int chromaStride;

    /**
     * The index of luma sample {@code (0, 0)}.
     */
    final int lumaOrigin;

    /**
     * The index of chroma sample {@code (0, 0)}.
     */
    final int chromaOrigin;

    /**
     * The luma plane.
     */
    final byte[] y;

    /**
     * The Cb plane.
     */
    final byte[] u;

    /**
     * The Cr plane.
     */
    final byte[] v;

    /**
     * The horizontal half-sample luma plane, or {@code null} until {@link #interpolate} runs.
     */
    byte[] halfH;

    /**
     * The vertical half-sample luma plane, or {@code null} until {@link #interpolate} runs.
     */
    byte[] halfV;

    /**
     * The center half-sample luma plane, or {@code null} until {@link #interpolate} runs.
     */
    byte[] halfC;

    /**
     * The motion of every macroblock when this frame was coded, kept for prediction candidates of
     * later frames: the 16x16-averaged vector of each macroblock packed as by
     * {@link MotionPredictor#pack(int, int)}.
     */
    final int[] motion;

    /**
     * The list 0 reference index of each 8x8 quadrant of each macroblock when this frame was coded,
     * {@code -1} for intra macroblocks: the co-located data of spatial direct prediction.
     */
    final byte[] colRef;

    /**
     * The horizontal vector of the outer corner 4x4 block of each 8x8 quadrant of each macroblock
     * when this frame was coded.
     */
    final short[] colMvX;

    /**
     * The vertical vector of the outer corner 4x4 block of each 8x8 quadrant of each macroblock
     * when this frame was coded.
     */
    final short[] colMvY;

    /**
     * The {@code frame_num} this picture was coded with.
     */
    int frameNum;

    /**
     * The picture order count of the picture, relative to its group of pictures.
     */
    long poc;

    /**
     * The picture order count distance from this picture to the list 0 reference that
     * {@link #motion} points at, at least 1.
     */
    int motionDistance = 1;

    /**
     * Allocates a frame.
     *
     * @param width the coded luma width, a multiple of 16
     * @param height the coded luma height, a multiple of 16
     */
    Frame(int width, int height) {
        this.width = width;
        this.height = height;
        this.lumaStride = width + 2 * LUMA_PAD;
        this.chromaStride = width / 2 + 2 * CHROMA_PAD;
        this.lumaOrigin = LUMA_PAD * lumaStride + LUMA_PAD;
        this.chromaOrigin = CHROMA_PAD * chromaStride + CHROMA_PAD;
        this.y = new byte[lumaStride * (height + 2 * LUMA_PAD)];
        this.u = new byte[chromaStride * (height / 2 + 2 * CHROMA_PAD)];
        this.v = new byte[chromaStride * (height / 2 + 2 * CHROMA_PAD)];
        this.motion = new int[(width / 16) * (height / 16)];
        this.colRef = new byte[(width / 16) * (height / 16) * 4];
        this.colMvX = new short[(width / 16) * (height / 16) * 4];
        this.colMvY = new short[(width / 16) * (height / 16) * 4];
    }

    /**
     * Replicates the first and last sample of each row of a band into the left and right
     * padding.
     *
     * @param plane the samples
     * @param origin the index of sample {@code (0, 0)}
     * @param stride the row stride
     * @param w the plane width
     * @param pad the padding on each side
     * @param from the first row of the band
     * @param to the row after the band
     */
    private static void padSides(byte[] plane, int origin, int stride, int w, int pad, int from, int to) {
        Planes.extendSides(plane, origin + from * stride, stride, w, to - from, pad, pad);
    }

    /**
     * Replicates the first row, with its side padding, into the padding above the plane.
     *
     * @param plane the samples
     * @param origin the index of sample {@code (0, 0)}
     * @param stride the row stride, the plane width plus twice {@code pad}
     * @param pad the padding on each side
     */
    private static void padTop(byte[] plane, int origin, int stride, int pad) {
        Planes.extendTop(plane, origin - pad, stride, stride, pad);
    }

    /**
     * Replicates the last row, with its side padding, into the padding below the plane.
     *
     * @param plane the samples
     * @param origin the index of sample {@code (0, 0)}
     * @param stride the row stride, the plane width plus twice {@code pad}
     * @param h the plane height
     * @param pad the padding on each side
     */
    private static void padBottom(byte[] plane, int origin, int stride, int h, int pad) {
        Planes.extendBottom(plane, origin + (h - 1) * stride - pad, stride, stride, pad);
    }

    /**
     * Pads the rows whose deblocked samples became final when the deblocking filter finished a
     * macroblock row, as one band of a reference built row by row.
     *
     * <p>Once every macroblock row up to {@code mbY} is filtered, the luma rows up to
     * {@code 16 * mbY + 12} and the chroma rows up to {@code 8 * mbY + 6} no longer change,
     * since filtering the next macroblock row's top edge modifies at most three luma rows and one
     * chroma row above it (clause 8.7.2.3 and 8.7.2.4). The band of {@code mbY} pads the side
     * padding of the rows that became final with it; the first band also fills the padding above
     * the picture and the last band every remaining row and the padding below the picture.
     * Bands pad disjoint rows, so any two can run concurrently.
     *
     * @param mbY the macroblock row whose filtering finished, with every row above it
     */
    void padBand(int mbY) {
        var mbHeight = height / 16;
        var last = mbY == mbHeight - 1;
        var lumaFrom = mbY == 0 ? 0 : 16 * mbY - 3;
        var lumaTo = last ? height : 16 * mbY + 13;
        var chromaFrom = mbY == 0 ? 0 : 8 * mbY - 1;
        var chromaTo = last ? height / 2 : 8 * mbY + 7;
        padSides(y, lumaOrigin, lumaStride, width, LUMA_PAD, lumaFrom, lumaTo);
        padSides(u, chromaOrigin, chromaStride, width / 2, CHROMA_PAD, chromaFrom, chromaTo);
        padSides(v, chromaOrigin, chromaStride, width / 2, CHROMA_PAD, chromaFrom, chromaTo);
        if (mbY == 0) {
            padTop(y, lumaOrigin, lumaStride, LUMA_PAD);
            padTop(u, chromaOrigin, chromaStride, CHROMA_PAD);
            padTop(v, chromaOrigin, chromaStride, CHROMA_PAD);
        }
        if (last) {
            padBottom(y, lumaOrigin, lumaStride, height, LUMA_PAD);
            padBottom(u, chromaOrigin, chromaStride, height / 2, CHROMA_PAD);
            padBottom(v, chromaOrigin, chromaStride, height / 2, CHROMA_PAD);
        }
    }

    /**
     * Computes the half-sample planes of the band of rows whose interpolation the deblocking of a
     * macroblock row enables, after {@link #allocateHalfPlanes()}.
     *
     * <p>The band of {@code mbY} covers the luma rows {@code 16 * mbY - 6} to
     * {@code 16 * mbY + 9}, whose six-tap filters read rows up to {@code 16 * mbY + 12}; the
     * first band starts at the top of the interpolated area and the last one ends at its bottom.
     * It reads rows that {@link #padBand} pads for {@code mbY} and {@code mbY - 1}, so it must run
     * after both, and it writes only its own rows of the half-sample planes.
     *
     * @param mbY the macroblock row
     * @param kernels the interpolation kernels
     * @param temp scratch space of at least {@code lumaStride} values, private to the caller
     */
    void interpolateBand(int mbY, InterpolationKernels kernels, short[] temp) {
        var mbHeight = height / 16;
        var from = mbY == 0 ? 0 : 16 * mbY + LUMA_PAD - 9;
        var to = mbY == mbHeight - 1 ? interpolationRows() : 16 * mbY + LUMA_PAD + 7;
        interpolateRows(kernels, temp, from, to);
    }

    /**
     * Computes the three half-sample luma planes over the padded area, after {@link #padBand}
     * ran for every macroblock row.
     *
     * <p>Rows and columns within three samples of the outer edge of the padding are left
     * unset; motion vectors are clamped so that prediction never reads them.
     *
     * @param kernels the interpolation kernels
     * @param temp scratch space of at least {@code lumaStride} values
     */
    void interpolate(InterpolationKernels kernels, short[] temp) {
        allocateHalfPlanes();
        interpolateRows(kernels, temp, 0, interpolationRows());
    }

    /**
     * Allocates the half-sample planes if they do not exist yet.
     */
    void allocateHalfPlanes() {
        if (halfH == null) {
            halfH = new byte[y.length];
            halfV = new byte[y.length];
            halfC = new byte[y.length];
        }
    }

    /**
     * Returns the number of padded rows whose half-sample values {@link #interpolate} computes.
     *
     * @return the row count
     */
    int interpolationRows() {
        return height + 2 * LUMA_PAD - 6;
    }

    /**
     * Computes the half-sample planes of a band of rows once the rows they read are padded and
     * the planes allocated; disjoint bands can be computed concurrently.
     *
     * @param kernels the interpolation kernels
     * @param temp scratch space of at least {@code lumaStride} values, private to the caller
     * @param from the first row of the band, counted from 0 to {@link #interpolationRows()}
     * @param to the row after the band
     */
    private void interpolateRows(InterpolationKernels kernels, short[] temp, int from, int to) {
        for (var row = 3 + from; row < 3 + to; row++) {
            kernels.halfPelRow(y, row * lumaStride + 3, lumaStride - 6, lumaStride, halfH, halfV, halfC, temp);
        }
    }
}
