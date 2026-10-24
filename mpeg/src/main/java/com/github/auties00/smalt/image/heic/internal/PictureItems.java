package com.github.auties00.smalt.image.heic.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.internal.heif.HeifComponentCodec;
import com.github.auties00.smalt.internal.heif.HeifComponentPictures;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifItem;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.internal.heif.HeifUncompressed;
import com.github.auties00.smalt.io.MediaSource;

/**
 * Decodes the image items that are not coded with a video codec: JPEG items ({@code jpeg},
 * ISO/IEC 23008-12 Annex H), JPEG 2000 items ({@code j2k1}, ISO/IEC 15444-16) and uncompressed
 * items ({@code unci}, ISO/IEC 23001-17).
 *
 * <p>JPEG items decode with the JPEG decoder the {@link Images} registry finds and JPEG 2000 items
 * with the {@link HeifComponentCodec} of their type, both provided by the {@code smalt-image}
 * artifact; without it such items raise {@link MediaException.Unsupported}. The data of a JPEG
 * item follows the bytes of its {@code jpgC} property, if any, to form the JPEG stream. Samples
 * keep their depth; Y'CbCr and monochrome samples of JPEG 2000 and uncompressed items take the
 * MIAF default color, which the item's color properties then override, and RGB samples are
 * coded with the identity matrix.
 */
final class PictureItems {
    /**
     * The format name, for messages.
     */
    private static final String FORMAT = "heic";

    /**
     * Prevents instantiation.
     */
    private PictureItems() {
        throw new AssertionError();
    }

    /**
     * Decodes an item.
     *
     * @param source the file
     * @param file the parsed file
     * @param item the item, of type {@code jpeg}, {@code j2k1} or {@code unci}
     * @param limits the bounds of the item data and of the picture
     * @return the decoded picture
     * @throws MediaException.Malformed if the item data is corrupt
     * @throws MediaException.Unsupported if the item uses a feature no decoder at hand implements
     * @throws MediaException.LimitExceeded if the picture exceeds the limits
     */
    static PictureTile decode(MediaSource source, HeifFile file, HeifItem item, MediaLimits limits) {
        if (!item.isLocated()) {
            throw new MediaException.Malformed(FORMAT + ": item " + item.id() + " has no data");
        }
        var data = file.readItem(source, item, limits);
        return switch (item.type()) {
            case "jpeg" -> jpeg(data, item, limits);
            case "j2k1" -> {
                var codec = HeifComponentCodec.find("j2k1");
                if (codec == null) {
                    throw new MediaException.Unsupported(FORMAT, "JPEG 2000 items need the smalt-image artifact");
                }
                var components = codec.decode(FORMAT, data, item, limits);
                var picture = HeifComponentPictures.toPicture(FORMAT, components, Colors.MIAF_DEFAULT, limits);
                yield PictureTile.of(picture, HeifComponentPictures.premultiplied(components));
            }
            case "unci" -> {
                var extent = item.property(HeifProperty.SpatialExtent.class);
                if (extent == null || extent.width() <= 0 || extent.height() <= 0 || extent.width() > Integer.MAX_VALUE
                        || extent.height() > Integer.MAX_VALUE) {
                    throw new MediaException.Malformed(FORMAT + ": uncompressed item " + item.id()
                            + " has no valid ispe property");
                }
                var components = HeifUncompressed.decode(FORMAT, data, item, (int) extent.width(),
                        (int) extent.height(), limits);
                var color = Colors.MIAF_DEFAULT;
                var location = item.property(HeifProperty.ChromaLocation.class);
                if (location != null && location.location() <= 5) {
                    color = color.withChromaSiting(ChromaSiting.of(location.location()));
                }
                yield PictureTile.of(HeifComponentPictures.toPicture(FORMAT, components, color, limits), false);
            }
            default -> throw new MediaException.Unsupported(FORMAT, "item " + item.id() + " is coded as '"
                    + item.type() + "'");
        };
    }

    /**
     * Decodes a JPEG item.
     *
     * @param data the item data
     * @param item the {@code jpeg} item, with its optional {@code jpgC} property
     * @param limits the bounds of the picture
     * @return the decoded picture
     */
    private static PictureTile jpeg(byte[] data, HeifItem item, MediaLimits limits) {
        var configuration = item.property(HeifProperty.JpegConfiguration.class);
        if (configuration != null && configuration.prefix().length > 0) {
            var prefix = configuration.prefix();
            var joined = new byte[prefix.length + data.length];
            System.arraycopy(prefix, 0, joined, 0, prefix.length);
            System.arraycopy(data, 0, joined, prefix.length, data.length);
            data = joined;
        }
        if (ImageFormat.detect(data, data.length).orElse(null) != ImageFormat.JPEG) {
            throw new MediaException.Malformed(FORMAT + ": JPEG item " + item.id() + " does not hold a JPEG stream");
        }
        if (!Images.isDecodable(ImageFormat.JPEG)) {
            throw new MediaException.Unsupported(FORMAT, "JPEG items need the smalt-image artifact");
        }
        var image = Images.decode(MediaSource.of(data), ImageDecoderOptions.builder().limits(limits)
                .highBitDepth(true).build());
        return PictureTile.of(image.picture(), false);
    }
}
