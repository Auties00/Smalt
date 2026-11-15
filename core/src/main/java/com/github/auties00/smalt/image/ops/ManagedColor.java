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
import com.github.auties00.smalt.image.color.Colorant;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Applies color transforms to pictures.
 *
 * <p>Pictures are converted at their own depth: 8-bit pictures through packed 8-bit RGBA, 16-bit
 * pictures through packed 16-bit RGBA. Y'CbCr pictures are first converted to R'G'B' with the
 * matrix and range of their {@link ColorInfo}: 8-bit ones as {@link ImageOps#toRgba(Picture)}
 * does, 16-bit ones as {@link ImageOps#toRgba16(Picture)} does, keeping their full precision. A
 * gray source profile reads the red channel; a gray target profile writes the same value to red,
 * green and blue. Alpha is never changed. A transform to a CMYK or multi-ink profile produces a
 * {@link ChannelPicture}, and so does the conversion of a channel picture, whose color channels are
 * converted while its extra channels are copied, premultiplied colors being divided by their alpha
 * before the transform and multiplied again after it. The channels a transform reads from a channel picture are
 * found as {@link #inputChannels(PixelLayout, int, List)} describes: named inks and spot colors are matched to the
 * colorants of the source profile by name.
 */
final class ManagedColor {
    /**
     * The approximate number of operations per pixel of a color transform, which weighs its work
     * against {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long WORK = 24;

    /**
     * The input mapping that reads the leading color channels in order.
     */
    static final int[] IDENTITY = new int[0];

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
     * @param transform the transform, from an RGB or gray profile (or, for a channel picture, a profile whose
     * channels {@link #inputChannels(PixelLayout, int, List)} finds in it) to an RGB, gray, CMYK or multi-ink
     * profile
     * @return an {@link RgbaPicture} for an 8-bit picture, an {@link Rgba16Picture} for a 16-bit one, or a
     * {@link ChannelPicture} of the transform's target color model for a channel picture or a CMYK or multi-ink
     * target
     * @throws IllegalArgumentException if a profile of the transform does not fit the picture
     */
    static Picture convert(Picture picture, ColorTransform transform) {
        var in = transform.inputChannels();
        var out = transform.outputChannels();
        var model = targetModel(transform);
        if (picture instanceof ChannelPicture channels) {
            var colorants = readableColorants(transform::inputColorants);
            var inputs = inputChannels(channels.layout(), in, colorants);
            if (inputs == null) {
                throw new IllegalArgumentException("the transform reads " + in + " channels, the picture has "
                        + channels.layout().colorChannels() + " color channels"
                        + (colorants.isEmpty() ? "" : " and no channels named as its colorants"));
            }
            return convertChannels(channels, transform, model, false, inputs);
        }
        var separation = model == ColorModel.CMYK || model == ColorModel.INK;
        if ((in != 1 && in != 3) || (out != 1 && out != 3 && !(separation && in == 3))) {
            throw new IllegalArgumentException("a picture converts only from RGB and gray profiles to RGB, gray,"
                    + " CMYK and multi-ink profiles, not " + transform.inputColorSpace() + " to "
                    + transform.outputColorSpace());
        }
        if (separation) {
            Picture channels = switch (picture) {
                case RgbaPicture _, YuvPicture _ -> ChannelOps.toChannel8(picture);
                default -> ChannelOps.toChannel16(picture);
            };
            return convertChannels((ChannelPicture) channels, transform, model, false, IDENTITY);
        }
        return switch (picture) {
            case RgbaPicture rgba -> convert8(rgba, transform);
            case YuvPicture yuv -> convert8(ImageOps.toRgba(yuv), transform);
            case Rgba16Picture rgba -> convert16(rgba, transform);
            case Yuv16Picture yuv -> convert16(DeepColor.toRgba16(yuv, ChromaUpsampling.TRIANGLE), transform);
            case ChannelPicture channels -> throw new AssertionError(channels);
        };
    }

    /**
     * Finds the channels of a channel picture that a transform reads.
     *
     * <p>When the source has as many channels as the picture's color channels, those are read in order, unless the
     * picture holds inks or CMYK and the source's colorants name each of its color channels: then each source
     * channel reads the color channel of its colorant's name. Otherwise, when the source has more channels and its
     * colorants name every color channel and some {@link ChannelType#SPOT} channels of the picture, each source
     * channel reads the channel of its colorant's name, and the spot channels read are no longer extra channels of
     * the result. Names are compared ignoring case and surrounding white space; a CMYK channel is named
     * {@code Cyan}, {@code Magenta}, {@code Yellow} or {@code Black}.
     *
     * @param layout the layout of the picture
     * @param needed the channels the transform reads
     * @param colorants the colorants of the transform's source, empty when it names none
     * @return the index of the picture channel each transform channel reads, {@link #IDENTITY} for the leading
     * color channels in order, or {@code null} when the transform does not fit the picture
     */
    static int[] inputChannels(PixelLayout layout, int needed, List<Colorant> colorants) {
        var colors = layout.colorChannels();
        var model = layout.colorModel();
        if (needed == colors) {
            if (model == ColorModel.INK || model == ColorModel.CMYK) {
                var named = byName(layout, colorants, false);
                if (named != null && !isLeading(named)) {
                    return named;
                }
            }
            return IDENTITY;
        }
        if (needed < colors || model == ColorModel.RGB || model == ColorModel.GRAY) {
            return null;
        }
        var named = byName(layout, colorants, true);
        if (named == null) {
            return null;
        }
        var read = 0;
        for (var index : named) {
            if (index < colors) {
                read++;
            }
        }
        return read == colors ? named : null;
    }

    /**
     * Matches colorants to the channels of a picture by name.
     *
     * @param layout the layout of the picture
     * @param colorants the colorants to find, in the order of the source channels
     * @param spots whether spot channels may be matched besides the color channels
     * @return the index of the channel of each colorant, or {@code null} when there are no colorants, or a colorant
     * has no name or not exactly one channel of its name
     */
    private static int[] byName(PixelLayout layout, List<Colorant> colorants, boolean spots) {
        if (colorants.isEmpty()) {
            return null;
        }
        var out = new int[colorants.size()];
        var used = new boolean[layout.count()];
        for (var k = 0; k < out.length; k++) {
            var name = colorants.get(k).name().strip();
            if (name.isEmpty()) {
                return null;
            }
            var found = -1;
            for (var i = 0; i < layout.count(); i++) {
                var channel = layout.channel(i);
                if ((channel.type().isColor() || (spots && channel.type() == ChannelType.SPOT))
                        && name.equalsIgnoreCase(channelName(channel))) {
                    if (found >= 0) {
                        return null;
                    }
                    found = i;
                }
            }
            if (found < 0 || used[found]) {
                return null;
            }
            used[found] = true;
            out[k] = found;
        }
        return out;
    }

    /**
     * Returns the name a channel is matched by.
     *
     * @param channel a channel of the picture
     * @return the process ink of a CMYK channel, otherwise the channel's own name without surrounding white space
     */
    private static String channelName(Channel channel) {
        return switch (channel.type()) {
            case CYAN -> "Cyan";
            case MAGENTA -> "Magenta";
            case YELLOW -> "Yellow";
            case BLACK -> "Black";
            default -> channel.name().strip();
        };
    }

    /**
     * Returns whether channel indexes are the leading channels in order.
     *
     * @param indexes the channel indexes
     * @return {@code true} when index {@code i} holds {@code i} for every {@code i}
     */
    private static boolean isLeading(int[] indexes) {
        for (var i = 0; i < indexes.length; i++) {
            if (indexes[i] != i) {
                return false;
            }
        }
        return true;
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
                image.sourceHeight(), Optional.empty(), image.exif(), image.xmp(), Optional.of(ColorProfile.srgb()),
                image.density());
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
        var inputs = profile == null || profile.isSrgb() ? null : fittingInputs(layout, profile);
        if (inputs != null) {
            try {
                var transform = ColorTransform.create(profile, ColorProfile.srgb(), options);
                var converted = convertChannels(channels, transform, ColorModel.RGB, true, inputs);
                return new DecodedImage(ChannelOps.toPacked(converted, sixteen), image.format(), image.orientation(),
                        image.sourceWidth(), image.sourceHeight(), Optional.empty(), image.exif(), image.xmp(),
                        Optional.of(ColorProfile.srgb()), image.density());
            } catch (MediaException.Malformed | MediaException.Unsupported exception) {
                // a profile that cannot be applied leaves the conversion without color management
            }
        }
        var model = layout.colorModel();
        var describes = profile != null && (model == ColorModel.RGB || model == ColorModel.GRAY)
                && profile.channels() == layout.colorChannels();
        return new DecodedImage(ChannelOps.toPacked(channels, sixteen), image.format(), image.orientation(),
                image.sourceWidth(), image.sourceHeight(), image.iccProfile(), image.exif(), image.xmp(),
                describes ? image.colorProfile() : Optional.empty(), image.density());
    }

    /**
     * Finds the channels of a channel picture that its profile reads.
     *
     * @param layout the layout of the picture
     * @param profile its profile
     * @return the channels as {@link #inputChannels(PixelLayout, int, List)} returns them, or {@code null} when the
     * profile does not fit the picture or its colorant table is damaged
     */
    private static int[] fittingInputs(PixelLayout layout, ColorProfile profile) {
        var needed = profile.channels();
        if (needed == 0) {
            return null;
        }
        var colorants = needed == layout.colorChannels() && layout.colorModel() != ColorModel.INK
                && layout.colorModel() != ColorModel.CMYK ? List.<Colorant>of() : readableColorants(profile::colorants);
        return inputChannels(layout, needed, colorants.size() == needed ? colorants : List.of());
    }

    /**
     * Reads a colorant table, which only names channels, so that a damaged one names none.
     *
     * @param table the accessor of the table
     * @return the colorants, empty when the table is damaged
     */
    private static List<Colorant> readableColorants(Supplier<List<Colorant>> table) {
        try {
            return table.get();
        } catch (MediaException.Malformed exception) {
            return List.of();
        }
    }

    /**
     * Applies a transform to the color channels of a channel picture.
     *
     * @param picture the channel picture to convert
     * @param transform the transform to apply
     * @param model the color model of the transform's target
     * @param straight whether premultiplied colors stay divided by their alpha, the alpha channel of the result
     * becoming straight
     * @param inputs the channels the transform reads, as {@link #inputChannels(PixelLayout, int, List)} returns
     * them
     * @return the picture of the target model, followed by the extra channels the transform does not read
     * @throws IllegalArgumentException if the target is not gray, RGB, CMYK or multi-ink
     */
    private static ChannelPicture convertChannels(ChannelPicture picture, ColorTransform transform,
                                                  ColorModel model, boolean straight, int[] inputs) {
        if (model == null) {
            throw new IllegalArgumentException("a picture converts only to RGB, gray, CMYK and multi-ink profiles,"
                    + " not " + transform.outputColorSpace());
        }
        var layout = picture.layout();
        var colors = layout.colorChannels();
        var reads = inputs == IDENTITY ? null : inputs;
        var needed = transform.inputChannels();
        var consumed = new boolean[layout.count()];
        Arrays.fill(consumed, 0, colors, true);
        if (reads != null) {
            for (var index : reads) {
                consumed[index] = true;
            }
        }
        var base = switch (model) {
            case GRAY -> PixelLayout.GRAY;
            case RGB -> PixelLayout.RGB;
            case CMYK -> PixelLayout.CMYK;
            default -> PixelLayout.ofInks(inkNames(transform));
        };
        var list = new ArrayList<>(base.channels());
        var alpha = layout.alphaIndex();
        var outAlpha = -1;
        var keptList = new ArrayList<Integer>();
        for (var i = colors; i < layout.count(); i++) {
            if (!consumed[i]) {
                if (i == alpha) {
                    outAlpha = list.size();
                }
                keptList.add(i);
                list.add(layout.channel(i));
            }
        }
        var alphaOut = outAlpha;
        var premultiplied = layout.isAlphaPremultiplied();
        var multiply = premultiplied && !straight;
        if (premultiplied && straight) {
            list.set(outAlpha, Channel.of(ChannelType.ALPHA));
        }
        var kept = new int[keptList.size()];
        for (var k = 0; k < kept.length; k++) {
            kept[k] = keptList.get(k);
        }
        var outLayout = new PixelLayout(list);
        var width = picture.width();
        var height = picture.height();
        OpChecks.checkArray((long) width * height * outLayout.count());
        var out = ChannelOps.allocateLike(picture, width, height, outLayout);
        var inChannels = layout.count();
        var outChannels = outLayout.count();
        var outColors = base.count();
        var bands = ParallelBands.bands(height, WORK * width * height);
        ParallelBands.run(bands, band -> {
            var top = ParallelBands.start(band, bands, height);
            var bottom = ParallelBands.start(band + 1, bands, height);
            var straight8 = premultiplied && picture instanceof Channel8Picture ? new byte[width * inChannels] : null;
            var straight16 = premultiplied && picture instanceof Channel16Picture ? new short[width * inChannels]
                    : null;
            var straightFloat = premultiplied && picture instanceof ChannelFloatPicture
                    ? new float[width * inChannels] : null;
            var gather8 = reads != null && picture instanceof Channel8Picture ? new byte[width * needed] : null;
            var gather16 = reads != null && picture instanceof Channel16Picture ? new short[width * needed] : null;
            var gatherFloat = reads != null && picture instanceof ChannelFloatPicture ? new float[width * needed]
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
                        var inputStride = inChannels;
                        if (premultiplied) {
                            input = straightRow8(src, s, width, inChannels, colors, alpha, straight8);
                            inputOffset = 0;
                        }
                        if (reads != null) {
                            for (var x = 0; x < width; x++) {
                                for (var c = 0; c < needed; c++) {
                                    gather8[x * needed + c] = input[inputOffset + x * inChannels + reads[c]];
                                }
                            }
                            input = gather8;
                            inputOffset = 0;
                            inputStride = needed;
                        }
                        transform.transform(input, inputOffset, inputStride, dst, d, outChannels, width);
                        for (var x = 0; kept.length > 0 && x < width; x++) {
                            for (var k = 0; k < kept.length; k++) {
                                dst[d + x * outChannels + outColors + k] = src[s + x * inChannels + kept[k]];
                            }
                        }
                        if (multiply) {
                            for (var x = 0; x < width; x++) {
                                var at = d + x * outChannels;
                                var a = dst[at + alphaOut] & 0xFF;
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
                        var inputStride = inChannels;
                        if (premultiplied) {
                            input = straightRow16(src, s, width, inChannels, colors, alpha, straight16);
                            inputOffset = 0;
                        }
                        if (reads != null) {
                            for (var x = 0; x < width; x++) {
                                for (var c = 0; c < needed; c++) {
                                    gather16[x * needed + c] = input[inputOffset + x * inChannels + reads[c]];
                                }
                            }
                            input = gather16;
                            inputOffset = 0;
                            inputStride = needed;
                        }
                        transform.transform(input, inputOffset, inputStride, dst, d, outChannels, width);
                        for (var x = 0; kept.length > 0 && x < width; x++) {
                            for (var k = 0; k < kept.length; k++) {
                                dst[d + x * outChannels + outColors + k] = src[s + x * inChannels + kept[k]];
                            }
                        }
                        if (multiply) {
                            for (var x = 0; x < width; x++) {
                                var at = d + x * outChannels;
                                var a = dst[at + alphaOut] & 0xFFFF;
                                for (var c = 0; c < outColors; c++) {
                                    dst[at + c] = (short) (((long) (dst[at + c] & 0xFFFF) * a + 32767) / 65535);
                                }
                            }
                        }
                    }
                    case ChannelFloatPicture floating -> {
                        var src = floating.pixels();
                        var dst = ((ChannelFloatPicture) out).pixels();
                        var input = src;
                        var inputOffset = s;
                        var inputStride = inChannels;
                        if (premultiplied) {
                            input = straightRowFloat(src, s, width, inChannels, colors, alpha, straightFloat);
                            inputOffset = 0;
                        }
                        if (reads != null) {
                            for (var x = 0; x < width; x++) {
                                for (var c = 0; c < needed; c++) {
                                    gatherFloat[x * needed + c] = input[inputOffset + x * inChannels + reads[c]];
                                }
                            }
                            input = gatherFloat;
                            inputOffset = 0;
                            inputStride = needed;
                        }
                        transform.transform(input, inputOffset, inputStride, dst, d, outChannels, width);
                        for (var x = 0; kept.length > 0 && x < width; x++) {
                            for (var k = 0; k < kept.length; k++) {
                                dst[d + x * outChannels + outColors + k] = src[s + x * inChannels + kept[k]];
                            }
                        }
                        if (multiply) {
                            for (var x = 0; x < width; x++) {
                                var at = d + x * outChannels;
                                var a = dst[at + alphaOut];
                                for (var c = 0; c < outColors; c++) {
                                    dst[at + c] *= a;
                                }
                            }
                        }
                    }
                }
            }
        });
        return out;
    }

    /**
     * Returns the names of the inks a transform writes.
     *
     * @param transform a transform to a multi-ink space
     * @return the name of each output colorant, or an empty string for each output channel when the target
     * profile names none or its colorant table is damaged
     */
    private static List<String> inkNames(ColorTransform transform) {
        var colorants = readableColorants(transform::outputColorants);
        var names = new ArrayList<String>(transform.outputChannels());
        for (var c = 0; c < transform.outputChannels(); c++) {
            names.add(colorants.isEmpty() ? "" : colorants.get(c).name());
        }
        return names;
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
     * Copies one row of floating-point pixels with their color channels divided by their alpha; colors of a pixel
     * whose alpha is not positive become zero.
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
    private static float[] straightRowFloat(float[] src, int offset, int width, int channels, int colors, int alpha,
                                            float[] row) {
        System.arraycopy(src, offset, row, 0, width * channels);
        for (var at = 0; at < width * channels; at += channels) {
            var a = row[at + alpha];
            for (var c = 0; c < colors; c++) {
                row[at + c] = a > 0 ? row[at + c] / a : 0;
            }
        }
        return row;
    }

    /**
     * Returns the color model a transform's target profile describes.
     *
     * @param transform the color transform to apply
     * @return {@link ColorModel#GRAY}, {@link ColorModel#RGB}, {@link ColorModel#CMYK} or {@link ColorModel#INK}, or
     * {@code null} for another color space
     */
    private static ColorModel targetModel(ColorTransform transform) {
        return switch (transform.outputColorSpace()) {
            case GRAY -> ColorModel.GRAY;
            case RGB -> ColorModel.RGB;
            case CMYK -> ColorModel.CMYK;
            case INK -> ColorModel.INK;
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
