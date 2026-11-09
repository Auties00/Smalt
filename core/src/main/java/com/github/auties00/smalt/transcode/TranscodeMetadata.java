package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.internal.codec.XmpLocations;
import com.github.auties00.smalt.internal.tiff.ExifBlocks;
import com.github.auties00.smalt.internal.tiff.TiffExif;
import com.github.auties00.smalt.metadata.XmpPacket;

/**
 * Holds the color profile, EXIF block and XMP packet an image transcode writes, derived from the decoded
 * input by the {@link MetadataMode} and {@link ColorMode} of the job.
 *
 * <p>Instances are immutable and thread-safe.
 */
final class TranscodeMetadata {
    /**
     * The EXIF properties of the XMP packet that hold the picture size.
     */
    private static final String PIXEL_X = "PixelXDimension";

    private static final String PIXEL_Y = "PixelYDimension";

    /**
     * The largest EXIF block a JPEG file holds, the payload of one {@code APP1} segment after its
     * {@code "Exif\0\0"} identifier.
     */
    private static final int MAX_JPEG_EXIF = 65533 - 6;

    /**
     * The metadata of a job that writes none.
     */
    private static final TranscodeMetadata NONE = new TranscodeMetadata(null, null, null, null);

    /**
     * The profile of the pixels, or {@code null} for sRGB.
     */
    private final ColorProfile profile;

    /**
     * The EXIF block, without thumbnail (and GPS directory when asked), or {@code null}.
     */
    private final byte[] exif;

    /**
     * The XMP packet as stored, written when {@link #packet} is {@code null}, or {@code null}.
     */
    private final byte[] xmp;

    /**
     * The parsed XMP packet when it holds the picture size, which follows the output, or {@code null}.
     */
    private final XmpPacket packet;

    private TranscodeMetadata(ColorProfile profile, byte[] exif, byte[] xmp, XmpPacket packet) {
        this.profile = profile;
        this.exif = exif;
        this.xmp = xmp;
        this.packet = packet;
    }

    /**
     * Derives the metadata of a transcode from its decoded input.
     *
     * <p>An EXIF block that does not start with a TIFF byte-order mark is not EXIF and is left out.
     *
     * @param decoded the decoded input, after any conversion to sRGB
     * @param metadata what happens to the EXIF block and XMP packet
     * @param color how the color space is treated
     * @return the metadata
     */
    static TranscodeMetadata of(DecodedImage decoded, MetadataMode metadata, ColorMode color) {
        ColorProfile profile = null;
        if (color == ColorMode.KEEP_PROFILE) {
            var declared = decoded.colorProfile().orElse(null);
            profile = declared == null || declared.isSrgb() && decoded.iccProfile().isEmpty() ? null : declared;
        }
        if (metadata == MetadataMode.STRIP) {
            return profile == null ? NONE : new TranscodeMetadata(profile, null, null, null);
        }
        var stripLocation = metadata == MetadataMode.STRIP_LOCATION;
        var exif = decoded.exif().map(TiffExif::stripExifPrefix).filter(ExifBlocks::isTiff)
                .map(ExifBlocks::withoutThumbnail)
                .map(block -> stripLocation ? ExifBlocks.withoutGps(block) : block).orElse(null);
        byte[] xmp = decoded.xmp().orElse(null);
        XmpPacket packet = null;
        if (xmp != null) {
            try {
                var stored = XmpPacket.parse(xmp);
                var parsed = stripLocation ? stored.withoutIf(XmpLocations::isLocation) : stored;
                var sized = parsed.get(XmpPacket.EXIF, PIXEL_X).isPresent()
                        || parsed.get(XmpPacket.EXIF, PIXEL_Y).isPresent();
                if (sized || parsed != stored) {
                    packet = parsed;
                }
            } catch (MediaException exception) {
                // a packet that does not parse is kept as stored, unless its location must go
                if (stripLocation) {
                    xmp = null;
                }
            }
        }
        return new TranscodeMetadata(profile, exif, packet == null ? xmp : null, packet);
    }

    /**
     * {@return the metadata of a job that writes the pixels in sRGB with no EXIF block or XMP packet}
     */
    static TranscodeMetadata none() {
        return NONE;
    }

    /**
     * {@return the profile the output records, or {@code null} for sRGB}
     */
    ColorProfile profile() {
        return profile;
    }

    /**
     * Returns encoder settings that record this metadata for an output of a format and size.
     *
     * <p>An EXIF block larger than a JPEG {@code APP1} segment holds is left out of a JPEG output.
     *
     * @param options the encoder settings of the job
     * @param format the output format
     * @param width the output width
     * @param height the output height
     * @return the settings, with the profile, EXIF block and XMP packet replaced and the orientation set to
     * {@link Orientation#NORMAL}, since the transcode turns the pixels upright
     */
    ImageEncoderOptions apply(ImageEncoderOptions options, ImageFormat format, int width, int height) {
        var builder = options.toBuilder().noColorProfile().noExif().noXmp().orientation(Orientation.NORMAL);
        if (profile != null) {
            builder.colorProfile(profile);
        }
        if (exif != null && (format != ImageFormat.JPEG || exif.length <= MAX_JPEG_EXIF)) {
            builder.exif(ExifBlocks.withPixelDimensions(exif, width, height));
        }
        if (packet != null) {
            var sized = packet;
            if (sized.get(XmpPacket.EXIF, PIXEL_X).isPresent()) {
                sized = sized.with(XmpPacket.EXIF, PIXEL_X, Integer.toString(width));
            }
            if (sized.get(XmpPacket.EXIF, PIXEL_Y).isPresent()) {
                sized = sized.with(XmpPacket.EXIF, PIXEL_Y, Integer.toString(height));
            }
            builder.xmp(sized);
        } else if (xmp != null) {
            builder.xmp(xmp);
        }
        return builder.build();
    }
}
