/* Ports libvpx 1.17.0 (vpx_scale/generic/yv12extend.c). Copyright (c) 2010, The WebM Project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.plane.Planes;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Holds a picture of the VP9 encoder with a border around it: a source frame or a reference
 * frame, in any of the four chroma subsamplings and at 8, 10 or 12 bits.
 *
 * <p>Sample {@code (x, y)} of plane {@code p}, which may lie in the border, is at index
 * {@code origin(p) + y * stride(p) + x}. The border repeats the edge samples of the visible
 * picture, so a block read anywhere within it sees what a decoder's edge extension shows, and
 * motion compensation and motion search need no bounds checks. The border is
 * {@link #BORDER} luma samples beyond the 8-aligned size; the chroma border is half as wide in
 * 4:2:0 and as wide in the other subsamplings. The buffers end with a margin for the samples
 * vector kernels read past a row.
 *
 * <p>A 10 or 12-bit picture holds its samples in {@link #planes16} and, in {@link #planes}, an
 * 8-bit proxy of them, each sample rounded to 8 bits ({@link #proxy(int, int)}), on which the
 * encoder takes its decisions; an 8-bit picture holds only {@link #planes}.
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
     * The horizontal chroma subsampling shift.
     */
    final int ssX;

    /**
     * The vertical chroma subsampling shift.
     */
    final int ssY;

    /**
     * The bit depth of the samples: 8, 10 or 12.
     */
    final int bitDepth;

    /**
     * The 8-bit planes, luma then Cb then Cr: the samples of an 8-bit picture, the proxies of a
     * 10 or 12-bit one.
     */
    final byte[][] planes = new byte[3][];

    /**
     * The 10 or 12-bit planes, luma then Cb then Cr, or {@code null} entries for an 8-bit picture.
     */
    final short[][] planes16 = new short[3][];

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
     * The 8-bit source planes of the copy in progress, or {@code null} entries.
     */
    private final byte[][] copySources8 = new byte[3][];

    /**
     * The 16-bit source planes of the copy in progress, or {@code null} entries.
     */
    private final short[][] copySources16 = new short[3][];

    /**
     * The source row strides of the copy in progress.
     */
    private final int[] copyStrides = new int[3];

    /**
     * The number of bands of the copy in progress.
     */
    private int copyCount;

    /**
     * Whether the copy in progress halves the chroma of a 4:4:4 picture vertically into this 4:4:0
     * picture.
     */
    private boolean copyDecimate;

    /**
     * The left shift of the 8-bit samples of the copy in progress into a high bit depth picture.
     */
    private int copyShift;

    /**
     * Copies one band of the copy in progress, created once so that a copy allocates no band.
     */
    private final ParallelBands.Band copyBand = this::copyBand;

    /**
     * Creates an 8-bit 4:2:0 picture.
     *
     * @param width the visible width
     * @param height the visible height
     */
    Vp9EncPicture(int width, int height) {
        this(width, height, 1, 1, 8);
    }

    /**
     * Creates a picture.
     *
     * @param width the visible width
     * @param height the visible height
     * @param ssX the horizontal chroma subsampling shift, 0 or 1
     * @param ssY the vertical chroma subsampling shift, 0 or 1
     * @param bitDepth the bit depth, 8, 10 or 12
     */
    Vp9EncPicture(int width, int height, int ssX, int ssY, int bitDepth) {
        this.width = width;
        this.height = height;
        this.ssX = ssX;
        this.ssY = ssY;
        this.bitDepth = bitDepth;
        var alignedWidth = (width + 7) & ~7;
        var alignedHeight = (height + 7) & ~7;
        for (var plane = 0; plane < 3; plane++) {
            var sx = plane == 0 ? 0 : ssX;
            var sy = plane == 0 ? 0 : ssY;
            var border = BORDER >> (sx & sy);
            var w = alignedWidth >> sx;
            var h = alignedHeight >> sy;
            borders[plane] = border;
            strides[plane] = w + 2 * border;
            alignedHeights[plane] = h;
            origins[plane] = border * strides[plane] + border;
            cropWidths[plane] = (width + sx) >> sx;
            cropHeights[plane] = (height + sy) >> sy;
            var size = strides[plane] * (h + 2 * border) + TAIL;
            planes[plane] = new byte[size];
            if (bitDepth > 8) {
                planes16[plane] = new short[size];
            }
        }
    }

    /**
     * Returns the 8-bit proxy of a high bit depth sample: the sample rounded to 8 bits.
     *
     * @param value the sample, from 0 to {@code 2^bitDepth - 1}
     * @param shift the bit depth minus 8, positive
     * @return the proxy, from 0 to 255
     */
    static int proxy(int value, int shift) {
        return Math.min(255, (value + (1 << (shift - 1))) >> shift);
    }

    /**
     * {@return whether the picture holds 10 or 12-bit samples}
     */
    boolean highBitDepth() {
        return bitDepth > 8;
    }

    /**
     * Copies the visible area of an 8-bit picture and extends the border; a high bit depth
     * picture receives the samples shifted to its depth.
     *
     * <p>A 4:4:0 picture copies a 4:4:4 picture, whose chroma rows it averages in pairs, the last
     * row of an odd height alone.
     *
     * @param picture the picture, of this picture's size and chroma subsampling, or 4:4:4 for a
     * 4:4:0 picture
     * @param bands the most threads of the module's pool to copy with
     */
    void copyFrom(YuvPicture picture, int bands) {
        copySources8[0] = picture.y();
        copySources8[1] = picture.u();
        copySources8[2] = picture.v();
        copyStrides[0] = picture.yStride();
        copyStrides[1] = picture.uvStride();
        copyStrides[2] = picture.uvStride();
        copyDecimate = ssX == 0 && ssY == 1 && picture.chromaFormat() == ChromaFormat.YUV444;
        copyShift = bitDepth - 8;
        try {
            copy(bands);
        } finally {
            Arrays.fill(copySources8, null);
            copyDecimate = false;
        }
    }

    /**
     * Copies the visible area of a picture of this picture's bit depth and extends the border.
     *
     * <p>A 4:4:0 picture copies a 4:4:4 picture as {@link #copyFrom(YuvPicture, int)} does.
     *
     * @param picture the picture, of this picture's size, bit depth and chroma subsampling, or
     * 4:4:4 for a 4:4:0 picture
     * @param bands the most threads of the module's pool to copy with
     */
    void copyFrom(Yuv16Picture picture, int bands) {
        copySources16[0] = picture.y();
        copySources16[1] = picture.u();
        copySources16[2] = picture.v();
        copyStrides[0] = picture.yStride();
        copyStrides[1] = picture.uvStride();
        copyStrides[2] = picture.uvStride();
        copyDecimate = ssX == 0 && ssY == 1 && picture.chromaFormat() == ChromaFormat.YUV444;
        try {
            copy(bands);
        } finally {
            Arrays.fill(copySources16, null);
            copyDecimate = false;
        }
    }

    /**
     * Copies the visible area of a decoded frame of this picture's format and extends the border.
     *
     * @param frame the reconstructed frame, of this picture's size, subsampling and bit depth
     * @param bands the most threads of the module's pool to copy with
     */
    void copyFrom(Vp9Frame frame, int bands) {
        for (var plane = 0; plane < 3; plane++) {
            if (bitDepth > 8) {
                copySources16[plane] = frame.planes16[plane];
            } else {
                copySources8[plane] = frame.planes8[plane];
            }
            copyStrides[plane] = frame.strides[plane];
        }
        copyShift = 0;
        try {
            copy(bands);
        } finally {
            Arrays.fill(copySources8, null);
            Arrays.fill(copySources16, null);
        }
    }

    /**
     * Copies the visible samples of the planes of the copy in progress and extends the border
     * ({@code vpx_extend_frame_borders}), in horizontal bands on up to {@code bands} threads: each
     * band copies and extends its rows sideways, the first band also fills the top border and the
     * last band the bottom border from the rows they extended.
     *
     * @param bands the most threads of the module's pool to copy with
     */
    private void copy(int bands) {
        var count = Math.max(1, Math.min(bands, cropHeights[2]));
        if (count == 1) {
            for (var plane = 0; plane < 3; plane++) {
                copyRows(plane, 0, cropHeights[plane]);
                extendTop(plane);
                extendBottom(plane);
            }
            return;
        }
        copyCount = count;
        ParallelBands.run(count, copyBand);
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
            copyRows(plane, ParallelBands.start(band, count, h), ParallelBands.start(band + 1, count, h));
            if (band == 0) {
                extendTop(plane);
            }
            if (band == count - 1) {
                extendBottom(plane);
            }
        }
    }

    /**
     * Copies a range of one plane's visible rows from the copy in progress and repeats their edge
     * samples into the left and right borders, deriving the proxies of a high bit depth picture.
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param first the first row
     * @param end the row after the last
     */
    private void copyRows(int plane, int first, int end) {
        var stride = strides[plane];
        var origin = origins[plane];
        var w = cropWidths[plane];
        var border = borders[plane];
        var sourceStride = copyStrides[plane];
        var decimate = plane > 0 && copyDecimate;
        var p = planes[plane];
        var row = origin + first * stride;
        var source8 = copySources8[plane];
        if (bitDepth == 8) {
            if (decimate) {
                for (var r = first; r < end; r++) {
                    var upper = 2 * r * sourceStride;
                    var lower = Math.min(2 * r + 1, height - 1) * sourceStride;
                    var dst = origin + r * stride;
                    for (var x = 0; x < w; x++) {
                        p[dst + x] = (byte) (((source8[upper + x] & 0xFF) + (source8[lower + x] & 0xFF) + 1) >> 1);
                    }
                }
            } else {
                Planes.copy(source8, first * sourceStride, sourceStride, p, row, stride, w, end - first);
            }
            Planes.extendSides(p, row, stride, w, end - first, border, stride - border - w);
            return;
        }
        var p16 = planes16[plane];
        var source16 = copySources16[plane];
        var shift = bitDepth - 8;
        for (var r = first; r < end; r++) {
            var upper = (decimate ? 2 * r : r) * sourceStride;
            var lower = decimate ? Math.min(2 * r + 1, height - 1) * sourceStride : upper;
            var dst = origin + r * stride;
            for (var x = 0; x < w; x++) {
                int v;
                if (source16 != null) {
                    v = (source16[upper + x] + source16[lower + x] + 1) >> 1;
                } else {
                    v = ((source8[upper + x] & 0xFF) + (source8[lower + x] & 0xFF) + 1) >> 1 << copyShift;
                }
                p16[dst + x] = (short) v;
                p[dst + x] = (byte) proxy(v, shift);
            }
        }
        Planes.extendSides(p16, row, stride, w, end - first, border, stride - border - w);
        Planes.extendSides(p, row, stride, w, end - first, border, stride - border - w);
    }

    /**
     * Repeats one plane's first extended row into its top border.
     *
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     */
    private void extendTop(int plane) {
        var stride = strides[plane];
        var start = origins[plane] - borders[plane];
        Planes.extendTop(planes[plane], start, stride, stride, borders[plane]);
        if (bitDepth > 8) {
            Planes.extendTop(planes16[plane], start, stride, stride, borders[plane]);
        }
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
        var start = origins[plane] + (h - 1) * stride - borders[plane];
        var rows = alignedHeights[plane] + borders[plane] - h;
        Planes.extendBottom(planes[plane], start, stride, stride, rows);
        if (bitDepth > 8) {
            Planes.extendBottom(planes16[plane], start, stride, stride, rows);
        }
    }

    /**
     * Extends the border of the 8-bit luma plane from its visible samples, after they were
     * written in place.
     */
    void extendLumaBorders() {
        var stride = strides[0];
        Planes.extendSides(planes[0], origins[0], stride, cropWidths[0], cropHeights[0], borders[0],
                stride - borders[0] - cropWidths[0]);
        var start = origins[0] - borders[0];
        Planes.extendTop(planes[0], start, stride, stride, borders[0]);
        var bottom = origins[0] + (cropHeights[0] - 1) * stride - borders[0];
        Planes.extendBottom(planes[0], bottom, stride, stride, alignedHeights[0] + borders[0] - cropHeights[0]);
    }

    /**
     * Extends the border of every plane from its visible samples, after they were written in
     * place; a high bit depth picture first derives its proxies from its samples.
     */
    void extendBorders() {
        for (var plane = 0; plane < 3; plane++) {
            var stride = strides[plane];
            var origin = origins[plane];
            var w = cropWidths[plane];
            var h = cropHeights[plane];
            var border = borders[plane];
            if (bitDepth > 8) {
                var p = planes[plane];
                var p16 = planes16[plane];
                var shift = bitDepth - 8;
                for (var r = 0; r < h; r++) {
                    var row = origin + r * stride;
                    for (var x = 0; x < w; x++) {
                        p[row + x] = (byte) proxy(p16[row + x], shift);
                    }
                }
                Planes.extendSides(p16, origin, stride, w, h, border, stride - border - w);
            }
            Planes.extendSides(planes[plane], origin, stride, w, h, border, stride - border - w);
            extendTop(plane);
            extendBottom(plane);
        }
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
