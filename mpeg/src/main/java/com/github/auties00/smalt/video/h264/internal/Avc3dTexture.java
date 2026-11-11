/* Ports the depth sampling, disparity derivation and adaptive luminance compensation of the 3D-AVC texture coding
   tools of 3DV-ATM 14.0 (ITU-T H.264.2 (02/2016), ldecod macroblock.c, mc_prediction.c and lcommon mv_prediction.c,
   alc.c). Copyright 2001-2015 International Telecommunication Union; ITU disclaimer of warranty. Modified for
   Smalt. */
package com.github.auties00.smalt.video.h264.internal;

/**
 * The 3D-AVC state of one texture slice (ITU-T H.264 Annex I): which coding tools it uses, the disparity tables
 * that turn depth samples into horizontal disparities, the depth view component its tools sample, and the
 * reference indices of its inter-view references.
 *
 * <p>Every texture slice of a 3D-AVC stream carries one, so that the reference views of its blocks are recorded
 * for the neighboring block disparity derivation of later pictures; only the slices of non-base texture views
 * with {@link #coding} set run the tools. The object is filled on the parsing thread before the slice is
 * decoded and only read afterwards.
 *
 * @implNote This implementation follows 3DV-ATM 14.0, which the 3D-AVC conformance bitstreams of ITU-T H.264.1
 * (02/2016) were produced with, where it departs from the text of ITU-T H.264 (08/2024) Annex I: the depth-based
 * motion vector prediction samples the four corners of the depth block, the view synthesis prediction derives one
 * disparity per 8x8 block and compensates it as inter-view motion, and the adaptive luminance compensation scales
 * each prediction block by the ratio of one-sample templates.
 */
final class Avc3dTexture {
    /**
     * The largest difference between a reference and a decoded template sample that still counts towards the
     * adaptive luminance compensation sums.
     */
    private static final int TEMPLATE_THRESHOLD = 30;

    /**
     * The fixed-point precision of the adaptive luminance compensation factor.
     */
    private static final int FACTOR_PRECISION = 15;

    /**
     * Whether the slice belongs to a non-base texture view of a 3D-AVC stream (profile 139), whose macroblock
     * syntax carries the tool flags.
     */
    boolean coding;

    /**
     * The {@code depth_based_mvp_flag} of the slice.
     */
    boolean dmvp;

    /**
     * The view synthesis prediction flag of the slice ({@code slice_vsp_flag}).
     */
    boolean vsp;

    /**
     * Whether the slice uses adaptive luminance compensation: a P slice of a non-base view in a sequence that
     * enables it.
     */
    boolean alc;

    /**
     * Whether the slice uses run-length skip coding: a B slice of a non-base view in a sequence that enables it.
     */
    boolean rle;

    /**
     * Whether no depth view component of the slice's own view precedes it in its access unit, so the
     * disparities come from the neighboring blocks instead of the view's depth.
     */
    boolean textureFirst;

    /**
     * The {@code view_id} of the slice.
     */
    int viewId;

    /**
     * The {@code view_id} of the base view.
     */
    int baseViewId;

    /**
     * The luma disparity in quarter samples of each depth sample value towards the first list 0 inter-view
     * reference view, or {@code null} without one.
     */
    int[] forward;

    /**
     * The luma disparity towards the first list 1 inter-view reference view, or {@code null} without one.
     */
    int[] backward;

    /**
     * The luma samples of the depth view component the tools sample, copied when the slice was set up, or
     * {@code null} when the slice samples none: the depth of its own view when {@link #textureFirst} is clear,
     * the depth of its view synthesis reference view otherwise.
     */
    byte[] depth;

    /**
     * The row stride of {@link #depth}.
     */
    int depthStride;

    /**
     * The horizontal factor that maps texture to depth sample positions.
     */
    int horMult = 1;

    /**
     * The vertical factor that maps texture to depth sample positions.
     */
    int verMult = 1;

    /**
     * The right shift that follows {@link #horMult}.
     */
    int horRsh;

    /**
     * The right shift that follows {@link #verMult}.
     */
    int verRsh;

    /**
     * The first column of the cropped depth picture.
     */
    int cropLeft;

    /**
     * The last column of the cropped depth picture.
     */
    int cropRight;

    /**
     * The first row of the cropped depth picture.
     */
    int cropTop;

    /**
     * The last row of the cropped depth picture.
     */
    int cropBottom;

    /**
     * The horizontal offset of the slice's view in the depth sampling grid.
     */
    int gridX;

    /**
     * The vertical offset of the slice's view in the depth sampling grid.
     */
    int gridY;

    /**
     * The horizontal grid offset of the view of {@link #depth} in texture-first coding.
     */
    int depthGridX;

    /**
     * The vertical grid offset of the view of {@link #depth} in texture-first coding.
     */
    int depthGridY;

    /**
     * The reference index of the first inter-view reference of each list that view synthesis prediction uses, or
     * -1.
     */
    final int[] vspRef = {-1, -1};

    /**
     * Whether either list has a view synthesis reference.
     */
    boolean vspRefExist;

    /**
     * The reference index of the first list 0 inter-view reference of a P slice, the reference of adaptive
     * luminance compensation, or -1.
     */
    int ivRef = -1;

    /**
     * Whether a list holds an inter-view reference.
     */
    boolean hasInterviewRef;

    /**
     * The base view picture of the access unit in list 0, whose motion the depth-based temporal predictors
     * sample, or {@code null}.
     */
    DecoderFrame interViewMotion;

    /**
     * The base view picture whose motion the depth-based skip and direct predictors sample, or {@code null}.
     */
    DecoderFrame skipMotion;

    /**
     * The state of the texture slices of the base view of a 3D-AVC stream, which use no tool and only record
     * the reference views of their blocks.
     */
    static final Avc3dTexture RECORD = new Avc3dTexture();

    /**
     * Returns the depth column a texture column maps to.
     *
     * @param x the texture column, relative to the grid of the view
     * @param grid the horizontal grid offset of the view
     * @return the depth column, inside the cropped depth picture
     */
    int depthColumn(int x, int grid) {
        return Math.clamp((x + grid) * horMult >> horRsh, cropLeft, cropRight);
    }

    /**
     * Returns the depth row a texture row maps to.
     *
     * @param y the texture row
     * @param grid the vertical grid offset of the view
     * @return the depth row, inside the cropped depth picture
     */
    int depthRow(int y, int grid) {
        return Math.clamp((y + grid) * verMult >> verRsh, cropTop, cropBottom);
    }

    /**
     * Returns the largest depth sample at the four corners a texture block maps to in the slice's own view.
     *
     * @param x the left texture column of the block
     * @param y the top texture row of the block
     * @param w the block width
     * @param h the block height
     * @return the largest corner depth sample
     */
    int cornerMax(int x, int y, int w, int h) {
        var x0 = depthColumn(x, gridX);
        var x1 = depthColumn(x + w - 1, gridX);
        var y0 = depthRow(y, gridY);
        var y1 = depthRow(y + h - 1, gridY);
        var d = depth;
        var s = depthStride;
        var top = Math.max(d[y0 * s + x0] & 0xFF, d[y0 * s + x1] & 0xFF);
        var bottom = Math.max(d[y1 * s + x0] & 0xFF, d[y1 * s + x1] & 0xFF);
        return Math.max(top, bottom);
    }

    /**
     * Returns the disparity of a texture block derived from the largest depth sample at its corners: the
     * depth-based inter-view motion vector predictor, and the disparity of view synthesis prediction, for
     * depth-first coding.
     *
     * @param table the disparity table of the list
     * @param x the left texture column of the block
     * @param y the top texture row of the block
     * @param w the block width
     * @param h the block height
     * @return the horizontal disparity in quarter samples
     */
    int cornerDisparity(int[] table, int x, int y, int w, int h) {
        return table == null ? 0 : table[cornerMax(x, y, w, h)];
    }

    /**
     * Returns the largest depth sample at the four corners of a texture block shifted by a disparity, in the
     * depth of the view synthesis reference view (texture-first coding).
     *
     * @param x the left texture column of the block
     * @param y the top texture row of the block
     * @param w the block width
     * @param h the block height
     * @param disparity the horizontal disparity in quarter samples
     * @return the largest corner depth sample
     */
    int shiftedCornerMax(int x, int y, int w, int h, int disparity) {
        var shift = disparity >> 2;
        var x0 = depthColumn(x + shift, depthGridX);
        var x1 = depthColumn(x + w - 1 + shift, depthGridX);
        var y0 = depthRow(y, depthGridY);
        var y1 = depthRow(y + h - 1, depthGridY);
        var d = depth;
        var s = depthStride;
        var top = Math.max(d[y0 * s + x0] & 0xFF, d[y0 * s + x1] & 0xFF);
        var bottom = Math.max(d[y1 * s + x0] & 0xFF, d[y1 * s + x1] & 0xFF);
        return Math.max(top, bottom);
    }

    /**
     * Returns the disparity table texture-first coding refines disparities with: the forward one when present.
     *
     * @return the table, or {@code null} without inter-view references
     */
    int[] refinementTable() {
        return forward != null ? forward : backward;
    }

    /**
     * Applies adaptive luminance compensation to the luma prediction of one block in place: the prediction is
     * scaled by the ratio of the sums of the decoded samples above and left of the block to the reference
     * samples at the same offset from its motion vector, when both templates lie inside their pictures.
     *
     * @param ref the reference frame
     * @param mv the packed motion vector of the block
     * @param plane the luma plane of the current frame, holding the prediction and the decoded samples
     * @param block the index of the block's top-left luma sample in {@code plane}
     * @param stride the luma row stride
     * @param px the left luma column of the block
     * @param py the top luma row of the block
     * @param w the block width
     * @param h the block height
     * @param width the luma width of the pictures
     * @param height the luma height of the pictures
     */
    static void compensateLuminance(DecoderFrame ref, int mv, byte[] plane, int block, int stride, int px, int py,
                                    int w, int h, int width, int height) {
        var vx = px * 4 + (short) mv - 4;
        var vy = py * 4 + (mv >> 16) - 4;
        if (vx < 0 || vy < 0 || px - 1 < 0 || py - 1 < 0 || vx + (w + 1) * 4 > width * 4
                || vy + (h + 1) * 4 > height * 4) {
            return;
        }
        var refSum = 1;
        var decSum = 1;
        for (var x = 0; x < w; x++) {
            var r = lumaSample(ref, vx + 4 + 4 * x, vy);
            var d = plane[block - stride + x] & 0xFF;
            if (Math.abs(r - d) <= TEMPLATE_THRESHOLD) {
                refSum += r;
                decSum += d;
            }
        }
        for (var y = 0; y < h; y++) {
            var r = lumaSample(ref, vx, vy + 4 + 4 * y);
            var d = plane[block + y * stride - 1] & 0xFF;
            if (Math.abs(r - d) <= TEMPLATE_THRESHOLD) {
                refSum += r;
                decSum += d;
            }
        }
        var factor = (int) (((long) decSum << FACTOR_PRECISION) + (refSum >> 1)) / refSum;
        if (refSum >> 4 == decSum >> 4) {
            return;
        }
        var round = 1 << (FACTOR_PRECISION - 1);
        for (var y = 0; y < h; y++) {
            var row = block + y * stride;
            for (var x = 0; x < w; x++) {
                var value = ((plane[row + x] & 0xFF) * factor + round) >> FACTOR_PRECISION;
                plane[row + x] = (byte) Math.min(value, 255);
            }
        }
    }

    /**
     * Returns the luma sample of a reference frame at a quarter-sample position, interpolated as luma motion
     * compensation does (ITU-T H.264 8.4.2.2.1); positions up to three samples outside the picture read its
     * replicated border.
     *
     * @param ref the reference frame, with its border extended
     * @param x4 the horizontal position in quarter samples
     * @param y4 the vertical position in quarter samples
     * @return the sample
     */
    static int lumaSample(DecoderFrame ref, int x4, int y4) {
        var x = x4 >> 2;
        var y = y4 >> 2;
        var fx = x4 & 3;
        var fy = y4 & 3;
        var p = ref.luma;
        var s = ref.lumaStride;
        var at = ref.lumaOrigin + y * s + x;
        if (fx == 0 && fy == 0) {
            return p[at] & 0xFF;
        }
        if (fy == 0) {
            var b = clip((horizontal(p, at) + 16) >> 5);
            return switch (fx) {
                case 1 -> (p[at] & 0xFF) + b + 1 >> 1;
                case 2 -> b;
                default -> (p[at + 1] & 0xFF) + b + 1 >> 1;
            };
        }
        if (fx == 0) {
            var hh = clip((vertical(p, at, s) + 16) >> 5);
            return switch (fy) {
                case 1 -> (p[at] & 0xFF) + hh + 1 >> 1;
                case 2 -> hh;
                default -> (p[at + s] & 0xFF) + hh + 1 >> 1;
            };
        }
        var j = clip((center(p, at, s) + 512) >> 10);
        if (fx == 2 && fy == 2) {
            return j;
        }
        var b = clip((horizontal(p, at) + 16) >> 5);
        var hh = clip((vertical(p, at, s) + 16) >> 5);
        var m = clip((vertical(p, at + 1, s) + 16) >> 5);
        var sb = clip((horizontal(p, at + s) + 16) >> 5);
        if (fx == 2) {
            return fy == 1 ? b + j + 1 >> 1 : j + sb + 1 >> 1;
        }
        if (fy == 2) {
            return fx == 1 ? hh + j + 1 >> 1 : j + m + 1 >> 1;
        }
        var horizontalHalf = fy == 1 ? b : sb;
        var verticalHalf = fx == 1 ? hh : m;
        return horizontalHalf + verticalHalf + 1 >> 1;
    }

    /**
     * Returns the unrounded horizontal six-tap sum of the half-sample position right of a sample.
     *
     * @param p the plane
     * @param at the index of the sample
     * @return the sum
     */
    private static int horizontal(byte[] p, int at) {
        return (p[at - 2] & 0xFF) - 5 * (p[at - 1] & 0xFF) + 20 * (p[at] & 0xFF) + 20 * (p[at + 1] & 0xFF)
                - 5 * (p[at + 2] & 0xFF) + (p[at + 3] & 0xFF);
    }

    /**
     * Returns the unrounded vertical six-tap sum of the half-sample position below a sample.
     *
     * @param p the plane
     * @param at the index of the sample
     * @param s the row stride
     * @return the sum
     */
    private static int vertical(byte[] p, int at, int s) {
        return (p[at - 2 * s] & 0xFF) - 5 * (p[at - s] & 0xFF) + 20 * (p[at] & 0xFF) + 20 * (p[at + s] & 0xFF)
                - 5 * (p[at + 2 * s] & 0xFF) + (p[at + 3 * s] & 0xFF);
    }

    /**
     * Returns the unrounded six-tap sum of the vertical intermediate horizontal sums, for the central
     * half-sample position below and right of a sample.
     *
     * @param p the plane
     * @param at the index of the sample
     * @param s the row stride
     * @return the sum
     */
    private static int center(byte[] p, int at, int s) {
        return horizontal(p, at - 2 * s) - 5 * horizontal(p, at - s) + 20 * horizontal(p, at)
                + 20 * horizontal(p, at + s) - 5 * horizontal(p, at + 2 * s) + horizontal(p, at + 3 * s);
    }

    /**
     * Clips a value to the 8-bit sample range.
     *
     * @param value a sample computed by an interpolation filter
     * @return the clipped value
     */
    private static int clip(int value) {
        return Math.clamp(value, 0, 255);
    }
}
