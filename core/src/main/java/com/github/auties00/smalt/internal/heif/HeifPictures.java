package com.github.auties00.smalt.internal.heif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;

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
     * The filter that resamples an alpha plane whose size differs from the color: its weights are
     * never negative, so the resampled alpha neither rings nor leaves the range of its source.
     */
    public static final ResizeFilter ALPHA_FILTER = ResizeFilter.BILINEAR;

    /**
     * Resamples the luma plane of a planar alpha picture to the size of its color image, since an
     * auxiliary image may differ in size from its master image.
     *
     * <p>The luma plane is filtered with {@link #ALPHA_FILTER} by
     * {@link ImageOps#resize(Picture, int, int, ResizeFilter)}, at its depth and in its range; the
     * result is a monochrome picture of the same type, depth and color description. A picture
     * that already has the size is returned as it is.
     *
     * @param alpha the alpha picture, a {@link YuvPicture} or a {@link Yuv16Picture}
     * @param width the color width, positive
     * @param height the color height, positive
     * @return the resampled picture, whose luma is the alpha plane
     * @throws MediaException.Malformed if the picture is not planar
     */
    public static Picture resampleAlpha(Picture alpha, int width, int height) {
        if (alpha.width() == width && alpha.height() == height) {
            return alpha;
        }
        var luma = switch (alpha) {
            case YuvPicture yuv -> YuvPicture.builder().size(yuv.width(), yuv.height())
                    .chromaFormat(ChromaFormat.MONOCHROME).color(yuv.color()).luma(yuv.y(), yuv.yStride()).build();
            case Yuv16Picture yuv -> Yuv16Picture.builder().size(yuv.width(), yuv.height())
                    .chromaFormat(ChromaFormat.MONOCHROME).bitDepth(yuv.bitDepth()).color(yuv.color())
                    .luma(yuv.y(), yuv.yStride()).build();
            default -> throw new MediaException.Malformed("heif: the alpha image is not a planar picture");
        };
        return ImageOps.resize(luma, width, height, ALPHA_FILTER);
    }

    /**
     * Copies the visible luma samples of a decoded plane into a monochrome picture, the form
     * {@link #resampleAlpha} takes.
     *
     * @param plane the samples
     * @param offset the index of the first visible sample
     * @param stride the row stride, in samples
     * @param width the visible width
     * @param height the visible height
     * @param depth the bit depth of the samples, from 8 to 16
     * @return a {@link YuvPicture} for 8-bit samples, a {@link Yuv16Picture} otherwise; its color
     * description is {@link ColorInfo#JPEG}, so the caller passes the range of the samples
     * separately
     */
    public static Picture lumaPicture(short[] plane, int offset, int stride, int width, int height, int depth) {
        if (depth == 8) {
            var luma = new byte[width * height];
            for (var row = 0; row < height; row++) {
                var in = offset + row * stride;
                for (var col = 0; col < width; col++) {
                    luma[row * width + col] = (byte) plane[in + col];
                }
            }
            return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.MONOCHROME).color(ColorInfo.JPEG)
                    .luma(luma, width).build();
        }
        var luma = new short[width * height];
        copyRows(plane, offset, stride, luma, width, width, height);
        return Yuv16Picture.builder().size(width, height).chromaFormat(ChromaFormat.MONOCHROME).bitDepth(depth)
                .color(ColorInfo.JPEG).luma(luma, width).build();
    }

    /**
     * Copies the visible luma samples of an 8-bit decoded plane into a monochrome picture.
     *
     * @param plane the samples
     * @param offset the index of the first visible sample
     * @param stride the row stride, in samples
     * @param width the visible width
     * @param height the visible height
     * @return the {@link YuvPicture}, with the color description {@link ColorInfo#JPEG}
     */
    public static YuvPicture lumaPicture(byte[] plane, int offset, int stride, int width, int height) {
        var luma = new byte[width * height];
        copyRows(plane, offset, stride, luma, width, width, height);
        return YuvPicture.builder().size(width, height).chromaFormat(ChromaFormat.MONOCHROME).color(ColorInfo.JPEG)
                .luma(luma, width).build();
    }

    /**
     * Converts a picture whose color is premultiplied by its alpha into straight-alpha RGBA at its
     * depth.
     *
     * <p>An 8-bit picture is converted to 8-bit RGBA as it is, and each color component is then
     * divided by its alpha, {@code min(255, round(c * 255 / a))}. A 16-bit picture is converted to
     * 16-bit RGBA at full precision with {@link ImageOps#toRgba16(Picture)} and divided the same way,
     * {@code min(65535, round(c * 65535 / a))}. Fully transparent pixels become black; rounding is
     * half up. A packed input is divided in place.
     *
     * @implNote This implementation divides as libavif's {@code avifRGBImageUnpremultiplyAlpha}
     * ({@code src/alpha.c}, BSD 2-clause) does for 8-bit RGBA, in integers, and with the same exact
     * integer rounding at 16 bits.
     * @param picture the picture with premultiplied color and an alpha plane
     * @return the straight-alpha picture: an {@link RgbaPicture} for an 8-bit picture, an
     * {@link Rgba16Picture} for a 16-bit one
     */
    public static Picture unpremultiply(Picture picture) {
        return switch (picture) {
            case Yuv16Picture _, Rgba16Picture _ -> unpremultiply(ImageOps.toRgba16(picture));
            default -> unpremultiply(ImageOps.toRgba(picture));
        };
    }

    /**
     * {@return whether a picture is packed RGBA}
     *
     * @param picture the decoded or composed picture
     */
    public static boolean isPacked(Picture picture) {
        return picture instanceof RgbaPicture || picture instanceof Rgba16Picture;
    }

    /**
     * {@return whether a picture stores samples of more than 8 bits}
     *
     * @param picture the decoded or composed picture
     */
    public static boolean isDeep(Picture picture) {
        return picture instanceof Yuv16Picture || picture instanceof Rgba16Picture;
    }

    /**
     * {@return the color description of a planar picture, {@link ColorInfo#JPEG} for another
     * picture}
     *
     * @param picture the decoded or composed picture
     */
    public static ColorInfo colorOf(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> yuv.color();
            case Yuv16Picture yuv -> yuv.color();
            default -> ColorInfo.JPEG;
        };
    }

    /**
     * {@return a planar picture with another color description, sharing its planes, or a packed
     * picture unchanged}
     *
     * @param picture the planar picture to relabel, or a packed one
     * @param color the color description
     */
    public static Picture withColor(Picture picture, ColorInfo color) {
        return switch (picture) {
            case YuvPicture yuv -> yuv.toBuilder().color(color).build();
            case Yuv16Picture yuv -> yuv.toBuilder().color(color).build();
            default -> picture;
        };
    }

    /**
     * Copies the top-left region of a picture into a region of a packed canvas, converted to RGBA
     * at the canvas depth, an input of 8 bits converted at 8 bits and widened as {@code v * 257};
     * the pixels of a picture without alpha become opaque.
     *
     * @param canvas the packed canvas, an {@link RgbaPicture} or an {@link Rgba16Picture} with alpha
     * @param picture the input picture, at least {@code w} by {@code h}
     * @param x the left edge of the region
     * @param y the top edge of the region
     * @param w the region width
     * @param h the region height
     * @return the canvas, promoted to 16 bits when it was 8-bit and the input is deeper
     */
    public static Picture place(Picture canvas, Picture picture, int x, int y, int w, int h) {
        if (canvas instanceof RgbaPicture narrow && isDeep(picture)) {
            canvas = ImageOps.toSixteenBit(narrow);
        }
        switch (canvas) {
            case Rgba16Picture target -> {
                var source = isDeep(picture) ? ImageOps.toRgba16(picture) : ImageOps.toSixteenBit(ImageOps.toRgba(picture));
                var opaque = !source.hasAlpha();
                for (var row = 0; row < h; row++) {
                    var out = (y + row) * target.stride() + 4 * x;
                    System.arraycopy(source.pixels(), row * source.stride(), target.pixels(), out, 4 * w);
                    if (opaque) {
                        for (var col = 0; col < w; col++) {
                            target.pixels()[out + 4 * col + 3] = (short) 0xFFFF;
                        }
                    }
                }
            }
            case RgbaPicture target -> {
                var source = ImageOps.toRgba(picture);
                var opaque = !source.hasAlpha();
                for (var row = 0; row < h; row++) {
                    var out = (y + row) * target.stride() + 4 * x;
                    System.arraycopy(source.pixels(), row * source.stride(), target.pixels(), out, 4 * w);
                    if (opaque) {
                        for (var col = 0; col < w; col++) {
                            target.pixels()[out + 4 * col + 3] = (byte) 0xFF;
                        }
                    }
                }
            }
            default -> throw new IllegalStateException("unexpected " + canvas.getClass().getSimpleName());
        }
        return canvas;
    }

    /**
     * Replaces the alpha channel of a packed canvas.
     *
     * @param canvas the packed canvas
     * @param alpha a monochrome picture of the canvas size and depth whose alpha plane holds the
     * full-range alpha
     */
    public static void replaceAlpha(Picture canvas, Picture alpha) {
        switch (canvas) {
            case Rgba16Picture target -> {
                var plane = (Yuv16Picture) alpha;
                for (var row = 0; row < target.height(); row++) {
                    var out = row * target.stride() + 3;
                    var in = row * plane.alphaStride();
                    for (var col = 0; col < target.width(); col++) {
                        target.pixels()[out + 4 * col] = plane.alpha()[in + col];
                    }
                }
            }
            case RgbaPicture target -> {
                var plane = (YuvPicture) alpha;
                for (var row = 0; row < target.height(); row++) {
                    var out = row * target.stride() + 3;
                    var in = row * plane.alphaStride();
                    for (var col = 0; col < target.width(); col++) {
                        target.pixels()[out + 4 * col] = plane.alpha()[in + col];
                    }
                }
            }
            default -> throw new IllegalStateException("unexpected " + canvas.getClass().getSimpleName());
        }
    }

    /**
     * Divides the colors of an 8-bit packed picture by their alpha, in place.
     *
     * @param rgba the picture with premultiplied color
     * @return {@code rgba}
     */
    private static RgbaPicture unpremultiply(RgbaPicture rgba) {
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
     * Divides the colors of a 16-bit packed picture by their alpha, in place.
     *
     * @param rgba the picture with premultiplied color
     * @return {@code rgba}
     */
    private static Rgba16Picture unpremultiply(Rgba16Picture rgba) {
        var pixels = rgba.pixels();
        for (var row = 0; row < rgba.height(); row++) {
            var offset = row * rgba.stride();
            for (var col = 0; col < rgba.width(); col++, offset += 4) {
                long alpha = pixels[offset + 3] & 0xFFFF;
                if (alpha == 0xFFFF) {
                    continue;
                }
                for (var c = 0; c < 3; c++) {
                    var value = alpha == 0 ? 0
                            : Math.min(0xFFFF, ((pixels[offset + c] & 0xFFFF) * 0x1FFFEL + alpha) / (2 * alpha));
                    pixels[offset + c] = (short) value;
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
