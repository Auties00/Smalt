/* Ports libjpeg-turbo (jdcolor.c). Copyright (C) 1991-1997, Thomas G. Lane; Copyright (C) 2009-2025,
   D. R. Commander and the libjpeg-turbo authors; IJG License. Modified for Smalt. */
package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Turns decoded component planes into a {@link Picture}.
 *
 * <p>Gray and YCbCr frames become a {@link YuvPicture} with {@link ColorInfo#JPEG}. The planes are
 * handed out without copying when the sampling factors map onto a {@link ChromaFormat} (luma at the
 * largest factors, both chroma components equal, and a ratio of 1x1, 2x1 or 2x2); any other layout
 * is upsampled to {@link ChromaFormat#YUV444}. RGB, CMYK and YCCK frames become an opaque
 * {@link RgbaPicture}. CMYK samples are taken as stored inverted, the convention of Adobe
 * applications, so a pixel is {@code R = C * K / 255} (rounded) and likewise for G and B; YCCK is
 * first converted to that CMYK form.
 *
 * @implNote This implementation follows libjpeg-turbo's {@code ycc_rgb_convert} tables,
 * {@code ycck_cmyk_convert} and {@code cmyk_to_rgb} for the color conversions.
 */
public final class JpegOutput {
    /**
     * The number of fraction bits of the color conversion tables.
     */
    private static final int SCALE_BITS = 16;

    /**
     * One half in the fixed-point format of the color conversion tables.
     */
    private static final int ONE_HALF = 1 << (SCALE_BITS - 1);

    /**
     * The red contribution of each Cr sample, {@code round(1.402 * (cr - 128))}.
     */
    private static final int[] CR_R = table(1.40200, true, 0);

    /**
     * The blue contribution of each Cb sample, {@code round(1.772 * (cb - 128))}.
     */
    private static final int[] CB_B = table(1.77200, true, 0);

    /**
     * The green contribution of each Cr sample in fixed point, {@code -0.71414 * (cr - 128)}.
     */
    private static final int[] CR_G = table(-0.71414, false, 0);

    /**
     * The green contribution of each Cb sample in fixed point plus one half,
     * {@code -0.34414 * (cb - 128)}.
     */
    private static final int[] CB_G = table(-0.34414, false, ONE_HALF);

    /**
     * Builds one of libjpeg's {@code build_ycc_rgb_table} tables (jdcolor.c).
     *
     * @param factor the conversion factor applied to the centered chroma sample
     * @param round whether the entry is rounded to an integer, rather than kept in fixed point
     * @param bias the constant added to a fixed-point entry
     * @return the 256 entries
     */
    private static int[] table(double factor, boolean round, int bias) {
        var table = new int[256];
        var fixed = factor < 0 ? -fix(-factor) : fix(factor);
        for (var i = 0; i < 256; i++) {
            var x = i - 128;
            table[i] = round ? (fixed * x + ONE_HALF) >> SCALE_BITS : fixed * x + bias;
        }
        return table;
    }

    /**
     * Prevents instantiation.
     */
    private JpegOutput() {
        throw new AssertionError();
    }

    /**
     * Converts a constant to the fixed-point format of the color conversion tables.
     *
     * @param value the constant
     * @return {@code round(value * 2^16)}
     */
    private static int fix(double value) {
        return (int) (value * (1 << SCALE_BITS) + 0.5);
    }

    /**
     * Builds the picture of a decoded frame.
     *
     * @param planes the decoded planes
     * @param colorSpace the frame's color space
     * @param limits the resource bounds checked before every allocation
     * @return a {@link YuvPicture} for gray and YCbCr frames, an {@link RgbaPicture} otherwise
     */
    public static Picture toPicture(JpegPlanes planes, JpegColorSpace colorSpace, MediaLimits limits) {
        return switch (colorSpace) {
            case GRAY, YCBCR -> toYuv(planes, colorSpace, limits);
            case RGB -> toRgba(planes, false, limits);
            case CMYK -> toRgba(planes, true, limits);
            case YCCK -> ycckToRgba(planes, limits);
        };
    }

    /**
     * Builds the planar picture of a gray or YCbCr frame.
     *
     * @param planes the decoded planes
     * @param colorSpace {@link JpegColorSpace#GRAY} or {@link JpegColorSpace#YCBCR}
     * @param limits the resource bounds checked before every allocation
     * @return the picture
     * @throws MediaException.Unsupported if the color space is neither gray nor YCbCr
     */
    public static YuvPicture toYuv(JpegPlanes planes, JpegColorSpace colorSpace, MediaLimits limits) {
        var width = planes.width();
        var height = planes.height();
        if (colorSpace == JpegColorSpace.GRAY) {
            return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.MONOCHROME).color(ColorInfo.JPEG)
                    .luma(planes.plane(0), planes.stride(0))
                    .build();
        }
        if (colorSpace != JpegColorSpace.YCBCR) {
            throw new MediaException.Unsupported("jpeg " + colorSpace.name().toLowerCase() + " color space",
                    "a JPEG coded as " + colorSpace + " has no planar Y'CbCr form");
        }
        var frame = planes.frame();
        var luma = frame.components[0];
        var cb = frame.components[1];
        var cr = frame.components[2];
        var format = directFormat(frame, luma, cb, cr);
        if (format != null) {
            return YuvPicture.builder().size(width, height).chromaFormat(format).color(ColorInfo.JPEG)
                    .luma(planes.plane(0), planes.stride(0))
                    .chroma(planes.plane(1), planes.plane(2), planes.stride(1))
                    .build();
        }
        LimitChecks.checkAllocation(limits, (long) width * height);
        byte[] y;
        int yStride;
        if (luma.h == frame.hMax && luma.v == frame.vMax) {
            y = planes.plane(0);
            yStride = planes.stride(0);
        } else {
            y = fullResolution(planes, 0);
            yStride = width;
        }
        var u = fullResolution(planes, 1);
        var v = fullResolution(planes, 2);
        return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.YUV444).color(ColorInfo.JPEG)
                .luma(y, yStride)
                .chroma(u, v, width)
                .build();
    }

    /**
     * Returns the chroma format whose layout matches the frame's sampling factors exactly.
     *
     * @param frame the frame whose sampling factors are matched
     * @param luma the luma component
     * @param cb the Cb component
     * @param cr the Cr component
     * @return the matching format, or {@code null} when the planes need upsampling
     */
    private static ChromaFormat directFormat(JpegFrame frame, JpegComponent luma, JpegComponent cb, JpegComponent cr) {
        if (luma.h != frame.hMax || luma.v != frame.vMax || cb.h != cr.h || cb.v != cr.v) {
            return null;
        }
        if (luma.h % cb.h != 0 || luma.v % cb.v != 0) {
            return null;
        }
        var rx = luma.h / cb.h;
        var ry = luma.v / cb.v;
        if (rx == 1 && ry == 1) {
            return ChromaFormat.YUV444;
        }
        if (rx == 2 && ry == 1) {
            return ChromaFormat.YUV422;
        }
        if (rx == 2 && ry == 2) {
            return ChromaFormat.YUV420;
        }
        return null;
    }

    /**
     * Returns one component at the picture resolution, upsampling it when needed.
     *
     * @param planes the decoded planes
     * @param component the frame component index
     * @return the samples with a tight stride equal to the picture width
     */
    private static byte[] fullResolution(JpegPlanes planes, int component) {
        var frame = planes.frame();
        var info = frame.components[component];
        return Upsampler.upsample(planes.plane(component), planes.stride(component),
                planes.componentWidth(component), planes.componentHeight(component), info.h, info.v,
                frame.hMax, frame.vMax, planes.width(), planes.height(), planes.scaleShift() < 3);
    }

    /**
     * Returns the source row and stride of a component at the picture resolution.
     *
     * @param planes the decoded planes
     * @param component the frame component index
     * @return the full-resolution samples; the stride is the plane stride when no upsampling was
     * needed and the picture width otherwise, see {@link #fullStride}
     */
    private static byte[] fullPlane(JpegPlanes planes, int component) {
        var frame = planes.frame();
        var info = frame.components[component];
        if (info.h == frame.hMax && info.v == frame.vMax) {
            return planes.plane(component);
        }
        return fullResolution(planes, component);
    }

    /**
     * Returns the stride of the plane {@link #fullPlane} returns.
     *
     * @param planes the decoded planes
     * @param component the frame component index
     * @return the stride
     */
    private static int fullStride(JpegPlanes planes, int component) {
        var frame = planes.frame();
        var info = frame.components[component];
        return info.h == frame.hMax && info.v == frame.vMax ? planes.stride(component) : planes.width();
    }

    /**
     * Builds the RGBA picture of an RGB or CMYK frame.
     *
     * @param planes the decoded planes
     * @param cmyk whether the frame is inverted CMYK rather than RGB
     * @param limits the resource bounds checked before every allocation
     * @return the opaque picture
     */
    private static RgbaPicture toRgba(JpegPlanes planes, boolean cmyk, MediaLimits limits) {
        var width = planes.width();
        var height = planes.height();
        LimitChecks.checkAllocation(limits, (long) width * height * 4);
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        var p0 = fullPlane(planes, 0);
        var p1 = fullPlane(planes, 1);
        var p2 = fullPlane(planes, 2);
        var s0 = fullStride(planes, 0);
        var s1 = fullStride(planes, 1);
        var s2 = fullStride(planes, 2);
        var p3 = cmyk ? fullPlane(planes, 3) : null;
        var s3 = cmyk ? fullStride(planes, 3) : 0;
        for (var y = 0; y < height; y++) {
            var out = y * width * 4;
            for (var x = 0; x < width; x++) {
                var c0 = p0[y * s0 + x] & 0xFF;
                var c1 = p1[y * s1 + x] & 0xFF;
                var c2 = p2[y * s2 + x] & 0xFF;
                if (cmyk) {
                    var k = p3[y * s3 + x] & 0xFF;
                    c0 = divide255(c0 * k);
                    c1 = divide255(c1 * k);
                    c2 = divide255(c2 * k);
                }
                pixels[out] = (byte) c0;
                pixels[out + 1] = (byte) c1;
                pixels[out + 2] = (byte) c2;
                pixels[out + 3] = (byte) 255;
                out += 4;
            }
        }
        return picture;
    }

    /**
     * Builds the RGBA picture of a YCCK frame.
     *
     * @param planes the decoded planes
     * @param limits the resource bounds checked before every allocation
     * @return the opaque picture
     */
    private static RgbaPicture ycckToRgba(JpegPlanes planes, MediaLimits limits) {
        var width = planes.width();
        var height = planes.height();
        LimitChecks.checkAllocation(limits, (long) width * height * 4);
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        var p0 = fullPlane(planes, 0);
        var p1 = fullPlane(planes, 1);
        var p2 = fullPlane(planes, 2);
        var p3 = fullPlane(planes, 3);
        var s0 = fullStride(planes, 0);
        var s1 = fullStride(planes, 1);
        var s2 = fullStride(planes, 2);
        var s3 = fullStride(planes, 3);
        for (var y = 0; y < height; y++) {
            var out = y * width * 4;
            for (var x = 0; x < width; x++) {
                var cmy = ycckToCmy(p0[y * s0 + x] & 0xFF, p1[y * s1 + x] & 0xFF, p2[y * s2 + x] & 0xFF);
                var k = p3[y * s3 + x] & 0xFF;
                pixels[out] = (byte) divide255((cmy >>> 16) * k);
                pixels[out + 1] = (byte) divide255(((cmy >>> 8) & 0xFF) * k);
                pixels[out + 2] = (byte) divide255((cmy & 0xFF) * k);
                pixels[out + 3] = (byte) 255;
                out += 4;
            }
        }
        return picture;
    }

    /**
     * Returns the CMYK samples of a CMYK or YCCK frame as stored, interleaved four per pixel with
     * a tight stride, at the picture resolution.
     *
     * <p>YCCK samples are first converted to the stored CMYK form, as libjpeg-turbo's
     * {@code ycck_cmyk_convert} does: {@code C = 255 - R(Y, Cb, Cr)}, likewise for M and Y, and K
     * unchanged.
     *
     * @param planes the decoded planes
     * @param colorSpace {@link JpegColorSpace#CMYK} or {@link JpegColorSpace#YCCK}
     * @param limits the resource bounds checked before every allocation
     * @return the samples
     * @throws MediaException.Unsupported if the frame is neither CMYK nor YCCK
     */
    public static byte[] toCmyk(JpegPlanes planes, JpegColorSpace colorSpace, MediaLimits limits) {
        if (colorSpace != JpegColorSpace.CMYK && colorSpace != JpegColorSpace.YCCK) {
            throw new MediaException.Unsupported("jpeg " + colorSpace.name().toLowerCase() + " color space",
                    "a JPEG coded as " + colorSpace + " has no CMYK form");
        }
        var width = planes.width();
        var height = planes.height();
        LimitChecks.checkAllocation(limits, (long) width * height * 4);
        var out = new byte[width * height * 4];
        var p0 = fullPlane(planes, 0);
        var p1 = fullPlane(planes, 1);
        var p2 = fullPlane(planes, 2);
        var p3 = fullPlane(planes, 3);
        var s0 = fullStride(planes, 0);
        var s1 = fullStride(planes, 1);
        var s2 = fullStride(planes, 2);
        var s3 = fullStride(planes, 3);
        var ycck = colorSpace == JpegColorSpace.YCCK;
        for (var y = 0; y < height; y++) {
            var o = y * width * 4;
            for (var x = 0; x < width; x++) {
                var c0 = p0[y * s0 + x] & 0xFF;
                var c1 = p1[y * s1 + x] & 0xFF;
                var c2 = p2[y * s2 + x] & 0xFF;
                if (ycck) {
                    var cmy = ycckToCmy(c0, c1, c2);
                    c0 = cmy >>> 16;
                    c1 = (cmy >>> 8) & 0xFF;
                    c2 = cmy & 0xFF;
                }
                out[o] = (byte) c0;
                out[o + 1] = (byte) c1;
                out[o + 2] = (byte) c2;
                out[o + 3] = p3[y * s3 + x];
                o += 4;
            }
        }
        return out;
    }

    /**
     * Converts the Y, Cb and Cr samples of one YCCK pixel to the C, M and Y samples of its stored
     * CMYK form ({@code ycck_cmyk_convert}): {@code C = 255 - R(Y, Cb, Cr)}, likewise for M and Y.
     *
     * @param luma the Y sample
     * @param cb the Cb sample
     * @param cr the Cr sample
     * @return the samples packed as {@code (c << 16) | (m << 8) | y}
     */
    private static int ycckToCmy(int luma, int cb, int cr) {
        var c = clamp(255 - (luma + CR_R[cr]));
        var m = clamp(255 - (luma + ((CB_G[cb] + CR_G[cr]) >> SCALE_BITS)));
        var y = clamp(255 - (luma + CB_B[cb]));
        return (c << 16) | (m << 8) | y;
    }

    /**
     * Converts one YCbCr sample to RGB with libjpeg's tables.
     *
     * @param luma the Y sample
     * @param cb the Cb sample
     * @param cr the Cr sample
     * @return the pixel packed as {@code (r << 16) | (g << 8) | b}
     */
    public static int yccToRgb(int luma, int cb, int cr) {
        var r = clamp(luma + CR_R[cr]);
        var g = clamp(luma + ((CB_G[cb] + CR_G[cr]) >> SCALE_BITS));
        var b = clamp(luma + CB_B[cb]);
        return (r << 16) | (g << 8) | b;
    }

    /**
     * Clamps a value to the sample range.
     *
     * @param value the number, possibly out of range
     * @return the value limited to 0..255
     */
    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    /**
     * Divides a product of two samples by 255, rounding to nearest.
     *
     * @param product the product, from 0 to {@code 255 * 255}
     * @return {@code round(product / 255)}
     */
    static int divide255(int product) {
        var t = product + 128;
        return (t + (t >> 8)) >> 8;
    }
}
