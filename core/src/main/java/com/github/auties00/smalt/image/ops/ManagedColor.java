package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Optional;

/**
 * Applies color transforms to pictures.
 *
 * <p>Pictures are converted at their own depth: 8-bit pictures through packed 8-bit RGBA, 16-bit
 * pictures through packed 16-bit RGBA. Y'CbCr pictures are first converted to R'G'B' with the
 * matrix and range of their {@link ColorInfo}: 8-bit ones as {@link ImageOps#toRgba(Picture)}
 * does, 16-bit ones in floating point with bilinear chroma interpolation, keeping their full
 * precision. A gray source profile reads the red channel; a gray target profile writes the same
 * value to red, green and blue. Alpha is never changed.
 */
final class ManagedColor {
    /**
     * The approximate number of operations per pixel of a color transform, which weighs its work
     * against {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long WORK = 24;

    /**
     * Prevents instantiation.
     */
    private ManagedColor() {
        throw new AssertionError();
    }

    /**
     * Applies a transform to a picture.
     *
     * @param picture the picture to convert
     * @param transform the transform, from and to an RGB or gray profile
     * @return an {@link RgbaPicture} for an 8-bit picture, an {@link Rgba16Picture} for a 16-bit one
     * @throws IllegalArgumentException if a profile of the transform is neither RGB nor gray
     */
    static Picture convert(Picture picture, ColorTransform transform) {
        var in = transform.inputChannels();
        var out = transform.outputChannels();
        if ((in != 1 && in != 3) || (out != 1 && out != 3)) {
            throw new IllegalArgumentException("a picture converts only between RGB and gray profiles, not "
                    + transform.source().colorSpace() + " to " + transform.target().colorSpace());
        }
        return switch (picture) {
            case RgbaPicture rgba -> convert8(rgba, transform);
            case YuvPicture yuv -> convert8(ImageOps.toRgba(yuv), transform);
            case Rgba16Picture rgba -> convert16(rgba, transform);
            case Yuv16Picture yuv -> convert16(toRgba16(yuv), transform);
        };
    }

    /**
     * Converts a picture to 8-bit sRGB.
     *
     * @param picture the picture to convert
     * @param options the transform options
     * @return the sRGB picture
     */
    static RgbaPicture toSrgb(Picture picture, ColorTransformOptions options) {
        return switch (picture) {
            case RgbaPicture rgba -> rgba;
            case Rgba16Picture rgba -> ImageOps.toEightBit(rgba);
            case YuvPicture yuv -> {
                var profile = ColorProfile.of(yuv.color());
                if (profile.isSrgb()) {
                    yield ImageOps.toRgba(yuv);
                }
                yield (RgbaPicture) convert8(ImageOps.toRgba(yuv), ColorTransform.create(profile, ColorProfile.srgb(), options));
            }
            case Yuv16Picture yuv -> {
                var profile = ColorProfile.of(yuv.color());
                if (profile.isSrgb()) {
                    yield ImageOps.toRgba(ImageOps.toEightBit(yuv));
                }
                var converted = convert16(toRgba16(yuv), ColorTransform.create(profile, ColorProfile.srgb(), options));
                yield ImageOps.toEightBit(converted);
            }
        };
    }

    /**
     * Converts a decoded image to sRGB with its declared profile, leaving it unchanged when the
     * profile is absent, is sRGB, or cannot be applied to its pixels.
     *
     * @param image the decoded image
     * @param options the transform options
     * @return the converted image, or {@code image} itself
     */
    static DecodedImage toSrgb(DecodedImage image, ColorTransformOptions options) {
        var profile = image.colorProfile().orElse(null);
        if (profile == null || profile.isSrgb() || (profile.channels() != 1 && profile.channels() != 3)) {
            return image;
        }
        Picture converted;
        try {
            var transform = ColorTransform.create(profile, ColorProfile.srgb(), options);
            converted = convert(image.picture(), transform);
        } catch (MediaException.Malformed | MediaException.Unsupported exception) {
            return image;
        }
        return new DecodedImage(converted, image.format(), image.orientation(), image.sourceWidth(),
                image.sourceHeight(), Optional.empty(), image.exif(), Optional.of(ColorProfile.srgb()));
    }

    /**
     * Applies a transform to an 8-bit RGBA picture.
     *
     * @param picture the picture to convert
     * @param transform the color transform to apply
     * @return the converted picture
     */
    private static Picture convert8(RgbaPicture picture, ColorTransform transform) {
        var width = picture.width();
        var height = picture.height();
        OpChecks.checkArray(4L * width * height);
        var out = RgbaPicture.allocate(width, height, picture.hasAlpha());
        var src = picture.pixels();
        var dst = out.pixels();
        var gray = transform.outputChannels() == 1;
        var bands = ParallelBands.bands(height, WORK * width * height);
        ParallelBands.run(bands, band -> {
            var top = ParallelBands.start(band, bands, height);
            var bottom = ParallelBands.start(band + 1, bands, height);
            for (var row = top; row < bottom; row++) {
                var d = row * out.stride();
                System.arraycopy(src, row * picture.stride(), dst, d, 4 * width);
                transform.transform(dst, d, 4, dst, d, 4, width);
                if (gray) {
                    for (var x = 0; x < width; x++) {
                        var value = dst[d + 4 * x];
                        dst[d + 4 * x + 1] = value;
                        dst[d + 4 * x + 2] = value;
                    }
                }
            }
        });
        return out;
    }

    /**
     * Applies a transform to a 16-bit RGBA picture.
     *
     * @param picture the picture to convert
     * @param transform the color transform to apply
     * @return the converted picture
     */
    private static Rgba16Picture convert16(Rgba16Picture picture, ColorTransform transform) {
        var width = picture.width();
        var height = picture.height();
        OpChecks.checkArray(4L * width * height);
        var out = Rgba16Picture.allocate(width, height, picture.hasAlpha());
        var src = picture.pixels();
        var dst = out.pixels();
        var gray = transform.outputChannels() == 1;
        var bands = ParallelBands.bands(height, WORK * width * height);
        ParallelBands.run(bands, band -> {
            var top = ParallelBands.start(band, bands, height);
            var bottom = ParallelBands.start(band + 1, bands, height);
            for (var row = top; row < bottom; row++) {
                var d = row * out.stride();
                System.arraycopy(src, row * picture.stride(), dst, d, 4 * width);
                transform.transform(dst, d, 4, dst, d, 4, width);
                if (gray) {
                    for (var x = 0; x < width; x++) {
                        var value = dst[d + 4 * x];
                        dst[d + 4 * x + 1] = value;
                        dst[d + 4 * x + 2] = value;
                    }
                }
            }
        });
        return out;
    }

    /**
     * Converts a 16-bit planar picture to 16-bit packed R'G'B' at full precision, interpolating
     * chroma bilinearly at sample positions centered between luma samples.
     *
     * @param picture the picture to convert
     * @return the packed picture; alpha is rescaled to 16 bits, absent alpha becomes opaque
     */
    static Rgba16Picture toRgba16(Yuv16Picture picture) {
        var width = picture.width();
        var height = picture.height();
        OpChecks.checkArray(4L * width * height);
        var matrix = ColorMatrix.of(picture.color());
        var out = Rgba16Picture.allocate(width, height, picture.hasAlpha());
        var bands = ParallelBands.bands(height, WORK * width * height);
        ParallelBands.run(bands, band -> toRgba16Rows(picture, matrix, out, ParallelBands.start(band, bands, height),
                ParallelBands.start(band + 1, bands, height)));
        return out;
    }

    /**
     * Converts the rows {@code [top, bottom)} of a 16-bit planar picture.
     *
     * @param picture the picture to convert
     * @param matrix the conversion coefficients
     * @param out the destination
     * @param top the first row
     * @param bottom the row after the last one
     */
    private static void toRgba16Rows(Yuv16Picture picture, ColorMatrix matrix, Rgba16Picture out, int top, int bottom) {
        var width = picture.width();
        var depth = picture.bitDepth();
        var unit = (float) (1.0 / (1 << (depth - 8)));
        var alphaScale = 65535.0f / ((1 << depth) - 1);
        var format = picture.chromaFormat();
        var hasChroma = format.hasChroma();
        var shiftX = format.shiftX();
        var shiftY = format.shiftY();
        var chromaWidth = picture.chromaWidth();
        var chromaHeight = picture.chromaHeight();
        var offsets = matrix.offsets;
        var r0 = matrix.ir[0] / 65536f;
        var r1 = matrix.ir[1] / 65536f;
        var r2 = matrix.ir[2] / 65536f;
        var g0 = matrix.ig[0] / 65536f;
        var g1 = matrix.ig[1] / 65536f;
        var g2 = matrix.ig[2] / 65536f;
        var b0 = matrix.ib[0] / 65536f;
        var b1 = matrix.ib[1] / 65536f;
        var b2 = matrix.ib[2] / 65536f;
        var grayScale = matrix.grayScale / 65536f;
        var full = matrix.range == ColorRange.FULL;
        var y = picture.y();
        var u = picture.u();
        var v = picture.v();
        var alpha = picture.alpha();
        var pixels = out.pixels();
        for (var row = top; row < bottom; row++) {
            var o = row * out.stride();
            var cy = ((row + 0.5f) / (1 << shiftY)) - 0.5f;
            var cy0 = Math.max(0, Math.min(chromaHeight - 1, (int) Math.floor(cy)));
            var cy1 = Math.min(chromaHeight - 1, cy0 + 1);
            var fy = Math.max(0f, Math.min(1f, cy - (float) Math.floor(cy)));
            if (cy < 0) {
                fy = 0;
            }
            for (var x = 0; x < width; x++) {
                var luma = (y[row * picture.yStride() + x] & 0xFFFF) * unit - offsets[0];
                float red;
                float green;
                float blue;
                if (hasChroma) {
                    var cx = ((x + 0.5f) / (1 << shiftX)) - 0.5f;
                    var cx0 = Math.max(0, Math.min(chromaWidth - 1, (int) Math.floor(cx)));
                    var cx1 = Math.min(chromaWidth - 1, cx0 + 1);
                    var fx = cx < 0 ? 0f : Math.max(0f, Math.min(1f, cx - (float) Math.floor(cx)));
                    var stride = picture.uvStride();
                    var cb = bilinear(u, stride, cx0, cx1, cy0, cy1, fx, fy) * unit - offsets[1];
                    var cr = bilinear(v, stride, cx0, cx1, cy0, cy1, fx, fy) * unit - offsets[2];
                    red = r0 * luma + r1 * cb + r2 * cr;
                    green = g0 * luma + g1 * cb + g2 * cr;
                    blue = b0 * luma + b1 * cb + b2 * cr;
                } else {
                    red = green = blue = full ? luma : luma * grayScale;
                }
                pixels[o] = toSample(red);
                pixels[o + 1] = toSample(green);
                pixels[o + 2] = toSample(blue);
                pixels[o + 3] = alpha == null ? (short) 0xFFFF
                        : (short) Math.round((alpha[row * picture.alphaStride() + x] & 0xFFFF) * alphaScale);
                o += 4;
            }
        }
    }

    /**
     * Interpolates a chroma plane bilinearly.
     *
     * @param plane the chroma samples
     * @param stride the row stride
     * @param x0 the left column
     * @param x1 the right column
     * @param y0 the top row
     * @param y1 the bottom row
     * @param fx the horizontal fraction
     * @param fy the vertical fraction
     * @return the interpolated sample
     */
    private static float bilinear(short[] plane, int stride, int x0, int x1, int y0, int y1, float fx, float fy) {
        var a = plane[y0 * stride + x0] & 0xFFFF;
        var b = plane[y0 * stride + x1] & 0xFFFF;
        var c = plane[y1 * stride + x0] & 0xFFFF;
        var d = plane[y1 * stride + x1] & 0xFFFF;
        var top = a + (b - a) * fx;
        var bottom = c + (d - c) * fx;
        return top + (bottom - top) * fy;
    }

    /**
     * Converts an R'G'B' value in 8-bit code units to a 16-bit sample.
     *
     * @param value the value, nominally {@code 0..255}
     * @return the sample, clamped
     */
    private static short toSample(float value) {
        return (short) Math.round(Math.min(Math.max(value * (65535f / 255f), 0f), 65535f));
    }
}
