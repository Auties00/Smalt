package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifWriter;
import com.github.auties00.smalt.image.metadata.ImageMetadataEdit.Change;
import com.github.auties00.smalt.internal.edit.FileEdits;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;
import com.github.auties00.smalt.metadata.XmpValue;

import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Changes the metadata of image files without re-encoding them.
 *
 * <p>The compressed pixel data is copied byte for byte: JPEG files are rewritten segment by
 * segment up to the first scan, PNG files chunk by chunk, WebP files chunk by chunk through the
 * RIFF structure, GIF files block by block, and TIFF files keep every byte outside the first
 * directory and its metadata where it is, and BMP files keep their color table and pixel data. The
 * metadata kinds an {@link ImageMetadataEdit} keeps stay byte-identical; EXIF edits go through
 * {@link ExifWriter#rewrite(byte[], ExifData)}, which keeps the maker note and every unchanged value at
 * its offset and zeroes what it removes.
 *
 * <p>JPEG, PNG and WebP accept every kind of change (IPTC only in JPEG, comments in JPEG and PNG,
 * text in PNG); GIF accepts XMP and comments; HEIC and AVIF accept EXIF and XMP, whose items are
 * rewritten while the coded image items stay where they are or move with their offsets corrected;
 * TIFF and BigTIFF accept EXIF, XMP, IPTC and ICC changes; BMP accepts ICC changes, a new profile
 * giving the file a V5 header. An edit a format cannot store raises
 * {@link MediaException.Unsupported}.
 *
 * <p>A TIFF edit rebuilds the first directory (IFD0) and, when the EXIF block changes, its Exif,
 * GPS and interoperability directories; the strips and tiles, the other pages and their
 * directories, and every value an entry still references stay at their offsets, unchanged values
 * included. The entries that lay out or describe the coded image (its size, samples, compression,
 * strips or tiles, colors, GeoTIFF and DNG tags) are not part of the EXIF block and are always
 * kept; {@code XResolution}, {@code YResolution} and {@code ResolutionUnit}, which TIFF requires,
 * are part of it but keep their values unless the edit sets them. IPTC data stored in the
 * Photoshop image resources (tag 34377) is changed there too. The old directories and the values
 * nothing references any more are zeroed, the new ones are written over that space or appended,
 * and freed space that ends the file is cut off, so that every copied byte stays at its offset and
 * {@link #apply(Path, ImageMetadataEdit)} edits a TIFF file in place.
 *
 * <p>HEIC and AVIF readers orient pictures by the {@code irot} and {@code imir} properties, not by
 * the EXIF orientation, which an orientation edit still records.
 *
 * <p>The class is stateless and thread-safe.
 */
public final class ImageMetadataEditor {
    /**
     * The XMP property that records the orientation.
     */
    private static final String ORIENTATION = "Orientation";

    /**
     * Prevents instantiation.
     */
    private ImageMetadataEditor() {
        throw new AssertionError();
    }

    /**
     * Returns a copy of an in-memory image with its metadata changed.
     *
     * @param image the complete image file
     * @param edit the change
     * @return the edited image; the input when the edit changes nothing
     * @throws NullPointerException if an argument is {@code null}
     * @throws MediaException.Unsupported if the format is not recognized or cannot store the
     * change
     * @throws MediaException.Malformed if the image structure is corrupt
     * @throws MediaException.LimitExceeded if an item exceeds a limit of the format or of the
     * edit
     */
    public static byte[] apply(byte[] image, ImageMetadataEdit edit) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(edit, "edit");
        return ImageFiles.guard(() -> {
            var file = ImageFiles.open(MediaSource.of(image), edit.limits());
            var plan = file.plan(resolve(file.metadata(), edit));
            return plan.isIdentity(image.length) ? image : FileEdits.apply(image, plan);
        });
    }

    /**
     * Writes a copy of an image with its metadata changed.
     *
     * <p>Only the metadata is held in memory (the whole file for WebP); the pixel data is copied
     * through a fixed buffer.
     *
     * @param source the image, read from offset zero; not closed
     * @param sink   the destination, written from its current position; not closed
     * @param edit   the change
     * @throws NullPointerException         if an argument is {@code null}
     * @throws MediaException.Unsupported   if the format is not recognized or cannot store the
     *                                      change
     * @throws MediaException.Malformed     if the image structure is corrupt
     * @throws MediaException.LimitExceeded if an item exceeds a limit of the format or of the
     *                                      edit
     * @throws MediaException.Io            if reading or writing fails
     */
    public static void apply(MediaSource source, MediaSink sink, ImageMetadataEdit edit) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(edit, "edit");
        ImageFiles.guard(() -> {
            var file = ImageFiles.open(source, edit.limits());
            file.plan(resolve(file.metadata(), edit)).writeTo(source, sink);
            return null;
        });
    }

    /**
     * Changes the metadata of an image file on disk.
     *
     * <p>The file is rewritten to a temporary file in the same directory that replaces it,
     * atomically where the file system supports it, unless the change fits in place (the same
     * metadata size, or any change of a TIFF file), in which case only the metadata bytes are
     * written and the file is extended or truncated. An edit that fails before writing leaves the
     * original untouched.
     *
     * @param file the image file
     * @param edit the change
     * @throws NullPointerException         if an argument is {@code null}
     * @throws MediaException.Unsupported   if the format is not recognized or cannot store the
     *                                      change
     * @throws MediaException.Malformed     if the image structure is corrupt
     * @throws MediaException.LimitExceeded if an item exceeds a limit of the format or of the
     *                                      edit
     * @throws MediaException.Io            if reading or writing fails
     */
    public static void apply(Path file, ImageMetadataEdit edit) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(edit, "edit");
        ImageFiles.guard(() -> FileEdits.apply(file, true, source -> {
            var image = ImageFiles.open(source, edit.limits());
            return image.plan(resolve(image.metadata(), edit));
        }));
    }

    /**
     * Returns a copy of an image without location metadata.
     *
     * @param image the complete image file
     * @return the edited image
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws MediaException.Unsupported if the format is not recognized or cannot be edited
     * @throws MediaException.Malformed if the image structure is corrupt
     */
    public static byte[] stripLocation(byte[] image) {
        return apply(image, ImageMetadataEdit.stripLocation());
    }

    /**
     * Returns a copy of an image without metadata, apart from its ICC color profile.
     *
     * @param image the complete image file
     * @return the edited image
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws MediaException.Unsupported if the format is not recognized or cannot be edited
     * @throws MediaException.Malformed if the image structure is corrupt
     */
    public static byte[] stripAll(byte[] image) {
        return apply(image, ImageMetadataEdit.stripAll());
    }

    /**
     * Resolves an edit against the metadata of a file.
     *
     * <p>A kind the edit keeps and its derived changes (orientation, location, thumbnail) leave
     * untouched stays {@link Change.Keep}, so its bytes are copied; a kind that becomes empty is
     * removed.
     *
     * @param current the metadata of the file
     * @param edit the edit to apply
     * @return the resolved metadata
     */
    static Resolved resolve(ImageMetadata current, ImageMetadataEdit edit) {
        var exif = resolveExif(current, edit);
        var xmp = resolveXmp(current, edit);
        var iptc = resolveIptc(current, edit);
        Change<byte[]> icc = switch (edit.colorProfile()) {
            case Change.Keep<ColorProfile> _ -> new Change.Keep<>();
            case Change.Remove<ColorProfile> _ -> new Change.Remove<>();
            case Change.Replace<ColorProfile> _ -> new Change.Replace<>(edit.iccBytes());
        };
        var hdrColor = edit.colorProfile() instanceof Change.Replace<ColorProfile>(var profile)
                ? profile.colorInfo().orElse(null) : null;
        return new Resolved(exif, xmp, iptc, icc, hdrColor, edit.comments(), edit.text(),
                edit.removesOtherMetadata());
    }

    /**
     * Resolves the EXIF block.
     *
     * @param current the metadata of the file
     * @param edit the edit to apply
     * @return the change, with the new block when replaced
     * @throws MediaException.LimitExceeded if a JPEG file's new block does not fit a segment
     */
    private static Change<byte[]> resolveExif(ImageMetadata current, ImageMetadataEdit edit) {
        var original = current.rawExif();
        ExifData base;
        switch (edit.exif()) {
            case Change.Remove<ExifData> _ -> {
                return new Change.Remove<>();
            }
            case Change.Replace<ExifData>(var value) -> base = value;
            case Change.Keep<ExifData> _ -> {
                if (original == null) {
                    var orientation = edit.orientation();
                    if (orientation.isEmpty() || orientation.get() == Orientation.NORMAL) {
                        return new Change.Keep<>();
                    }
                    base = ExifData.EMPTY;
                } else {
                    var parsed = current.exif();
                    if (parsed.isEmpty()) {
                        var modifies = edit.removesLocation() || edit.removesThumbnail() || edit.orientation().isPresent();
                        if (!modifies) {
                            return new Change.Keep<>();
                        }
                        base = ExifData.EMPTY;
                        original = null;
                    } else {
                        base = parsed.get();
                    }
                }
            }
        }
        var target = base;
        if (edit.orientation().isPresent()) {
            target = target.withOrientation(edit.orientation().get());
        }
        if (edit.removesLocation()) {
            target = target.withoutGps();
        }
        if (edit.removesThumbnail()) {
            target = target.withoutThumbnail();
        }
        if (edit.exif() instanceof Change.Keep && original != null && target.equals(base)) {
            return new Change.Keep<>();
        }
        if (target.ifd0().isEmpty() && target.exif().isEmpty() && target.gps().isEmpty() && target.interop().isEmpty()
                && target.thumbnail().isEmpty()) {
            return new Change.Remove<>();
        }
        var bytes = original != null ? ExifWriter.rewrite(original, target) : ExifWriter.write(target);
        if (current.format() == ImageFormat.JPEG && bytes.length + JpegFile.EXIF_ID.length > JpegFile.MAX_PAYLOAD) {
            bytes = ExifWriter.write(target, bytes[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            if (bytes.length + JpegFile.EXIF_ID.length > JpegFile.MAX_PAYLOAD) {
                throw new MediaException.LimitExceeded("EXIF block of " + bytes.length + " bytes does not fit a JPEG segment");
            }
        }
        return new Change.Replace<>(bytes);
    }

    /**
     * Resolves the XMP packet.
     *
     * @param current the metadata of the file
     * @param edit the edit to apply
     * @return the change, with the new packet when replaced
     */
    private static Change<XmpPacket> resolveXmp(ImageMetadata current, ImageMetadataEdit edit) {
        XmpPacket base;
        switch (edit.xmp()) {
            case Change.Remove<XmpPacket> _ -> {
                return new Change.Remove<>();
            }
            case Change.Replace<XmpPacket>(var value) -> base = value;
            case Change.Keep<XmpPacket> _ -> {
                base = current.xmp().orElse(null);
                if (base == null) {
                    return new Change.Keep<>();
                }
            }
        }
        var target = base;
        if (edit.removesLocation()) {
            target = target.withoutIf(Locations::isLocation);
        }
        if (edit.orientation().isPresent() && target.get(XmpPacket.TIFF, ORIENTATION).isPresent()) {
            target = target.with(XmpPacket.TIFF, ORIENTATION, XmpValue.text(Integer.toString(edit.orientation().get().exifCode())));
        }
        if (edit.xmp() instanceof Change.Keep && target.equals(base)) {
            return new Change.Keep<>();
        }
        if (target.isEmpty()) {
            return new Change.Remove<>();
        }
        return new Change.Replace<>(target);
    }

    /**
     * Resolves the IPTC data.
     *
     * @param current the metadata of the file
     * @param edit the edit to apply
     * @return the change, with the new data when replaced
     */
    private static Change<IptcData> resolveIptc(ImageMetadata current, ImageMetadataEdit edit) {
        IptcData base;
        switch (edit.iptc()) {
            case Change.Remove<IptcData> _ -> {
                return new Change.Remove<>();
            }
            case Change.Replace<IptcData>(var value) -> base = value;
            case Change.Keep<IptcData> _ -> {
                base = current.iptc().orElse(null);
                if (base == null) {
                    return new Change.Keep<>();
                }
            }
        }
        var target = edit.removesLocation() ? base.withoutLocation() : base;
        if (edit.iptc() instanceof Change.Keep && target.equals(base)) {
            return new Change.Keep<>();
        }
        if (target.isEmpty()) {
            return new Change.Remove<>();
        }
        return new Change.Replace<>(target);
    }
}
