package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.plane.Planes;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Holds one decoded VP8 frame: three planes sized to whole macroblocks and surrounded by a border
 * of replicated edge pixels that lets motion compensation read outside the frame.
 *
 * <p>The luma plane is {@code 16 * mbCols} by {@code 16 * mbRows} samples with a border of
 * {@link #LUMA_BORDER} samples on every side; each chroma plane is half that size with a border of
 * {@link #CHROMA_BORDER}. Sample {@code (x, y)} of the luma plane is at
 * {@code yOrigin + y * yStride + x}, where {@code x} and {@code y} may be negative inside the border.
 */
final class FrameBuffer {
    /**
     * The luma border width in samples.
     */
    static final int LUMA_BORDER = 32;

    /**
     * Reads and writes eight bytes of a byte array as a little-endian {@code long}.
     */
    private static final VarHandle LONG = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Replicates a byte into every byte of a {@code long}.
     */
    private static final long SPLAT = 0x0101010101010101L;

    /**
     * The chroma border width in samples.
     */
    static final int CHROMA_BORDER = 16;

    /**
     * The luma plane width in samples, a multiple of 16.
     */
    final int width16;

    /**
     * The luma plane height in samples, a multiple of 16.
     */
    final int height16;

    /**
     * The luma row stride.
     */
    final int yStride;

    /**
     * The chroma row stride.
     */
    final int uvStride;

    /**
     * The index of luma sample {@code (0, 0)}.
     */
    final int yOrigin;

    /**
     * The index of chroma sample {@code (0, 0)} in both chroma planes.
     */
    final int uvOrigin;

    /**
     * The luma plane including its border.
     */
    final byte[] y;

    /**
     * The Cb plane including its border.
     */
    final byte[] u;

    /**
     * The Cr plane including its border.
     */
    final byte[] v;

    /**
     * Allocates a frame of the given size in macroblocks.
     *
     * @param mbCols the width in macroblocks; positive
     * @param mbRows the height in macroblocks; positive
     * @param limits the bounds checked before allocating
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if a plane exceeds the
     *                                                                        allocation limit
     */
    FrameBuffer(int mbCols, int mbRows, MediaLimits limits) {
        this.width16 = mbCols * 16;
        this.height16 = mbRows * 16;
        this.yStride = width16 + 2 * LUMA_BORDER;
        this.uvStride = width16 / 2 + 2 * CHROMA_BORDER;
        var ySize = (long) yStride * (height16 + 2 * LUMA_BORDER);
        var uvSize = (long) uvStride * (height16 / 2 + 2 * CHROMA_BORDER);
        LimitChecks.checkAllocation(limits, ySize);
        LimitChecks.checkAllocation(limits, uvSize);
        this.y = new byte[(int) ySize];
        this.u = new byte[(int) uvSize];
        this.v = new byte[(int) uvSize];
        this.yOrigin = LUMA_BORDER * yStride + LUMA_BORDER;
        this.uvOrigin = CHROMA_BORDER * uvStride + CHROMA_BORDER;
    }

    /**
     * Creates a view of planes the caller owns, without borders.
     *
     * @param y        the luma plane
     * @param yStride  the luma row stride
     * @param u        the Cb plane
     * @param v        the Cr plane
     * @param uvStride the chroma row stride
     * @param mbCols   the number of macroblock columns the planes cover
     * @param mbRows   the number of macroblock rows the planes cover
     */
    private FrameBuffer(byte[] y, int yStride, byte[] u, byte[] v, int uvStride, int mbCols, int mbRows) {
        this.width16 = mbCols * 16;
        this.height16 = mbRows * 16;
        this.yStride = yStride;
        this.uvStride = uvStride;
        this.y = y;
        this.u = u;
        this.v = v;
        this.yOrigin = 0;
        this.uvOrigin = 0;
    }

    /**
     * Returns a view of planes the caller owns that cover whole macroblocks, without borders: a
     * frame that can be read inside its macroblocks but not predicted from, filtered or extended.
     *
     * @param y        the luma plane, at least {@code 16 * mbRows} rows of {@code 16 * mbCols}
     *                 samples
     * @param yStride  the luma row stride
     * @param u        the Cb plane, at least {@code 8 * mbRows} rows of {@code 8 * mbCols} samples
     * @param v        the Cr plane, like {@code u}
     * @param uvStride the chroma row stride
     * @param mbCols   the number of macroblock columns
     * @param mbRows   the number of macroblock rows
     * @return the view
     */
    static FrameBuffer wrap(byte[] y, int yStride, byte[] u, byte[] v, int uvStride, int mbCols, int mbRows) {
        return new FrameBuffer(y, yStride, u, v, uvStride, mbCols, mbRows);
    }

    /**
     * Returns the total number of bytes a frame of the given size occupies.
     *
     * @param mbCols the width in macroblocks
     * @param mbRows the height in macroblocks
     * @return the size of the three planes in bytes
     */
    static long bytesFor(int mbCols, int mbRows) {
        var y = (long) (mbCols * 16 + 2 * LUMA_BORDER) * (mbRows * 16 + 2 * LUMA_BORDER);
        var uv = (long) (mbCols * 8 + 2 * CHROMA_BORDER) * (mbRows * 8 + 2 * CHROMA_BORDER);
        return y + 2 * uv;
    }

    /**
     * Replicates the first and last samples of the rows of one macroblock row into the left and
     * right borders, in all three planes.
     *
     * @param mbRow the macroblock row
     */
    void extendRowSides(int mbRow) {
        extendSides(y, yOrigin + mbRow * 16 * yStride, yStride, width16, 16, LUMA_BORDER);
        var uvOff = uvOrigin + mbRow * 8 * uvStride;
        extendSides(u, uvOff, uvStride, width16 / 2, 8, CHROMA_BORDER);
        extendSides(v, uvOff, uvStride, width16 / 2, 8, CHROMA_BORDER);
    }

    /**
     * Replicates the first and last samples of a run of rows into the side borders.
     *
     * @param p      the plane
     * @param off    the index of the first sample of the first row
     * @param stride the row stride
     * @param width  the plane width
     * @param rows   the number of rows
     * @param border the border width, a multiple of 8
     */
    private static void extendSides(byte[] p, int off, int stride, int width, int rows, int border) {
        for (var r = 0; r < rows; r++, off += stride) {
            var left = (p[off] & 0xFFL) * SPLAT;
            var right = (p[off + width - 1] & 0xFFL) * SPLAT;
            for (var i = 0; i < border; i += 8) {
                LONG.set(p, off - border + i, left);
                LONG.set(p, off + width + i, right);
            }
        }
    }

    /**
     * Replicates the first and last rows of every plane, including their side borders, into the
     * top and bottom borders.
     */
    void extendTopBottom() {
        extendTopBottom(y, yOrigin - LUMA_BORDER, yStride, height16, LUMA_BORDER);
        extendTopBottom(u, uvOrigin - CHROMA_BORDER, uvStride, height16 / 2, CHROMA_BORDER);
        extendTopBottom(v, uvOrigin - CHROMA_BORDER, uvStride, height16 / 2, CHROMA_BORDER);
    }

    /**
     * Replicates the first and last rows of one plane into its top and bottom borders.
     *
     * @param p the plane
     * @param rowStart the index of the first byte of the first frame row, border included
     * @param stride the row stride
     * @param height the plane height
     * @param border the border height
     */
    private static void extendTopBottom(byte[] p, int rowStart, int stride, int height, int border) {
        Planes.extendTop(p, rowStart, stride, stride, border);
        Planes.extendBottom(p, rowStart + (height - 1) * stride, stride, stride, border);
    }

    /**
     * Copies the frame, cropped to a picture size, into a 4:2:0 picture.
     *
     * @param out the picture to fill, at least {@code width} by {@code height}
     * @param width the picture width, at most {@link #width16}
     * @param height the picture height, at most {@link #height16}
     */
    void copyTo(YuvPicture out, int width, int height) {
        copyRowsTo(out, width, height, 0, height);
    }

    /**
     * Copies luma lines {@code [top, bottom)} of the frame, and the chroma lines they cover,
     * cropped to a picture size, into a 4:2:0 picture.
     *
     * @param out the picture to fill, at least {@code width} by {@code height}
     * @param width the picture width, at most {@link #width16}
     * @param height the picture height, at most {@link #height16}
     * @param top the first luma line, even
     * @param bottom the luma line after the last, at most {@code height}, even unless it is
     * {@code height}
     */
    void copyRowsTo(YuvPicture out, int width, int height, int top, int bottom) {
        var yStrideOut = out.yStride();
        Planes.copy(y, yOrigin + top * yStride, yStride, out.y(), top * yStrideOut, yStrideOut, width,
                bottom - top);
        var chromaTop = top >> 1;
        var chromaBottom = bottom == height ? (height + 1) >> 1 : bottom >> 1;
        var chromaWidth = (width + 1) >> 1;
        var uvStrideOut = out.uvStride();
        var uvOff = uvOrigin + chromaTop * uvStride;
        Planes.copy(u, uvOff, uvStride, out.u(), chromaTop * uvStrideOut, uvStrideOut, chromaWidth,
                chromaBottom - chromaTop);
        Planes.copy(v, uvOff, uvStride, out.v(), chromaTop * uvStrideOut, uvStrideOut, chromaWidth,
                chromaBottom - chromaTop);
    }

    /**
     * Prepares the borders read by intra prediction before a frame is decoded: the row above the
     * frame becomes 127 (from one sample left of the frame to four samples right of it).
     */
    void setupIntraTopBorder() {
        var off = yOrigin - yStride - 1;
        Arrays.fill(y, off, off + width16 + 5, (byte) 127);
        var uvOff = uvOrigin - uvStride - 1;
        Arrays.fill(u, uvOff, uvOff + width16 / 2 + 5, (byte) 127);
        Arrays.fill(v, uvOff, uvOff + width16 / 2 + 5, (byte) 127);
    }

    /**
     * Sets the column left of one macroblock row to 129 in all planes, as intra prediction
     * expects.
     *
     * @param mbRow the macroblock row
     */
    void setupIntraLeftBorder(int mbRow) {
        var off = yOrigin + mbRow * 16 * yStride - 1;
        for (var i = 0; i < 16; i++) {
            y[off + i * yStride] = (byte) 129;
        }
        var uvOff = uvOrigin + mbRow * 8 * uvStride - 1;
        for (var i = 0; i < 8; i++) {
            u[uvOff + i * uvStride] = (byte) 129;
            v[uvOff + i * uvStride] = (byte) 129;
        }
    }

    /**
     * Replicates the last sample of the two bottom rows of a macroblock row four samples into
     * the right border, providing the above-right samples of the next row's last macroblock.
     *
     * @param mbRow the macroblock row
     */
    void extendRowAboveRight(int mbRow) {
        var yOff = yOrigin + (mbRow * 16 + 14) * yStride + width16;
        var uvOff = uvOrigin + (mbRow * 8 + 6) * uvStride + width16 / 2;
        for (var r = 0; r < 2; r++) {
            var yv = y[yOff - 1];
            var uv = u[uvOff - 1];
            var vv = v[uvOff - 1];
            for (var i = 0; i < 4; i++) {
                y[yOff + i] = yv;
                u[uvOff + i] = uv;
                v[uvOff + i] = vv;
            }
            yOff += yStride;
            uvOff += uvStride;
        }
    }
}
