package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Applies color transforms to pictures.
 *
 * <p>Pictures are converted at their own depth: 8-bit pictures through packed 8-bit RGBA, 16-bit
 * pictures through packed 16-bit RGBA. Y'CbCr pictures are first converted to R'G'B' with the
 * matrix and range of their {@link ColorInfo}: 8-bit ones as {@link ImageOps#toRgba(Picture)}
 * does, 16-bit ones as {@link ImageOps#toRgba16(Picture)} does, keeping their full precision. A
 * gray source profile reads the red channel; a gray target profile writes the same value to red,
 * green and blue. Alpha is never changed. A transform to a CMYK profile produces a
 * {@link ChannelPicture}, and so does the conversion of a channel picture, whose color channels are
 * converted while its extra channels are copied, premultiplied colors being divided by their alpha
 * before the transform and multiplied again after it.
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
     * @param transform the transform, from an RGB or gray profile (or, for a channel picture, a profile with as
     * many channels as its color channels) to an RGB, gray or CMYK profile
     * @return an {@link RgbaPicture} for an 8-bit picture, an {@link Rgba16Picture} for a 16-bit one, or a
     * {@link ChannelPicture} of the transform's target color model for a channel picture or a CMYK target
     * @throws IllegalArgumentException if a profile of the transform does not fit the picture
     * @throws MediaException.Unsupported if the picture has floating-point samples
     */
    static Picture convert(Picture picture, ColorTransform transform) {
        var in = transform.inputChannels();
        var out = transform.outputChannels();
        var model = targetModel(transform);
        if (picture instanceof ChannelPicture channels) {
            return convertChannels(channels, transform, model, false);
        }
        if ((in != 1 && in != 3) || (out != 1 && out != 3 && !(model == ColorModel.CMYK && in == 3))) {
            throw new IllegalArgumentException("a picture converts only from RGB and gray profiles to RGB, gray"
                    + " and CMYK profiles, not " + transform.source().colorSpace() + " to "
                    + transform.target().colorSpace());
        }
        if (model == ColorModel.CMYK) {
            Picture channels = switch (picture) {
                case RgbaPicture _, YuvPicture _ -> ChannelOps.toChannel8(picture);
                default -> ChannelOps.toChannel16(picture);
            };
            return convertChannels((ChannelPicture) channels, transform, model, false);
        }
        return switch (picture) {
            case RgbaPicture rgba -> convert8(rgba, transform);
            case YuvPicture yuv -> convert8(ImageOps.toRgba(yuv), transform);
            case Rgba16Picture rgba -> convert16(rgba, transform);
            case Yuv16Picture yuv -> convert16(DeepColor.toRgba16(yuv, ChromaUpsampling.TRIANGLE), transform);
            case ChannelPicture channels -> convertChannels(channels, transform, model, false);
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
                    yield ImageOps.toRgba(yuv);
                }
                var converted = convert16(DeepColor.toRgba16(yuv, ChromaUpsampling.TRIANGLE),
                        ColorTransform.create(profile, ColorProfile.srgb(), options));
                yield ImageOps.toEightBit(converted);
            }
            case ChannelPicture channels -> (RgbaPicture) ChannelOps.toPacked(channels, false);
        };
    }

    /**
     * Converts a decoded image to sRGB with its declared profile, leaving it unchanged when the
     * profile is absent, is sRGB, or cannot be applied to its pixels; a channel picture is always
     * converted to packed RGBA.
     *
     * @param image the decoded image
     * @param options the transform options
     * @return the converted image, or {@code image} itself
     */
    static DecodedImage toSrgb(DecodedImage image, ColorTransformOptions options) {
        if (image.picture() instanceof ChannelPicture channels) {
            return channelsToSrgb(image, channels, options);
        }
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
                image.sourceHeight(), Optional.empty(), image.exif(), image.xmp(), Optional.of(ColorProfile.srgb()));
    }

    /**
     * Converts a decoded channel picture to packed sRGB: through its profile when the profile applies to its
     * color channels, otherwise without color management.
     *
     * @param image the decoded image
     * @param channels its picture
     * @param options the transform options
     * @return the image holding an {@link RgbaPicture} for 8-bit samples and an {@link Rgba16Picture} otherwise
     */
    private static DecodedImage channelsToSrgb(DecodedImage image, ChannelPicture channels,
                                               ColorTransformOptions options) {
        var profile = image.colorProfile().orElse(null);
        var sixteen = !(channels instanceof Channel8Picture);
        var layout = channels.layout();
        if (profile != null && !profile.isSrgb() && profile.channels() == layout.colorChannels()) {
            try {
                var transform = ColorTransform.create(profile, ColorProfile.srgb(), options);
                ChannelPicture integer = channels instanceof ChannelFloatPicture ? ChannelOps.toChannel16(channels)
                        : channels;
                var converted = convertChannels(integer, transform, ColorModel.RGB, true);
                return new DecodedImage(ChannelOps.toPacked(converted, sixteen), image.format(), image.orientation(),
                        image.sourceWidth(), image.sourceHeight(), Optional.empty(), image.exif(),
                        Optional.of(ColorProfile.srgb()));
            } catch (MediaException.Malformed | MediaException.Unsupported exception) {
                // a profile that cannot be applied leaves the conversion without color management
            }
        }
        var model = layout.colorModel();
        var describes = profile != null && (model == ColorModel.RGB || model == ColorModel.GRAY)
                && profile.channels() == layout.colorChannels();
        return new DecodedImage(ChannelOps.toPacked(channels, sixteen), image.format(), image.orientation(),
                image.sourceWidth(), image.sourceHeight(), image.iccProfile(), image.exif(),
                describes ? image.colorProfile() : Optional.empty());
    }

    /**
     * Applies a transform to the color channels of a channel picture.
     *
     * @param picture the channel picture to convert
     * @param transform the transform, reading as many channels as the picture's color channels
     * @param model the color model of the transform's target
     * @param straight whether premultiplied colors stay divided by their alpha, the alpha channel of the result
     * becoming straight
     * @return the picture of the target model, the extra channels copied
     * @throws IllegalArgumentException if the channel counts do not match or the target is not gray, RGB or
     * CMYK
     * @throws MediaException.Unsupported if the picture has floating-point samples
     */
    private static ChannelPicture convertChannels(ChannelPicture picture, ColorTransform transform,
                                                  ColorModel model, boolean straight) {
        var layout = picture.layout();
        var colors = layout.colorChannels();
        if (transform.inputChannels() != colors) {
            throw new IllegalArgumentException("the transform reads " + transform.inputChannels()
                    + " channels, the picture has " + colors + " color channels");
        }
        if (model == null) {
            throw new IllegalArgumentException("a picture converts only to RGB, gray and CMYK profiles, not "
                    + transform.target().colorSpace());
        }
        if (picture instanceof ChannelFloatPicture) {
            // TODO: floating-point color transforms, which keep values outside 0..1
            throw new MediaException.Unsupported("floating-point color transform",
                    "color transforms of floating-point samples are not supported; convert with"
                            + " ImageOps.toChannel16 first");
        }
        var base = switch (model) {
            case GRAY -> PixelLayout.GRAY;
            case RGB -> PixelLayout.RGB;
            default -> PixelLayout.CMYK;
        };
        var list = new ArrayList<>(base.channels());
        list.addAll(layout.channels().subList(colors, layout.count()));
        var premultiplied = layout.isAlphaPremultiplied();
        var multiply = premultiplied && !straight;
        if (premultiplied && straight) {
            list.set(base.count() + layout.alphaIndex() - colors, Channel.of(ChannelType.ALPHA));
        }
        var outLayout = new PixelLayout(list);
        var width = picture.width();
        var height = picture.height();
        OpChecks.checkArray((long) width * height * outLayout.count());
        var out = ChannelOps.allocateLike(picture, width, height, outLayout);
        var inChannels = layout.count();
        var outChannels = outLayout.count();
        var outColors = base.count();
        var extras = inChannels - colors;
        var alpha = layout.alphaIndex();
        var bands = ParallelBands.bands(height, WORK * width * height);
        ParallelBands.run(bands, band -> {
            var top = ParallelBands.start(band, bands, height);
            var bottom = ParallelBands.start(band + 1, bands, height);
            var straight8 = premultiplied && picture instanceof Channel8Picture ? new byte[width * inChannels] : null;
            var straight16 = premultiplied && picture instanceof Channel16Picture ? new short[width * inChannels]
                    : null;
            for (var y = top; y < bottom; y++) {
                var s = y * picture.stride();
                var d = y * out.stride();
                switch (picture) {
                    case Channel8Picture eight -> {
                        var src = eight.pixels();
                        var dst = ((Channel8Picture) out).pixels();
                        var input = src;
                        var inputOffset = s;
                        if (premultiplied) {
                            input = straightRow8(src, s, width, inChannels, colors, alpha, straight8);
                            inputOffset = 0;
                        }
                        transform.transform(input, inputOffset, inChannels, dst, d, outChannels, width);
                        for (var x = 0; x < width; x++) {
                            System.arraycopy(src, s + x * inChannels + colors, dst, d + x * outChannels + outColors,
                                    extras);
                        }
                        if (multiply) {
                            var outAlpha = outColors + alpha - colors;
                            for (var x = 0; x < width; x++) {
                                var at = d + x * outChannels;
                                var a = dst[at + outAlpha] & 0xFF;
                                for (var c = 0; c < outColors; c++) {
                                    dst[at + c] = (byte) (((dst[at + c] & 0xFF) * a + 127) / 255);
                                }
                            }
                        }
                    }
                    case Channel16Picture sixteen -> {
                        var src = sixteen.pixels();
                        var dst = ((Channel16Picture) out).pixels();
                        var input = src;
                        var inputOffset = s;
                        if (premultiplied) {
                            input = straightRow16(src, s, width, inChannels, colors, alpha, straight16);
                            inputOffset = 0;
                        }
                        transform.transform(input, inputOffset, inChannels, dst, d, outChannels, width);
                        for (var x = 0; x < width; x++) {
                            System.arraycopy(src, s + x * inChannels + colors, dst, d + x * outChannels + outColors,
                                    extras);
                        }
                        if (multiply) {
                            var outAlpha = outColors + alpha - colors;
                            for (var x = 0; x < width; x++) {
                                var at = d + x * outChannels;
                                var a = dst[at + outAlpha] & 0xFFFF;
                                for (var c = 0; c < outColors; c++) {
                                    dst[at + c] = (short) (((long) (dst[at + c] & 0xFFFF) * a + 32767) / 65535);
                                }
                            }
                        }
                    }
                    case ChannelFloatPicture _ -> throw new AssertionError();
                }
            }
        });
        return out;
    }

    /**
     * Copies one row of 8-bit pixels with their color channels divided by their alpha.
     *
     * @param src the samples
     * @param offset the index of the row
     * @param width the pixels in the row
     * @param channels the samples per pixel
     * @param colors the leading color channels
     * @param alpha the index of the alpha channel
     * @param row the destination, {@code width * channels} samples
     * @return {@code row}
     */
    private static byte[] straightRow8(byte[] src, int offset, int width, int channels, int colors, int alpha,
                                       byte[] row) {
        System.arraycopy(src, offset, row, 0, width * channels);
        for (var at = 0; at < width * channels; at += channels) {
            var a = row[at + alpha] & 0xFF;
            for (var c = 0; c < colors; c++) {
                row[at + c] = (byte) ChannelOps.unpremultiply(row[at + c] & 0xFF, a, 255);
            }
        }
        return row;
    }

    /**
     * Copies one row of 16-bit pixels with their color channels divided by their alpha.
     *
     * @param src the samples
     * @param offset the index of the row
     * @param width the pixels in the row
     * @param channels the samples per pixel
     * @param colors the leading color channels
     * @param alpha the index of the alpha channel
     * @param row the destination, {@code width * channels} samples
     * @return {@code row}
     */
    private static short[] straightRow16(short[] src, int offset, int width, int channels, int colors, int alpha,
                                         short[] row) {
        System.arraycopy(src, offset, row, 0, width * channels);
        for (var at = 0; at < width * channels; at += channels) {
            var a = row[at + alpha] & 0xFFFF;
            for (var c = 0; c < colors; c++) {
                row[at + c] = (short) ChannelOps.unpremultiply(row[at + c] & 0xFFFF, a, 65535);
            }
        }
        return row;
    }

    /**
     * Returns the color model a transform's target profile describes.
     *
     * @param transform the color transform to apply
     * @return {@link ColorModel#GRAY}, {@link ColorModel#RGB} or {@link ColorModel#CMYK}, or {@code null} for
     * another color space
     */
    private static ColorModel targetModel(ColorTransform transform) {
        return switch (transform.target().colorSpace()) {
            case GRAY -> ColorModel.GRAY;
            case RGB -> ColorModel.RGB;
            case CMYK -> ColorModel.CMYK;
            default -> null;
        };
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
}
