/* Ports libvpx 1.17.0 (vpx_scale/generic/yv12extend.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Holds an 8-bit 4:2:0 picture of the VP9 encoder with a border around it: a source frame or a
 * reference frame.
 *
 * <p>Sample {@code (x, y)} of plane {@code p}, which may lie in the border, is at index
 * {@code origin(p) + y * stride(p) + x}. The border repeats the edge samples of the visible
 * picture, so a block read anywhere within it sees what a decoder's edge extension shows, and
 * motion compensation and motion search need no bounds checks. The border is
 * {@link #BORDER} luma samples beyond the 8-aligned size and half as many chroma samples; the
 * buffers end with a margin for the samples vector kernels read past a row.
 *
 * @implNote This implementation follows libvpx's encoder frame buffers, allocated with
 * {@code VP9_ENC_BORDER_IN_PIXELS} and extended by {@code vpx_extend_frame_borders}
 * ({@code vpx_scale/generic/yv12extend.c}), which extends from the visible edge.
 */
final class Vp9EncPicture {
    /**
     * The luma border in samples beyond the 8-aligned size ({@code VP9_ENC_BORDER_IN_PIXELS}).
     */
    static final int BORDER = 160;

    /**
     * The extra samples at the end of each plane for vector reads past the last row.
     */
    private static final int TAIL = 64;

    /**
     * The visible width in luma samples.
     */
    final int width;

    /**
     * The visible height in luma samples.
     */
    final int height;

    /**
     * The planes: luma, then Cb, then Cr.
     */
    final byte[][] planes = new byte[3][];

    /**
     * The row strides of the planes.
     */
    final int[] strides = new int[3];

    /**
     * The index of sample {@code (0, 0)} of each plane.
     */
    final int[] origins = new int[3];

    /**
     * The visible widths of the planes.
     */
    final int[] cropWidths = new int[3];

    /**
     * The visible heights of the planes.
     */
    final int[] cropHeights = new int[3];

    /**
     * The borders of the planes: luma, then chroma.
     */
    final int[] borders = new int[3];

    /**
     * The 8-aligned heights of the planes.
     */
    private final int[] alignedHeights = new int[3];

    /**
     * The planes of the picture {@link #copyFrom(YuvPicture, int)} copies, kept so that a copy
     * allocates nothing.
     */
    private final byte[][] pictureSources = new byte[3][];

    /**
     * The row strides of the planes in {@link #pictureSources}.
     */
    private final int[] pictureStrides = new int[3];

    /**
     * The source planes of the copy in progress.
     */
    private byte[][] copySources;

    /**
     * The source row strides of the copy in progress.
     */
    private int[] copySourceStrides;

    /**
     * The number of bands of the copy in progress.
     */
    private int copyCount;

    /**
     * Copies one band of the copy in progress, created once so that a copy allocates no band.
     */
    private final ParallelBands.Band copyBand = this::copyBand;

    /**
     * Creates a picture.
     *
     * @param width the visible width
     * @param height the visible height
     */
    Vp9EncPicture(int width, int height) {
        this.width = width;
        this.height = height;
        var alignedWidth = (width + 7) & ~7;
        var alignedHeight = (height + 7) & ~7;
        for (var plane = 0; plane < 3; plane++) {
            var ss = plane == 0 ? 0 : 1;
            var border = BORDER >> ss;
            var w = alignedWidth >> ss;
            var h = alignedHeight >> ss;
            borders[plane] = border;
            strides[plane] = w + 2 * border;
            alignedHeights[plane] = h;
            origins[plane] = border * strides[plane] + border;
            cropWidths[plane] = (width + ss) >> ss;
            cropHeights[plane] = (height + ss) >> ss;
            planes[plane] = new byte[strides[plane] * (h + 2 * border) + TAIL];
        }
    }

    /**
     * Copies the visible area of a 4:2:0 picture and extends the border.
     *
     * @param picture the picture, of this picture's size
     * @param bands the most threads of the module's pool to copy with
     */
    void copyFrom(YuvPicture picture, int bands) {
        pictureSources[0] = picture.y();
        pictureSources[1] = picture.u();
        pictureSources[2] = picture.v();
        pictureStrides[0] = picture.yStride();
        pictureStrides[1] = picture.uvStride();
        pictureStrides[2] = picture.uvStride();
        try {
            copy(pictureSources, pictureStrides, bands);
        } finally {
            Arrays.fill(pictureSources, null);
        }
    }

    /**
     * Copies the visible area of a decoded frame and extends the border.
     *
     * @param frame the reconstructed frame, of this picture's size
     * @param bands the most threads of the module's pool to copy with
     */
    void copyFrom(Vp9Frame frame, int bands) {
        copy(frame.planes8, frame.strides, bands);
    }

    /**
     * Copies the visible samples of three planes and extends the border
     * ({@code vpx_extend_frame_borders}), in horizontal bands on up to {@code bands} threads: each
     * band copies and extends its rows sideways, the first band also fills the top border and the
     * last band the bottom border from the rows they extended.
     *
     * @param sources the source planes
     * @param sourceStrides the source row strides
     * @param bands the most threads of the module's pool to copy with
     */
    private void copy(byte[][] sources, int[] sourceStrides, int bands) {
        var count = Math.max(1, Math.min(bands, cropHeights[2]));
        if (count == 1) {
            for (var plane = 0; plane < 3; plane++) {
                copyRows(sources[plane], sourceStrides[plane], plane, 0, cropHeights[plane]);
                extendTop(plane);
                extendBottom(plane);
            }
            return;
        }
        copySources = sources;
        copySourceStrides = sourceStrides;
        copyCount = count;
        try {
            ParallelBands.run(count, copyBand);
        } finally {
            copySources = null;
            copySourceStrides = null;
        }
    }

    /**
     * Copies and extends the rows of one band of the copy in progress: the band's share of every
     * plane's rows, the top border on the first band and the bottom border on the last.
     *
     * @param band the band, from zero to {@link #copyCount} minus one
     */
    private void copyBand(int band) {
        var count = copyCount;
        for (var plane = 0; plane < 3; plane++) {
            var h = cropHeights[plane];
            copyRows(copySources[plane], copySourceStrides[plane], plane, ParallelBands.start(band, count, h),
                    ParallelBands.start(band + 1, count, h));
            if (band == 0) {
                extendTop(plane);
            }
            if (band == count - 1) {
                extendBottom(plane);
            }
        }
    }

    /**
     * Copies a range of one plane's visible rows and repeats their edge samples into the left and
     * right borders.
     *
     * @param source the source samples
     * @param sourceStride the source row stride
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param first the first row
     * @param end the row after the last
     */
    private void copyRows(byte[] source, int sourceStride, int plane, int first, int end) {
        var p = planes[plane];
        var stride = strides[plane];
        var origin = origins[plane];
        var w = cropWidths[plane];
        var border = borders[plane];
        var row = origin + first * stride;
        Planes.copy(source, first * sourceStride, sourceStride, p, row, stride, w, end - first);
        Planes.extendSides(p, row, stride, w, end - first, border, stride - border - w);
    }

    /**
     * Repeats one plane's first extended row into its top border.
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     */
    private void extendTop(int plane) {
        var stride = strides[plane];
        Planes.extendTop(planes[plane], origins[plane] - borders[plane], stride, stride, borders[plane]);
    }

    /**
     * Repeats one plane's last extended visible row into the rows below it, down to the end of the
     * bottom border.
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     */
    private void extendBottom(int plane) {
        var stride = strides[plane];
        var h = cropHeights[plane];
        Planes.extendBottom(planes[plane], origins[plane] + (h - 1) * stride - borders[plane], stride, stride,
                alignedHeights[plane] + borders[plane] - h);
    }

    /**
     * Returns the index of a sample.
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param x the column, which may lie in the border
     * @param y the row, which may lie in the border
     * @return the index
     */
    int offset(int plane, int x, int y) {
        return origins[plane] + y * strides[plane] + x;
    }
}
