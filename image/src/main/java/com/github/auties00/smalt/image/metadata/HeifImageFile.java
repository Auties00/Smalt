package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.internal.heif.HeifFile;
import com.github.auties00.smalt.internal.heif.HeifProperty;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the metadata of a HEIC or AVIF file: the {@code Exif} and XMP ({@code mime} item of type
 * {@code application/rdf+xml}) items that describe the primary item, and its ICC color
 * property; edits replace or remove the EXIF and XMP items through {@link HeifMetaEditor}.
 */
final class HeifImageFile implements ImageFile {
    /**
     * The source.
     */
    private final MediaSource source;

    /**
     * The resource bounds.
     */
    private final MediaLimits limits;

    /**
     * The parsed file.
     */
    private final HeifFile file;

    /**
     * The format, HEIC or AVIF.
     */
    private final ImageFormat format;

    /**
     * Parses the file's boxes.
     *
     * @param source the HEIF file
     * @param limits the resource bounds
     * @param format the format detected
     * @throws MediaException.Malformed if the boxes are corrupt
     */
    HeifImageFile(MediaSource source, MediaLimits limits, ImageFormat format) {
        this.source = source;
        this.limits = limits;
        this.format = format;
        this.file = HeifFile.parse(source, limits);
    }

    @Override
    public ImageMetadata metadata() {
        var primary = file.hasMeta() ? file.primaryItem() : null;
        if (primary == null) {
            return new ImageMetadata(format, null, null, null, null, List.of(), Map.of());
        }
        byte[] exif = null;
        XmpPacket xmp = null;
        for (var item : file.descriptionsOf(primary.id())) {
            if (!item.isLocated()) {
                continue;
            }
            if (item.type().equals("Exif") && exif == null) {
                exif = HeifFile.exifTiff(file.readItem(source, item, limits));
            } else if (item.type().equals("mime") && xmp == null
                    && item.contentType() != null && item.contentType().toLowerCase(Locale.ROOT).startsWith(HeifMetaEditor.XMP_TYPE)) {
                try {
                    xmp = XmpPacket.parse(file.readItem(source, item, limits));
                } catch (MediaException.Malformed ignored) {
                    // a packet that does not parse is left out
                }
            }
        }
        var icc = primary.property(HeifProperty.ColorIcc.class);
        return new ImageMetadata(format, exif, xmp, null, icc != null ? icc.profile().clone() : null, List.of(), Map.of());
    }

    @Override
    public EditPlan plan(Resolved target) {
        var name = format.name().toLowerCase(Locale.ROOT);
        if (!(target.icc() instanceof ImageMetadataEdit.Change.Keep)) {
            // TODO: rewrite the colr property of the primary item to replace or remove its ICC profile.
            throw new MediaException.Unsupported(name, "the ICC profile of " + format.name() + " files cannot be edited");
        }
        if ((target.iptc() instanceof ImageMetadataEdit.Change.Replace && target.iptcData() != null && !target.iptcData().isEmpty())
                || (target.comments() instanceof ImageMetadataEdit.Change.Replace && !target.commentList().isEmpty())
                || (target.text() instanceof ImageMetadataEdit.Change.Replace && !target.textEntries().isEmpty())) {
            throw ImageFiles.unsupported(format, "IPTC data, comments or text");
        }
        if (target.exif() instanceof ImageMetadataEdit.Change.Keep && target.xmp() instanceof ImageMetadataEdit.Change.Keep) {
            return EditPlan.builder().copy(0, source.size()).build();
        }
        return new HeifMetaEditor(source, limits).plan(!(target.exif() instanceof ImageMetadataEdit.Change.Keep), target.exifBytes(),
                !(target.xmp() instanceof ImageMetadataEdit.Change.Keep), target.xmpPacket() != null ? target.xmpPacket().toBytes() : null);
    }
}
