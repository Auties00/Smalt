package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.image.ops.ChromaUpsampling;
import com.github.auties00.smalt.image.ops.ImageOps;

/**
 * Converts the pictures of a video stream to the chroma format, bit depth and color the encoder
 * receives.
 *
 * <p>When the primaries or the transfer characteristics of the target differ from those of the
 * picture, the picture goes through a {@link ColorTransform} between the profiles
 * {@link ColorProfile#of(ColorInfo)} gives the two descriptions, with the conversion options: PQ and
 * HLG pictures are tone-mapped to a standard-dynamic-range target, standard-dynamic-range pictures
 * placed at the reference white of a PQ or HLG target, and colors converted between the primaries.
 * The standard-dynamic-range transfers that the profiles map to the same curve (BT.709, BT.601,
 * sRGB, the BT.2020 ones and unspecified) count as equal, and so do unspecified and BT.709
 * primaries; otherwise only the matrix, the range, the chroma format and the depth change.
 *
 * <p>For a conversion between profiles, subsampled chroma is replicated over the pixels it covers and
 * averaged back after the conversion, so that colors the conversion keeps keep their chroma samples
 * rather than being blurred by a filter at every conversion.
 *
 * <p>Pictures without a color description (RGBA) count as sRGB. Y'CbCr pictures of PQ or HLG that
 * carry no mastering display or content light level take those of the track when it declares the
 * same transfer.
 *
 * <p>An instance is not thread-safe.
 */
final class VideoColor {
    /**
     * The color of RGBA pictures, which carry none.
     */
    private static final ColorInfo SRGB = new ColorInfo(MatrixCoefficients.BT709, ColorRange.FULL,
            ColorPrimaries.BT709, TransferCharacteristics.SRGB);

    /**
     * The target color, or {@code null} to keep the picture's.
     */
    private final ColorInfo target;

    /**
     * The target chroma format, or {@code null} to keep the picture's.
     */
    private final ChromaFormat format;

    /**
     * The target bit depth, or 0 for 10 bits with a PQ or HLG output and 8 bits otherwise.
     */
    private final int bitDepth;

    /**
     * The intent, tone mapping and reference white of a conversion between profiles.
     */
    private final ColorTransformOptions conversion;

    /**
     * The color the track declares, or {@code null}.
     */
    private final ColorInfo trackColor;

    /**
     * The source color {@link #transform} converts from, or {@code null} before the first transform.
     */
    private ColorInfo transformSource;

    /**
     * The transform last used, or {@code null}.
     */
    private ColorTransform transform;

    /**
     * Constructs a conversion.
     *
     * @param target the output color, or {@code null} to keep the picture's
     * @param format the output chroma format, or {@code null} to keep the picture's (4:2:0 for RGBA)
     * @param bitDepth the output depth from 8 to 16, or 0 for 10 bits with a PQ or HLG output and 8 bits
     * otherwise
     * @param conversion the intent, tone mapping and reference white of a conversion between profiles
     * @param trackColor the color the track declares, or {@code null}
     */
    VideoColor(ColorInfo target, ChromaFormat format, int bitDepth, ColorTransformOptions conversion,
               ColorInfo trackColor) {
        this.target = target;
        this.format = format;
        this.bitDepth = bitDepth;
        this.conversion = conversion;
        this.trackColor = trackColor;
    }

    /**
     * {@return whether the conversion leaves RGBA pictures to the encoder: no target color, chroma format
     * or depth is set}
     */
    boolean keepsRgba() {
        return target == null && format == null && bitDepth == 0;
    }

    /**
     * {@return the color pictures of a source color are converted to}
     *
     * @param source the color of the source pictures
     */
    ColorInfo outputColor(ColorInfo source) {
        return target != null ? target : source;
    }

    /**
     * {@return the depth pictures are converted to, for an output color}
     *
     * @param output the output color
     */
    int outputDepth(ColorInfo output) {
        if (bitDepth != 0) {
            return bitDepth;
        }
        return output.transfer().isHdr() ? 10 : 8;
    }

    /**
     * Returns the color of a picture, with the high-dynamic-range metadata of the track when the
     * picture lacks it.
     *
     * @param picture the decoded picture
     * @return the color; sRGB for an RGBA or channel picture
     */
    ColorInfo sourceColor(Picture picture) {
        var color = switch (picture) {
            case YuvPicture yuv -> yuv.color();
            case Yuv16Picture yuv -> yuv.color();
            case RgbaPicture _, Rgba16Picture _, ChannelPicture _ -> SRGB;
        };
        if (trackColor != null && color.transfer().isHdr() && trackColor.transfer() == color.transfer()) {
            if (color.masteringDisplay().isEmpty() && trackColor.masteringDisplay().isPresent()) {
                color = color.withMasteringDisplay(trackColor.masteringDisplay().get());
            }
            if (color.contentLightLevel().isEmpty() && trackColor.contentLightLevel().isPresent()) {
                color = color.withContentLightLevel(trackColor.contentLightLevel().get());
            }
        }
        return color;
    }

    /**
     * Converts one picture.
     *
     * @param picture the decoded picture
     * @return the converted picture, which is {@code picture} itself when nothing changes
     */
    Picture apply(Picture picture) {
        var rgba = picture instanceof RgbaPicture || picture instanceof Rgba16Picture || picture instanceof ChannelPicture;
        if (rgba && keepsRgba()) {
            return picture;
        }
        var source = sourceColor(picture);
        var output = outputColor(source);
        var chroma = format != null ? format : switch (picture) {
            case YuvPicture yuv -> yuv.chromaFormat();
            case Yuv16Picture yuv -> yuv.chromaFormat();
            default -> ChromaFormat.YUV420;
        };
        var depth = outputDepth(output);
        var converted = picture;
        if (!sameColorimetry(source, output)) {
            converted = ImageOps.convertColor(ImageOps.toRgba16(picture, ChromaUpsampling.NEAREST),
                    transform(source, output));
        } else if (picture instanceof YuvPicture yuv && depth == 8 && yuv.chromaFormat() == chroma
                && yuv.color().equals(output)) {
            return picture;
        } else if (picture instanceof Yuv16Picture yuv && depth == yuv.bitDepth() && yuv.chromaFormat() == chroma
                && yuv.color().equals(output)) {
            return picture;
        }
        return depth == 8 ? ImageOps.toYuv(converted, chroma, output) : ImageOps.toYuv16(converted, chroma, output, depth);
    }

    /**
     * Returns the transform from one color to another, reusing the last one for the same pair.
     *
     * @param source the source color
     * @param output the target color
     * @return the transform
     */
    private ColorTransform transform(ColorInfo source, ColorInfo output) {
        if (transform == null || !source.equals(transformSource)) {
            transform = ColorTransform.create(ColorProfile.of(source), ColorProfile.of(output), conversion);
            transformSource = source;
        }
        return transform;
    }

    /**
     * Returns whether two colors share primaries and transfer characteristics, so that converting
     * between them only changes the matrix and range.
     *
     * @param source the source color
     * @param output the target color
     * @return {@code true} when no conversion between profiles is needed
     */
    static boolean sameColorimetry(ColorInfo source, ColorInfo output) {
        return primaries(source.primaries()) == primaries(output.primaries())
                && transfer(source.transfer()) == transfer(output.transfer());
    }

    /**
     * {@return the primaries as their chromaticities identify them: unspecified as BT.709, SMPTE 240M as
     * SMPTE 170M}
     *
     * @param primaries the primaries to normalize
     */
    private static ColorPrimaries primaries(ColorPrimaries primaries) {
        return switch (primaries) {
            case UNSPECIFIED -> ColorPrimaries.BT709;
            case SMPTE240M -> ColorPrimaries.SMPTE170M;
            default -> primaries;
        };
    }

    /**
     * {@return the transfer as the profiles map it: the standard-dynamic-range curves that
     * {@link ColorProfile#of(ColorInfo)} displays with the sRGB curve as sRGB}
     *
     * @param transfer the transfer characteristics
     */
    private static TransferCharacteristics transfer(TransferCharacteristics transfer) {
        return switch (transfer) {
            case BT709, UNSPECIFIED, SMPTE170M, BT2020_10, BT2020_12, IEC61966_2_4, BT1361 ->
                    TransferCharacteristics.SRGB;
            default -> transfer;
        };
    }
}
