/* Ports libwebp 1.6.0 (src/dsp/upsampling.c, src/dec/io_dec.c, src/enc/picture_csp_enc.c). Copyright 2010
   Google Inc.; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.webp.internal.color;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;

import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Converts between packed RGBA and the limited-range BT.601 4:2:0 Y'CbCr of WebP lossy coding.
 *
 * <p>{@link #toRgba} reconstructs RGB with the "fancy" upsampler: every output chroma sample is
 * interpolated from the four nearest input samples with weights 9, 3, 3 and 1, and the edges
 * replicate their nearest rows and columns. {@link #fromRgba} downsamples chroma by averaging each
 * 2x2 block in a gamma-linearized domain; blocks that are partially transparent are averaged
 * weighted by alpha, so the color of invisible pixels does not bleed into visible ones.
 * {@link #cleanupTransparentArea} replaces the samples of fully transparent pixels with values
 * that compress better.
 *
 * @implNote This implementation follows libwebp: {@code UpsampleRgbaLinePair} of
 * {@code src/dsp/upsampling.c} and {@code EmitFancyRGB} of {@code src/dec/io_dec.c} for the
 * decoder, {@code ImportYUVAFromRGBA} (without dithering or sharp YUV) of
 * {@code src/enc/picture_csp_enc.c} and {@code WebPCleanupTransparentArea} of
 * {@code src/enc/picture_tools_enc.c} for the encoder. The gamma tables are the values libwebp
 * computes at run time with {@code pow()}, stored as constants so that the output does not
 * depend on the platform's floating-point library.
 */
public final class YuvConverter {
    /**
     * The color kernels.
     */
    private static final YuvDsp DSP = YuvDsp.INSTANCE;

    /**
     * The rounding term of chroma conversion, {@code YUV_HALF << 2}.
     */
    private static final int UV_ROUNDING = 1 << 17;

    /**
     * The gamma-to-linear table: {@code (int) (pow(v / 255.0, 0.8) * 4095 + 0.5)} for every 8-bit
     * value, from libwebp's {@code kGammaToLinearTab}.
     */
    private static final int[] GAMMA_TO_LINEAR = {
            0, 49, 85, 117, 147, 176, 204, 231, 257, 282, 307, 331, 355, 379, 402, 425,
            447, 469, 491, 513, 534, 556, 577, 598, 618, 639, 659, 679, 699, 719, 739, 759,
            778, 798, 817, 836, 855, 874, 893, 912, 930, 949, 967, 986, 1004, 1022, 1040, 1059,
            1077, 1094, 1112, 1130, 1148, 1165, 1183, 1200, 1218, 1235, 1252, 1270, 1287, 1304, 1321, 1338,
            1355, 1372, 1389, 1406, 1422, 1439, 1456, 1472, 1489, 1505, 1522, 1538, 1555, 1571, 1587, 1604,
            1620, 1636, 1652, 1668, 1684, 1700, 1716, 1732, 1748, 1764, 1780, 1796, 1812, 1827, 1843, 1859,
            1874, 1890, 1905, 1921, 1937, 1952, 1967, 1983, 1998, 2014, 2029, 2044, 2059, 2075, 2090, 2105,
            2120, 2135, 2151, 2166, 2181, 2196, 2211, 2226, 2241, 2256, 2270, 2285, 2300, 2315, 2330, 2345,
            2359, 2374, 2389, 2403, 2418, 2433, 2447, 2462, 2477, 2491, 2506, 2520, 2535, 2549, 2564, 2578,
            2592, 2607, 2621, 2636, 2650, 2664, 2679, 2693, 2707, 2721, 2736, 2750, 2764, 2778, 2792, 2806,
            2820, 2835, 2849, 2863, 2877, 2891, 2905, 2919, 2933, 2947, 2961, 2975, 2988, 3002, 3016, 3030,
            3044, 3058, 3072, 3085, 3099, 3113, 3127, 3140, 3154, 3168, 3182, 3195, 3209, 3222, 3236, 3250,
            3263, 3277, 3291, 3304, 3318, 3331, 3345, 3358, 3372, 3385, 3399, 3412, 3426, 3439, 3452, 3466,
            3479, 3493, 3506, 3519, 3533, 3546, 3559, 3573, 3586, 3599, 3612, 3626, 3639, 3652, 3665, 3678,
            3692, 3705, 3718, 3731, 3744, 3757, 3771, 3784, 3797, 3810, 3823, 3836, 3849, 3862, 3875, 3888,
            3901, 3914, 3927, 3940, 3953, 3966, 3979, 3992, 4005, 4018, 4031, 4044, 4056, 4069, 4082, 4095
    };

    /**
     * The linear-to-gamma table: {@code (int) (255 * pow(v * 128 / 4095.0, 1 / 0.8) + 0.5)} for
     * {@code v} from 0 to 32, from libwebp's {@code kLinearToGammaTab}.
     */
    private static final int[] LINEAR_TO_GAMMA = {
            0, 3, 8, 13, 19, 25, 31, 38, 45, 52, 60, 67, 75, 83, 91, 99, 107,
            116, 124, 133, 142, 151, 160, 169, 178, 187, 197, 206, 216, 226, 235, 245, 255
    };

    /**
     * The fixed-point precision of {@link #INV_ALPHA}.
     */
    private static final int ALPHA_FIX = 19;

    /**
     * The reciprocal of every alpha sum of a 2x2 block, {@code (1 << 19) / a}, from libwebp's
     * {@code kInvAlpha}.
     */
    private static final int[] INV_ALPHA = buildInverseAlpha();

    /**
     * The side of the blocks examined by {@link #cleanupTransparentArea}.
     */
    private static final int CLEANUP_BLOCK = 8;

    /**
     * Prevents instantiation.
     */
    private YuvConverter() {
        throw new AssertionError();
    }

    /**
     * Builds {@link #INV_ALPHA}.
     *
     * @return the table of 1021 reciprocals, with {@code 0} for a zero sum
     */
    private static int[] buildInverseAlpha() {
        var table = new int[4 * 0xFF + 1];
        for (var a = 1; a < table.length; a++) {
            table[a] = (1 << ALPHA_FIX) / a;
        }
        return table;
    }

    /**
     * Converts a 4:2:0 picture to RGBA with fancy upsampling, taking alpha from the picture's alpha
     * plane or making every pixel opaque.
     *
     * @param picture the picture, in the 4:2:0 layout
     * @param dst the RGBA destination
     * @param dstOffset the index of the first byte of the first row
     * @param dstStride the distance in bytes between destination rows
     * @throws MediaException.Unsupported if the picture is not 4:2:0
     */
    public static void toRgba(YuvPicture picture, byte[] dst, int dstOffset, int dstStride) {
        if (picture.chromaFormat() != ChromaFormat.YUV420) {
            throw new MediaException.Unsupported("webp " + picture.chromaFormat(), "WebP lossy pictures are 4:2:0");
        }
        var width = picture.width();
        var height = picture.height();
        var y = picture.y();
        var u = picture.u();
        var v = picture.v();
        var yStride = picture.yStride();
        var uvStride = picture.uvStride();
        var topU = new int[width];
        var topV = new int[width];
        var bottomU = new int[width];
        var bottomV = new int[width];
        upsampleChroma(u, v, 0, 0, width, topU, topV, null, null);
        DSP.yuvToRgbaRow(y, 0, topU, topV, dst, dstOffset, width);
        for (var row = 1; row + 1 < height; row += 2) {
            var topUv = ((row - 1) >> 1) * uvStride;
            var curUv = ((row + 1) >> 1) * uvStride;
            upsampleChroma(u, v, topUv, curUv, width, topU, topV, bottomU, bottomV);
            DSP.yuvToRgbaRow(y, row * yStride, topU, topV, dst, dstOffset + row * dstStride, width);
            DSP.yuvToRgbaRow(y, (row + 1) * yStride, bottomU, bottomV, dst, dstOffset + (row + 1) * dstStride, width);
        }
        if (height > 1 && (height & 1) == 0) {
            var lastUv = ((height - 1) >> 1) * uvStride;
            upsampleChroma(u, v, lastUv, lastUv, width, topU, topV, null, null);
            DSP.yuvToRgbaRow(y, (height - 1) * yStride, topU, topV, dst, dstOffset + (height - 1) * dstStride, width);
        }
        var alpha = picture.alpha();
        if (alpha != null) {
            var alphaStride = picture.alphaStride();
            for (var row = 0; row < height; row++) {
                var s = row * alphaStride;
                var d = dstOffset + row * dstStride + 3;
                for (var x = 0; x < width; x++) {
                    dst[d + (x << 2)] = alpha[s + x];
                }
            }
        }
    }

    /**
     * Interpolates the chroma of a pair of output rows from two chroma rows.
     *
     * <p>Chroma values are packed as {@code u | v << 16} so both components are interpolated with
     * one integer operation, exactly as libwebp does.
     *
     * @param u the Cb plane
     * @param v the Cr plane
     * @param topPos the index of the chroma row above the pair
     * @param curPos the index of the chroma row below the pair
     * @param len the number of output pixels per row
     * @param topU the Cb destination of the upper output row
     * @param topV the Cr destination of the upper output row
     * @param bottomU the Cb destination of the lower output row, or {@code null}
     * @param bottomV the Cr destination of the lower output row, or {@code null}
     */
    static void upsampleChroma(byte[] u, byte[] v, int topPos, int curPos, int len,
                               int[] topU, int[] topV, int[] bottomU, int[] bottomV) {
        var lastPixelPair = (len - 1) >> 1;
        var tl = (u[topPos] & 0xFF) | (v[topPos] & 0xFF) << 16;
        var l = (u[curPos] & 0xFF) | (v[curPos] & 0xFF) << 16;
        var uv0 = (3 * tl + l + 0x00020002) >> 2;
        topU[0] = uv0 & 0xFF;
        topV[0] = uv0 >>> 16;
        if (bottomU != null) {
            uv0 = (3 * l + tl + 0x00020002) >> 2;
            bottomU[0] = uv0 & 0xFF;
            bottomV[0] = uv0 >>> 16;
        }
        for (var x = 1; x <= lastPixelPair; x++) {
            var t = (u[topPos + x] & 0xFF) | (v[topPos + x] & 0xFF) << 16;
            var c = (u[curPos + x] & 0xFF) | (v[curPos + x] & 0xFF) << 16;
            var avg = tl + t + l + c + 0x00080008;
            var diag12 = (avg + 2 * (t + l)) >> 3;
            var diag03 = (avg + 2 * (tl + c)) >> 3;
            var a = (diag12 + tl) >> 1;
            var b = (diag03 + t) >> 1;
            topU[2 * x - 1] = a & 0xFF;
            topV[2 * x - 1] = a >>> 16;
            topU[2 * x] = b & 0xFF;
            topV[2 * x] = b >>> 16;
            if (bottomU != null) {
                a = (diag03 + l) >> 1;
                b = (diag12 + c) >> 1;
                bottomU[2 * x - 1] = a & 0xFF;
                bottomV[2 * x - 1] = a >>> 16;
                bottomU[2 * x] = b & 0xFF;
                bottomV[2 * x] = b >>> 16;
            }
            tl = t;
            l = c;
        }
        if ((len & 1) == 0) {
            uv0 = (3 * tl + l + 0x00020002) >> 2;
            topU[len - 1] = uv0 & 0xFF;
            topV[len - 1] = uv0 >>> 16;
            if (bottomU != null) {
                uv0 = (3 * l + tl + 0x00020002) >> 2;
                bottomU[len - 1] = uv0 & 0xFF;
                bottomV[len - 1] = uv0 >>> 16;
            }
        }
    }

    /**
     * Converts RGBA to a 4:2:0 picture, with an alpha plane when {@code considerAlpha} is set and
     * at least one pixel is not opaque.
     *
     * @param rgba the RGBA samples, the first row first
     * @param stride the distance in bytes between rows
     * @param width the width in pixels
     * @param height the height in pixels
     * @param considerAlpha whether the alpha channel is meaningful; when {@code false} every
     * pixel is treated as opaque
     * @param limits the resource bounds
     * @return the picture, tagged {@link ColorInfo#BT601_LIMITED}
     */
    public static YuvPicture fromRgba(byte[] rgba, int stride, int width, int height, boolean considerAlpha,
                                      MediaLimits limits) {
        var hasAlpha = considerAlpha && hasTransparency(rgba, stride, width, height);
        var uvWidth = (width + 1) >> 1;
        var uvHeight = (height + 1) >> 1;
        LimitChecks.checkAllocation(limits, (long) width * height * (hasAlpha ? 2 : 1) + 2L * uvWidth * uvHeight);
        var y = new byte[width * height];
        var u = new byte[uvWidth * uvHeight];
        var v = new byte[uvWidth * uvHeight];
        var a = hasAlpha ? new byte[width * height] : null;
        var pairs = height >> 1;
        var bands = ParallelBands.bands(Math.max(1, pairs), (long) width * height);
        var hasAlphaPlane = hasAlpha;
        ParallelBands.run(bands, band -> {
            var tmp = new int[4 * uvWidth];
            var firstPair = ParallelBands.start(band, bands, pairs);
            var endPair = ParallelBands.start(band + 1, bands, pairs);
            var lastBand = band == bands - 1;
            var rowEnd = lastBand ? height : 2 * endPair;
            for (var row = 2 * firstPair; row < rowEnd; row++) {
                DSP.rgbaToYRow(rgba, row * stride, y, row * width, width);
            }
            for (var pair = firstPair; pair < endPair; pair++) {
                var src = 2 * pair * stride;
                var rowsHaveAlpha = hasAlphaPlane && extractAlpha(rgba, src, stride, width, 2, a, 2 * pair * width);
                if (rowsHaveAlpha) {
                    accumulateRgba(rgba, src, stride, tmp, width);
                } else {
                    accumulateRgb(rgba, src, stride, tmp, width);
                }
                convertToUv(tmp, u, v, pair * uvWidth, uvWidth);
            }
            if (lastBand && (height & 1) != 0) {
                var src = (height - 1) * stride;
                var rowHasAlpha = hasAlphaPlane && extractAlpha(rgba, src, 0, width, 1, a, (height - 1) * width);
                if (rowHasAlpha) {
                    accumulateRgba(rgba, src, 0, tmp, width);
                } else {
                    accumulateRgb(rgba, src, 0, tmp, width);
                }
                convertToUv(tmp, u, v, (uvHeight - 1) * uvWidth, uvWidth);
            }
        });
        return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.YUV420).color(ColorInfo.BT601_LIMITED)
                .luma(y, width)
                .chroma(u, v, uvWidth)
                .alpha(a, width)
                .build();
    }

    /**
     * {@return whether some pixel of an RGBA image has alpha below 255}
     *
     * @param rgba the RGBA samples, the first row first
     * @param stride the distance in bytes between rows
     * @param width the width in pixels
     * @param height the height in pixels
     */
    public static boolean hasTransparency(byte[] rgba, int stride, int width, int height) {
        return !ImageOps.isOpaque(RgbaPicture.of(width, height, stride, rgba, true));
    }

    /**
     * Copies the alpha channel of rows to the alpha plane.
     *
     * @param rgba the RGBA samples
     * @param src the index of the first byte of the first row
     * @param stride the distance in bytes between the rows
     * @param width the width in pixels
     * @param rows the number of rows
     * @param alpha the alpha plane
     * @param alphaPos the index in {@code alpha} of the first row
     * @return whether some copied alpha is below 255
     */
    private static boolean extractAlpha(byte[] rgba, int src, int stride, int width, int rows, byte[] alpha, int alphaPos) {
        var mask = 0xFF;
        for (var row = 0; row < rows; row++) {
            var p = src + row * stride + 3;
            var d = alphaPos + row * width;
            for (var x = 0; x < width; x++) {
                var value = rgba[p + (x << 2)];
                alpha[d + x] = value;
                mask &= value;
            }
        }
        return mask != 0xFF;
    }

    /**
     * Returns the gamma-domain value of a linear sum, interpolated from
     * {@link #LINEAR_TO_GAMMA}.
     *
     * @param base the linear value (a sum of up to four linearized samples)
     * @param shift the left shift scaling {@code base} to a four-sample sum
     * @return the gamma-domain value, four times the average
     */
    private static int linearToGamma(int base, int shift) {
        var v = base << shift;
        var tabPos = v >> 9;
        var x = v & 511;
        var v0 = LINEAR_TO_GAMMA[tabPos];
        var v1 = LINEAR_TO_GAMMA[tabPos + 1];
        var y = v1 * x + v0 * (512 - x);
        return (y + 64) >> 7;
    }

    /**
     * Returns the gamma-domain sum of a 2x2 block of one channel.
     *
     * @param rgba the RGBA samples
     * @param p the index of the channel of the top-left pixel
     * @param step the distance to the right pixel, 4 or 0
     * @param stride the distance to the lower pixel
     * @return four times the gamma-averaged value
     */
    private static int sum4(byte[] rgba, int p, int step, int stride) {
        return linearToGamma(GAMMA_TO_LINEAR[rgba[p] & 0xFF] + GAMMA_TO_LINEAR[rgba[p + step] & 0xFF]
                + GAMMA_TO_LINEAR[rgba[p + stride] & 0xFF] + GAMMA_TO_LINEAR[rgba[p + stride + step] & 0xFF], 0);
    }

    /**
     * Returns the gamma-domain sum of a vertical pair of one channel.
     *
     * @param rgba the RGBA samples
     * @param p the index of the channel of the upper pixel
     * @param stride the distance to the lower pixel
     * @return four times the gamma-averaged value
     */
    private static int sum2(byte[] rgba, int p, int stride) {
        return linearToGamma(GAMMA_TO_LINEAR[rgba[p] & 0xFF] + GAMMA_TO_LINEAR[rgba[p + stride] & 0xFF], 1);
    }

    /**
     * Returns the alpha-weighted gamma-domain average of a 2x2 block of one channel.
     *
     * @param rgba the RGBA samples
     * @param p the index of the channel of the top-left pixel
     * @param alpha the index of the alpha of the top-left pixel
     * @param totalA the sum of the four alpha values, from 1 to 1019
     * @param step the distance to the right pixel, 4 or 0
     * @param stride the distance to the lower pixel
     * @return four times the weighted average
     */
    private static int linearToGammaWeighted(byte[] rgba, int p, int alpha, int totalA, int step, int stride) {
        var sum = (rgba[alpha] & 0xFF) * GAMMA_TO_LINEAR[rgba[p] & 0xFF]
                + (rgba[alpha + step] & 0xFF) * GAMMA_TO_LINEAR[rgba[p + step] & 0xFF]
                + (rgba[alpha + stride] & 0xFF) * GAMMA_TO_LINEAR[rgba[p + stride] & 0xFF]
                + (rgba[alpha + stride + step] & 0xFF) * GAMMA_TO_LINEAR[rgba[p + stride + step] & 0xFF];
        var divided = (int) (((long) sum * INV_ALPHA[totalA]) >>> (ALPHA_FIX - 2));
        return linearToGamma(divided, 0);
    }

    /**
     * Accumulates the gamma-averaged RGB of every 2x2 block of a row pair, ignoring alpha.
     *
     * @param rgba the RGBA samples
     * @param src the index of the first byte of the upper row
     * @param stride the distance to the lower row, or {@code 0} to repeat the upper row
     * @param dst the destination, four entries per chroma sample
     * @param width the width in pixels
     */
    private static void accumulateRgb(byte[] rgba, int src, int stride, int[] dst, int width) {
        var d = 0;
        var j = src;
        for (var i = 0; i < (width >> 1); i++, j += 8, d += 4) {
            dst[d] = sum4(rgba, j, 4, stride);
            dst[d + 1] = sum4(rgba, j + 1, 4, stride);
            dst[d + 2] = sum4(rgba, j + 2, 4, stride);
        }
        if ((width & 1) != 0) {
            dst[d] = sum2(rgba, j, stride);
            dst[d + 1] = sum2(rgba, j + 1, stride);
            dst[d + 2] = sum2(rgba, j + 2, stride);
        }
    }

    /**
     * Accumulates the alpha-weighted gamma-averaged RGB of every 2x2 block of a row pair.
     *
     * @param rgba the RGBA samples
     * @param src the index of the first byte of the upper row
     * @param stride the distance to the lower row, or {@code 0} to repeat the upper row
     * @param dst the destination, four entries per chroma sample
     * @param width the width in pixels
     */
    private static void accumulateRgba(byte[] rgba, int src, int stride, int[] dst, int width) {
        var d = 0;
        var j = src;
        for (var i = 0; i < (width >> 1); i++, j += 8, d += 4) {
            var a = (rgba[j + 3] & 0xFF) + (rgba[j + 3 + stride] & 0xFF) + (rgba[j + 7] & 0xFF) + (rgba[j + 7 + stride] & 0xFF);
            if (a == 4 * 0xFF || a == 0) {
                dst[d] = sum4(rgba, j, 4, stride);
                dst[d + 1] = sum4(rgba, j + 1, 4, stride);
                dst[d + 2] = sum4(rgba, j + 2, 4, stride);
            } else {
                dst[d] = linearToGammaWeighted(rgba, j, j + 3, a, 4, stride);
                dst[d + 1] = linearToGammaWeighted(rgba, j + 1, j + 3, a, 4, stride);
                dst[d + 2] = linearToGammaWeighted(rgba, j + 2, j + 3, a, 4, stride);
            }
            dst[d + 3] = a;
        }
        if ((width & 1) != 0) {
            var a = 2 * ((rgba[j + 3] & 0xFF) + (rgba[j + 3 + stride] & 0xFF));
            if (a == 4 * 0xFF || a == 0) {
                dst[d] = sum2(rgba, j, stride);
                dst[d + 1] = sum2(rgba, j + 1, stride);
                dst[d + 2] = sum2(rgba, j + 2, stride);
            } else {
                dst[d] = linearToGammaWeighted(rgba, j, j + 3, a, 0, stride);
                dst[d + 1] = linearToGammaWeighted(rgba, j + 1, j + 3, a, 0, stride);
                dst[d + 2] = linearToGammaWeighted(rgba, j + 2, j + 3, a, 0, stride);
            }
            dst[d + 3] = a;
        }
    }

    /**
     * Converts accumulated RGB sums to one row of Cb and Cr.
     *
     * @param rgb the accumulated sums, four entries per chroma sample
     * @param u the Cb plane
     * @param v the Cr plane
     * @param pos the index of the chroma row
     * @param width the chroma width
     */
    private static void convertToUv(int[] rgb, byte[] u, byte[] v, int pos, int width) {
        for (var i = 0; i < width; i++) {
            var r = rgb[4 * i];
            var g = rgb[4 * i + 1];
            var b = rgb[4 * i + 2];
            u[pos + i] = (byte) ScalarYuvDsp.rgbToU(r, g, b, UV_ROUNDING);
            v[pos + i] = (byte) ScalarYuvDsp.rgbToV(r, g, b, UV_ROUNDING);
        }
    }

    /**
     * Replaces the samples of fully transparent regions with values that compress better.
     *
     * <p>Every 8x8 luma block (4x4 chroma) whose alpha is all zero is flattened to the samples of
     * the first such block of its run; in partially transparent blocks the luma of transparent
     * pixels is replaced by the average luma of the visible ones. Visible pixels are never
     * changed. Pictures without an alpha plane are left untouched.
     *
     * @param picture the 4:2:0 picture to modify in place
     */
    public static void cleanupTransparentArea(YuvPicture picture) {
        var a = picture.alpha();
        if (a == null || picture.chromaFormat() != ChromaFormat.YUV420) {
            return;
        }
        var width = picture.width();
        var height = picture.height();
        var yStride = picture.yStride();
        var uvStride = picture.uvStride();
        var aStride = picture.alphaStride();
        var yPlane = picture.y();
        var uPlane = picture.u();
        var vPlane = picture.v();
        var aPos = 0;
        var yPos = 0;
        var uvPos = 0;
        int y;
        for (y = 0; y + CLEANUP_BLOCK <= height; y += CLEANUP_BLOCK) {
            var needReset = true;
            var value0 = 0;
            var value1 = 0;
            var value2 = 0;
            int x;
            for (x = 0; x + CLEANUP_BLOCK <= width; x += CLEANUP_BLOCK) {
                if (smoothenBlock(a, aPos + x, aStride, yPlane, yPos + x, yStride, CLEANUP_BLOCK, CLEANUP_BLOCK)) {
                    if (needReset) {
                        value0 = yPlane[yPos + x];
                        value1 = uPlane[uvPos + (x >> 1)];
                        value2 = vPlane[uvPos + (x >> 1)];
                        needReset = false;
                    }
                    flatten(yPlane, yPos + x, value0, yStride, CLEANUP_BLOCK);
                    flatten(uPlane, uvPos + (x >> 1), value1, uvStride, CLEANUP_BLOCK / 2);
                    flatten(vPlane, uvPos + (x >> 1), value2, uvStride, CLEANUP_BLOCK / 2);
                } else {
                    needReset = true;
                }
            }
            if (x < width) {
                smoothenBlock(a, aPos + x, aStride, yPlane, yPos + x, yStride, width - x, CLEANUP_BLOCK);
            }
            aPos += CLEANUP_BLOCK * aStride;
            yPos += CLEANUP_BLOCK * yStride;
            uvPos += (CLEANUP_BLOCK / 2) * uvStride;
        }
        if (y < height) {
            var subHeight = height - y;
            int x;
            for (x = 0; x + CLEANUP_BLOCK <= width; x += CLEANUP_BLOCK) {
                smoothenBlock(a, aPos + x, aStride, yPlane, yPos + x, yStride, CLEANUP_BLOCK, subHeight);
            }
            if (x < width) {
                smoothenBlock(a, aPos + x, aStride, yPlane, yPos + x, yStride, width - x, subHeight);
            }
        }
    }

    /**
     * Replaces the luma of transparent pixels of a block with the average luma of its visible
     * pixels.
     *
     * @param a the alpha plane
     * @param aPos the index of the block in the alpha plane
     * @param aStride the alpha stride
     * @param y the luma plane
     * @param yPos the index of the block in the luma plane
     * @param yStride the luma stride
     * @param width the block width
     * @param height the block height
     * @return whether the block is fully transparent
     */
    private static boolean smoothenBlock(byte[] a, int aPos, int aStride, byte[] y, int yPos, int yStride, int width, int height) {
        var sum = 0;
        var count = 0;
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                if (a[aPos + row * aStride + x] != 0) {
                    ++count;
                    sum += y[yPos + row * yStride + x] & 0xFF;
                }
            }
        }
        if (count > 0 && count < width * height) {
            var avg = (byte) (sum / count);
            for (var row = 0; row < height; row++) {
                for (var x = 0; x < width; x++) {
                    if (a[aPos + row * aStride + x] == 0) {
                        y[yPos + row * yStride + x] = avg;
                    }
                }
            }
        }
        return count == 0;
    }

    /**
     * Fills a square block with one value.
     *
     * @param plane the samples, modified in place
     * @param pos the index of the block
     * @param value the fill value
     * @param stride the plane stride
     * @param size the block side
     */
    private static void flatten(byte[] plane, int pos, int value, int stride, int size) {
        for (var row = 0; row < size; row++) {
            Arrays.fill(plane, pos + row * stride, pos + row * stride + size, (byte) value);
        }
    }
}
