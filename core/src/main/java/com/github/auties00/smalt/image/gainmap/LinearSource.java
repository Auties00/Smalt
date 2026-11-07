package com.github.auties00.smalt.image.gainmap;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.ops.ImageOps;

/**
 * Reads a decoded image as relative linear light in its own primaries, row by row.
 *
 * <p>The color of the image is, in order: the CICP description of a Y'CbCr picture from a file that embeds no
 * ICC profile, when its transfer function decodes analytically; the CICP description of a high-dynamic-range
 * profile; sRGB for an absent profile or one equal to sRGB; otherwise the profile, through which the samples
 * are converted at 16 bits to its own primaries with a gamma 2.2 tone curve. Instances are immutable and
 * thread-safe.
 */
final class LinearSource {
    /**
     * The width in pixels.
     */
    final int width;

    /**
     * The height in pixels.
     */
    final int height;

    /**
     * The primaries of the linear values.
     */
    final Primaries primaries;

    /**
     * Whether the picture reports alpha.
     */
    final boolean hasAlpha;

    /**
     * Whether the image is a PQ or HLG rendition.
     */
    final boolean highDynamicRange;

    /**
     * The 8-bit RGBA samples, or {@code null} when {@link #shorts} holds them.
     */
    private final byte[] bytes;

    /**
     * The 16-bit RGBA samples, or {@code null} when {@link #bytes} holds them.
     */
    private final short[] shorts;

    /**
     * The distance in samples between rows.
     */
    private final int stride;

    /**
     * The decoding of a code value to linear light, or to scene light for HLG.
     */
    private final float[] table;

    /**
     * Whether the table gives HLG scene light, which the system gamma turns into display light.
     */
    private final boolean hlg;

    /**
     * The display light of HLG scene light 1 relative to the reference white.
     */
    private final float hlgScale;

    /**
     * The luminance weights of red, green and blue.
     */
    private final float lumaRed;
    private final float lumaGreen;
    private final float lumaBlue;

    private LinearSource(Picture picture, Primaries primaries, TransferCharacteristics transfer,
                         double referenceWhite) {
        this.width = picture.width();
        this.height = picture.height();
        this.primaries = primaries;
        this.hasAlpha = picture.hasAlpha();
        this.highDynamicRange = transfer.isHdr();
        if (picture instanceof RgbaPicture rgba) {
            this.bytes = rgba.pixels();
            this.shorts = null;
            this.stride = rgba.stride();
            this.table = Transfers.decode(transfer, 8, referenceWhite);
        } else {
            var rgba = ImageOps.toRgba16(picture);
            this.bytes = null;
            this.shorts = rgba.pixels();
            this.stride = rgba.stride();
            this.table = Transfers.decode(transfer, 16, referenceWhite);
        }
        this.hlg = transfer == TransferCharacteristics.HLG;
        this.hlgScale = (float) (Transfers.HLG_PEAK / referenceWhite);
        var luminance = primaries.luminance();
        this.lumaRed = (float) luminance[0];
        this.lumaGreen = (float) luminance[1];
        this.lumaBlue = (float) luminance[2];
    }

    /**
     * Prepares an image for reading as linear light.
     *
     * @param image the decoded image
     * @param referenceWhite the luminance of linear 1, in candelas per square meter
     * @return the source
     * @throws com.github.auties00.smalt.MediaException.Unsupported if the color profile is neither RGB nor gray,
     * or the picture holds ink separations
     * @throws com.github.auties00.smalt.MediaException.Malformed if the color profile is damaged
     */
    static LinearSource of(DecodedImage image, double referenceWhite) {
        var picture = image.picture();
        var color = cicp(picture);
        if (image.iccProfile().isEmpty() && color != null && Transfers.isAnalytic(color.transfer())) {
            return new LinearSource(picture, Primaries.of(color.primaries()), color.transfer(), referenceWhite);
        }
        var profile = image.colorProfile().orElse(null);
        return of(picture, profile, referenceWhite);
    }

    /**
     * Prepares a picture in a color profile for reading as linear light.
     *
     * @param picture the pixels
     * @param profile the color profile, or {@code null} for sRGB
     * @param referenceWhite the luminance of linear 1, in candelas per square meter
     * @return the source
     */
    static LinearSource of(Picture picture, ColorProfile profile, double referenceWhite) {
        if (profile == null || profile.isSrgb()) {
            return new LinearSource(picture, Primaries.of(ColorPrimaries.BT709), TransferCharacteristics.SRGB,
                    referenceWhite);
        }
        var info = profile.colorInfo().orElse(null);
        if (info != null && Transfers.isAnalytic(info.transfer())) {
            return new LinearSource(picture, Primaries.of(info.primaries()), info.transfer(), referenceWhite);
        }
        var primaries = profile.colorSpace() == ProfileColorSpace.GRAY
                ? Primaries.of(ColorPrimaries.BT709) : Primaries.of(profile);
        var transform = ColorTransform.create(profile, primaries.profile(2.2),
                ColorTransformOptions.relativeColorimetric());
        var wide = ImageOps.toRgba16(picture);
        var converted = ImageOps.convertColor(wide, transform);
        return new LinearSource(converted, primaries, TransferCharacteristics.GAMMA22, referenceWhite);
    }

    /**
     * {@return the CICP description of a Y'CbCr picture, or {@code null} for other pictures}
     */
    private static ColorInfo cicp(Picture picture) {
        return switch (picture) {
            case YuvPicture yuv -> yuv.color();
            case Yuv16Picture yuv -> yuv.color();
            default -> null;
        };
    }

    /**
     * Reads one row as relative linear light.
     *
     * @param y the row
     * @param red the red values, at least {@link #width} long
     * @param green the green values
     * @param blue the blue values
     */
    void row(int y, float[] red, float[] green, float[] blue) {
        var at = y * stride;
        if (bytes != null) {
            for (var x = 0; x < width; x++, at += 4) {
                red[x] = table[bytes[at] & 0xFF];
                green[x] = table[bytes[at + 1] & 0xFF];
                blue[x] = table[bytes[at + 2] & 0xFF];
            }
        } else {
            for (var x = 0; x < width; x++, at += 4) {
                red[x] = table[shorts[at] & 0xFFFF];
                green[x] = table[shorts[at + 1] & 0xFFFF];
                blue[x] = table[shorts[at + 2] & 0xFFFF];
            }
        }
        if (hlg) {
            for (var x = 0; x < width; x++) {
                var luminance = lumaRed * red[x] + lumaGreen * green[x] + lumaBlue * blue[x];
                var gain = luminance > 0 ? Transfers.hlgGamma(Math.min(luminance, 1)) / luminance * hlgScale : 0;
                red[x] *= gain;
                green[x] *= gain;
                blue[x] *= gain;
            }
        }
    }

    /**
     * Reads the alpha of one row at 16 bits.
     *
     * @param y the row
     * @param alpha the alpha values, at least {@link #width} long
     */
    void alpha(int y, short[] alpha) {
        var at = y * stride + 3;
        if (bytes != null) {
            for (var x = 0; x < width; x++, at += 4) {
                alpha[x] = (short) ((bytes[at] & 0xFF) * 257);
            }
        } else {
            for (var x = 0; x < width; x++, at += 4) {
                alpha[x] = shorts[at];
            }
        }
    }
}
