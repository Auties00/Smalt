package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.webp.WebpContainer;
import com.github.auties00.smalt.internal.edit.EditPlan;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Reads and rewrites the {@code EXIF}, {@code XMP } and {@code ICCP} chunks of a WebP file
 * through {@link WebpContainer}, which keeps the image chunks unchanged and the {@code VP8X}
 * flags consistent.
 */
final class WebpFile implements ImageFile {
    /**
     * The whole file.
     */
    private final byte[] file;

    /**
     * Reads and validates the file.
     *
     * @param source the WebP file
     * @param limits the resource bounds
     * @throws MediaException.Malformed if the RIFF structure is invalid
     */
    WebpFile(MediaSource source, MediaLimits limits) {
        this.file = ImageFiles.read(source, 0, source.size(), limits);
        WebpContainer.parse(file);
    }

    @Override
    public ImageMetadata metadata() {
        var exif = WebpContainer.exif(file).orElse(null);
        if (exif != null && exif.length >= 6 && exif[0] == 'E' && exif[1] == 'x' && exif[2] == 'i' && exif[3] == 'f'
                && exif[4] == 0 && exif[5] == 0) {
            exif = Arrays.copyOfRange(exif, 6, exif.length);
        }
        var xmpBytes = WebpContainer.xmp(file).orElse(null);
        XmpPacket xmp = null;
        if (xmpBytes != null) {
            try {
                xmp = XmpPacket.parse(xmpBytes);
            } catch (MediaException ignored) {
                // a packet that does not parse is left out
            }
        }
        var icc = WebpContainer.iccProfile(file).orElse(null);
        return new ImageMetadata(ImageFormat.WEBP, exif, xmp, null, icc, List.of(), Map.of());
    }

    @Override
    public EditPlan plan(Resolved target) {
        if (target.iptc() instanceof ImageMetadataEdit.Change.Replace && target.iptcData() != null && !target.iptcData().isEmpty()) {
            throw ImageFiles.unsupported(ImageFormat.WEBP, "IPTC-IIM data");
        }
        if (target.comments() instanceof ImageMetadataEdit.Change.Replace && !target.commentList().isEmpty()) {
            throw ImageFiles.unsupported(ImageFormat.WEBP, "comments");
        }
        if (target.text() instanceof ImageMetadataEdit.Change.Replace && !target.textEntries().isEmpty()) {
            throw ImageFiles.unsupported(ImageFormat.WEBP, "text");
        }
        var result = file;
        if (!(target.exif() instanceof ImageMetadataEdit.Change.Keep)) {
            var exif = target.exifBytes();
            result = exif != null ? WebpContainer.withExif(result, exif) : WebpContainer.removeExif(result);
        }
        if (!(target.icc() instanceof ImageMetadataEdit.Change.Keep)) {
            var icc = target.iccProfile();
            result = icc != null ? WebpContainer.withIccProfile(result, icc) : WebpContainer.removeIccProfile(result);
        }
        if (!(target.xmp() instanceof ImageMetadataEdit.Change.Keep)) {
            result = target.xmpPacket() != null ? WebpContainer.withXmp(result, target.xmpPacket().toBytes())
                    : WebpContainer.removeXmp(result);
        }
        if (result == file) {
            return EditPlan.builder().copy(0, file.length).build();
        }
        return EditPlan.builder().bytes(result).build();
    }
}
