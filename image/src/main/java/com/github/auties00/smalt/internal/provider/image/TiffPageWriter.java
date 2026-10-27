package com.github.auties00.smalt.internal.provider.image;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.PageWriter;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.tiff.TiffEncoder;
import com.github.auties00.smalt.image.tiff.TiffEncoderOptions;
import com.github.auties00.smalt.internal.codec.EncodeMetadata;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.util.Objects;

/**
 * Writes the pages of a multi-page TIFF through a {@link TiffEncoder}, one directory per page, with
 * {@link TiffEncoderOptions#defaults()}.
 *
 * <p>A picture page records the color profile, EXIF metadata, XMP packet and orientation of the writer's
 * options, as {@link TiffCodecProvider} records them for a single image.
 *
 * <p>A decoded page keeps its orientation, color profile, pixel density, EXIF metadata and XMP packet; the
 * orientation written is the decoded one, whatever the EXIF metadata says, a density that is only the
 * aspect ratio of square pixels is not written (the page then takes its resolution from the EXIF metadata
 * or records 72 dots per inch), and EXIF or XMP bytes that do not parse are left out.
 */
final class TiffPageWriter implements PageWriter {
    /**
     * The encoder writing the file.
     */
    private final TiffEncoder encoder;

    /**
     * The options of the writer, whose metadata the picture pages record.
     */
    private final ImageEncoderOptions options;

    /**
     * Constructs a writer.
     *
     * @param sink the output, written from its position; not closed
     * @param options the options whose metadata the picture pages record
     */
    TiffPageWriter(MediaSink sink, ImageEncoderOptions options) {
        this.encoder = new TiffEncoder(TiffEncoderOptions.defaults(), sink);
        this.options = options;
    }

    @Override
    public void write(Picture picture) {
        Objects.requireNonNull(picture, "picture");
        var prepared = EncodeMetadata.color(picture, options, EncoderMetadata::recordsCoded);
        encoder.addPage(prepared.picture(), EncoderMetadata.tiff(TiffEncoderOptions.defaults(), options, prepared));
    }

    @Override
    public void write(DecodedImage page) {
        Objects.requireNonNull(page, "page");
        var options = TiffEncoderOptions.defaults().toBuilder();
        // the decoded orientation is authoritative (an HEIF transform overrides the EXIF entry), so
        // an EXIF orientation that disagrees with it is never written
        if (page.orientation() != Orientation.NORMAL || page.exif().isPresent()) {
            options.orientation(page.orientation());
        }
        page.colorProfile().ifPresent(options::colorProfile);
        page.density().filter(density -> density.isPhysical() || density.horizontal() != density.vertical())
                .ifPresent(options::density);
        page.exif().ifPresent(bytes -> {
            try {
                options.exif(ExifData.parse(bytes).withoutThumbnail());
            } catch (MediaException.Malformed | MediaException.Unsupported exception) {
                // EXIF bytes that do not parse are left out
            }
        });
        page.xmp().ifPresent(bytes -> {
            try {
                options.xmp(XmpPacket.parse(bytes));
            } catch (MediaException.Malformed | MediaException.Unsupported exception) {
                // an XMP packet that does not parse is left out
            }
        });
        encoder.addPage(page.picture(), options.build());
    }

    @Override
    public int pageCount() {
        return encoder.pageCount();
    }

    @Override
    public void finish() {
        encoder.finish();
    }

    @Override
    public void close() {
    }
}
