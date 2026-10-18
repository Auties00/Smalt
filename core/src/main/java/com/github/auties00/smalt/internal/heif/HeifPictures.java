package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;

import java.util.Optional;

/**
 * Applies the picture-level rules HEIF readers share to decoded pictures: the exact clean
 * aperture crop, premultiplied alpha, the color profile, and the conversion of the
 * high-dynamic-range properties.
 */
public final class HeifPictures {
    private HeifPictures() {
        throw new AssertionError();
    }

    /**
     * Copies a rectangle of a picture exactly.
     *
     * <p>For a planar picture whose rectangle starts on a chroma sample boundary the chroma planes
     * are cropped as they are. Otherwise the chroma cannot be cut at the rectangle's edge, so the
     * result is 4:4:4: each pixel takes the chroma sample that covers it in the source, which
     * keeps every pixel's color unchanged. Other pictures are cropped with
     * {@link ImageOps#crop(Picture, int, int, int, int)}.
     *
     * @param picture the decoded picture
     * @param x the left edge of the rectangle, in pixels
     * @param y the top edge of the rectangle, in pixels
     * @param width the width of the rectangle, in pixels
     * @param height the height of the rectangle, in pixels
     * @return the cropped picture
     */
    public static Picture crop(Picture picture, int x, int y, int width, int height) {
        return switch (picture) {
            case YuvPicture yuv -> crop(yuv, x, y, width, height);
            case Yuv16Picture yuv -> crop(yuv, x, y, width, height);
            default -> ImageOps.crop(picture, x, y, width, height);
        };
    }

    /**
     * Copies a rectangle of an 8-bit planar picture.
     *
     * @param picture the source picture
     * @param x the left edge of the rectangle, in pixels
     * @param y the top edge of the rectangle, in pixels
     * @param width the width of the rectangle, in pixels
     * @param height the height of the rectangle, in pixels
     * @return the cropped picture
     */
    private static YuvPicture crop(YuvPicture picture, int x, int y, int width, int height) {
        var format = picture.chromaFormat();
        var aligned = (x & ((1 << format.shiftX()) - 1)) == 0 && (y & ((1 << format.shiftY()) - 1)) == 0;
        var outFormat = aligned ? format : ChromaFormat.YUV444;
        var luma = new byte[width * height];
        copyRows(picture.y(), x + y * picture.yStride(), picture.yStride(), luma, width, width, height);
        var builder = YuvPicture.builder().size(width, height).chromaFormat(outFormat).color(picture.color())
                .luma(luma, width);
        if (format.hasChroma()) {
            var chromaWidth = outFormat.chromaWidth(width);
            var chromaHeight = outFormat.chromaHeight(height);
            var u = new byte[chromaWidth * chromaHeight];
            var v = new byte[chromaWidth * chromaHeight];
            var stride = picture.uvStride();
            if (aligned) {
                var offset = (x >> format.shiftX()) + (y >> format.shiftY()) * stride;
                copyRows(picture.u(), offset, stride, u, chromaWidth, chromaWidth, chromaHeight);
                copyRows(picture.v(), offset, stride, v, chromaWidth, chromaWidth, chromaHeight);
            } else {
                for (var row = 0; row < height; row++) {
                    var in = ((y + row) >> format.shiftY()) * stride;
                    for (var col = 0; col < width; col++) {
                        var sample = in + ((x + col) >> format.shiftX());
                        u[row * width + col] = picture.u()[sample];
                        v[row * width + col] = picture.v()[sample];
                    }
                }
            }
            builder.chroma(u, v, chromaWidth);
        }
        if (picture.hasAlpha()) {
            var alpha = new byte[width * height];
            copyRows(picture.alpha(), x + y * picture.alphaStride(), picture.alphaStride(), alpha, width, width, height);
            builder.alpha(alpha, width);
        }
        return builder.build();
    }

    /**
     * Copies a rectangle of a deep planar picture.
     *
     * @param picture the source picture
     * @param x the left edge of the rectangle, in pixels
     * @param y the top edge of the rectangle, in pixels
     * @param width the width of the rectangle, in pixels
     * @param height the height of the rectangle, in pixels
     * @return the cropped picture
     */
    private static Yuv16Picture crop(Yuv16Picture picture, int x, int y, int width, int height) {
        var format = picture.chromaFormat();
        var aligned = (x & ((1 << format.shiftX()) - 1)) == 0 && (y & ((1 << format.shiftY()) - 1)) == 0;
        var outFormat = aligned ? format : ChromaFormat.YUV444;
        var luma = new short[width * height];
        copyRows(picture.y(), x + y * picture.yStride(), picture.yStride(), luma, width, width, height);
        var builder = Yuv16Picture.builder().size(width, height).chromaFormat(outFormat).bitDepth(picture.bitDepth())
                .color(picture.color()).luma(luma, width);
        if (format.hasChroma()) {
            var chromaWidth = outFormat.chromaWidth(width);
            var chromaHeight = outFormat.chromaHeight(height);
            var u = new short[chromaWidth * chromaHeight];
            var v = new short[chromaWidth * chromaHeight];
            var stride = picture.uvStride();
            if (aligned) {
                var offset = (x >> format.shiftX()) + (y >> format.shiftY()) * stride;
                copyRows(picture.u(), offset, stride, u, chromaWidth, chromaWidth, chromaHeight);
                copyRows(picture.v(), offset, stride, v, chromaWidth, chromaWidth, chromaHeight);
            } else {
                for (var row = 0; row < height; row++) {
                    var in = ((y + row) >> format.shiftY()) * stride;
                    for (var col = 0; col < width; col++) {
                        var sample = in + ((x + col) >> format.shiftX());
                        u[row * width + col] = picture.u()[sample];
                        v[row * width + col] = picture.v()[sample];
                    }
                }
            }
            builder.chroma(u, v, chromaWidth);
        }
        if (picture.hasAlpha()) {
            var alpha = new short[width * height];
            copyRows(picture.alpha(), x + y * picture.alphaStride(), picture.alphaStride(), alpha, width, width, height);
            builder.alpha(alpha, width);
        }
        return builder.build();
    }

    /**
     * Copies rows of bytes.
     *
     * @param src the source
     * @param srcOff the first source byte
     * @param srcStride the source stride
     * @param dst the destination, rows starting at {@code 0}
     * @param dstStride the destination stride
     * @param width the row length
     * @param height the number of rows
     */
    private static void copyRows(byte[] src, int srcOff, int srcStride, byte[] dst, int dstStride, int width, int height) {
        for (var row = 0; row < height; row++) {
            System.arraycopy(src, srcOff + row * srcStride, dst, row * dstStride, width);
        }
    }

    /**
     * Copies rows of samples.
     *
     * @param src the source
     * @param srcOff the first source sample
     * @param srcStride the source stride
     * @param dst the destination, rows starting at {@code 0}
     * @param dstStride the destination stride
     * @param width the row length
     * @param height the number of rows
     */
    private static void copyRows(short[] src, int srcOff, int srcStride, short[] dst, int dstStride, int width, int height) {
        for (var row = 0; row < height; row++) {
            System.arraycopy(src, srcOff + row * srcStride, dst, row * dstStride, width);
        }
    }

    /**
     * Converts a picture whose color is premultiplied by its alpha into straight-alpha RGBA.
     *
     * <p>The planes are converted to 8-bit RGBA as they are, and each color component is then
     * divided by its alpha, {@code min(255, round(c * 255 / a))}, with fully transparent pixels
     * becoming black.
     *
     * @param picture the picture with premultiplied color and an alpha plane
     * @return the straight-alpha picture
     * @implNote This implementation divides as libavif's {@code avifRGBImageUnpremultiplyAlpha}
     * ({@code src/alpha.c}, BSD 2-clause) does for 8-bit RGBA, in integers.
     */
    public static RgbaPicture unpremultiply(Picture picture) {
        // TODO: keep premultiplied high-bit-depth images at their depth instead of reducing them to 8 bits
        var rgba = ImageOps.toRgba(picture);
        var pixels = rgba.pixels();
        for (var row = 0; row < rgba.height(); row++) {
            var offset = row * rgba.stride();
            for (var col = 0; col < rgba.width(); col++, offset += 4) {
                var alpha = pixels[offset + 3] & 0xFF;
                if (alpha == 255) {
                    continue;
                }
                for (var c = 0; c < 3; c++) {
                    var value = alpha == 0 ? 0 : Math.min(255, ((pixels[offset + c] & 0xFF) * 510 + alpha) / (2 * alpha));
                    pixels[offset + c] = (byte) value;
                }
            }
        }
        return rgba;
    }

    /**
     * Returns the color space the pixels of a decoded HEIF image are in.
     *
     * <p>An ICC profile ({@code colr} of type {@code prof} or {@code rICC}) that parses describes
     * it. Without one, the color description of a planar picture (from the {@code nclx} color
     * box or the coded stream) describes it through {@link ColorProfile#of(ColorInfo)}, unless
     * that is sRGB or cannot be expressed as a profile. An RGBA picture without an ICC profile is
     * taken as sRGB.
     *
     * @param icc the ICC profile bytes, or {@code null}
     * @param picture the decoded picture
     * @return the profile, or empty for sRGB
     */
    public static Optional<ColorProfile> colorProfile(byte[] icc, Picture picture) {
        if (icc != null) {
            try {
                return Optional.of(ColorProfile.parse(icc));
            } catch (MediaException exception) {
                return Optional.empty();
            }
        }
        var color = switch (picture) {
            case YuvPicture yuv -> yuv.color();
            case Yuv16Picture yuv -> yuv.color();
            default -> null;
        };
        if (color == null) {
            return Optional.empty();
        }
        try {
            var profile = ColorProfile.of(color);
            return profile.isSrgb() ? Optional.empty() : Optional.of(profile);
        } catch (MediaException exception) {
            return Optional.empty();
        }
    }

    /**
     * Converts an {@code mdcv} property, whose primaries are stored in the order green, blue, red
     * in units of 0.00002 and whose luminances are in units of 0.0001 candelas per square meter.
     *
     * @param property the parsed {@code mdcv} property
     * @return the mastering display
     */
    public static com.github.auties00.smalt.image.MasteringDisplay masteringDisplay(HeifProperty.MasteringDisplay property) {
        var p = property.primaries();
        var green = new Chromaticity(p[0] * 0.00002, p[1] * 0.00002);
        var blue = new Chromaticity(p[2] * 0.00002, p[3] * 0.00002);
        var red = new Chromaticity(p[4] * 0.00002, p[5] * 0.00002);
        var white = new Chromaticity(property.whitePointX() * 0.00002, property.whitePointY() * 0.00002);
        return new com.github.auties00.smalt.image.MasteringDisplay(red, green, blue, white,
                property.maxLuminance() * 0.0001, property.minLuminance() * 0.0001);
    }

    /**
     * Converts a {@code clli} property.
     *
     * @param property the parsed {@code clli} property
     * @return the content light level
     */
    public static com.github.auties00.smalt.image.ContentLightLevel contentLightLevel(HeifProperty.ContentLightLevel property) {
        return new com.github.auties00.smalt.image.ContentLightLevel(property.maxContentLightLevel(),
                property.maxPictureAverageLightLevel());
    }
}
