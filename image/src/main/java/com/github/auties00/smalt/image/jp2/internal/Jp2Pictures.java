package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2ChannelType;
import com.github.auties00.smalt.image.jp2.Jp2ColorSpace;
import com.github.auties00.smalt.image.jp2.Jp2Channel;
import com.github.auties00.smalt.image.jp2.Jp2Image;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Converts the channels of a decoded JPEG 2000 image to a packed RGBA picture, or the channels of
 * a CMYK image to a channel picture.
 *
 * <p>The color channels are chosen by the channel definitions, or without them the first
 * channels the color space needs (one for gray, three for RGB-like spaces, four for CMYK), and
 * the opacity channel likewise (without channel definitions, the channel after the colors of a
 * two- or four-channel image). Subsampled channels are upsampled by replication, each image
 * sample taking the channel sample whose reference grid area covers it. sYCC and e-sYCC samples
 * are converted to RGB, CMYK and YCCK samples with the naive formula, CMY samples by complement, the YCbCr,
 * YPbPr and PhotoYCC spaces to R'G'B' with their matrices, and CIE L*a*b* and CIECAM97s J*a*b* samples to 16-bit
 * sRGB (see {@code Jp2Colors}); bi-level samples are inverted (1 is black); other color spaces are passed through,
 * one color channel as gray and three as RGB in the declared color space. Channels wider than 31 bits keep their
 * 31 most significant bits. Samples
 * are scaled from their precision to 8 bits as {@code round(v * 255 / (2^p - 1))}, or to 16 bits
 * likewise, after signed samples are offset by {@code 2^(p-1)}; premultiplied opacity is divided
 * out (for CMYK, from the inks before the conversion).
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
     * Returns the number of color channels of an image, given its color space, its ICC profile and
     * its channel count: an ICC profile of a gray or CMYK color space decides 1 or 4 channels, and
     * otherwise the color space decides: 1 for gray, 3 for RGB-like spaces and for an unspecified or ICC
     * color space of at least three channels, 4 for CMYK.
     *
     * @param colorSpace the color space
     * @param icc        the ICC profile, or {@code null}
     * @param channels   the number of channels
     * @return the color channels, at most {@code channels}
     */
    public static int colorChannels(Jp2ColorSpace colorSpace, byte[] icc, int channels) {
        if (icc != null) {
            try {
                var profile = ColorProfile.parse(icc);
                if (profile.colorSpace() == ProfileColorSpace.GRAY) {
                    return Math.min(1, channels);
                }
                if (profile.colorSpace() == ProfileColorSpace.CMYK) {
                    return Math.min(4, channels);
                }
            } catch (MediaException exception) {
                // an unreadable profile leaves the color space to decide
            }
        }
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
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public static Picture toPicture(Jp2Image image, boolean highBitDepth, MediaLimits limits) {
        image = narrow(image);
        var width = image.width();
        var height = image.height();
        LimitChecks.checkAllocation(limits, (long) width * height * 4 * (highBitDepth ? 4 : 2));
        var channels = image.channels();
        var colors = new ArrayList<Jp2Channel>();
        var alpha = select(image, colors);
        var space = effectiveColorSpace(image, colors);
        var count = colors.size() >= 3 ? 3 : 1;
        var inksStraight = false;
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
        } else if ((space == Jp2ColorSpace.CMYK || space == Jp2ColorSpace.YCCK) && colors.size() >= 4) {
            var cmyk = new int[4][];
            for (var i = 0; i < 4; i++) {
                cmyk[i] = upsample(image, colors.get(i)).clone();
            }
            if (space == Jp2ColorSpace.YCCK) {
                Jp2Colors.ycck(cmyk, colors.subList(0, 4).toArray(Jp2Channel[]::new));
            }
            if (alpha != null && alpha.type() == Jp2ChannelType.PREMULTIPLIED_OPACITY) {
                var opacity = upsample(image, alpha);
                for (var i = 0; i < 4; i++) {
                    cmyk[i] = straightInks(cmyk[i], colors.get(i).precision(), opacity, alpha);
                }
                inksStraight = true;
            }
            cmyk(cmyk, colors);
            for (var i = 0; i < 3; i++) {
                planes[i] = cmyk[i];
                precisions[i] = 8;
            }
        } else if (count == 3 && converted(space)) {
            var three = colors.subList(0, 3).toArray(Jp2Channel[]::new);
            for (var i = 0; i < 3; i++) {
                planes[i] = upsample(image, colors.get(i)).clone();
            }
            switch (space) {
                case CIELAB -> Jp2Colors.lab(planes, three, image.colorParameters().orElse(null));
                case CIEJAB -> Jp2Colors.jab(planes, three, image.colorParameters().orElse(null));
                case PHOTO_YCC -> Jp2Colors.photoYcc(planes, three);
                case CMY -> Jp2Colors.cmy(planes, three);
                default -> Jp2Colors.ycc(planes, three, space);
            }
            var lab = space == Jp2ColorSpace.CIELAB || space == Jp2ColorSpace.CIEJAB;
            for (var i = 0; i < 3; i++) {
                precisions[i] = lab ? 16 : space == Jp2ColorSpace.CMY ? three[i].precision() : three[0].precision();
            }
        } else {
            for (var i = 0; i < count; i++) {
                planes[i] = upsample(image, colors.get(i));
                precisions[i] = colors.get(i).precision();
                signed[i] = colors.get(i).signed();
            }
            if (space == Jp2ColorSpace.BILEVEL && count == 1) {
                var max = (int) ((1L << precisions[0]) - 1);
                var inverted = new int[planes[0].length];
                var offset = signed[0] ? 1 << (precisions[0] - 1) : 0;
                for (var i = 0; i < inverted.length; i++) {
                    inverted[i] = max - Math.clamp(planes[0][i] + offset, 0, max);
                }
                planes[0] = inverted;
                signed[0] = false;
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
            if (alpha.type() == Jp2ChannelType.PREMULTIPLIED_OPACITY && !inksStraight) {
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
     * Returns whether the color channels of a color space are converted by {@code Jp2Colors}.
     *
     * @param space the color space
     * @return whether it is CMY, a YCbCr or YPbPr space, PhotoYCC, CIE L*a*b* or CIECAM97s J*a*b*
     */
    private static boolean converted(Jp2ColorSpace space) {
        return switch (space) {
            case CMY, YCBCR_709, YCBCR_601_625, YCBCR_601_525, YPBPR_1125_60, YPBPR_1250_50, PHOTO_YCC, CIELAB,
                 CIEJAB -> true;
            default -> false;
        };
    }

    /**
     * Returns the color profile of the pixels a color space converts to.
     *
     * @param space the enumerated color space
     * @return sRGB for the sRGB, e-sRGB, sYCC, e-sYCC, YCbCr(2), CIE L*a*b* and CIECAM97s J*a*b* spaces, the Rec.
     * ITU-R BT.709 profile for YCbCr(1), the YPbPr spaces and PhotoYCC, the BT.601 525-line profile for YCbCr(3),
     * ProPhoto RGB for ROMM-RGB, or empty
     */
    public static Optional<ColorProfile> profile(Jp2ColorSpace space) {
        return switch (space) {
            case SRGB, E_SRGB, SYCC, E_SYCC, YCBCR_601_625, CIELAB, CIEJAB -> Optional.of(ColorProfile.srgb());
            case YCBCR_709, YPBPR_1125_60, YPBPR_1250_50, PHOTO_YCC -> Optional.of(ColorProfile.rec709());
            case YCBCR_601_525 -> Optional.of(ColorProfile.of(new ColorInfo(MatrixCoefficients.IDENTITY,
                    ColorRange.FULL, ColorPrimaries.SMPTE170M, TransferCharacteristics.BT709)));
            case ROMM_RGB -> Optional.of(ColorProfile.proPhotoRgb());
            default -> Optional.empty();
        };
    }

    /**
     * Narrows the channels wider than 31 bits to their 31 most significant bits.
     *
     * @param image the decoded image
     * @return the image, itself when no channel is wider
     */
    private static Jp2Image narrow(Jp2Image image) {
        var wide = false;
        for (var channel : image.channels()) {
            wide |= channel.wide();
        }
        if (!wide) {
            return image;
        }
        var channels = new ArrayList<Jp2Channel>();
        for (var channel : image.channels()) {
            if (!channel.wide()) {
                channels.add(channel);
                continue;
            }
            var shift = channel.precision() - 31;
            var source = channel.wideSamples();
            var samples = new int[source.length];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (int) (source[i] >> shift);
            }
            channels.add(new Jp2Channel(channel.x0(), channel.y0(), channel.width(), channel.height(),
                    channel.subsamplingX(), channel.subsamplingY(), 31, channel.signed(), channel.type(),
                    channel.association(), samples));
        }
        return new Jp2Image(image.x0(), image.y0(), image.width(), image.height(), image.fullWidth(),
                image.fullHeight(), image.reduction(), channels, image.colorSpace(), image.colorParameters(),
                image.iccProfile(), image.exif(), image.xmp());
    }

    /**
     * Converts the channels of a CMYK image to a channel picture, keeping the inks.
     *
     * <p>The cyan, magenta, yellow and black channels are chosen as {@link #toPicture} chooses
     * them, upsampled as it upsamples them, and followed by the opacity channel when there is one,
     * straight or premultiplied as the channel definitions declare. The samples keep their stored
     * meaning, 0 being no ink, and are scaled to 8 bits as {@code round(v * 255 / (2^p - 1))}, or to
     * 16 bits likewise when {@code highBitDepth} is set and a kept channel is wider than 8 bits,
     * after signed samples are offset by {@code 2^(p-1)}.
     *
     * @param image        the decoded image
     * @param highBitDepth whether an image with channels wider than 8 bits keeps 16 bits
     * @param limits       the resource bounds checked before the allocation
     * @return the picture of the {@link PixelLayout#CMYK} layout, followed by alpha when the image
     *         has an opacity channel, or {@code null} when the color channels are not CMYK
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    public static ChannelPicture toChannels(Jp2Image image, boolean highBitDepth, MediaLimits limits) {
        image = narrow(image);
        var colors = new ArrayList<Jp2Channel>();
        var alpha = select(image, colors);
        if (effectiveColorSpace(image, colors) != Jp2ColorSpace.CMYK || colors.size() < 4) {
            return null;
        }
        var kept = new ArrayList<>(colors.subList(0, 4));
        var layout = PixelLayout.CMYK;
        if (alpha != null) {
            kept.add(alpha);
            layout = layout.withChannel(Channel.of(alpha.type() == Jp2ChannelType.PREMULTIPLIED_OPACITY
                    ? ChannelType.PREMULTIPLIED_ALPHA : ChannelType.ALPHA));
        }
        var wide = false;
        for (var channel : kept) {
            wide |= channel.precision() > 8;
        }
        var target = highBitDepth && wide ? 65535 : 255;
        var width = image.width();
        var height = image.height();
        var count = kept.size();
        LimitChecks.checkAllocation(limits, (long) width * height * count * (target == 65535 ? 6 : 5));
        var length = width * height;
        if (target == 65535) {
            var picture = Channel16Picture.allocate(width, height, layout);
            var pixels = picture.pixels();
            for (var c = 0; c < count; c++) {
                var channel = kept.get(c);
                var plane = scale(upsample(image, channel), channel.precision(), channel.signed(), target);
                for (var i = 0; i < length; i++) {
                    pixels[i * count + c] = (short) plane[i];
                }
            }
            return picture;
        }
        var picture = Channel8Picture.allocate(width, height, layout);
        var pixels = picture.pixels();
        for (var c = 0; c < count; c++) {
            var channel = kept.get(c);
            var plane = scale(upsample(image, channel), channel.precision(), channel.signed(), target);
            for (var i = 0; i < length; i++) {
                pixels[i * count + c] = (byte) plane[i];
            }
        }
        return picture;
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
        var wanted = colorChannels(image.colorSpace(), image.iccProfile().orElse(null), channels.size());
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
     * Divides premultiplied opacity out of an ink plane at the inks' precision, so that the naive
     * conversion reads the inks of the color rather than the inks scaled by the opacity.
     *
     * @param inks      the ink samples
     * @param precision the precision of the inks
     * @param opacity   the opacity samples, upsampled to the image
     * @param alpha     the opacity channel, for its precision and signedness
     * @return the straight inks, {@code min(max, round(v * A / a))} for an opacity {@code a} of
     *         largest value {@code A}, 0 where {@code a} is 0; in a new array
     */
    private static int[] straightInks(int[] inks, int precision, int[] opacity, Jp2Channel alpha) {
        var max = (1L << precision) - 1;
        var alphaMax = (1L << alpha.precision()) - 1;
        var offset = alpha.signed() ? 1L << (alpha.precision() - 1) : 0;
        var result = new int[inks.length];
        for (var i = 0; i < inks.length; i++) {
            var a = Math.clamp(opacity[i] + offset, 0, alphaMax);
            var value = Math.clamp(inks[i], 0, max);
            result[i] = a == 0 ? 0 : (int) Math.min(max, (value * alphaMax + a / 2) / a);
        }
        return result;
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
