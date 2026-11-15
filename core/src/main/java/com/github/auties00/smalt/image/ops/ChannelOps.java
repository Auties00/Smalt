package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Implements the {@link ImageOps} operations on {@link ChannelPicture}s: conversions to and from packed RGBA and
 * between sample types, cropping, orientation and the opacity scan. Arguments are validated by the caller.
 *
 * <p>Conversions to integer RGBA work on integer samples: floating-point samples are first quantized to 16 bits
 * as {@link #quantize16(float)} does, premultiplied colors are divided by their alpha as
 * {@link #unpremultiply(int, int, int)} does, and CMYK converts as {@link #cmyk(int, int, int)} does, every value
 * computed at the integer depth of the picture and then rescaled with {@link #rescale(int, int, int)}, so the
 * 8-bit conversion equals the 16-bit one reduced to 8 bits.
 */
final class ChannelOps {
    /**
     * The approximate number of operations per sample of a conversion, which weighs its work against
     * {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long WORK = 6;

    /**
     * Prevents instantiation.
     */
    private ChannelOps() {
        throw new AssertionError();
    }

    /**
     * Converts a channel picture to packed RGBA without color management.
     *
     * @param picture the channel picture to convert
     * @param sixteen whether to produce 16-bit samples
     * @return the {@link RgbaPicture} or {@link Rgba16Picture}, reporting alpha exactly when the layout has an
     * alpha channel
     * @throws MediaException.Unsupported if the layout's color model is {@link ColorModel#INK}
     */
    static Picture toPacked(ChannelPicture picture, boolean sixteen) {
        var layout = picture.layout();
        var model = layout.colorModel();
        if (model == ColorModel.INK) {
            throw new MediaException.Unsupported("ink separations", "converting " + layout.colorChannels()
                    + " ink separations to RGB needs a color profile with the inks' colors");
        }
        var width = picture.width();
        var height = picture.height();
        var channels = picture.channelCount();
        OpChecks.checkArray(4L * width * height);
        var source = sourceMax(picture);
        var target = sixteen ? 65535 : 255;
        var alpha = layout.alphaIndex();
        var premultiplied = layout.isAlphaPremultiplied();
        Rgba16Picture wide = sixteen ? Rgba16Picture.allocate(width, height, layout.hasAlpha()) : null;
        RgbaPicture narrow = sixteen ? null : RgbaPicture.allocate(width, height, layout.hasAlpha());
        var bands = ParallelBands.bands(height, WORK * width * height * channels);
        ParallelBands.run(bands, band -> {
            var top = ParallelBands.start(band, bands, height);
            var bottom = ParallelBands.start(band + 1, bands, height);
            var row = new int[width * channels];
            for (var y = top; y < bottom; y++) {
                load(picture, y, row);
                var out = y * width * 4;
                for (var x = 0; x < width; x++, out += 4) {
                    var at = x * channels;
                    var a = alpha >= 0 ? row[at + alpha] : source;
                    int r;
                    int g;
                    int b;
                    switch (model) {
                        case GRAY -> {
                            r = rescale(color(row[at], a, premultiplied, source), source, target);
                            g = r;
                            b = r;
                        }
                        case RGB -> {
                            r = rescale(color(row[at], a, premultiplied, source), source, target);
                            g = rescale(color(row[at + 1], a, premultiplied, source), source, target);
                            b = rescale(color(row[at + 2], a, premultiplied, source), source, target);
                        }
                        default -> {
                            var k = color(row[at + 3], a, premultiplied, source);
                            r = rescale(cmyk(color(row[at], a, premultiplied, source), k, source), source, target);
                            g = rescale(cmyk(color(row[at + 1], a, premultiplied, source), k, source), source, target);
                            b = rescale(cmyk(color(row[at + 2], a, premultiplied, source), k, source), source, target);
                        }
                    }
                    var outAlpha = alpha >= 0 ? rescale(a, source, target) : target;
                    if (sixteen) {
                        var pixels = wide.pixels();
                        pixels[out] = (short) r;
                        pixels[out + 1] = (short) g;
                        pixels[out + 2] = (short) b;
                        pixels[out + 3] = (short) outAlpha;
                    } else {
                        var pixels = narrow.pixels();
                        pixels[out] = (byte) r;
                        pixels[out + 1] = (byte) g;
                        pixels[out + 2] = (byte) b;
                        pixels[out + 3] = (byte) outAlpha;
                    }
                }
            }
        });
        return sixteen ? wide : narrow;
    }

    /**
     * Converts a picture to 8-bit channels.
     *
     * @param picture the picture to convert, of any representation
     * @return the picture itself if it is a {@link Channel8Picture}, otherwise a new one
     */
    static Channel8Picture toChannel8(Picture picture) {
        return switch (picture) {
            case Channel8Picture channels -> channels;
            case Channel16Picture channels -> {
                var out = allocate8(channels.width(), channels.height(), channels.layout());
                convert(channels, out);
                yield out;
            }
            case ChannelFloatPicture channels -> {
                var out = allocate8(channels.width(), channels.height(), channels.layout());
                convert(channels, out);
                yield out;
            }
            case RgbaPicture rgba -> fromRgba(rgba);
            case Rgba16Picture rgba -> toChannel8(fromRgba16(rgba));
            case YuvPicture yuv -> fromRgba(ImageOps.toRgba(yuv));
            case Yuv16Picture yuv -> toChannel8(fromRgba16(DeepColor.toRgba16(yuv, ChromaUpsampling.TRIANGLE)));
        };
    }

    /**
     * Converts a picture to 16-bit channels.
     *
     * @param picture the picture to convert, of any representation
     * @return the picture itself if it is a {@link Channel16Picture}, otherwise a new one
     */
    static Channel16Picture toChannel16(Picture picture) {
        return switch (picture) {
            case Channel16Picture channels -> channels;
            case Channel8Picture channels -> {
                var out = allocate16(channels.width(), channels.height(), channels.layout());
                convert(channels, out);
                yield out;
            }
            case ChannelFloatPicture channels -> {
                var out = allocate16(channels.width(), channels.height(), channels.layout());
                convert(channels, out);
                yield out;
            }
            case RgbaPicture rgba -> toChannel16(fromRgba(rgba));
            case Rgba16Picture rgba -> fromRgba16(rgba);
            case YuvPicture yuv -> toChannel16(fromRgba(ImageOps.toRgba(yuv)));
            case Yuv16Picture yuv -> fromRgba16(DeepColor.toRgba16(yuv, ChromaUpsampling.TRIANGLE));
        };
    }

    /**
     * Converts a picture to floating-point channels.
     *
     * @param picture the picture to convert, of any representation
     * @return the picture itself if it is a {@link ChannelFloatPicture}, otherwise a new one
     */
    static ChannelFloatPicture toChannelFloat(Picture picture) {
        return switch (picture) {
            case ChannelFloatPicture channels -> channels;
            case Channel8Picture channels -> {
                var out = allocateFloat(channels.width(), channels.height(), channels.layout());
                convert(channels, out);
                yield out;
            }
            case Channel16Picture channels -> {
                var out = allocateFloat(channels.width(), channels.height(), channels.layout());
                convert(channels, out);
                yield out;
            }
            case RgbaPicture rgba -> toChannelFloat(fromRgba(rgba));
            case Rgba16Picture rgba -> toChannelFloat(fromRgba16(rgba));
            case YuvPicture yuv -> toChannelFloat(fromRgba(ImageOps.toRgba(yuv)));
            case Yuv16Picture yuv -> toChannelFloat(fromRgba16(DeepColor.toRgba16(yuv, ChromaUpsampling.TRIANGLE)));
        };
    }

    /**
     * Copies a rectangle of a channel picture.
     *
     * @param picture the picture to copy from
     * @param x the rectangle's left edge
     * @param y the rectangle's top edge
     * @param width the rectangle's width
     * @param height the rectangle's height
     * @return the cropped picture, of the same type and layout
     */
    static ChannelPicture crop(ChannelPicture picture, int x, int y, int width, int height) {
        var channels = picture.channelCount();
        OpChecks.checkArray((long) width * height * channels);
        var out = allocateLike(picture, width, height, picture.layout());
        var src = samples(picture);
        var dst = samples(out);
        var rowLength = width * channels;
        for (var row = 0; row < height; row++) {
            System.arraycopy(src, (y + row) * picture.stride() + x * channels, dst, row * out.stride(), rowLength);
        }
        return out;
    }

    /**
     * Applies an orientation transform to a channel picture.
     *
     * <p>Every output row walks the source along a row or a column, so it is copied from a start index and a
     * step between source pixels.
     *
     * @param picture the picture to transform
     * @param orientation the transform, not {@link Orientation#NORMAL}
     * @return the oriented picture, of the same type and layout
     */
    static ChannelPicture orient(ChannelPicture picture, Orientation orientation) {
        var width = picture.width();
        var height = picture.height();
        var channels = picture.channelCount();
        var stride = picture.stride();
        var swap = orientation.swapsDimensions();
        var outWidth = swap ? height : width;
        var outHeight = swap ? width : height;
        OpChecks.checkArray((long) width * height * channels);
        var out = allocateLike(picture, outWidth, outHeight, picture.layout());
        var reverseRows = orientation == Orientation.TRANSVERSE || orientation == Orientation.ROTATE_270;
        var reverseColumns = orientation == Orientation.ROTATE_90 || orientation == Orientation.TRANSVERSE;
        var bands = ParallelBands.bands(outHeight, (long) width * height * channels);
        ParallelBands.run(bands, band -> {
            var top = ParallelBands.start(band, bands, outHeight);
            var bottom = ParallelBands.start(band + 1, bands, outHeight);
            for (var row = top; row < bottom; row++) {
                int start;
                int step;
                if (swap) {
                    var column = reverseRows ? width - 1 - row : row;
                    start = (reverseColumns ? (height - 1) * stride : 0) + column * channels;
                    step = reverseColumns ? -stride : stride;
                } else {
                    var flipRows = orientation == Orientation.ROTATE_180
                            || orientation == Orientation.FLIP_VERTICAL;
                    var flipColumns = orientation == Orientation.ROTATE_180
                            || orientation == Orientation.FLIP_HORIZONTAL;
                    var sourceRow = flipRows ? height - 1 - row : row;
                    start = sourceRow * stride + (flipColumns ? (width - 1) * channels : 0);
                    step = flipColumns ? -channels : channels;
                }
                copyPixels(picture, start, step, out, row * out.stride(), outWidth, channels);
            }
        });
        return out;
    }

    /**
     * Returns whether every pixel of a channel picture is fully opaque.
     *
     * @param picture the channel picture to read
     * @return {@code true} when the layout has no alpha channel or every alpha sample is at least the nominal
     * maximum
     */
    static boolean isOpaque(ChannelPicture picture) {
        var alpha = picture.layout().alphaIndex();
        if (alpha < 0) {
            return true;
        }
        var width = picture.width();
        var channels = picture.channelCount();
        for (var y = 0; y < picture.height(); y++) {
            var offset = y * picture.stride() + alpha;
            switch (picture) {
                case Channel8Picture eight -> {
                    var pixels = eight.pixels();
                    for (var x = 0; x < width; x++) {
                        if (pixels[offset + x * channels] != (byte) 0xFF) {
                            return false;
                        }
                    }
                }
                case Channel16Picture sixteen -> {
                    var pixels = sixteen.pixels();
                    for (var x = 0; x < width; x++) {
                        if (pixels[offset + x * channels] != (short) 0xFFFF) {
                            return false;
                        }
                    }
                }
                case ChannelFloatPicture floating -> {
                    var pixels = floating.pixels();
                    for (var x = 0; x < width; x++) {
                        if (!(pixels[offset + x * channels] >= 1.0F)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Allocates a channel picture of the same sample type as another.
     *
     * @param like the picture whose sample type is used
     * @param width the width in pixels
     * @param height the height in pixels
     * @param layout the channels of every pixel
     * @return the zero-filled picture
     */
    static ChannelPicture allocateLike(ChannelPicture like, int width, int height, PixelLayout layout) {
        return switch (like) {
            case Channel8Picture _ -> allocate8(width, height, layout);
            case Channel16Picture _ -> allocate16(width, height, layout);
            case ChannelFloatPicture _ -> allocateFloat(width, height, layout);
        };
    }

    /**
     * Returns the backing sample array of a channel picture.
     *
     * @param picture the channel picture to read
     * @return the {@code byte[]}, {@code short[]} or {@code float[]} samples
     */
    static Object samples(ChannelPicture picture) {
        return switch (picture) {
            case Channel8Picture eight -> eight.pixels();
            case Channel16Picture sixteen -> sixteen.pixels();
            case ChannelFloatPicture floating -> floating.pixels();
        };
    }

    /**
     * Returns the largest integer sample of the integer form of a channel picture.
     *
     * @param picture the channel picture to read
     * @return 255 for 8-bit samples, 65535 for 16-bit and floating-point samples
     */
    static int sourceMax(ChannelPicture picture) {
        return picture instanceof Channel8Picture ? 255 : 65535;
    }

    /**
     * Loads one row of a channel picture as integer samples: 8-bit samples as stored, 16-bit samples as stored,
     * floating-point samples quantized with {@link #quantize16(float)}.
     *
     * @param picture the channel picture to read
     * @param y the row
     * @param row the destination, {@code width * channelCount} samples
     */
    static void load(ChannelPicture picture, int y, int[] row) {
        var offset = y * picture.stride();
        var count = picture.width() * picture.channelCount();
        switch (picture) {
            case Channel8Picture eight -> {
                var pixels = eight.pixels();
                for (var i = 0; i < count; i++) {
                    row[i] = pixels[offset + i] & 0xFF;
                }
            }
            case Channel16Picture sixteen -> {
                var pixels = sixteen.pixels();
                for (var i = 0; i < count; i++) {
                    row[i] = pixels[offset + i] & 0xFFFF;
                }
            }
            case ChannelFloatPicture floating -> {
                var pixels = floating.pixels();
                for (var i = 0; i < count; i++) {
                    row[i] = quantize16(pixels[offset + i]);
                }
            }
        }
    }

    /**
     * Quantizes a nominal floating-point sample to 16 bits.
     *
     * @param value the sample, nominally 0.0 to 1.0
     * @return {@code round(value * 65535)} clamped to 0..65535, rounding halves up; 0 for NaN
     */
    static int quantize16(float value) {
        var scaled = value * 65535.0;
        return scaled >= 0 ? (scaled >= 65535 ? 65535 : (int) (scaled + 0.5)) : 0;
    }

    /**
     * Divides a premultiplied integer sample by its alpha.
     *
     * @param value the sample
     * @param alpha the alpha of the pixel, from 0 to {@code max}
     * @param max the largest sample value
     * @return {@code min(max, (value * max + alpha / 2) / alpha)}, or 0 when {@code alpha} is 0
     */
    static int unpremultiply(int value, int alpha, int max) {
        if (alpha == 0) {
            return 0;
        }
        return (int) Math.min(max, ((long) value * max + (alpha >> 1)) / alpha);
    }

    /**
     * Rescales an integer sample between the 8-bit and 16-bit ranges.
     *
     * @param value the sample
     * @param from the largest source value, 255 or 65535
     * @param to the largest target value, 255 or 65535
     * @return the sample; {@code v * 257} when widening, {@code (v * 255 + 32767) / 65535} when narrowing
     */
    static int rescale(int value, int from, int to) {
        if (from == to) {
            return value;
        }
        return to > from ? value * 257 : (value * 255 + 32767) / 65535;
    }

    /**
     * Converts one CMYK ink pair to an RGB component without color management, at the inks' depth.
     *
     * @param ink the cyan, magenta or yellow amount
     * @param black the black amount
     * @param max the largest sample value, 255 or 65535
     * @return {@code round((max - ink) * (max - black) / max)}; {@code max} is odd, so no value is halfway
     */
    static int cmyk(int ink, int black, int max) {
        return (int) (((long) (max - ink) * (max - black) + max / 2) / max);
    }

    /**
     * Returns the straight value of a color sample.
     *
     * @param value the stored sample
     * @param alpha the alpha of the pixel
     * @param premultiplied whether the sample is premultiplied
     * @param max the largest sample value
     * @return the straight sample
     */
    private static int color(int value, int alpha, boolean premultiplied, int max) {
        return premultiplied ? unpremultiply(value, alpha, max) : value;
    }

    /**
     * Copies one output row of pixels from a source start index and step.
     *
     * @param picture the source
     * @param start the index of the first source pixel
     * @param step the distance in samples between consecutive source pixels
     * @param out the destination
     * @param dstOffset the index of the first destination pixel
     * @param count the number of pixels
     * @param channels the samples per pixel
     */
    private static void copyPixels(ChannelPicture picture, int start, int step, ChannelPicture out, int dstOffset,
                                   int count, int channels) {
        switch (picture) {
            case Channel8Picture eight -> {
                var src = eight.pixels();
                var dst = ((Channel8Picture) out).pixels();
                for (int i = 0, s = start, d = dstOffset; i < count; i++, s += step, d += channels) {
                    for (var c = 0; c < channels; c++) {
                        dst[d + c] = src[s + c];
                    }
                }
            }
            case Channel16Picture sixteen -> {
                var src = sixteen.pixels();
                var dst = ((Channel16Picture) out).pixels();
                for (int i = 0, s = start, d = dstOffset; i < count; i++, s += step, d += channels) {
                    for (var c = 0; c < channels; c++) {
                        dst[d + c] = src[s + c];
                    }
                }
            }
            case ChannelFloatPicture floating -> {
                var src = floating.pixels();
                var dst = ((ChannelFloatPicture) out).pixels();
                for (int i = 0, s = start, d = dstOffset; i < count; i++, s += step, d += channels) {
                    for (var c = 0; c < channels; c++) {
                        dst[d + c] = src[s + c];
                    }
                }
            }
        }
    }

    /**
     * Converts the samples of one channel picture into another of the same size and layout.
     *
     * @param src the source
     * @param dst the destination, of another sample type
     */
    private static void convert(ChannelPicture src, ChannelPicture dst) {
        var width = src.width();
        var height = src.height();
        var count = width * src.channelCount();
        var bands = ParallelBands.bands(height, 2L * width * height * src.channelCount());
        ParallelBands.run(bands, band -> {
            var top = ParallelBands.start(band, bands, height);
            var bottom = ParallelBands.start(band + 1, bands, height);
            for (var y = top; y < bottom; y++) {
                var s = y * src.stride();
                var d = y * dst.stride();
                switch (src) {
                    case Channel8Picture eight -> {
                        var in = eight.pixels();
                        if (dst instanceof Channel16Picture wide) {
                            var out = wide.pixels();
                            for (var i = 0; i < count; i++) {
                                out[d + i] = (short) ((in[s + i] & 0xFF) * 257);
                            }
                        } else {
                            var out = ((ChannelFloatPicture) dst).pixels();
                            for (var i = 0; i < count; i++) {
                                out[d + i] = (in[s + i] & 0xFF) / 255.0F;
                            }
                        }
                    }
                    case Channel16Picture sixteen -> {
                        var in = sixteen.pixels();
                        if (dst instanceof Channel8Picture narrow) {
                            var out = narrow.pixels();
                            for (var i = 0; i < count; i++) {
                                out[d + i] = (byte) (((in[s + i] & 0xFFFF) * 255 + 32767) / 65535);
                            }
                        } else {
                            var out = ((ChannelFloatPicture) dst).pixels();
                            for (var i = 0; i < count; i++) {
                                out[d + i] = (in[s + i] & 0xFFFF) / 65535.0F;
                            }
                        }
                    }
                    case ChannelFloatPicture floating -> {
                        var in = floating.pixels();
                        if (dst instanceof Channel8Picture narrow) {
                            var out = narrow.pixels();
                            for (var i = 0; i < count; i++) {
                                out[d + i] = (byte) ((quantize16(in[s + i]) * 255 + 32767) / 65535);
                            }
                        } else {
                            var out = ((Channel16Picture) dst).pixels();
                            for (var i = 0; i < count; i++) {
                                out[d + i] = (short) quantize16(in[s + i]);
                            }
                        }
                    }
                }
            }
        });
    }

    /**
     * Wraps the samples of an 8-bit RGBA picture as channels, RGBA with alpha and RGB without.
     *
     * @param rgba the picture
     * @return the channel picture
     */
    private static Channel8Picture fromRgba(RgbaPicture rgba) {
        var width = rgba.width();
        var height = rgba.height();
        var alpha = rgba.hasAlpha();
        var channels = alpha ? 4 : 3;
        OpChecks.checkArray((long) width * height * channels);
        var out = Channel8Picture.allocate(width, height, alpha ? PixelLayout.RGBA : PixelLayout.RGB);
        var src = rgba.pixels();
        var dst = out.pixels();
        for (var y = 0; y < height; y++) {
            var s = y * rgba.stride();
            var d = y * out.stride();
            if (alpha) {
                System.arraycopy(src, s, dst, d, width * 4);
            } else {
                for (var x = 0; x < width; x++, s += 4, d += 3) {
                    dst[d] = src[s];
                    dst[d + 1] = src[s + 1];
                    dst[d + 2] = src[s + 2];
                }
            }
        }
        return out;
    }

    /**
     * Wraps the samples of a 16-bit RGBA picture as channels, RGBA with alpha and RGB without.
     *
     * @param rgba the picture
     * @return the channel picture
     */
    private static Channel16Picture fromRgba16(Rgba16Picture rgba) {
        var width = rgba.width();
        var height = rgba.height();
        var alpha = rgba.hasAlpha();
        var channels = alpha ? 4 : 3;
        OpChecks.checkArray((long) width * height * channels);
        var out = Channel16Picture.allocate(width, height, alpha ? PixelLayout.RGBA : PixelLayout.RGB);
        var src = rgba.pixels();
        var dst = out.pixels();
        for (var y = 0; y < height; y++) {
            var s = y * rgba.stride();
            var d = y * out.stride();
            if (alpha) {
                System.arraycopy(src, s, dst, d, width * 4);
            } else {
                for (var x = 0; x < width; x++, s += 4, d += 3) {
                    dst[d] = src[s];
                    dst[d + 1] = src[s + 1];
                    dst[d + 2] = src[s + 2];
                }
            }
        }
        return out;
    }

    /**
     * Allocates an 8-bit channel picture after checking its size.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param layout the channels of every pixel
     * @return the picture
     */
    private static Channel8Picture allocate8(int width, int height, PixelLayout layout) {
        OpChecks.checkArray((long) width * height * layout.count());
        return Channel8Picture.allocate(width, height, layout);
    }

    /**
     * Allocates a 16-bit channel picture after checking its size.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param layout the channels of every pixel
     * @return the picture
     */
    private static Channel16Picture allocate16(int width, int height, PixelLayout layout) {
        OpChecks.checkArray((long) width * height * layout.count());
        return Channel16Picture.allocate(width, height, layout);
    }

    /**
     * Allocates a floating-point channel picture after checking its size.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param layout the channels of every pixel
     * @return the picture
     */
    private static ChannelFloatPicture allocateFloat(int width, int height, PixelLayout layout) {
        OpChecks.checkArray((long) width * height * layout.count());
        return ChannelFloatPicture.allocate(width, height, layout);
    }

    /**
     * Returns the failure of an operation that needs packed RGBA or planar Y'CbCr pixels.
     *
     * @param picture the channel picture the operation received
     * @return the exception to throw
     */
    static MediaException.Unsupported unsupported(ChannelPicture picture) {
        return new MediaException.Unsupported("channel picture", "this operation needs RGBA or Y'CbCr pixels, not "
                + picture.layout().colorModel() + " channels; convert the picture with ImageOps.toRgba or toRgba16"
                + " first");
    }
}
