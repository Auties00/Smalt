package com.github.auties00.smalt.internal.plane;

import java.util.Arrays;

/**
 * Copies strided sample planes and replicates their edges into the borders around them.
 *
 * <p>A plane is an array with a first sample index and a row stride; rows may be wider than the
 * samples copied. Every method moves samples only, so its result is bit-exact by construction.
 * This class is thread-safe. Arguments are not checked beyond the array bounds checks; values
 * outside the documented ranges give unspecified results.
 */
public final class Planes {
    /**
     * Prevents instantiation.
     */
    private Planes() {
        throw new AssertionError();
    }

    /**
     * Copies a rectangle of 8-bit samples, in one array copy when both planes are contiguous.
     *
     * @param src the source samples
     * @param srcOffset the index of the first sample of the first source row
     * @param srcStride the source row stride, at least {@code width}
     * @param dst the destination samples, which may be {@code src} when the rectangles do not
     * overlap
     * @param dstOffset the index of the first sample of the first destination row
     * @param dstStride the destination row stride, at least {@code width}
     * @param width the number of samples per row
     * @param height the number of rows
     */
    public static void copy(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                            int width, int height) {
        if (srcStride == width && dstStride == width) {
            System.arraycopy(src, srcOffset, dst, dstOffset, width * height);
            return;
        }
        for (var row = 0; row < height; row++) {
            System.arraycopy(src, srcOffset + row * srcStride, dst, dstOffset + row * dstStride, width);
        }
    }

    /**
     * Copies a rectangle of 16-bit samples, in one array copy when both planes are contiguous.
     *
     * @param src the source samples
     * @param srcOffset the index of the first sample of the first source row
     * @param srcStride the source row stride, at least {@code width}
     * @param dst the destination samples, which may be {@code src} when the rectangles do not
     * overlap
     * @param dstOffset the index of the first sample of the first destination row
     * @param dstStride the destination row stride, at least {@code width}
     * @param width the number of samples per row
     * @param height the number of rows
     */
    public static void copy(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride,
                            int width, int height) {
        if (srcStride == width && dstStride == width) {
            System.arraycopy(src, srcOffset, dst, dstOffset, width * height);
            return;
        }
        for (var row = 0; row < height; row++) {
            System.arraycopy(src, srcOffset + row * srcStride, dst, dstOffset + row * dstStride, width);
        }
    }

    /**
     * Replicates the first and last sample of each row of an 8-bit plane into the borders left and
     * right of it.
     *
     * @param plane the samples, edited in place
     * @param offset the index of the first sample of the first row
     * @param stride the row stride
     * @param width the number of samples per row, positive
     * @param rows the number of rows
     * @param left the number of border samples left of each row
     * @param right the number of border samples right of each row
     */
    public static void extendSides(byte[] plane, int offset, int stride, int width, int rows, int left, int right) {
        for (var row = 0; row < rows; row++, offset += stride) {
            Arrays.fill(plane, offset - left, offset, plane[offset]);
            var end = offset + width;
            Arrays.fill(plane, end, end + right, plane[end - 1]);
        }
    }

    /**
     * Replicates the first and last sample of each row of a 16-bit plane into the borders left and
     * right of it.
     *
     * @param plane the samples, edited in place
     * @param offset the index of the first sample of the first row
     * @param stride the row stride
     * @param width the number of samples per row, positive
     * @param rows the number of rows
     * @param left the number of border samples left of each row
     * @param right the number of border samples right of each row
     */
    public static void extendSides(short[] plane, int offset, int stride, int width, int rows, int left, int right) {
        for (var row = 0; row < rows; row++, offset += stride) {
            Arrays.fill(plane, offset - left, offset, plane[offset]);
            var end = offset + width;
            Arrays.fill(plane, end, end + right, plane[end - 1]);
        }
    }

    /**
     * Replicates one row of an 8-bit plane into the rows above it.
     *
     * @param plane the samples, edited in place
     * @param rowStart the index of the first sample of the row to replicate, border included when
     * the border is to be filled too
     * @param stride the row stride
     * @param length the number of samples of the row to replicate, at most {@code stride}
     * @param rows the number of rows above to fill
     */
    public static void extendTop(byte[] plane, int rowStart, int stride, int length, int rows) {
        for (var row = 1; row <= rows; row++) {
            System.arraycopy(plane, rowStart, plane, rowStart - row * stride, length);
        }
    }

    /**
     * Replicates one row of a 16-bit plane into the rows above it.
     *
     * @param plane the samples, edited in place
     * @param rowStart the index of the first sample of the row to replicate, border included when
     * the border is to be filled too
     * @param stride the row stride
     * @param length the number of samples of the row to replicate, at most {@code stride}
     * @param rows the number of rows above to fill
     */
    public static void extendTop(short[] plane, int rowStart, int stride, int length, int rows) {
        for (var row = 1; row <= rows; row++) {
            System.arraycopy(plane, rowStart, plane, rowStart - row * stride, length);
        }
    }

    /**
     * Replicates one row of an 8-bit plane into the rows below it.
     *
     * @param plane the samples, edited in place
     * @param rowStart the index of the first sample of the row to replicate, border included when
     * the border is to be filled too
     * @param stride the row stride
     * @param length the number of samples of the row to replicate, at most {@code stride}
     * @param rows the number of rows below to fill
     */
    public static void extendBottom(byte[] plane, int rowStart, int stride, int length, int rows) {
        for (var row = 1; row <= rows; row++) {
            System.arraycopy(plane, rowStart, plane, rowStart + row * stride, length);
        }
    }

    /**
     * Replicates one row of a 16-bit plane into the rows below it.
     *
     * @param plane the samples, edited in place
     * @param rowStart the index of the first sample of the row to replicate, border included when
     * the border is to be filled too
     * @param stride the row stride
     * @param length the number of samples of the row to replicate, at most {@code stride}
     * @param rows the number of rows below to fill
     */
    public static void extendBottom(short[] plane, int rowStart, int stride, int length, int rows) {
        for (var row = 1; row <= rows; row++) {
            System.arraycopy(plane, rowStart, plane, rowStart + row * stride, length);
        }
    }
}
