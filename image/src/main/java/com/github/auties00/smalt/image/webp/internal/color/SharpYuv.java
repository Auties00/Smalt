/* Ports libwebp 1.6.0 (sharpyuv/sharpyuv.c, sharpyuv_dsp.c, sharpyuv_gamma.c, sharpyuv_csp.c). Copyright 2022
   Google Inc.; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.webp.internal.color;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Converts RGBA to the limited-range BT.601 4:2:0 Y'CbCr of WebP lossy coding with the iterative
 * "sharp" conversion ({@code SharpYuvConvert} with the WebP matrix and the sRGB transfer).
 *
 * <p>Instead of averaging each 2x2 block, the conversion searches luma and chroma whose fancy
 * upsampled reconstruction, compared in a gamma-linearized domain, best matches the source, which
 * keeps the edges of saturated colors sharp. It costs several times the plain conversion. Pictures
 * narrower or shorter than 4 pixels are converted by {@link YuvConverter#fromRgba}.
 *
 * <p>The planes are bit-identical to libwebp's {@code WebPPictureSharpARGBToYUVA} for 8-bit
 * input.
 */
public final class SharpYuv {
    /**
     * The fixed-point precision of the RGB to Y'CbCr matrix.
     */
    private static final int YUV_FIX = 16;

    /**
     * The precision added to the 8-bit samples.
     */
    private static final int SFIX = 2;

    /**
     * The largest sample value at the working precision.
     */
    private static final int MAX_Y = (1 << (8 + SFIX)) - 1;

    /**
     * The number of refinement iterations.
     */
    private static final int ITERATIONS = 4;

    /**
     * The smallest side converted iteratively.
     */
    private static final int MIN_DIMENSION = 4;

    /**
     * The WebP RGB to luma coefficients, the last one being the scaled offset.
     */
    private static final int[] TO_Y = {16839, 33059, 6420, (16 << 16) << SFIX};

    /**
     * The WebP RGB to Cb coefficients.
     */
    private static final int[] TO_U = {-9719, -19081, 28800, (128 << 16) << SFIX};

    /**
     * The WebP RGB to Cr coefficients.
     */
    private static final int[] TO_V = {28800, -24116, -4684, (128 << 16) << SFIX};

    /**
     * The 10-bit gamma to 16-bit linear table, with a repeated last entry.
     */
    static final int[] GAMMA_TO_LINEAR = new int[1024 + 2];

    /**
     * The linear to 16-bit gamma table over 512 steps, with a repeated last entry.
     */
    static final int[] LINEAR_TO_GAMMA = new int[512 + 2];

    static {
        var a = 0.09929682680944;
        var thresh = 0.018053968510807;
        var gamma = 1.0 / 0.45;
        var scale = (double) (1 << 16);
        var norm = 1.0 / 1024;
        var aRec = 1.0 / (1.0 + a);
        for (var v = 0; v <= 1024; v++) {
            var g = norm * v;
            var value = g <= thresh * 4.5 ? g / 4.5 : StrictMath.pow(aRec * (g + a), gamma);
            GAMMA_TO_LINEAR[v] = (int) (value * scale + 0.5);
        }
        GAMMA_TO_LINEAR[1025] = GAMMA_TO_LINEAR[1024];
        var step = 1.0 / 512;
        for (var v = 0; v <= 512; v++) {
            var g = step * v;
            var value = g <= thresh ? 4.5 * g : (1.0 + a) * StrictMath.pow(g, 1.0 / gamma) - a;
            LINEAR_TO_GAMMA[v] = (int) (scale * value + 0.5);
        }
        LINEAR_TO_GAMMA[513] = LINEAR_TO_GAMMA[512];
    }

    /**
     * Prevents instantiation.
     */
    private SharpYuv() {
        throw new AssertionError();
    }

    /**
     * Converts RGBA to a 4:2:0 picture, with an alpha plane when {@code considerAlpha} is set and
     * at least one pixel is not opaque.
     *
     * <p>Alpha does not weigh the chroma: the color of transparent pixels takes part like any
     * other.
     *
     * @param rgba the RGBA samples, the first row first
     * @param stride the distance in bytes between rows
     * @param width the width in pixels
     * @param height the height in pixels
     * @param considerAlpha whether the alpha channel is meaningful
     * @param limits the resource bounds
     * @return the picture, tagged {@link ColorInfo#BT601_LIMITED}
     */
    public static YuvPicture fromRgba(byte[] rgba, int stride, int width, int height, boolean considerAlpha,
                                      MediaLimits limits) {
        if (width < MIN_DIMENSION || height < MIN_DIMENSION) {
            return YuvConverter.fromRgba(rgba, stride, width, height, considerAlpha, limits);
        }
        var hasAlpha = considerAlpha && YuvConverter.hasTransparency(rgba, stride, width, height);
        var w = (width + 1) & ~1;
        var h = (height + 1) & ~1;
        var uvW = w >> 1;
        var uvH = h >> 1;
        LimitChecks.checkAllocation(limits, (long) width * height * (hasAlpha ? 2 : 1) + 2L * uvW * uvH
                + 4L * w * h + 12L * uvW * uvH);
        var bestY = new short[w * h];
        var targetY = new short[w * h];
        var bestUv = new short[3 * uvW * uvH];
        var targetUv = new short[3 * uvW * uvH];
        var pairs = uvH;
        var bands = ParallelBands.bands(pairs, (long) w * h * 16);
        ParallelBands.run(bands, band -> {
            var src1 = new short[3 * w];
            var src2 = new short[3 * w];
            for (var pair = ParallelBands.start(band, bands, pairs); pair < ParallelBands.start(band + 1, bands,
                    pairs); pair++) {
                var j = 2 * pair;
                importRow(rgba, j * stride, width, w, src1);
                if (j + 1 < height) {
                    importRow(rgba, (j + 1) * stride, width, w, src2);
                } else {
                    System.arraycopy(src1, 0, src2, 0, 3 * w);
                }
                storeGray(src1, bestY, j * w, w);
                storeGray(src2, bestY, (j + 1) * w, w);
                updateW(src1, targetY, j * w, w);
                updateW(src2, targetY, (j + 1) * w, w);
                updateChroma(src1, src2, targetUv, 3 * uvW * pair, uvW);
                System.arraycopy(targetUv, 3 * uvW * pair, bestUv, 3 * uvW * pair, 3 * uvW);
            }
        });
        refine(bestY, targetY, bestUv, targetUv, w, h);
        var y = new byte[width * height];
        var u = new byte[uvW * uvH];
        var v = new byte[uvW * uvH];
        var rows = ParallelBands.bands(height, (long) width * height * 4);
        ParallelBands.run(rows, band -> {
            for (var j = ParallelBands.start(band, rows, height); j < ParallelBands.start(band + 1, rows, height);
                 j++) {
                var yRow = j * w;
                var uvRow = 3 * uvW * (j >> 1);
                for (var i = 0; i < width; i++) {
                    var off = uvRow + (i >> 1);
                    var luma = bestY[yRow + i];
                    var r = bestUv[off] + luma;
                    var g = bestUv[off + uvW] + luma;
                    var b = bestUv[off + 2 * uvW] + luma;
                    y[j * width + i] = clip8(toYuv(r, g, b, TO_Y));
                }
            }
        });
        for (var j = 0; j < uvH; j++) {
            var row = 3 * uvW * j;
            for (var i = 0; i < uvW; i++) {
                var r = bestUv[row + i];
                var g = bestUv[row + i + uvW];
                var b = bestUv[row + i + 2 * uvW];
                u[j * uvW + i] = clip8(toYuv(r, g, b, TO_U));
                v[j * uvW + i] = clip8(toYuv(r, g, b, TO_V));
            }
        }
        byte[] a = null;
        if (hasAlpha) {
            a = new byte[width * height];
            for (var j = 0; j < height; j++) {
                for (var i = 0; i < width; i++) {
                    a[j * width + i] = rgba[j * stride + 4 * i + 3];
                }
            }
        }
        return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.YUV420).color(ColorInfo.BT601_LIMITED)
                .luma(y, width)
                .chroma(u, v, uvW)
                .alpha(a, width)
                .build();
    }

    /**
     * Refines luma and chroma until the reconstruction stops improving
     * ({@code DoSharpArgbToYuv}).
     *
     * <p>Rows are visited in order because each row pair's upsampling reads the chroma the
     * previous pair just updated.
     *
     * @param bestY the luma estimate, refined in place
     * @param targetY the linear-domain luma of the source
     * @param bestUv the chroma estimate, refined in place
     * @param targetUv the chroma of the source
     * @param w the even width
     * @param h the even height
     */
    private static void refine(short[] bestY, short[] targetY, short[] bestUv, short[] targetUv, int w, int h) {
        var uvW = w >> 1;
        var src1 = new short[3 * w];
        var src2 = new short[3 * w];
        var bestRgbY = new short[2 * w];
        var bestRgbUv = new short[3 * uvW];
        var threshold = (long) (3.0 * w * h);
        var previous = -1L;
        for (var iter = 0; iter < ITERATIONS; iter++) {
            var cur = 0;
            var prev = 0;
            long diff = 0;
            for (var j = 0; j < h; j += 2) {
                var next = cur + (j < h - 2 ? 3 * uvW : 0);
                interpolateTwoRows(bestY, j * w, bestUv, prev, cur, next, w, src1, src2);
                prev = cur;
                cur = next;
                updateW(src1, bestRgbY, 0, w);
                updateW(src2, bestRgbY, w, w);
                updateChroma(src1, src2, bestRgbUv, 0, uvW);
                diff += updateY(targetY, j * w, bestRgbY, bestY, 2 * w);
                var uvRow = 3 * uvW * (j >> 1);
                for (var i = 0; i < 3 * uvW; i++) {
                    bestUv[uvRow + i] = (short) (bestUv[uvRow + i] + targetUv[uvRow + i] - bestRgbUv[i]);
                }
            }
            if (iter > 0 && (Long.compareUnsigned(diff, threshold) < 0 || Long.compareUnsigned(diff, previous) > 0)) {
                break;
            }
            previous = diff;
        }
    }

    /**
     * Copies one RGBA row to the R, G and B rows of the working precision, replicating the last
     * pixel when the width is odd.
     *
     * @param rgba the RGBA samples
     * @param pos the index of the row's first byte
     * @param width the width in pixels
     * @param w the even width
     * @param dst the destination: {@code w} red, then green, then blue samples
     */
    private static void importRow(byte[] rgba, int pos, int width, int w, short[] dst) {
        for (var i = 0; i < width; i++) {
            var p = pos + 4 * i;
            dst[i] = (short) ((rgba[p] & 0xFF) << SFIX);
            dst[i + w] = (short) ((rgba[p + 1] & 0xFF) << SFIX);
            dst[i + 2 * w] = (short) ((rgba[p + 2] & 0xFF) << SFIX);
        }
        if ((width & 1) != 0) {
            dst[width] = dst[width - 1];
            dst[width + w] = dst[width + w - 1];
            dst[width + 2 * w] = dst[width + 2 * w - 1];
        }
    }

    /**
     * Returns the luma of linear or gamma RGB with the BT.709 weights ({@code RGBToGray}).
     *
     * @param r the red value
     * @param g the green value
     * @param b the blue value
     * @return the weighted sum, rounded
     */
    private static int gray(long r, long g, long b) {
        return (int) ((13933 * r + 46871 * g + 4732 * b + (1 << (YUV_FIX - 1))) >> YUV_FIX);
    }

    /**
     * {@return the 16-bit linear value of a 10-bit gamma value}
     *
     * @param v the gamma value, from 0 to 1023
     */
    private static int toLinear(int v) {
        return GAMMA_TO_LINEAR[v];
    }

    /**
     * Returns the 10-bit gamma value of a 16-bit linear value, interpolated between the table
     * entries.
     *
     * @param value the linear value, from 0 to 65536
     * @return the gamma value
     */
    private static int toGamma(int value) {
        var pos = value >>> 7;
        var x = value - (pos << 7);
        var v0 = LINEAR_TO_GAMMA[pos] >>> 6;
        var v1 = LINEAR_TO_GAMMA[pos + 1] >>> 6;
        return v0 + (((v1 - v0) * x + 64) >>> 7);
    }

    /**
     * Writes the gamma-domain luma of a row ({@code StoreGray}).
     *
     * @param rgb the R, G and B rows
     * @param y the destination
     * @param pos the index of the first destination sample
     * @param w the row length
     */
    private static void storeGray(short[] rgb, short[] y, int pos, int w) {
        for (var i = 0; i < w; i++) {
            y[pos + i] = (short) gray(rgb[i], rgb[w + i], rgb[2 * w + i]);
        }
    }

    /**
     * Writes the luma of a row computed in the linear domain ({@code UpdateW}).
     *
     * @param src the R, G and B rows
     * @param dst the destination
     * @param pos the index of the first destination sample
     * @param w the row length
     */
    private static void updateW(short[] src, short[] dst, int pos, int w) {
        for (var i = 0; i < w; i++) {
            var luma = gray(toLinear(src[i]), toLinear(src[w + i]), toLinear(src[2 * w + i]));
            dst[pos + i] = (short) toGamma(luma);
        }
    }

    /**
     * Returns the gamma value of the linear average of four gamma values ({@code ScaleDown}).
     *
     * @param a the first value
     * @param b the second value
     * @param c the third value
     * @param d the fourth value
     * @return the gamma average
     */
    private static int scaleDown(int a, int b, int c, int d) {
        return toGamma((toLinear(a) + toLinear(b) + toLinear(c) + toLinear(d) + 2) >> 2);
    }

    /**
     * Writes the chroma (each channel minus the luma) of every 2x2 block of a row pair
     * ({@code UpdateChroma}).
     *
     * @param src1 the R, G and B rows of the upper row
     * @param src2 the R, G and B rows of the lower row
     * @param dst the destination: {@code uvW} red, then green, then blue differences
     * @param pos the index of the first destination entry
     * @param uvW the chroma width
     */
    private static void updateChroma(short[] src1, short[] src2, short[] dst, int pos, int uvW) {
        var w = 2 * uvW;
        for (var i = 0; i < uvW; i++) {
            var k = 2 * i;
            var r = scaleDown(src1[k], src1[k + 1], src2[k], src2[k + 1]);
            var g = scaleDown(src1[w + k], src1[w + k + 1], src2[w + k], src2[w + k + 1]);
            var b = scaleDown(src1[2 * w + k], src1[2 * w + k + 1], src2[2 * w + k], src2[2 * w + k + 1]);
            var luma = gray(r, g, b);
            dst[pos + i] = (short) (r - luma);
            dst[pos + i + uvW] = (short) (g - luma);
            dst[pos + i + 2 * uvW] = (short) (b - luma);
        }
    }

    /**
     * Clamps a value to the working precision.
     *
     * @param v the value
     * @return the value clamped to {@code [0, 1023]}
     */
    private static int clipY(int v) {
        return v < 0 ? 0 : Math.min(v, MAX_Y);
    }

    /**
     * Returns the upsampled value at an edge column ({@code Filter2}).
     *
     * @param a the nearer chroma
     * @param b the farther chroma
     * @param luma the luma at the column, at the working precision
     * @return the value
     */
    private static short filter2(int a, int b, int luma) {
        var v0 = (a * 3 + b + 2) >> 2;
        return (short) clipY(v0 + luma);
    }

    /**
     * Reconstructs the R, G and B rows of a row pair by fancy upsampling of the chroma estimate
     * added to the luma estimate ({@code InterpolateTwoRows}).
     *
     * @param bestY the luma estimate
     * @param yPos the index of the pair's first luma sample
     * @param uv the chroma estimate
     * @param prev the index of the chroma row above
     * @param cur the index of the pair's chroma row
     * @param next the index of the chroma row below
     * @param w the even width
     * @param out1 the reconstruction of the upper row
     * @param out2 the reconstruction of the lower row
     */
    private static void interpolateTwoRows(short[] bestY, int yPos, short[] uv, int prev, int cur, int next, int w,
                                           short[] out1, short[] out2) {
        var uvW = w >> 1;
        var len = (w - 1) >> 1;
        for (var k = 0; k < 3; k++) {
            var o = k * w;
            var c = cur + k * uvW;
            var p = prev + k * uvW;
            var n = next + k * uvW;
            out1[o] = filter2(uv[c], uv[p], bestY[yPos]);
            out2[o] = filter2(uv[c], uv[n], bestY[yPos + w]);
            filterRow(uv, c, p, len, bestY, yPos + 1, out1, o + 1);
            filterRow(uv, c, n, len, bestY, yPos + w + 1, out2, o + 1);
            out1[o + w - 1] = filter2(uv[c + uvW - 1], uv[p + uvW - 1], bestY[yPos + w - 1]);
            out2[o + w - 1] = filter2(uv[c + uvW - 1], uv[n + uvW - 1], bestY[yPos + 2 * w - 1]);
        }
    }

    /**
     * Upsamples the interior of one row ({@code SharpYuvFilterRow_C}).
     *
     * @param uv the chroma
     * @param a the index of the nearer chroma row
     * @param b the index of the farther chroma row
     * @param len the number of output pairs
     * @param bestY the luma
     * @param yPos the index of the first luma sample
     * @param out the destination
     * @param outPos the index of the first output sample
     */
    private static void filterRow(short[] uv, int a, int b, int len, short[] bestY, int yPos, short[] out, int outPos) {
        for (var i = 0; i < len; i++, a++, b++) {
            var a0 = uv[a];
            var a1 = uv[a + 1];
            var b0 = uv[b];
            var b1 = uv[b + 1];
            var v0 = (a0 * 9 + a1 * 3 + b0 * 3 + b1 + 8) >> 4;
            var v1 = (a1 * 9 + a0 * 3 + b1 * 3 + b0 + 8) >> 4;
            out[outPos + 2 * i] = (short) clipY(bestY[yPos + 2 * i] + v0);
            out[outPos + 2 * i + 1] = (short) clipY(bestY[yPos + 2 * i + 1] + v1);
        }
    }

    /**
     * Moves the luma estimate by the difference between the target and the reconstruction
     * ({@code SharpYuvUpdateY_C}).
     *
     * @param target the target luma
     * @param targetPos the index of the first target sample
     * @param reconstructed the luma of the reconstruction
     * @param best the luma estimate, updated in place at {@code targetPos}
     * @param len the number of samples
     * @return the sum of the absolute differences
     */
    private static long updateY(short[] target, int targetPos, short[] reconstructed, short[] best, int len) {
        long diff = 0;
        for (var i = 0; i < len; i++) {
            var d = target[targetPos + i] - reconstructed[i];
            best[targetPos + i] = (short) clipY(best[targetPos + i] + d);
            diff += Math.abs(d);
        }
        return diff;
    }

    /**
     * Applies a row of the RGB to Y'CbCr matrix at the working precision
     * ({@code RGBToYUVComponent}).
     *
     * @param r the red value
     * @param g the green value
     * @param b the blue value
     * @param coeffs the matrix row
     * @return the component, before clipping
     */
    private static int toYuv(int r, int g, int b, int[] coeffs) {
        var rounder = 1 << (YUV_FIX + SFIX - 1);
        return (coeffs[0] * r + coeffs[1] * g + coeffs[2] * b + coeffs[3] + rounder) >> (YUV_FIX + SFIX);
    }

    /**
     * Clamps a component to 8 bits after narrowing it to 16 bits ({@code clip_8b}).
     *
     * @param v the component
     * @return the clamped byte
     */
    private static byte clip8(int v) {
        var s = (short) v;
        return (byte) ((s & ~0xFF) == 0 ? s : s < 0 ? 0 : 255);
    }
}
