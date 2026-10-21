/* Ports jxl-oxide 0.12.6 (crates/jxl-oxide: the output conversion). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.Chromaticity;
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
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.image.jxl.internal.color.ColorConverter;
import com.github.auties00.smalt.image.jxl.internal.color.IccSynthesizer;
import com.github.auties00.smalt.image.jxl.internal.header.ColorEncoding;
import com.github.auties00.smalt.image.jxl.internal.header.ExtraChannelInfo;
import com.github.auties00.smalt.image.jxl.internal.header.ImageHeader;
import com.github.auties00.smalt.image.jxl.internal.render.FrameImage;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Optional;

/**
 * Converts decoded canvases to Smalt pictures and describes their color space.
 *
 * <p>Floating-point samples are clamped to {@code 0..1} and rounded to 8 or 16 bits; a grayscale
 * image is replicated into the three color channels; premultiplied alpha is divided out, since
 * Smalt pictures hold straight alpha; a CMYK image is converted to sRGB through its embedded
 * profile; and an XYB image whose original color space is an ICC profile, decoded to linear sRGB,
 * is converted to that profile when Smalt can apply it, else to sRGB.
 */
public final class JxlOutput {
    /**
     * Prevents instantiation.
     */
    private JxlOutput() {
        throw new AssertionError();
    }

    /**
     * Holds a picture with the color space of its pixels.
     *
     * @param picture the pixels
     * @param icc the ICC profile describing the pixels
     * @param profile the parsed profile, when Smalt can parse it
     */
    public record Output(Picture picture, Optional<byte[]> icc, Optional<ColorProfile> profile) {
    }

    /**
     * Returns the ICC profile describing the color planes of a canvas.
     *
     * @param header the image header
     * @param icc the embedded ICC profile, or {@code null}
     * @return the profile bytes
     */
    public static byte[] canvasIcc(ImageHeader header, byte[] icc) {
        if (!header.xybEncoded()) {
            return icc != null ? icc : IccSynthesizer.synthesize(header.colorEncoding());
        }
        return IccSynthesizer.synthesize(ColorConverter.xybTarget(header));
    }

    /**
     * Converts a canvas to a picture.
     *
     * @param canvas the canvas, in the output color space
     * @param header the image header
     * @param icc the embedded ICC profile, or {@code null}
     * @param highBitDepth whether to return 16-bit samples for images deeper than 8 bits
     * @return the picture and its color space
     */
    public static Output toPicture(FrameImage canvas, ImageHeader header, byte[] icc, boolean highBitDepth) {
        var width = canvas.width;
        var height = canvas.height;
        var length = width * height;
        var alphaIndex = header.alphaChannel();
        float[] alpha = alphaIndex >= 0 && alphaIndex < canvas.extraChannels() ? canvas.extra(alphaIndex) : null;
        var premultiplied = alphaIndex >= 0 && header.extraChannels().get(alphaIndex).alphaAssociated();
        var deep = highBitDepth && (header.bitDepth().bitsPerSample() > 8 || header.bitDepth().floatSample());
        float[][] rgb;
        byte[] outIcc;
        var cmyk = isCmyk(header, icc, canvas);
        if (cmyk) {
            rgb = cmykToSrgb(canvas, header, icc);
            outIcc = ColorProfile.srgb().toByteArray();
            premultiplied = false;
        } else {
            var color = canvas.planes;
            if (canvas.colorChannels == 1) {
                rgb = new float[][]{color[0], color[0], color[0]};
            } else {
                rgb = new float[][]{color[0], color[1], color[2]};
            }
            outIcc = canvasIcc(header, icc);
        }
        if (premultiplied && alpha != null) {
            var straight = new float[3][length];
            for (var i = 0; i < length; i++) {
                var a = alpha[i];
                var inv = a > 1e-7f ? 1.0f / a : 0.0f;
                for (var c = 0; c < 3; c++) {
                    straight[c][i] = rgb[c][i] * inv;
                }
            }
            rgb = straight;
        }
        var xybIcc = header.xybEncoded() && header.colorEncoding().wantIcc() && icc != null;
        Picture picture;
        if (xybIcc) {
            var linear = toRgba16(rgb, alpha, width, height);
            var converted = convertLinear(linear, header, icc);
            if (converted != null) {
                picture = deep ? converted : ImageOps.toEightBit(converted);
                outIcc = icc;
            } else {
                var encoded = new float[3][length];
                var srgb = ColorEncoding.SRGB;
                for (var c = 0; c < 3; c++) {
                    for (var i = 0; i < length; i++) {
                        encoded[c][i] = ColorConverter.fromLinear(rgb[c][i], srgb, 255.0f);
                    }
                }
                picture = deep ? toRgba16(encoded, alpha, width, height) : toRgba8(encoded, alpha, width, height);
                outIcc = ColorProfile.srgb().toByteArray();
            }
        } else {
            picture = deep ? toRgba16(rgb, alpha, width, height) : toRgba8(rgb, alpha, width, height);
        }
        return new Output(picture, Optional.ofNullable(outIcc), profileOf(header, outIcc, cmyk || (xybIcc && outIcc != icc)));
    }

    /**
     * Converts a canvas to a channel picture when a packed RGBA picture cannot hold it: a CMYK image, an image
     * of floating-point samples, or one with extra channels other than a single alpha channel.
     *
     * <p>The color channels are gray, RGB or, for an image with a black extra channel, CMYK, whose ink amounts
     * are {@code 1 - v} of the stored values; the other extra channels follow in file order, the alpha channel
     * straight or premultiplied as stored. Floating-point images keep every value unclamped in a
     * {@link ChannelFloatPicture}; integer images are quantized as {@link #toPicture} quantizes them, to 16
     * bits when {@code highBitDepth} is set and the image is deeper than 8 bits, else to 8 bits.
     *
     * @param canvas the canvas, in the color space of {@link #canvasIcc}
     * @param header the image header
     * @param icc the embedded ICC profile, or {@code null}
     * @param highBitDepth whether integer samples deeper than 8 bits keep 16 bits
     * @return the picture and its color space, or {@code null} when the image needs no channel picture
     */
    public static Output toChannels(FrameImage canvas, ImageHeader header, byte[] icc, boolean highBitDepth) {
        var infos = header.extraChannels();
        var black = -1;
        for (var i = 0; i < infos.size() && i < canvas.extraChannels(); i++) {
            if (infos.get(i).isBlack()) {
                black = i;
                break;
            }
        }
        var cmyk = black >= 0 && canvas.colorChannels == 3 && !header.xybEncoded();
        var alpha = header.alphaChannel();
        var extraCount = Math.min(infos.size(), canvas.extraChannels());
        var others = extraCount - (alpha >= 0 && alpha < extraCount ? 1 : 0) - (cmyk ? 1 : 0);
        var floating = header.bitDepth().floatSample();
        if (!cmyk && !floating && others == 0) {
            return null;
        }
        var channels = new ArrayList<Channel>();
        var sources = new ArrayList<float[]>();
        if (cmyk) {
            channels.addAll(PixelLayout.CMYK.channels());
            sources.add(canvas.planes[0]);
            sources.add(canvas.planes[1]);
            sources.add(canvas.planes[2]);
            sources.add(canvas.extra(black));
        } else if (canvas.colorChannels == 1) {
            channels.addAll(PixelLayout.GRAY.channels());
            sources.add(canvas.planes[0]);
        } else {
            channels.addAll(PixelLayout.RGB.channels());
            sources.add(canvas.planes[0]);
            sources.add(canvas.planes[1]);
            sources.add(canvas.planes[2]);
        }
        var colors = channels.size();
        for (var i = 0; i < extraCount; i++) {
            if (cmyk && i == black) {
                continue;
            }
            var info = infos.get(i);
            channels.add(new Channel(extraType(info, i == alpha), info.name()));
            sources.add(canvas.extra(i));
        }
        var layout = new PixelLayout(channels);
        var width = canvas.width;
        var height = canvas.height;
        var count = layout.count();
        var length = width * height;
        var deep = highBitDepth && header.bitDepth().bitsPerSample() > 8;
        var planes = sources.toArray(float[][]::new);
        ChannelPicture picture = floating ? ChannelFloatPicture.allocate(width, height, layout)
                : deep ? Channel16Picture.allocate(width, height, layout)
                : Channel8Picture.allocate(width, height, layout);
        interleave(planes, count, colors, cmyk, length, height, picture);
        var outIcc = cmyk ? icc : canvasIcc(header, icc);
        return new Output(picture, Optional.ofNullable(outIcc), profileOf(header, outIcc, false));
    }

    /**
     * Interleaves planes into the samples of a channel picture, inverting the leading CMYK planes.
     *
     * @param planes the planes, in channel order
     * @param count the number of channels
     * @param colors the number of leading color channels
     * @param cmyk whether the color planes hold CMYK as {@code 1 - ink}
     * @param length the samples per plane
     * @param height the rows per plane
     * @param picture the destination, with a tight stride: floating-point samples are stored as they are, integer
     * ones quantized as {@link #toPicture} quantizes them
     */
    private static void interleave(float[][] planes, int count, int colors, boolean cmyk, int length, int height,
                                   ChannelPicture picture) {
        var width = length / height;
        var bands = ParallelBands.bands(height, (long) length * count);
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, height) * width;
            var to = ParallelBands.start(band + 1, bands, height) * width;
            for (var c = 0; c < count; c++) {
                var plane = planes[c];
                var invert = cmyk && c < colors;
                switch (picture) {
                    case ChannelFloatPicture floating -> {
                        var pixels = floating.pixels();
                        for (var i = from; i < to; i++) {
                            pixels[i * count + c] = invert ? 1.0f - plane[i] : plane[i];
                        }
                    }
                    case Channel16Picture sixteen -> {
                        var pixels = sixteen.pixels();
                        for (var i = from; i < to; i++) {
                            pixels[i * count + c] = toUnsigned16(invert ? 1.0f - plane[i] : plane[i]);
                        }
                    }
                    case Channel8Picture eight -> {
                        var pixels = eight.pixels();
                        for (var i = from; i < to; i++) {
                            pixels[i * count + c] = (byte) toUnsigned8(invert ? 1.0f - plane[i] : plane[i]);
                        }
                    }
                }
            }
        });
    }

    /**
     * Returns the channel type of a JPEG XL extra channel.
     *
     * @param info the extra channel
     * @param alpha whether it is the image's alpha channel
     * @return the type; alpha channels other than the image's are unspecified
     */
    private static ChannelType extraType(ExtraChannelInfo info, boolean alpha) {
        return switch (info.type()) {
            case ExtraChannelInfo.TYPE_ALPHA -> alpha ? (info.alphaAssociated() ? ChannelType.PREMULTIPLIED_ALPHA
                    : ChannelType.ALPHA) : ChannelType.UNSPECIFIED;
            case ExtraChannelInfo.TYPE_DEPTH -> ChannelType.DEPTH;
            case ExtraChannelInfo.TYPE_SPOT_COLOR -> ChannelType.SPOT;
            case ExtraChannelInfo.TYPE_SELECTION_MASK -> ChannelType.SELECTION_MASK;
            case ExtraChannelInfo.TYPE_THERMAL -> ChannelType.THERMAL;
            default -> ChannelType.UNSPECIFIED;
        };
    }

    /**
     * Returns the parsed profile of the output.
     *
     * @param header the image header
     * @param icc the output profile bytes
     * @param srgb whether the output was converted to sRGB
     * @return the profile, when Smalt can parse it
     */
    private static Optional<ColorProfile> profileOf(ImageHeader header, byte[] icc, boolean srgb) {
        if (srgb) {
            return Optional.of(ColorProfile.srgb());
        }
        var encoding = header.colorEncoding().wantIcc() ? null : header.colorEncoding();
        if (encoding != null && encoding.isHdr()) {
            var hdr = hdrProfile(encoding);
            if (hdr != null) {
                return Optional.of(hdr);
            }
        }
        if (icc == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ColorProfile.parse(icc));
        } catch (MediaException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * Returns the Smalt HDR profile of a PQ or HLG encoding with standard primaries.
     *
     * @param encoding the PQ or HLG encoding
     * @return the profile, or {@code null} for custom primaries or white points
     */
    private static ColorProfile hdrProfile(ColorEncoding encoding) {
        if (encoding.whitePoint() != ColorEncoding.WHITE_D65) {
            return null;
        }
        var primaries = switch (encoding.primaries()) {
            case ColorEncoding.PRIMARIES_SRGB -> ColorPrimaries.BT709;
            case ColorEncoding.PRIMARIES_BT2100 -> ColorPrimaries.BT2020;
            case ColorEncoding.PRIMARIES_P3 -> ColorPrimaries.SMPTE432;
            default -> null;
        };
        if (primaries == null) {
            return null;
        }
        var transfer = encoding.transfer() == ColorEncoding.TF_PQ
                ? TransferCharacteristics.PQ
                : TransferCharacteristics.HLG;
        var info = new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL, primaries, transfer);
        return ColorProfile.of(info);
    }

    /**
     * Returns whether a canvas holds CMYK samples to convert through the embedded profile.
     *
     * @param header the image header
     * @param icc the embedded profile, or {@code null}
     * @param canvas the decoded planes
     * @return whether the profile is CMYK and the image has a black channel
     */
    private static boolean isCmyk(ImageHeader header, byte[] icc, FrameImage canvas) {
        if (icc == null || icc.length < 20 || header.xybEncoded() || canvas.colorChannels != 3) {
            return false;
        }
        if (icc[16] != 'C' || icc[17] != 'M' || icc[18] != 'Y' || icc[19] != 'K') {
            return false;
        }
        for (var info : header.extraChannels()) {
            if (info.isBlack()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts CMYK planes to sRGB through the embedded profile.
     *
     * @param canvas the canvas: CMY color planes and a black extra channel, 1.0 meaning no ink
     * @param header the image header
     * @param icc the CMYK profile
     * @return the sRGB planes
     * @throws MediaException.Unsupported if the profile cannot be applied
     */
    private static float[][] cmykToSrgb(FrameImage canvas, ImageHeader header, byte[] icc) {
        var black = -1;
        for (var i = 0; i < header.extraChannels().size(); i++) {
            if (header.extraChannels().get(i).isBlack()) {
                black = i;
                break;
            }
        }
        var length = canvas.width * canvas.height;
        var ink = new short[length * 4];
        var k = canvas.extra(black);
        for (var i = 0; i < length; i++) {
            ink[4 * i] = toUnsigned16(1.0f - canvas.planes[0][i]);
            ink[4 * i + 1] = toUnsigned16(1.0f - canvas.planes[1][i]);
            ink[4 * i + 2] = toUnsigned16(1.0f - canvas.planes[2][i]);
            ink[4 * i + 3] = toUnsigned16(1.0f - k[i]);
        }
        ColorTransform transform;
        try {
            transform = ColorTransform.create(ColorProfile.parse(icc), ColorProfile.srgb());
        } catch (IllegalArgumentException exception) {
            throw new MediaException.Unsupported("jxl", "CMYK profile cannot be applied: " + exception.getMessage());
        }
        var out = new short[length * 3];
        transform.transform(ink, 0, 4, out, 0, 3, length);
        var rgb = new float[3][length];
        for (var i = 0; i < length; i++) {
            for (var c = 0; c < 3; c++) {
                rgb[c][i] = (out[3 * i + c] & 0xFFFF) / 65535.0f;
            }
        }
        return rgb;
    }

    /**
     * Converts a linear sRGB picture to the embedded profile.
     *
     * @param linear the linear picture
     * @param header the image header
     * @param icc the embedded profile
     * @return the converted picture, or {@code null} when Smalt cannot apply the profile
     */
    private static Rgba16Picture convertLinear(Rgba16Picture linear, ImageHeader header, byte[] icc) {
        try {
            var target = ColorProfile.parse(icc);
            if (header.isGray()) {
                // TODO: convert grayscale XYB images to their gray ICC profile; they fall back to sRGB.
                return null;
            }
            var source = ColorProfile.rgb(new Chromaticity(0.639998686, 0.330010138),
                    new Chromaticity(0.300003784, 0.600003357), new Chromaticity(0.150002046, 0.059997204),
                    new Chromaticity(0.3127, 0.329), 1.0);
            // linear 1.0 is the intensity target, which a high-dynamic-range target places in candelas per square meter
            var white = Math.min(Math.max(header.toneMapping().intensityTarget(), 1.0), 10000.0);
            var options = ColorTransformOptions.builder().referenceWhite(white).build();
            var converted = ImageOps.convertColor(linear, source, target, options);
            return converted instanceof Rgba16Picture deep ? deep : null;
        } catch (MediaException | IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Quantizes a sample to 16 bits.
     *
     * @param value the nominal value
     * @return the sample as an unsigned 16-bit pattern
     */
    private static short toUnsigned16(float value) {
        var v = value * 65535.0f + 0.5f;
        if (!(v > 0)) {
            return 0;
        }
        return (short) Math.min((int) v, 65535);
    }

    /**
     * Quantizes a sample to 8 bits.
     *
     * @param value the nominal value
     * @return the sample, from 0 to 255
     */
    private static int toUnsigned8(float value) {
        var v = value * 255.0f + 0.5f;
        if (!(v > 0)) {
            return 0;
        }
        return Math.min((int) v, 255);
    }

    /**
     * Packs planes into an 8-bit RGBA picture.
     *
     * @param rgb the color planes
     * @param alpha the alpha plane, or {@code null}
     * @param width the picture width in pixels
     * @param height the picture height in pixels
     * @return the picture
     */
    public static RgbaPicture toRgba8(float[][] rgb, float[] alpha, int width, int height) {
        var length = width * height;
        var pixels = new byte[length * 4];
        var bands = ParallelBands.bands(height, (long) length * 4);
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, height) * width;
            var to = ParallelBands.start(band + 1, bands, height) * width;
            for (var i = from; i < to; i++) {
                pixels[4 * i] = (byte) toUnsigned8(rgb[0][i]);
                pixels[4 * i + 1] = (byte) toUnsigned8(rgb[1][i]);
                pixels[4 * i + 2] = (byte) toUnsigned8(rgb[2][i]);
                pixels[4 * i + 3] = (byte) (alpha == null ? 255 : toUnsigned8(alpha[i]));
            }
        });
        return RgbaPicture.of(width, height, width * 4, pixels, alpha != null);
    }

    /**
     * Packs planes into a 16-bit RGBA picture.
     *
     * @param rgb the color planes
     * @param alpha the alpha plane, or {@code null}
     * @param width the picture width in pixels
     * @param height the picture height in pixels
     * @return the picture
     */
    public static Rgba16Picture toRgba16(float[][] rgb, float[] alpha, int width, int height) {
        var length = width * height;
        var pixels = new short[length * 4];
        var bands = ParallelBands.bands(height, (long) length * 4);
        ParallelBands.run(bands, band -> {
            var from = ParallelBands.start(band, bands, height) * width;
            var to = ParallelBands.start(band + 1, bands, height) * width;
            for (var i = from; i < to; i++) {
                pixels[4 * i] = toUnsigned16(rgb[0][i]);
                pixels[4 * i + 1] = toUnsigned16(rgb[1][i]);
                pixels[4 * i + 2] = toUnsigned16(rgb[2][i]);
                pixels[4 * i + 3] = alpha == null ? (short) 0xFFFF : toUnsigned16(alpha[i]);
            }
        });
        return Rgba16Picture.of(width, height, width * 4, pixels, alpha != null);
    }
}
