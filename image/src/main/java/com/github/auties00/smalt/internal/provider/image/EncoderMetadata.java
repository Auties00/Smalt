package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.tiff.TiffEncoderOptions;
import com.github.auties00.smalt.internal.codec.EncodeMetadata;
import com.github.auties00.smalt.metadata.XmpPacket;

/**
 * Maps the metadata of {@link ImageEncoderOptions} onto the options of the encoders that take parsed EXIF
 * and XMP metadata (TIFF and JPEG 2000).
 *
 * <p>This class is thread-safe.
 */
final class EncoderMetadata {
    private EncoderMetadata() {
        throw new AssertionError();
    }

    /**
     * {@return an EXIF block parsed, or {@code null} when it does not parse}
     *
     * @param tiff the block
     * @param thumbnail whether the thumbnail is kept
     */
    static ExifData exif(byte[] tiff, boolean thumbnail) {
        try {
            var exif = ExifData.parse(tiff);
            return thumbnail ? exif : exif.withoutThumbnail();
        } catch (MediaException.Malformed | MediaException.Unsupported exception) {
            return null;
        }
    }

    /**
     * {@return an XMP packet parsed, or {@code null} when it does not parse}
     *
     * @param xmp the serialized packet
     */
    static XmpPacket xmp(byte[] xmp) {
        try {
            return XmpPacket.parse(xmp);
        } catch (MediaException.Malformed | MediaException.Unsupported exception) {
            return null;
        }
    }

    /**
     * Returns the options of a TIFF page that record the metadata of the encoder options: the color profile
     * when it describes the coded samples, the EXIF directories without a thumbnail (which a TIFF file would
     * take for a second page), the XMP packet and the orientation, as the {@code Orientation} tag.
     *
     * @param base the TIFF options the metadata is added to
     * @param options the encoder options
     * @param prepared the picture and the profile it records, from
     * {@link EncodeMetadata#color(Picture, ImageEncoderOptions, java.util.function.BiPredicate)}
     * @return the page options
     */
    static TiffEncoderOptions tiff(TiffEncoderOptions base, ImageEncoderOptions options,
                                   EncodeMetadata.Prepared prepared) {
        var builder = base.toBuilder();
        if (prepared.profile() != null) {
            builder.colorProfile(prepared.profile());
        }
        var orientation = EncodeMetadata.displayOrientation(options);
        var exif = options.exif().map(block -> exif(block, false)).orElse(null);
        if (exif != null) {
            builder.exif(exif.withOrientation(orientation));
        }
        if (orientation != Orientation.NORMAL || exif != null || options.orientation().isPresent()) {
            builder.orientation(orientation);
        }
        EncodeMetadata.xmp(options).map(EncoderMetadata::xmp).ifPresent(builder::xmp);
        return builder.build();
    }

    /**
     * {@return whether a TIFF or JPEG 2000 file records a profile for a picture: one that describes the color
     * model the picture is coded in}
     *
     * @param profile the profile of the picture
     * @param picture the picture to encode
     */
    static boolean recordsCoded(ColorProfile profile, Picture picture) {
        return EncodeMetadata.describes(profile, EncodeMetadata.codedModel(picture));
    }
}
