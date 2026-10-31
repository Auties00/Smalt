package com.github.auties00.smalt.image.internal.raster;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.region.ImageRegions;

import java.util.Optional;

/**
 * Turns the pixels the simple raster decoders produce into the {@link DecodedImage} their options ask for.
 *
 * <p>Integer pictures of gray, RGB and alpha samples become packed RGBA of 8 bits, or of 16 bits with
 * {@link ImageDecoderOptions#highBitDepth()} when the samples are wider than 8 bits; channel pictures of other
 * layouts (CMYK, extra channels) are kept with {@link ImageDecoderOptions#keepChannels()} and converted to packed
 * RGBA otherwise. Floating-point pictures are kept with {@code keepChannels} and converted to packed RGBA of 8 bits,
 * or of 16 bits with {@code highBitDepth}, otherwise. {@link ImageDecoderOptions#convertToSrgb()} converts through the
 * color profile last, from the floating-point samples for a floating-point picture.
 */
public final class RasterOutput {
    /**
     * The BT.709 red primary.
     */
    public static final Chromaticity RED = new Chromaticity(0.64, 0.33);

    /**
     * The BT.709 green primary.
     */
    public static final Chromaticity GREEN = new Chromaticity(0.30, 0.60);

    /**
     * The BT.709 blue primary.
     */
    public static final Chromaticity BLUE = new Chromaticity(0.15, 0.06);

    /**
     * The D65 white point.
     */
    public static final Chromaticity D65 = new Chromaticity(0.3127, 0.3290);

    /**
     * Holds the linear profiles, built on first use.
     */
    private static final class Linear {
        static final ColorProfile RGB = ColorProfile.rgb(RED, GREEN, BLUE, D65, 1.0);

        static final ColorProfile GRAY = ColorProfile.gray(1.0);
    }

    private RasterOutput() {
        throw new AssertionError();
    }

    /**
     * {@return the linear-light profile of the BT.709 primaries and the D65 white, the color space of the
     * floating-point formats that declare no other}
     */
    public static ColorProfile linearRgb() {
        return Linear.RGB;
    }

    /**
     * {@return the linear-light gray profile, for gray floating-point pictures}
     */
    public static ColorProfile linearGray() {
        return Linear.GRAY;
    }

    /**
     * {@return the linear-light profile matching the color channels of a layout: gray for one, RGB otherwise}
     *
     * @param layout the layout of the pixels
     */
    public static ColorProfile linearFor(PixelLayout layout) {
        return layout.colorChannels() == 1 ? Linear.GRAY : Linear.RGB;
    }

    /**
     * Finishes a decode.
     *
     * @param picture the decoded pixels: an {@link RgbaPicture} or {@link Rgba16Picture} of the stored samples, or a
     * {@link ChannelPicture}
     * @param format the format decoded
     * @param orientation the display orientation
     * @param profile the color space of the pixels the file declares, or {@code null} for sRGB
     * @param icc the embedded ICC profile bytes, or {@code null}
     * @param options the decoding options
     * @return the decoded image
     */
    public static DecodedImage finish(Picture picture, ImageFormat format, Orientation orientation,
                                      ColorProfile profile, byte[] icc, ImageDecoderOptions options) {
        var width = picture.width();
        var height = picture.height();
        var converted = convert(picture, profile, options);
        var described = profile;
        if (converted != picture && profile != null && profile.colorSpace().channels() != 1
                && profile.colorSpace().channels() != 3) {
            described = null;
        }
        var image = new DecodedImage(converted, format, orientation, width, height, Optional.ofNullable(icc),
                Optional.empty(), Optional.empty(), Optional.ofNullable(described));
        if (!options.convertToSrgb()) {
            return image;
        }
        var source = picture instanceof ChannelPicture && profile != null && fitsChannels(picture, profile)
                ? new DecodedImage(picture, format, orientation, width, height, Optional.ofNullable(icc),
                Optional.empty(), Optional.empty(), Optional.of(profile))
                : image;
        var srgb = ImageOps.toSrgb(source);
        if (!options.highBitDepth() && srgb.picture() instanceof Rgba16Picture wide) {
            return ImageRegions.withPicture(srgb, ImageOps.toEightBit(wide));
        }
        return srgb;
    }

    /**
     * Converts a picture to the representation the options ask for, before any color conversion.
     *
     * @param picture the decoded pixels
     * @param profile the color space of the pixels, or {@code null}
     * @param options the decoding options
     * @return the picture to return, {@code picture} itself when it is kept
     */
    private static Picture convert(Picture picture, ColorProfile profile, ImageDecoderOptions options) {
        var keep = options.keepChannels() && !options.convertToSrgb();
        return switch (picture) {
            case RgbaPicture rgba -> rgba;
            case Rgba16Picture wide -> options.highBitDepth() ? wide : ImageOps.toEightBit(wide);
            case ChannelFloatPicture floats -> keep ? floats
                    : options.highBitDepth() ? ImageOps.toRgba16(floats) : ImageOps.toRgba(floats);
            case Channel16Picture wide -> keep && !plain(wide.layout()) ? (options.highBitDepth() ? wide
                    : ImageOps.toChannel8(wide)) : options.highBitDepth() ? ImageOps.toRgba16(wide)
                    : ImageOps.toRgba(wide);
            case Channel8Picture narrow -> keep && !plain(narrow.layout()) ? narrow : ImageOps.toRgba(narrow);
            default -> picture;
        };
    }

    /**
     * {@return whether a layout is gray or RGB, alone or followed by one alpha channel, which packed RGBA holds
     * without loss}
     *
     * @param layout the channel layout to inspect
     */
    public static boolean plain(PixelLayout layout) {
        return layout.equals(PixelLayout.GRAY) || layout.equals(PixelLayout.GRAY_ALPHA)
                || layout.equals(PixelLayout.RGB) || layout.equals(PixelLayout.RGBA);
    }

    /**
     * {@return whether a profile has as many channels as the color channels of a picture}
     *
     * @param picture the picture to read
     * @param profile the color space of the pixels, or {@code null}
     */
    private static boolean fitsChannels(Picture picture, ColorProfile profile) {
        return picture instanceof ChannelPicture channels
                && channels.layout().colorChannels() == profile.colorSpace().channels();
    }
}
