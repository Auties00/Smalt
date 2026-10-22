/* Ports Pillow (src/libImaging/Geometry.c, the affine sampling point). Copyright Secret Labs AB, Fredrik Lundh and
 * contributors, and Jeffrey A. Clark and contributors; MIT-CMU License. Modified for Smalt. */
package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Resamples packed RGBA pictures through an affine map: every output pixel is the source sampled
 * at the point its center maps to.
 *
 * <p>The map goes from output coordinates to source coordinates (the inverse of the transform the
 * caller asked for). Output pixel {@code (i, j)} samples the source at
 * {@code (sx, sy) = inverse(i + 0.5, j + 0.5)}:
 * <ul>
 *   <li>{@link Interpolation#NEAREST} copies source pixel {@code (floor(sx), floor(sy))}, or the
 *       background when that pixel does not exist;</li>
 *   <li>{@link Interpolation#BILINEAR} and {@link Interpolation#BICUBIC} weight the 2x2 or 4x4
 *       source pixels around {@code (sx - 0.5, sy - 0.5)}; taps that fall outside the source read
 *       the background color, so the edges of a rotated picture are antialiased against the
 *       background rather than cut.</li>
 * </ul>
 * Interpolation happens in premultiplied alpha, in single-precision floating point: each tap
 * contributes {@code w * a * c} to a color sum and {@code w * a} to the alpha sum, the alpha of
 * the result is the rounded alpha sum and its color the color sum divided by the alpha sum,
 * rounded and clamped. A pixel whose alpha rounds to zero becomes transparent black. A picture
 * that reports no alpha is read as opaque.
 *
 * <p>Output rows are independent, so large pictures split them into bands computed on several
 * threads by {@link ParallelBands}; the result does not depend on the split.
 *
 * @implNote This implementation evaluates the map directly at each pixel center in double precision, so no rounding
 * error accumulates along a row and the output does not depend on the band split. The sampling point and the
 * nearest-neighbor rule follow Pillow's {@code libImaging/Geometry.c}, whose output this reproduces for
 * {@link Interpolation#NEAREST}; the bilinear and bicubic kernels read the background at missing taps and round. The
 * 8-bit interpolating samplers pad the source with {@value #PAD} background pixels so that no tap needs a bounds
 * check, and leave the weighted sums to {@link WarpKernel}.
 */
final class Warp {
    /**
     * The approximate work per output pixel and tap, weighed against
     * {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long TAP_WORK = 6;

    /**
     * The background pixels around the padded copy of an 8-bit source: the widest reach of the
     * bicubic taps beyond the picture.
     */
    static final int PAD = 3;

    /**
     * Prevents instantiation.
     */
    private Warp() {
        throw new AssertionError();
    }

    /**
     * Returns the number of source pixels an interpolation reads along each axis.
     *
     * @param interpolation how source pixels are interpolated
     * @return {@code 1}, {@code 2} or {@code 4}
     */
    static int taps(Interpolation interpolation) {
        return switch (interpolation) {
            case NEAREST -> 1;
            case BILINEAR -> 2;
            case BICUBIC -> 4;
        };
    }

    /**
     * Resamples an 8-bit RGBA picture.
     *
     * @param src the source picture
     * @param inverse the map from output coordinates to source coordinates
     * @param width the output width
     * @param height the output height
     * @param interpolation how source pixels are interpolated
     * @param argbBackground the background as {@code 0xAARRGGBB}, straight alpha
     * @param hasAlpha whether the result reports alpha
     * @return the resampled picture
     */
    static RgbaPicture rgba(RgbaPicture src, AffineTransform inverse, int width, int height, Interpolation interpolation,
                            int argbBackground, boolean hasAlpha) {
        var out = RgbaPicture.allocate(width, height, hasAlpha);
        var taps = taps(interpolation);
        var bands = ParallelBands.bands(height, TAP_WORK * taps * taps * width * height);
        if (interpolation == Interpolation.NEAREST) {
            ParallelBands.run(bands, band -> nearestRows(src, inverse, argbBackground, out,
                    ParallelBands.start(band, bands, height), ParallelBands.start(band + 1, bands, height)));
            return out;
        }
        var padded = pad(src, argbBackground);
        ParallelBands.run(bands, band -> interpolatedRows(padded, src.width(), src.height(), inverse, taps, out,
                ParallelBands.start(band, bands, height), ParallelBands.start(band + 1, bands, height)));
        return out;
    }

    /**
     * Copies an 8-bit picture into packed pixels surrounded by {@link #PAD} background pixels.
     *
     * @param src the picture
     * @param argbBackground the background as {@code 0xAARRGGBB}
     * @return the padded pixels, {@code width + 2 * PAD} per row, red in the low byte
     */
    static int[] pad(RgbaPicture src, int argbBackground) {
        var width = src.width() + 2 * PAD;
        var height = src.height() + 2 * PAD;
        OpChecks.checkArray((long) width * height);
        var padded = new int[width * height];
        var background = ((argbBackground >>> 16) & 0xFF) | (argbBackground & 0xFF00) | (argbBackground & 0xFF) << 16
                | (argbBackground & 0xFF000000);
        Arrays.fill(padded, background);
        var pixels = src.pixels();
        var opaque = src.hasAlpha() ? 0 : 0xFF000000;
        for (var y = 0; y < src.height(); y++) {
            var p = y * src.stride();
            var d = (y + PAD) * width + PAD;
            for (var x = 0; x < src.width(); x++, p += 4) {
                padded[d + x] = (pixels[p] & 0xFF) | (pixels[p + 1] & 0xFF) << 8 | (pixels[p + 2] & 0xFF) << 16
                        | (pixels[p + 3] & 0xFF) << 24 | opaque;
            }
        }
        return padded;
    }

    /**
     * Interpolates the output rows {@code [top, bottom)} of an 8-bit picture.
     *
     * <p>A pixel whose taps would all lie beyond the padding samples the corner of the padding,
     * all background, with the weights of a centered position.
     *
     * @param padded the padded packed source
     * @param width the source width
     * @param height the source height
     * @param inverse the map from output coordinates to source coordinates
     * @param taps {@code 2} for bilinear, {@code 4} for bicubic interpolation
     * @param out the output picture
     * @param top the first row
     * @param bottom the row after the last one
     */
    private static void interpolatedRows(int[] padded, int width, int height, AffineTransform inverse, int taps,
                                         RgbaPicture out, int top, int bottom) {
        var kernel = WarpKernel.INSTANCE;
        var stride = width + 2 * PAD;
        var count = out.width();
        var base = new int[count];
        var wx = new float[taps][count];
        var wy = new float[taps][count];
        var sums = new float[4][count];
        var pixels = new int[taps][count];
        var weights = new float[4];
        var reach = taps / 2;
        for (var j = top; j < bottom; j++) {
            var cy = j + 0.5;
            var rowX = inverse.m01() * cy + inverse.m02();
            var rowY = inverse.m11() * cy + inverse.m12();
            for (var i = 0; i < count; i++) {
                var cx = i + 0.5;
                var u = inverse.m00() * cx + rowX - 0.5;
                var v = inverse.m10() * cx + rowY - 0.5;
                if (u <= -reach || v <= -reach || u >= width + reach - 1 || v >= height + reach - 1) {
                    base[i] = 0;
                    weights(0, taps, weights);
                    for (var t = 0; t < taps; t++) {
                        wx[t][i] = weights[t];
                        wy[t][i] = weights[t];
                    }
                    continue;
                }
                var x0 = (int) Math.floor(u);
                var y0 = (int) Math.floor(v);
                base[i] = (y0 + PAD - reach + 1) * stride + x0 + PAD - reach + 1;
                weights((float) (u - x0), taps, weights);
                for (var t = 0; t < taps; t++) {
                    wx[t][i] = weights[t];
                }
                weights((float) (v - y0), taps, weights);
                for (var t = 0; t < taps; t++) {
                    wy[t][i] = weights[t];
                }
            }
            for (var sum : sums) {
                Arrays.fill(sum, 0);
            }
            for (var k = 0; k < taps; k++) {
                for (var t = 0; t < taps; t++) {
                    gather(padded, k * stride + t, base, pixels[t], count);
                }
                if (taps == 2) {
                    kernel.accumulate2(pixels[0], pixels[1], wx[0], wx[1], wy[k], sums[0], sums[1], sums[2], sums[3],
                            count);
                } else {
                    kernel.accumulate4(pixels[0], pixels[1], pixels[2], pixels[3], wx[0], wx[1], wx[2], wx[3], wy[k],
                            sums[0], sums[1], sums[2], sums[3], count);
                }
            }
            kernel.resolve(sums[0], sums[1], sums[2], sums[3], count, out.pixels(), j * out.stride());
        }
    }

    /**
     * Copies one tap of every pixel of a run out of the padded source.
     *
     * @param padded the padded packed source
     * @param offset the offset of the tap from each pixel's base index
     * @param base the index of each pixel's top-left tap
     * @param pixels the destination, one packed pixel per output pixel
     * @param count the number of pixels
     */
    private static void gather(int[] padded, int offset, int[] base, int[] pixels, int count) {
        for (var i = 0; i < count; i++) {
            pixels[i] = padded[base[i] + offset];
        }
    }

    /**
     * Computes the weights of the taps around a fractional position.
     *
     * @param t the distance from the tap at or before the position, in {@code [0, 1)}
     * @param taps {@code 2} for linear, {@code 4} for Catmull-Rom weights
     * @param weights the destination of the weights
     */
    private static void weights(float t, int taps, float[] weights) {
        if (taps == 2) {
            weights[0] = 1 - t;
            weights[1] = t;
        } else {
            cubicWeights(t, weights);
        }
    }

    /**
     * Resamples a 16-bit RGBA picture.
     *
     * @param src the source picture
     * @param inverse the map from output coordinates to source coordinates
     * @param width the output width
     * @param height the output height
     * @param interpolation how source pixels are interpolated
     * @param argbBackground the background as {@code 0xAARRGGBB}, straight alpha; each 8-bit channel is widened as
     * {@code v * 257}
     * @param hasAlpha whether the result reports alpha
     * @return the resampled picture
     */
    static Rgba16Picture rgba16(Rgba16Picture src, AffineTransform inverse, int width, int height,
                                Interpolation interpolation, int argbBackground, boolean hasAlpha) {
        var out = Rgba16Picture.allocate(width, height, hasAlpha);
        var taps = taps(interpolation);
        var bands = ParallelBands.bands(height, TAP_WORK * taps * taps * width * height);
        ParallelBands.run(bands, band -> rgba16Rows(src, inverse, interpolation, argbBackground, out,
                ParallelBands.start(band, bands, height), ParallelBands.start(band + 1, bands, height)));
        return out;
    }

    /**
     * Samples the output rows {@code [top, bottom)} of an 8-bit picture with the nearest
     * neighbor.
     *
     * @param src the source picture
     * @param inverse the map from output coordinates to source coordinates
     * @param argbBackground the background
     * @param out the output picture
     * @param top the first row
     * @param bottom the row after the last one
     */
    private static void nearestRows(RgbaPicture src, AffineTransform inverse, int argbBackground, RgbaPicture out, int top,
                                    int bottom) {
        var sampler = new Sampler8(src, argbBackground);
        var pixels = out.pixels();
        var stride = out.stride();
        var width = out.width();
        for (var j = top; j < bottom; j++) {
            var cy = j + 0.5;
            var rowX = inverse.m01() * cy + inverse.m02();
            var rowY = inverse.m11() * cy + inverse.m12();
            var offset = j * stride;
            for (var i = 0; i < width; i++, offset += 4) {
                var cx = i + 0.5;
                var sx = inverse.m00() * cx + rowX;
                var sy = inverse.m10() * cx + rowY;
                sampler.nearest(sx, sy, pixels, offset);
            }
        }
    }

    /**
     * Resamples the output rows {@code [top, bottom)} of a 16-bit picture.
     *
     * @param src the source picture
     * @param inverse the map from output coordinates to source coordinates
     * @param interpolation how source pixels are interpolated
     * @param argbBackground the background
     * @param out the output picture
     * @param top the first row
     * @param bottom the row after the last one
     */
    private static void rgba16Rows(Rgba16Picture src, AffineTransform inverse, Interpolation interpolation,
                                   int argbBackground, Rgba16Picture out, int top, int bottom) {
        var sampler = new Sampler16(src, argbBackground);
        var pixels = out.pixels();
        var stride = out.stride();
        var width = out.width();
        for (var j = top; j < bottom; j++) {
            var cy = j + 0.5;
            var rowX = inverse.m01() * cy + inverse.m02();
            var rowY = inverse.m11() * cy + inverse.m12();
            var offset = j * stride;
            for (var i = 0; i < width; i++, offset += 4) {
                var cx = i + 0.5;
                var sx = inverse.m00() * cx + rowX;
                var sy = inverse.m10() * cx + rowY;
                switch (interpolation) {
                    case NEAREST -> sampler.nearest(sx, sy, pixels, offset);
                    case BILINEAR -> sampler.bilinear(sx - 0.5, sy - 0.5, pixels, offset);
                    case BICUBIC -> sampler.bicubic(sx - 0.5, sy - 0.5, pixels, offset);
                }
            }
        }
    }

    /**
     * Computes the Catmull-Rom weights of the four taps around a fractional position.
     *
     * @param t the distance from the second tap, in {@code [0, 1)}
     * @param weights the destination of the four weights
     */
    static void cubicWeights(float t, float[] weights) {
        var t2 = t * t;
        var t3 = t2 * t;
        weights[0] = -0.5f * t3 + t2 - 0.5f * t;
        weights[1] = 1.5f * t3 - 2.5f * t2 + 1;
        weights[2] = -1.5f * t3 + 2 * t2 + 0.5f * t;
        weights[3] = 0.5f * t3 - 0.5f * t2;
    }

    /**
     * Samples one 8-bit picture with the nearest neighbor; one instance per band of rows.
     */
    private static final class Sampler8 {
        /**
         * The source samples.
         */
        private final byte[] pixels;

        /**
         * The source row stride.
         */
        private final int stride;

        /**
         * The source width.
         */
        private final int width;

        /**
         * The source height.
         */
        private final int height;

        /**
         * Whether the source alpha is read rather than taken as opaque.
         */
        private final boolean alpha;

        /**
         * The background red, green, blue and alpha, straight.
         */
        private final byte backgroundR, backgroundG, backgroundB, backgroundA;

        /**
         * Creates a sampler.
         *
         * @param src the source picture
         * @param argbBackground the background as {@code 0xAARRGGBB}
         */
        Sampler8(RgbaPicture src, int argbBackground) {
            this.pixels = src.pixels();
            this.stride = src.stride();
            this.width = src.width();
            this.height = src.height();
            this.alpha = src.hasAlpha();
            this.backgroundR = (byte) (argbBackground >>> 16);
            this.backgroundG = (byte) (argbBackground >>> 8);
            this.backgroundB = (byte) argbBackground;
            this.backgroundA = (byte) (argbBackground >>> 24);
        }

        /**
         * Writes the pixel containing a point, or the background.
         *
         * @param sx the column of the point
         * @param sy the row of the point
         * @param out the destination samples
         * @param offset the index of the destination pixel
         */
        void nearest(double sx, double sy, byte[] out, int offset) {
            if (sx < 0 || sy < 0 || sx >= width || sy >= height) {
                out[offset] = backgroundR;
                out[offset + 1] = backgroundG;
                out[offset + 2] = backgroundB;
                out[offset + 3] = backgroundA;
                return;
            }
            var source = (int) sy * stride + 4 * (int) sx;
            out[offset] = pixels[source];
            out[offset + 1] = pixels[source + 1];
            out[offset + 2] = pixels[source + 2];
            out[offset + 3] = alpha ? pixels[source + 3] : (byte) 255;
        }
    }

    /**
     * Samples one 16-bit picture; one instance per band of rows.
     */
    private static final class Sampler16 {
        /**
         * The source samples.
         */
        private final short[] pixels;

        /**
         * The source row stride.
         */
        private final int stride;

        /**
         * The source width.
         */
        private final int width;

        /**
         * The source height.
         */
        private final int height;

        /**
         * Whether the source alpha is read rather than taken as opaque.
         */
        private final boolean alpha;

        /**
         * The background red, green, blue and alpha, straight.
         */
        private final short backgroundR, backgroundG, backgroundB, backgroundA;

        /**
         * The background alpha as a weight, and the premultiplied background channels.
         */
        private final float backgroundAlpha, backgroundRed, backgroundGreen, backgroundBlue;

        /**
         * The horizontal weights of the current pixel.
         */
        private final float[] wx = new float[4];

        /**
         * The vertical weights of the current pixel.
         */
        private final float[] wy = new float[4];

        /**
         * Creates a sampler.
         *
         * @param src the source picture
         * @param argbBackground the background as {@code 0xAARRGGBB}
         */
        Sampler16(Rgba16Picture src, int argbBackground) {
            this.pixels = src.pixels();
            this.stride = src.stride();
            this.width = src.width();
            this.height = src.height();
            this.alpha = src.hasAlpha();
            var r = ((argbBackground >>> 16) & 0xFF) * 257;
            var g = ((argbBackground >>> 8) & 0xFF) * 257;
            var b = (argbBackground & 0xFF) * 257;
            var a = (argbBackground >>> 24) * 257;
            this.backgroundR = (short) r;
            this.backgroundG = (short) g;
            this.backgroundB = (short) b;
            this.backgroundA = (short) a;
            this.backgroundAlpha = a;
            this.backgroundRed = (float) a * r;
            this.backgroundGreen = (float) a * g;
            this.backgroundBlue = (float) a * b;
        }

        /**
         * Writes the pixel containing a point, or the background.
         *
         * @param sx the column of the point
         * @param sy the row of the point
         * @param out the destination samples
         * @param offset the index of the destination pixel
         */
        void nearest(double sx, double sy, short[] out, int offset) {
            if (sx < 0 || sy < 0 || sx >= width || sy >= height) {
                out[offset] = backgroundR;
                out[offset + 1] = backgroundG;
                out[offset + 2] = backgroundB;
                out[offset + 3] = backgroundA;
                return;
            }
            var source = (int) sy * stride + 4 * (int) sx;
            out[offset] = pixels[source];
            out[offset + 1] = pixels[source + 1];
            out[offset + 2] = pixels[source + 2];
            out[offset + 3] = alpha ? pixels[source + 3] : (short) 0xFFFF;
        }

        /**
         * Writes the bilinear interpolation at a point given in pixel-center coordinates.
         *
         * @param u the column, with source pixel centers at integers
         * @param v the row, with source pixel centers at integers
         * @param out the destination samples
         * @param offset the index of the destination pixel
         */
        void bilinear(double u, double v, short[] out, int offset) {
            if (u <= -1 || v <= -1 || u >= width || v >= height) {
                background(out, offset);
                return;
            }
            var x0 = (int) Math.floor(u);
            var y0 = (int) Math.floor(v);
            var fx = (float) (u - x0);
            var fy = (float) (v - y0);
            wx[0] = 1 - fx;
            wx[1] = fx;
            wy[0] = 1 - fy;
            wy[1] = fy;
            accumulate(x0, y0, 2, out, offset);
        }

        /**
         * Writes the bicubic interpolation at a point given in pixel-center coordinates.
         *
         * @param u the column, with source pixel centers at integers
         * @param v the row, with source pixel centers at integers
         * @param out the destination samples
         * @param offset the index of the destination pixel
         */
        void bicubic(double u, double v, short[] out, int offset) {
            if (u <= -2 || v <= -2 || u >= width + 1 || v >= height + 1) {
                background(out, offset);
                return;
            }
            var x0 = (int) Math.floor(u);
            var y0 = (int) Math.floor(v);
            cubicWeights((float) (u - x0), wx);
            cubicWeights((float) (v - y0), wy);
            accumulate(x0 - 1, y0 - 1, 4, out, offset);
        }

        /**
         * Writes the background color.
         *
         * @param out the destination samples
         * @param offset the index of the destination pixel
         */
        private void background(short[] out, int offset) {
            out[offset] = backgroundR;
            out[offset + 1] = backgroundG;
            out[offset + 2] = backgroundB;
            out[offset + 3] = backgroundA;
        }

        /**
         * Accumulates a square of taps in premultiplied alpha and writes the result.
         *
         * @param left the column of the first tap
         * @param top the row of the first tap
         * @param taps the number of taps along each axis
         * @param out the destination samples
         * @param offset the index of the destination pixel
         */
        private void accumulate(int left, int top, int taps, short[] out, int offset) {
            float red = 0;
            float green = 0;
            float blue = 0;
            float sum = 0;
            float outside = 0;
            for (var ty = 0; ty < taps; ty++) {
                var y = top + ty;
                var weightY = wy[ty];
                if (y < 0 || y >= height) {
                    for (var tx = 0; tx < taps; tx++) {
                        outside += weightY * wx[tx];
                    }
                    continue;
                }
                var row = y * stride;
                for (var tx = 0; tx < taps; tx++) {
                    var x = left + tx;
                    var weight = weightY * wx[tx];
                    if (x < 0 || x >= width) {
                        outside += weight;
                        continue;
                    }
                    var p = row + 4 * x;
                    var a = alpha ? weight * (pixels[p + 3] & 0xFFFF) : weight * 65535;
                    red += a * (pixels[p] & 0xFFFF);
                    green += a * (pixels[p + 1] & 0xFFFF);
                    blue += a * (pixels[p + 2] & 0xFFFF);
                    sum += a;
                }
            }
            if (outside != 0) {
                red += outside * backgroundRed;
                green += outside * backgroundGreen;
                blue += outside * backgroundBlue;
                sum += outside * backgroundAlpha;
            }
            var a = (int) (sum + 0.5f);
            if (a <= 0) {
                out[offset] = 0;
                out[offset + 1] = 0;
                out[offset + 2] = 0;
                out[offset + 3] = 0;
                return;
            }
            var reciprocal = 1 / sum;
            out[offset] = (short) clamp(red * reciprocal, 65535);
            out[offset + 1] = (short) clamp(green * reciprocal, 65535);
            out[offset + 2] = (short) clamp(blue * reciprocal, 65535);
            out[offset + 3] = (short) Math.min(a, 65535);
        }
    }

    /**
     * Rounds a value to the nearest integer and clamps it to {@code [0, max]}.
     *
     * @param value the value to round
     * @param max the largest result
     * @return the clamped integer
     */
    static int clamp(float value, int max) {
        if (!(value > 0)) {
            return 0;
        }
        var rounded = (int) (value + 0.5f);
        return Math.min(rounded, max);
    }
}
