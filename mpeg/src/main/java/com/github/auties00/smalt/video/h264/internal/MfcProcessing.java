package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.image.YuvPicture;

/**
 * Implements the frame-compatible stereo processing of the MFC High profile (ITU-T H.264 Annex G): the
 * reference processing that turns a base view component into the inter-view prediction reference of the
 * enhancement view (G.8.4.1), and the reconstruction of the left and right views at enhanced resolution from
 * the two decoded view components (G.8.6, informative).
 *
 * <p>Samples are 8-bit. A plane is addressed by an array, the index of its first sample, and the distance
 * between its rows, which for a field is twice the distance between the rows of its frame. Arguments are not
 * checked; values outside the documented ranges give unspecified results.
 */
public final class MfcProcessing {
    private MfcProcessing() {
    }

    /**
     * Reconstructs the left and right views of an access unit at enhanced resolution (ITU-T H.264 G.8.6)
     * from its cropped base view and enhancement view pictures, processing the fields of each picture
     * separately when the extension's {@code rpu_field_processing_flag} is set.
     *
     * @param base the cropped 8-bit 4:2:0 picture of the base view component
     * @param enhancement the cropped picture of the enhancement view component, of the same size
     * @param left receives the left view, of the same size
     * @param right receives the right view, of the same size
     * @param extension the MFC extension of the stream
     * @param workspace the reusable buffers of the reconstruction
     */
    public static void reconstruct(YuvPicture base, YuvPicture enhancement, YuvPicture left, YuvPicture right,
                                   H264MvcExtension extension, Workspace workspace) {
        for (var plane = 0; plane < 3; plane++) {
            var b = plane == 0 ? base.y() : plane == 1 ? base.u() : base.v();
            var e = plane == 0 ? enhancement.y() : plane == 1 ? enhancement.u() : enhancement.v();
            var l = plane == 0 ? left.y() : plane == 1 ? left.u() : left.v();
            var r = plane == 0 ? right.y() : plane == 1 ? right.u() : right.v();
            var bStride = plane == 0 ? base.yStride() : base.uvStride();
            var eStride = plane == 0 ? enhancement.yStride() : enhancement.uvStride();
            var oStride = plane == 0 ? left.yStride() : left.uvStride();
            var width = plane == 0 ? base.width() : base.chromaWidth();
            var height = plane == 0 ? base.height() : base.chromaHeight();
            var fields = extension.rpuFieldProcessing ? 2 : 1;
            var rows = height / fields;
            workspace.ensure(width * rows);
            for (var parity = 0; parity < fields; parity++) {
                processReference(b, parity * bStride, bStride * fields, workspace.reference, 0, width, width, rows,
                        0, 0, 0, 0, extension, workspace.scratch);
                reconstruct(b, parity * bStride, bStride * fields, e, parity * eStride, eStride * fields,
                        workspace.reference, 0, width, l, parity * oStride, r, parity * oStride, oStride * fields,
                        width, rows, extension, workspace.scratch);
            }
        }
    }

    /**
     * The reusable buffers of {@link #reconstruct(YuvPicture, YuvPicture, YuvPicture, YuvPicture,
     * H264MvcExtension, Workspace)}.
     *
     * <p>Instances are not thread-safe.
     */
    public static final class Workspace {
        private byte[] reference = new byte[0];

        private int[] scratch = new int[0];

        /**
         * Grows the buffers to hold a plane of a number of samples.
         *
         * @param samples the sample count of the largest plane processed
         */
        private void ensure(int samples) {
            if (reference.length < samples) {
                reference = new byte[samples];
                scratch = new int[2 * samples];
            }
        }
    }

    /**
     * Applies the reference processing of ITU-T H.264 G.8.4.1 to one color plane of a frame or field.
     *
     * <p>The active area, the plane without its cropping offsets, is converted from the frame packing of the
     * base view (side by side for {@code mfc_format_idc} 0, top and bottom for 1) to that of the enhancement
     * view by a downsampling filter across the packing direction followed by an upsampling filter along it;
     * the samples outside the active area repeat its edges. Without the filter every sample is 128.
     *
     * @param src the samples of the base view component
     * @param srcOrigin the index of the first sample of {@code src}
     * @param srcStride the distance between rows of {@code src}
     * @param dst the samples of the processed reference
     * @param dstOrigin the index of the first sample of {@code dst}
     * @param dstStride the distance between rows of {@code dst}
     * @param width the plane width ({@code refW})
     * @param height the plane height ({@code refH})
     * @param left the left cropping offset of the plane, in samples
     * @param right the right cropping offset of the plane, in samples
     * @param top the top cropping offset of the plane, in rows of the frame or field
     * @param bottom the bottom cropping offset of the plane, in rows of the frame or field
     * @param extension the MFC extension, giving {@code mfc_format_idc}, the grid positions and whether the
     * filter is enabled
     * @param scratch a buffer of at least {@code width * height} entries
     */
    public static void processReference(byte[] src, int srcOrigin, int srcStride, byte[] dst, int dstOrigin,
                                 int dstStride, int width, int height, int left, int right, int top, int bottom,
                                 H264MvcExtension extension, int[] scratch) {
        if (!extension.rpuFilterEnabled) {
            for (var y = 0; y < height; y++) {
                java.util.Arrays.fill(dst, dstOrigin + y * dstStride, dstOrigin + y * dstStride + width,
                        (byte) 128);
            }
            return;
        }
        var rpuW = width - left - right;
        var rpuH = height - top - bottom;
        var sbsV = rpuW >> 1;
        var tabV = rpuH >> 1;
        var grid = extension.gridPositions;
        var in = srcOrigin + top * srcStride + left;
        var out = dstOrigin + top * dstStride + left;
        if (extension.mfcFormatIdc == 0) {
            for (var view = 0; view < 2; view++) {
                var offset = grid[2 * view] == 12 ? 1 : 0;
                var from = in + view * sbsV;
                for (var y = 0; y < tabV; y++) {
                    var row = y * sbsV;
                    for (var x = 0; x < sbsV; x++) {
                        var column = from + x;
                        scratch[row + x] = clip1((4 * u(src, column + clamp(2 * y - 2, rpuH) * srcStride)
                                + 7 * u(src, column + clamp(2 * y - 1, rpuH) * srcStride)
                                + 10 * u(src, column + clamp(2 * y, rpuH) * srcStride)
                                + 7 * u(src, column + clamp(2 * y + 1, rpuH) * srcStride)
                                + 4 * u(src, column + clamp(2 * y + 2, rpuH) * srcStride) + 32) >> 6);
                    }
                }
                var gMin = -2 - offset;
                for (var y = 0; y < tabV; y++) {
                    var row = y * sbsV;
                    var target = out + (y + view * tabV) * dstStride;
                    for (var x = 0; x < sbsV; x++) {
                        dst[target + 2 * x + offset] = (byte) scratch[row + x];
                        dst[target + 2 * x + 1 - offset] = (byte) clip1((3 * scratch[row + clamp(x + gMin, sbsV)]
                                - 17 * scratch[row + clamp(x + gMin + 1, sbsV)]
                                + 78 * scratch[row + clamp(x + gMin + 2, sbsV)]
                                + 78 * scratch[row + clamp(x + gMin + 3, sbsV)]
                                - 17 * scratch[row + clamp(x + gMin + 4, sbsV)]
                                + 3 * scratch[row + clamp(x + gMin + 5, sbsV)] + 64) >> 7);
                    }
                }
            }
        } else {
            for (var view = 0; view < 2; view++) {
                var offset = grid[2 * view + 1] == 12 ? 1 : 0;
                var from = in + view * tabV * srcStride;
                for (var y = 0; y < tabV; y++) {
                    var row = y * sbsV;
                    var source = from + y * srcStride;
                    for (var x = 0; x < sbsV; x++) {
                        scratch[row + x] = clip1((4 * u(src, source + clamp(2 * x - 2, rpuW))
                                + 7 * u(src, source + clamp(2 * x - 1, rpuW))
                                + 10 * u(src, source + clamp(2 * x, rpuW))
                                + 7 * u(src, source + clamp(2 * x + 1, rpuW))
                                + 4 * u(src, source + clamp(2 * x + 2, rpuW)) + 32) >> 6);
                    }
                }
                var gMin = -2 - offset;
                var target = out + view * sbsV;
                for (var y = 0; y < tabV; y++) {
                    var even = target + (2 * y + offset) * dstStride;
                    var odd = target + (2 * y + 1 - offset) * dstStride;
                    for (var x = 0; x < sbsV; x++) {
                        dst[even + x] = (byte) scratch[y * sbsV + x];
                        dst[odd + x] = (byte) clip1((3 * scratch[clamp(y + gMin, tabV) * sbsV + x]
                                - 17 * scratch[clamp(y + gMin + 1, tabV) * sbsV + x]
                                + 78 * scratch[clamp(y + gMin + 2, tabV) * sbsV + x]
                                + 78 * scratch[clamp(y + gMin + 3, tabV) * sbsV + x]
                                - 17 * scratch[clamp(y + gMin + 4, tabV) * sbsV + x]
                                + 3 * scratch[clamp(y + gMin + 5, tabV) * sbsV + x] + 64) >> 7);
                    }
                }
            }
        }
        for (var y = top; y < top + rpuH; y++) {
            var row = dstOrigin + y * dstStride;
            java.util.Arrays.fill(dst, row, row + left, dst[row + left]);
            java.util.Arrays.fill(dst, row + left + rpuW, row + width, dst[row + left + rpuW - 1]);
        }
        for (var y = 0; y < top; y++) {
            System.arraycopy(dst, dstOrigin + top * dstStride, dst, dstOrigin + y * dstStride, width);
        }
        for (var y = top + rpuH; y < height; y++) {
            System.arraycopy(dst, dstOrigin + (top + rpuH - 1) * dstStride, dst, dstOrigin + y * dstStride, width);
        }
    }

    /**
     * Reconstructs one color plane of the left and right views at enhanced resolution (ITU-T H.264 G.8.6):
     * each view is the base view component's half upsampled along the packing direction, plus the difference
     * between the enhancement view component and the processed reference upsampled across it.
     *
     * <p>Every plane is the cropped active area of {@code width} by {@code height} samples; {@code rpu} is the
     * active area of the reference processing of {@code base}.
     *
     * @param base the samples of the cropped base view component
     * @param baseOrigin the index of the first sample of {@code base}
     * @param baseStride the distance between rows of {@code base}
     * @param enhancement the samples of the cropped enhancement view component
     * @param enhancementOrigin the index of the first sample of {@code enhancement}
     * @param enhancementStride the distance between rows of {@code enhancement}
     * @param rpu the samples of the cropped processed reference
     * @param rpuOrigin the index of the first sample of {@code rpu}
     * @param rpuStride the distance between rows of {@code rpu}
     * @param leftView the samples of the left view, written
     * @param leftOrigin the index of the first sample of {@code leftView}
     * @param rightView the samples of the right view, written
     * @param rightOrigin the index of the first sample of {@code rightView}
     * @param outStride the distance between rows of {@code leftView} and {@code rightView}
     * @param width the width of the active area ({@code RpuW})
     * @param height the height of the active area ({@code RpuH})
     * @param extension the MFC extension, giving {@code mfc_format_idc} and the grid positions
     * @param scratch a buffer of at least {@code 2 * width * height} entries
     */
    static void reconstruct(byte[] base, int baseOrigin, int baseStride, byte[] enhancement, int enhancementOrigin,
                            int enhancementStride, byte[] rpu, int rpuOrigin, int rpuStride, byte[] leftView,
                            int leftOrigin, byte[] rightView, int rightOrigin, int outStride, int width, int height,
                            H264MvcExtension extension, int[] scratch) {
        var sbsV = width >> 1;
        var tabV = height >> 1;
        var grid = extension.gridPositions;
        var upBase = 0;
        var upRes = width * height;
        for (var view = 0; view < 2; view++) {
            var target = view == 0 ? leftView : rightView;
            var targetOrigin = view == 0 ? leftOrigin : rightOrigin;
            if (extension.mfcFormatIdc == 0) {
                var offset = grid[2 * view] == 12 ? 1 : 0;
                var gMin = -2 - offset;
                var from = baseOrigin + view * sbsV;
                for (var y = 0; y < height; y++) {
                    var source = from + y * baseStride;
                    var row = upBase + y * width;
                    for (var x = 0; x < sbsV; x++) {
                        scratch[row + 2 * x + offset] = u(base, source + x);
                        scratch[row + 2 * x + 1 - offset] = clip1((3 * u(base, source + clamp(x + gMin, sbsV))
                                - 17 * u(base, source + clamp(x + gMin + 1, sbsV))
                                + 78 * u(base, source + clamp(x + gMin + 2, sbsV))
                                + 78 * u(base, source + clamp(x + gMin + 3, sbsV))
                                - 17 * u(base, source + clamp(x + gMin + 4, sbsV))
                                + 3 * u(base, source + clamp(x + gMin + 5, sbsV)) + 64) >> 7);
                    }
                }
                var enh = enhancementOrigin + view * tabV * enhancementStride;
                var ref = rpuOrigin + view * tabV * rpuStride;
                for (var y = 0; y < tabV; y++) {
                    for (var x = 0; x < width; x++) {
                        scratch[upRes + 2 * y * width + x] = residual(enhancement, enh + y * enhancementStride + x,
                                rpu, ref + y * rpuStride + x);
                    }
                }
                for (var y = 0; y < tabV; y++) {
                    for (var x = 0; x < width; x++) {
                        scratch[upRes + (2 * y + 1) * width + x] = clip3(-128, 127,
                                (3 * scratch[upRes + 2 * clamp(y - 2, tabV) * width + x]
                                        - 17 * scratch[upRes + 2 * clamp(y - 1, tabV) * width + x]
                                        + 78 * scratch[upRes + 2 * clamp(y, tabV) * width + x]
                                        + 78 * scratch[upRes + 2 * clamp(y + 1, tabV) * width + x]
                                        - 17 * scratch[upRes + 2 * clamp(y + 2, tabV) * width + x]
                                        + 3 * scratch[upRes + 2 * clamp(y + 3, tabV) * width + x] + 64) >> 7);
                    }
                }
            } else {
                var offset = grid[2 * view + 1] == 12 ? 1 : 0;
                var gMin = -2 - offset;
                var from = baseOrigin + view * tabV * baseStride;
                for (var y = 0; y < tabV; y++) {
                    for (var x = 0; x < width; x++) {
                        var column = from + x;
                        scratch[upBase + (2 * y + offset) * width + x] = u(base, column + y * baseStride);
                        scratch[upBase + (2 * y + 1 - offset) * width + x] = clip1(
                                (3 * u(base, column + clamp(y + gMin, tabV) * baseStride)
                                        - 17 * u(base, column + clamp(y + gMin + 1, tabV) * baseStride)
                                        + 78 * u(base, column + clamp(y + gMin + 2, tabV) * baseStride)
                                        + 78 * u(base, column + clamp(y + gMin + 3, tabV) * baseStride)
                                        - 17 * u(base, column + clamp(y + gMin + 4, tabV) * baseStride)
                                        + 3 * u(base, column + clamp(y + gMin + 5, tabV) * baseStride) + 64) >> 7);
                    }
                }
                var enh = enhancementOrigin + view * sbsV;
                var ref = rpuOrigin + view * sbsV;
                for (var y = 0; y < height; y++) {
                    var row = upRes + y * width;
                    for (var x = 0; x < sbsV; x++) {
                        scratch[row + 2 * x] = residual(enhancement, enh + y * enhancementStride + x, rpu,
                                ref + y * rpuStride + x);
                    }
                    for (var x = 0; x < sbsV; x++) {
                        scratch[row + 2 * x + 1] = clip3(-128, 127, (3 * scratch[row + 2 * clamp(x - 2, sbsV)]
                                - 17 * scratch[row + 2 * clamp(x - 1, sbsV)]
                                + 78 * scratch[row + 2 * clamp(x, sbsV)]
                                + 78 * scratch[row + 2 * clamp(x + 1, sbsV)]
                                - 17 * scratch[row + 2 * clamp(x + 2, sbsV)]
                                + 3 * scratch[row + 2 * clamp(x + 3, sbsV)] + 64) >> 7);
                    }
                }
            }
            for (var y = 0; y < height; y++) {
                var row = y * width;
                var out = targetOrigin + y * outStride;
                for (var x = 0; x < width; x++) {
                    target[out + x] = (byte) clip1(scratch[upBase + row + x] + scratch[upRes + row + x]);
                }
            }
        }
    }

    private static int residual(byte[] enhancement, int at, byte[] rpu, int rpuAt) {
        return clip3(-128, 127, u(enhancement, at) - u(rpu, rpuAt));
    }

    private static int u(byte[] plane, int at) {
        return plane[at] & 0xFF;
    }

    private static int clamp(int value, int size) {
        return value < 0 ? 0 : Math.min(value, size - 1);
    }

    private static int clip1(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    private static int clip3(int low, int high, int value) {
        return value < low ? low : Math.min(value, high);
    }
}
