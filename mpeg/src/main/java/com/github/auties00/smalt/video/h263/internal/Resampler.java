package com.github.auties00.smalt.video.h263.internal;

/**
 * Resamples reference pictures: the warping of the Reference Picture Resampling mode (ITU-T H.263 P.3) and the
 * interpolation of the reference layer for spatial scalability (O.6).
 */
final class Resampler {
    /**
     * Prevents instantiation.
     */
    private Resampler() {
    }

    /**
     * Warps a reference picture into the prediction reference of a picture (P.3), over the whole coded area of the
     * target.
     *
     * @param reference the reference picture
     * @param target the picture receiving the resampled samples, of the current picture's size
     * @param warping the eight warping parameters in half samples, in the order they are sent
     * @param accuracy the displacement accuracy: 2 or 16
     * @param fillMode the fill mode, 0 to 3 as Table P.1
     * @param fillColor the fill color as Y, Cb and Cr
     * @param rounding the rounding control RCRPR
     */
    static void warp(PlusPicture reference, PlusPicture target, int[] warping, int accuracy, int fillMode,
                     int[] fillColor, int rounding) {
        long h = target.width;
        long v = target.height;
        long hr = reference.width;
        long vr = reference.height;
        var m = ceilLog2(h);
        var n = ceilLog2(v);
        long hv = 1L << m;
        long vv = 1L << n;
        long u00x = 16L * warping[0];
        long u00y = 16L * warping[1];
        long uH0x = 16L * (warping[0] + warping[2] + 2 * (hr - h));
        long uH0y = 16L * (warping[1] + warping[3]);
        long u0Vx = 16L * (warping[0] + warping[4]);
        long u0Vy = 16L * (warping[1] + warping[5] + 2 * (vr - v));
        long uHVx = 16L * (warping[0] + warping[2] + warping[4] + warping[6] + 2 * (hr - h));
        long uHVy = 16L * (warping[1] + warping[3] + warping[5] + warping[7] + 2 * (vr - v));
        long[] corners = {
                u00x, u00y,
                round((h - hv) * u00x + hv * uH0x, h), round((h - hv) * u00y + hv * uH0y, h),
                round((v - vv) * u00x + vv * u0Vx, v), round((v - vv) * u00y + vv * u0Vy, v),
                round((v - vv) * ((h - hv) * u00x + hv * uH0x) + vv * ((h - hv) * u0Vx + hv * uHVx), h * v),
                round((v - vv) * ((h - hv) * u00y + hv * uH0y) + vv * ((h - hv) * u0Vy + hv * uHVy), h * v)
        };
        for (var component = 0; component < 3; component++) {
            var s = component == 0 ? 2 : 1;
            var width = component == 0 ? target.codedWidth : target.codedWidth >> 1;
            var height = component == 0 ? target.codedHeight : target.codedHeight >> 1;
            var stride = component == 0 ? target.lumaStride() : target.chromaStride();
            var maxX = (int) (s * hr / 2 - 1);
            var maxY = (int) (s * vr / 2 - 1);
            var source = reference.plane(component);
            var sourceStride = component == 0 ? reference.lumaStride() : reference.chromaStride();
            var out = target.plane(component);
            var fill = fillMode == 3 ? -1 : fillColor[component];
            long divisor = 64L * hv / accuracy;
            long offset = 32L * hv / accuracy;
            long p2 = (long) accuracy * accuracy;
            for (var j = 0; j < height; j++) {
                long ulx = round((s * vv - 2L * j - 1) * corners[0] + (2L * j + 1) * corners[4], s * vv);
                long uly = round((s * vv - 2L * j - 1) * corners[1] + (2L * j + 1) * corners[5], s * vv);
                long urx = round((s * vv - 2L * j - 1) * corners[2] + (2L * j + 1) * corners[6], s * vv);
                long ury = round((s * vv - 2L * j - 1) * corners[3] + (2L * j + 1) * corners[7], s * vv);
                for (var i = 0; i < width; i++) {
                    long ir = accuracy * (long) i + Math.floorDiv((s * hv - 2L * i - 1) * ulx + (2L * i + 1) * urx
                            + offset, divisor);
                    long jr = accuracy * (long) j + Math.floorDiv((s * hv - 2L * i - 1) * uly + (2L * i + 1) * ury
                            + offset, divisor);
                    var x0 = (int) Math.floorDiv(ir, accuracy);
                    var y0 = (int) Math.floorDiv(jr, accuracy);
                    var px = (int) (ir - (long) x0 * accuracy);
                    var py = (int) (jr - (long) y0 * accuracy);
                    var a = sample(source, sourceStride, x0, y0, maxX, maxY, fill);
                    var b = sample(source, sourceStride, x0 + 1, y0, maxX, maxY, fill);
                    var c = sample(source, sourceStride, x0, y0 + 1, maxX, maxY, fill);
                    var d = sample(source, sourceStride, x0 + 1, y0 + 1, maxX, maxY, fill);
                    long value = (accuracy - py) * ((long) (accuracy - px) * a + (long) px * b)
                            + py * ((long) (accuracy - px) * c + (long) px * d) + p2 / 2 - 1 + rounding;
                    out[j * stride + i] = (byte) (value / p2);
                }
            }
        }
    }

    /**
     * Returns a sample of the reference picture with the fill mode applied outside it.
     *
     * @param plane the samples of one plane of the reference picture
     * @param stride its row stride
     * @param x the column
     * @param y the row
     * @param maxX the last column of the picture
     * @param maxY the last row of the picture
     * @param fill the fill value, or -1 to clip the coordinates
     * @return the sample
     */
    private static int sample(byte[] plane, int stride, int x, int y, int maxX, int maxY, int fill) {
        if (x < 0 || y < 0 || x > maxX || y > maxY) {
            if (fill >= 0) {
                return fill;
            }
            x = x < 0 ? 0 : Math.min(x, maxX);
            y = y < 0 ? 0 : Math.min(y, maxY);
        }
        return plane[y * stride + x] & 0xFF;
    }

    /**
     * Divides rounding the quotient to the nearest integer, half integers away from zero ("//" of P.3).
     *
     * @param numerator the dividend
     * @param denominator the positive denominator
     * @return the quotient
     */
    private static long round(long numerator, long denominator) {
        if (numerator >= 0) {
            return (2 * numerator + denominator) / (2 * denominator);
        }
        return -((-2 * numerator + denominator) / (2 * denominator));
    }

    /**
     * Returns the exponent of the smallest power of two not below a value, at least 1.
     *
     * @param value the number the power of two must reach
     * @return the exponent
     */
    private static int ceilLog2(long value) {
        var exponent = 1;
        while ((1L << exponent) < value) {
            exponent++;
        }
        return exponent;
    }

    /**
     * Interpolates a region of a reference layer picture by two horizontally, vertically or both for the upward
     * prediction of spatial scalability (O.6), replicating the edges of the source region.
     *
     * @param source the plane of the reference layer picture
     * @param sourceStride its row stride
     * @param left the first column of the source region
     * @param top the first row of the source region
     * @param right the last column of the source region
     * @param bottom the last row of the source region
     * @param horizontal whether the picture is interpolated horizontally
     * @param vertical whether the picture is interpolated vertically
     * @param x the first column of the output in enhancement layer samples
     * @param y the first row of the output
     * @param width the output columns
     * @param height the output rows
     * @param out the output
     * @param outOffset the index of the output's first sample
     * @param outStride the output row stride
     */
    static void upsample(byte[] source, int sourceStride, int left, int top, int right, int bottom,
                         boolean horizontal, boolean vertical, int x, int y, int width, int height, int[] out,
                         int outOffset, int outStride) {
        for (var row = 0; row < height; row++) {
            var oy = y + row;
            int y0;
            int y1;
            if (vertical) {
                y0 = oy >> 1;
                y1 = (oy & 1) == 0 ? y0 - 1 : y0 + 1;
            } else {
                y0 = oy;
                y1 = oy;
            }
            y0 = clamp(y0, top, bottom);
            y1 = clamp(y1, top, bottom);
            for (var column = 0; column < width; column++) {
                var ox = x + column;
                int x0;
                int x1;
                if (horizontal) {
                    x0 = ox >> 1;
                    x1 = (ox & 1) == 0 ? x0 - 1 : x0 + 1;
                } else {
                    x0 = ox;
                    x1 = ox;
                }
                x0 = clamp(x0, left, right);
                x1 = clamp(x1, left, right);
                var a = source[y0 * sourceStride + x0] & 0xFF;
                int value;
                if (horizontal && vertical) {
                    var b = source[y0 * sourceStride + x1] & 0xFF;
                    var c = source[y1 * sourceStride + x0] & 0xFF;
                    var d = source[y1 * sourceStride + x1] & 0xFF;
                    value = (9 * a + 3 * b + 3 * c + d + 8) >> 4;
                } else if (horizontal) {
                    value = (3 * a + (source[y0 * sourceStride + x1] & 0xFF) + 2) >> 2;
                } else if (vertical) {
                    value = (3 * a + (source[y1 * sourceStride + x0] & 0xFF) + 2) >> 2;
                } else {
                    value = a;
                }
                out[outOffset + row * outStride + column] = value;
            }
        }
    }

    /**
     * Upsamples an 8 by 8 reduced-resolution prediction error block to 16 by 16 samples (Q.6), interpolating inside
     * the block alone: the edge samples of the source repeat outward, which gives the boundary formulas of Q.6.2.
     *
     * @param source the 64 samples of the block in raster order
     * @param out receives the 256 samples
     */
    static void upsampleResidual(int[] source, int[] out) {
        for (var row = 0; row < 16; row++) {
            var y0 = row >> 1;
            var y1 = clamp((row & 1) == 0 ? y0 - 1 : y0 + 1, 0, 7);
            for (var column = 0; column < 16; column++) {
                var x0 = column >> 1;
                var x1 = clamp((column & 1) == 0 ? x0 - 1 : x0 + 1, 0, 7);
                var a = source[y0 * 8 + x0];
                if (y1 == y0 && x1 == x0) {
                    out[row * 16 + column] = a;
                    continue;
                }
                var b = source[y0 * 8 + x1];
                var c = source[y1 * 8 + x0];
                var d = source[y1 * 8 + x1];
                out[row * 16 + column] = (9 * a + 3 * b + 3 * c + d + 8) / 16;
            }
        }
    }

    /**
     * Clamps a coordinate into a range.
     *
     * @param value the coordinate
     * @param low the lowest value
     * @param high the highest value
     * @return the clamped coordinate
     */
    private static int clamp(int value, int low, int high) {
        return value < low ? low : Math.min(value, high);
    }
}
