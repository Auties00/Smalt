package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ProfileCurve;
import com.github.auties00.smalt.image.internal.raster.RasterInput;
import com.github.auties00.smalt.image.internal.raster.RasterOutput;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.codec.EncodeMetadata;

/**
 * Prepares the pictures of the simple raster formats, which record no color profile, orientation or metadata.
 */
final class RasterEncoding {
    private RasterEncoding() {
        throw new AssertionError();
    }

    /**
     * Returns a picture for a format that stores sRGB samples: converted to sRGB through the options' profile and
     * turned upright.
     *
     * @param picture the picture to encode
     * @param options the encoder options
     * @return the picture to write
     * @throws IllegalArgumentException if the profile does not describe the picture
     */
    static Picture srgb(Picture picture, ImageEncoderOptions options) {
        var prepared = EncodeMetadata.color(picture, options, (profile, image) -> false);
        return EncodeMetadata.upright(prepared.picture(), options);
    }

    /**
     * Returns a picture for a format that stores linear light in the BT.709 primaries: its floating-point samples
     * converted from the options' profile, or from sRGB without one, to {@link RasterOutput#linearRgb()} (or the
     * linear gray profile for a gray picture), turned upright.
     *
     * @param picture the picture to encode
     * @param options the encoder options
     * @return the floating-point picture to write, gray or RGB, with alpha when the picture has it
     * @throws IllegalArgumentException if the profile does not describe the picture
     */
    static ChannelFloatPicture linear(Picture picture, ImageEncoderOptions options) {
        var gray = RasterInput.isGray(picture);
        var floats = RasterInput.floats(picture, gray, true);
        var source = options.colorProfile().orElse(gray ? Gray.SRGB : ColorProfile.srgb());
        EncodeMetadata.checkProfile(floats, source);
        var target = RasterOutput.linearFor(floats.layout());
        var converted = (ChannelFloatPicture) ImageOps.convertColor(floats, source, target);
        return (ChannelFloatPicture) EncodeMetadata.upright(converted, options);
    }

    /**
     * Holds the gray profile of the sRGB tone curve, the space of gray pictures without a profile.
     */
    private static final class Gray {
        static final ColorProfile SRGB = ColorProfile.gray(RasterOutput.D65, ProfileCurve.srgb());
    }
}
