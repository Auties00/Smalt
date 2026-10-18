package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2ChannelType;
import com.github.auties00.smalt.image.jp2.Jp2ColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2Image;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts the channels of a decoded JPEG 2000 image to a packed RGBA picture.
 *
 * <p>The color channels are chosen by the channel definitions, or without them the first
 * channels the color space needs (one for gray, three for RGB-like spaces, four for CMYK), and
 * the opacity channel likewise (without channel definitions, the channel after the colors of a
 * two- or four-channel image). Subsampled channels are upsampled by replication, each image
 * sample taking the channel sample whose reference grid area covers it. sYCC and e-sYCC samples
 * are converted to RGB and CMYK samples with the naive formula; other color spaces are passed
 * through, one color channel as gray and three as RGB in the declared color space. Samples
 * are scaled from their precision to 8 bits as {@code round(v * 255 / (2^p - 1))}, or to 16 bits
 * likewise, after signed samples are offset by {@code 2^(p-1)}; premultiplied opacity is divided
 * out.
 *
 * @implNote This implementation follows the color conversions of OpenJPEG's
 * {@code opj_decompress} ({@code color.c}): the per-sample formulas of {@code sycc_to_rgb},
 * {@code color_esycc_to_rgb} and {@code color_cmyk_to_rgb}, and its rule that a three-channel
 * image whose second channel is subsampled is sYCC whatever it declares. Unlike
 * {@code sycc422_to_rgb} and {@code sycc420_to_rgb}, a luma sample without a chroma sample of its
 * own (the first column or row at an odd image offset) takes the nearest chroma sample rather
 * than a chroma value of zero.
 */
public final class Jp2Pictures {
    /**
     * Prevents instantiation.
     */
    private Jp2Pictures() {
        throw new AssertionError();
    }

    /**
     * Returns the number of color channels of a color space, given the image's channel count.
     *
     * @param colorSpace the color space
     * @param channels   the number of channels
     * @return the color channels: 1 for gray, 3 for RGB-like spaces, 4 for CMYK, at most
     *         {@code channels}
     */
    public static int colorChannels(Jp2ColorSpace colorSpace, int channels) {
        var colors = switch (colorSpace) {
            case BILEVEL, BILEVEL_INVERTED, GRAYSCALE -> 1;
            case CMYK, YCCK -> 4;
            case UNSPECIFIED, ICC -> channels >= 3 ? 3 : 1;
            default -> 3;
        };
        return Math.min(colors, channels);
    }

    /**
     * Converts a decoded image to a picture.
     *
     * @param image        the decoded image
     * @param highBitDepth whether an image with channels wider than 8 bits keeps 16 bits
     * @param limits       the limits
     * @return the picture: an {@link Rgba16Picture} when {@code highBitDepth} is set and a channel
     *         is wider than 8 bits, an {@link RgbaPicture} otherwise
     * @throws MediaException.Unsupported   if the color space cannot be converted to RGB
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public static Picture toPicture(Jp2Image image, boolean highBitDepth, MediaLimits limits) {
        var width = image.width();
        var height = image.height();
        LimitChecks.checkAllocation(limits, (long) width * height * 4 * (highBitDepth ? 4 : 2));
        var channels = image.channels();
        var colors = new ArrayList<Jp2Channel>();
        var alpha = select(image, colors);
        var space = effectiveColorSpace(image, colors);
        if (space == Jp2ColorSpace.CIELAB || space == Jp2ColorSpace.CIEJAB || space == Jp2ColorSpace.YCCK
                || space == Jp2ColorSpace.PHOTO_YCC) {
            throw new MediaException.Unsupported("JPEG 2000", "converting " + space + " samples to RGB");
        }
        var count = colors.size() >= 3 ? 3 : 1;
        var planes = new int[count][];
        var precisions = new int[count];
        var signed = new boolean[count];
        if ((space == Jp2ColorSpace.SYCC || space == Jp2ColorSpace.E_SYCC) && colors.size() >= 3) {
            var y = upsample(image, colors.get(0));
            var cb = upsample(image, colors.get(1));
            var cr = upsample(image, colors.get(2));
            if (space == Jp2ColorSpace.SYCC) {
                sycc(y, cb, cr, colors.get(0).precision());
            } else {
                esycc(y, cb, cr, colors);
            }
            planes[0] = y;
            planes[1] = cb;
            planes[2] = cr;
            precisions[0] = precisions[1] = precisions[2] = colors.get(0).precision();
        } else if (space == Jp2ColorSpace.CMYK && colors.size() >= 4) {
            var cmyk = new int[4][];
            for (var i = 0; i < 4; i++) {
                cmyk[i] = upsample(image, colors.get(i));
            }
            cmyk(cmyk, colors);
            for (var i = 0; i < 3; i++) {
                planes[i] = cmyk[i];
                precisions[i] = 8;
            }
        } else {
            for (var i = 0; i < count; i++) {
                planes[i] = upsample(image, colors.get(i));
                precisions[i] = colors.get(i).precision();
                signed[i] = colors.get(i).signed();
            }
        }
        var wide = alpha != null && alpha.precision() > 8;
        for (var precision : precisions) {
            wide |= precision > 8;
        }
        var target = highBitDepth && wide ? 65535 : 255;
        var scaled = new int[count][];
        for (var i = 0; i < count; i++) {
            scaled[i] = scale(planes[i], precisions[i], signed[i], target);
        }
        int[] opacity = null;
        if (alpha != null) {
            opacity = scale(upsample(image, alpha), alpha.precision(), alpha.signed(), target);
            if (alpha.type() == Jp2ChannelType.PREMULTIPLIED_OPACITY) {
                for (var plane : scaled) {
                    unpremultiply(plane, opacity, target);
                }
            }
        }
        var pixelCount = width * height;
        if (target == 65535) {
            var pixels = new short[pixelCount * 4];
            for (var i = 0; i < pixelCount; i++) {
                pixels[4 * i] = (short) scaled[0][i];
                pixels[4 * i + 1] = (short) scaled[count == 3 ? 1 : 0][i];
                pixels[4 * i + 2] = (short) scaled[count == 3 ? 2 : 0][i];
                pixels[4 * i + 3] = (short) (opacity == null ? 65535 : opacity[i]);
            }
            return Rgba16Picture.of(width, height, width * 4, pixels, opacity != null);
        }
        var pixels = new byte[pixelCount * 4];
        for (var i = 0; i < pixelCount; i++) {
            pixels[4 * i] = (byte) scaled[0][i];
            pixels[4 * i + 1] = (byte) scaled[count == 3 ? 1 : 0][i];
            pixels[4 * i + 2] = (byte) scaled[count == 3 ? 2 : 0][i];
            pixels[4 * i + 3] = (byte) (opacity == null ? 255 : opacity[i]);
        }
        return RgbaPicture.of(width, height, width * 4, pixels, opacity != null);
    }

    /**
     * Chooses the color channels and the opacity channel.
     *
     * @param image  the decoded image
     * @param colors receives the color channels, in color order
     * @return the opacity channel, or {@code null}
     */
    private static Jp2Channel select(Jp2Image image, List<Jp2Channel> colors) {
        var channels = image.channels();
        Jp2Channel alpha = null;
        var defined = false;
        for (var channel : channels) {
            if (channel.type() == Jp2ChannelType.OPACITY || channel.type() == Jp2ChannelType.PREMULTIPLIED_OPACITY) {
                defined = true;
                if (alpha == null && channel.association() <= 0) {
                    alpha = channel;
                }
            }
        }
        var wanted = colorChannels(image.colorSpace(), channels.size());
        var icc = image.iccProfile();
        if (icc.isPresent()) {
            try {
                var profile = ColorProfile.parse(icc.get());
                if (profile.colorSpace() == ProfileColorSpace.GRAY) {
                    wanted = 1;
                } else if (profile.colorSpace() == ProfileColorSpace.CMYK) {
                    wanted = Math.min(4, channels.size());
                }
            } catch (MediaException exception) {
                // an unreadable profile leaves the channel count to decide
            }
        }
        for (var channel : channels) {
            if (channel.type() == Jp2ChannelType.COLOR && colors.size() < wanted) {
                colors.add(channel);
            }
        }
        if (colors.isEmpty()) {
            colors.add(channels.getFirst());
        }
        if (colors.size() == 2) {
            colors.removeLast();
        }
        if (alpha == null && !defined && wanted != 4 && channels.size() > colors.size()
                && (channels.size() == 2 || channels.size() == 4)) {
            alpha = channels.get(colors.size());
        }
        return alpha;
    }

    /**
     * Returns the color space the color channels are converted from.
     *
     * @param image  the decoded image
     * @param colors the color channels
     * @return the declared color space, sYCC for a three-channel image with subsampled chroma,
     *         or CMYK for four color channels of an ICC-specified image
     */
    private static Jp2ColorSpace effectiveColorSpace(Jp2Image image, List<Jp2Channel> colors) {
        var space = image.colorSpace();
        if (space != Jp2ColorSpace.SYCC && image.channels().size() == 3 && colors.size() == 3
                && colors.get(0).subsamplingX() == colors.get(0).subsamplingY() && colors.get(1).subsamplingX() != 1) {
            return Jp2ColorSpace.SYCC;
        }
        if (space == Jp2ColorSpace.ICC && colors.size() >= 4) {
            return Jp2ColorSpace.CMYK;
        }
        return space;
    }

    /**
     * Upsamples a channel to the image's sample grid by replication.
     *
     * <p>Image sample {@code (x, y)} sits at reference grid position
     * {@code (image.x0 + x, image.y0 + y)} and takes the channel sample at
     * {@code (floor((image.x0 + x) / dx) - channel.x0, floor((image.y0 + y) / dy) - channel.y0)},
     * clamped to the channel.
     *
     * @param image   the decoded image
     * @param channel the channel
     * @return the samples, {@code width * height} of them; the channel's own array when it is not
     *         subsampled
     */
    static int[] upsample(Jp2Image image, Jp2Channel channel) {
        var width = image.width();
        var height = image.height();
        if (channel.subsamplingX() == 1 && channel.subsamplingY() == 1 && channel.width() == width
                && channel.height() == height) {
            return channel.samples();
        }
        var result = new int[width * height];
        if (channel.width() == 0 || channel.height() == 0) {
            return result;
        }
        var columns = new int[width];
        for (var x = 0; x < width; x++) {
            columns[x] = (int) Math.clamp(Math.floorDiv((long) image.x0() + x, channel.subsamplingX()) - channel.x0(),
                    0, channel.width() - 1);
        }
        var samples = channel.samples();
        for (var y = 0; y < height; y++) {
            var row = (int) Math.clamp(Math.floorDiv((long) image.y0() + y, channel.subsamplingY()) - channel.y0(),
                    0, channel.height() - 1);
            var base = row * channel.width();
            var out = y * width;
            for (var x = 0; x < width; x++) {
                result[out + x] = samples[base + columns[x]];
            }
        }
        return result;
    }

    /**
     * Converts sYCC planes to RGB in place, as {@code opj_decompress}'s {@code sycc_to_rgb} does.
     *
     * @param y         the luma plane, replaced by red
     * @param cb        the blue-difference plane, replaced by green
     * @param cr        the red-difference plane, replaced by blue
     * @param precision the precision of the samples
     */
    private static void sycc(int[] y, int[] cb, int[] cr, int precision) {
        var offset = 1 << (precision - 1);
        var upb = (int) ((1L << precision) - 1);
        var red = new int[y.length];
        for (var i = 0; i < y.length; i++) {
            var luma = y[i];
            var blue = cb[i] - offset;
            var redDifference = cr[i] - offset;
            red[i] = Math.clamp(luma + (int) (1.402 * (float) redDifference), 0, upb);
            cb[i] = Math.clamp(luma - (int) (0.344 * (float) blue + 0.714 * (float) redDifference), 0, upb);
            cr[i] = Math.clamp(luma + (int) (1.772 * (float) blue), 0, upb);
        }
        System.arraycopy(red, 0, y, 0, y.length);
    }

    /**
     * Converts e-sYCC planes to RGB in place, as {@code color_esycc_to_rgb} does.
     *
     * @param y      the luma plane, replaced by red
     * @param cb     the blue-difference plane, replaced by green
     * @param cr     the red-difference plane, replaced by blue
     * @param colors the channels, for their precision and signedness
     */
    private static void esycc(int[] y, int[] cb, int[] cr, List<Jp2Channel> colors) {
        var precision = colors.get(0).precision();
        var flip = 1 << (precision - 1);
        var max = (int) ((1L << precision) - 1);
        var signedCb = colors.get(1).signed();
        var signedCr = colors.get(2).signed();
        for (var i = 0; i < y.length; i++) {
            var luma = y[i];
            var blue = signedCb ? cb[i] : cb[i] - flip;
            var red = signedCr ? cr[i] : cr[i] - flip;
            var r = (int) ((float) luma - 0.0000368f * (float) blue + 1.40199f * (float) red + 0.5f);
            var g = (int) (1.0003f * (float) luma - 0.344125f * (float) blue - 0.7141128f * (float) red + 0.5f);
            var b = (int) (0.999823f * (float) luma + 1.77204f * (float) blue - 0.000008f * (float) red + 0.5f);
            y[i] = Math.clamp(r, 0, max);
            cb[i] = Math.clamp(g, 0, max);
            cr[i] = Math.clamp(b, 0, max);
        }
    }

    /**
     * Converts CMYK planes to 8-bit RGB in place, as {@code color_cmyk_to_rgb} does.
     *
     * @param planes the C, M, Y and K planes; the first three are replaced by red, green and blue
     * @param colors the channels, for their precision
     */
    private static void cmyk(int[][] planes, List<Jp2Channel> colors) {
        var sc = 1.0f / (float) ((1L << colors.get(0).precision()) - 1);
        var sm = 1.0f / (float) ((1L << colors.get(1).precision()) - 1);
        var sy = 1.0f / (float) ((1L << colors.get(2).precision()) - 1);
        var sk = 1.0f / (float) ((1L << colors.get(3).precision()) - 1);
        var c = planes[0];
        var m = planes[1];
        var y = planes[2];
        var k = planes[3];
        var length = c.length;
        var red = new int[length];
        var green = new int[length];
        var blue = new int[length];
        for (var i = 0; i < length; i++) {
            var cyan = 1.0f - (float) c[i] * sc;
            var magenta = 1.0f - (float) m[i] * sm;
            var yellow = 1.0f - (float) y[i] * sy;
            var black = 1.0f - (float) k[i] * sk;
            red[i] = Math.clamp((int) (255.0f * cyan * black), 0, 255);
            green[i] = Math.clamp((int) (255.0f * magenta * black), 0, 255);
            blue[i] = Math.clamp((int) (255.0f * yellow * black), 0, 255);
        }
        planes[0] = red;
        planes[1] = green;
        planes[2] = blue;
    }

    /**
     * Scales samples to 8 or 16 bits.
     *
     * @param samples   the samples
     * @param precision their precision
     * @param signed    whether they are signed
     * @param target    the largest output value, 255 or 65535
     * @return the scaled samples, in a new array
     */
    private static int[] scale(int[] samples, int precision, boolean signed, int target) {
        var max = (1L << precision) - 1;
        var offset = signed ? 1L << (precision - 1) : 0;
        var result = new int[samples.length];
        if (!signed && max == target) {
            System.arraycopy(samples, 0, result, 0, samples.length);
            return result;
        }
        for (var i = 0; i < samples.length; i++) {
            var value = Math.clamp(samples[i] + offset, 0, max);
            result[i] = (int) ((value * target * 2 + max) / (2 * max));
        }
        return result;
    }

    /**
     * Divides premultiplied opacity out of a color plane.
     *
     * @param plane   the scaled color plane
     * @param opacity the scaled opacity
     * @param target  the largest value
     */
    private static void unpremultiply(int[] plane, int[] opacity, int target) {
        for (var i = 0; i < plane.length; i++) {
            var a = opacity[i];
            plane[i] = a == 0 ? 0 : Math.min(target, (int) (((long) plane[i] * target + a / 2) / a));
        }
    }
}
