package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.image.exif.ExifReader;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the metadata embedded in an image file: EXIF, XMP, IPTC, the ICC color profile,
 * comments and text.
 *
 * <p>{@link #read(MediaSource)} recognizes the format from the content and reads:
 * <ul>
 *   <li>JPEG: the {@code APP1 Exif} segment, the {@code APP1} XMP packet with its Extended XMP
 *       segments merged in, the {@code APP2 ICC_PROFILE} segments, the IPTC-IIM data of the
 *       {@code APP13 Photoshop 3.0} segment, and the {@code COM} comments;</li>
 *   <li>PNG: the {@code eXIf} chunk, the {@code iTXt XML:com.adobe.xmp} chunk, the {@code iCCP}
 *       chunk, and the {@code tEXt}, {@code zTXt} and {@code iTXt} text, the {@code Comment}
 *       keyword giving the comments;</li>
 *   <li>WebP: the {@code EXIF}, {@code XMP } and {@code ICCP} chunks;</li>
 *   <li>GIF: the comment extensions, the {@code XMP DataXMP} application extension and the
 *       {@code ICCRGBG1} application extension;</li>
 *   <li>HEIC and AVIF: the {@code Exif} and XMP items describing the primary item, and its ICC
 *       color property;</li>
 *   <li>TIFF and BigTIFF: the descriptive entries of IFD0 and its Exif, GPS and interoperability
 *       directories as EXIF (the entries that lay out the coded image are left out), with the XMP
 *       (tag 700), IPTC (tag 33723, or else the IPTC resource of the Photoshop image resources in
 *       tag 34377) and ICC (tag 34675) entries;</li>
 *   <li>BMP: the ICC profile a V5 header embeds.</li>
 * </ul>
 *
 * <p>Instances are immutable and thread-safe; the byte-array accessors return copies.
 *
 * @apiNote Change the metadata of a file with {@link ImageMetadataEditor}, which rewrites only
 * the metadata and copies the compressed pixels byte for byte.
 */
public final class ImageMetadata {
    /**
     * The keyword of the PNG text that holds comments.
     */
    static final String COMMENT_KEYWORD = "Comment";

    /**
     * The format of the file.
     */
    private final ImageFormat format;

    /**
     * The EXIF block, starting with its byte-order mark, or {@code null}.
     */
    private final byte[] exif;

    /**
     * The XMP packet, or {@code null}.
     */
    private final XmpPacket xmp;

    /**
     * The IPTC-IIM data, or {@code null}.
     */
    private final IptcData iptc;

    /**
     * The ICC profile, or {@code null}.
     */
    private final byte[] iccProfile;

    /**
     * The comments.
     */
    private final List<String> comments;

    /**
     * The other text entries, keyed by keyword.
     */
    private final Map<String, String> text;

    /**
     * Creates an instance.
     *
     * @param format the format of the file read
     * @param exif the EXIF block, or {@code null}
     * @param xmp the XMP packet, or {@code null}
     * @param iptc the IPTC data, or {@code null}
     * @param iccProfile the ICC profile, or {@code null}
     * @param comments the comments, in file order
     * @param text the other text entries
     */
    ImageMetadata(ImageFormat format, byte[] exif, XmpPacket xmp, IptcData iptc, byte[] iccProfile, List<String> comments,
                  Map<String, String> text) {
        this.format = Objects.requireNonNull(format, "format");
        this.exif = exif;
        this.xmp = xmp;
        this.iptc = iptc;
        this.iccProfile = iccProfile;
        this.comments = List.copyOf(comments);
        this.text = Collections.unmodifiableMap(new LinkedHashMap<>(text));
    }

    /**
     * Reads the metadata of an image under {@link MediaLimits#DEFAULT}.
     *
     * @param source the image, read from offset zero; not closed
     * @return the metadata
     * @throws NullPointerException         if {@code source} is {@code null}
     * @throws MediaException.Unsupported   if the format is not recognized
     * @throws MediaException.Malformed     if the file structure is corrupt
     * @throws MediaException.LimitExceeded if a metadata item exceeds the limits
     * @throws MediaException.Io            if reading fails
     */
    public static ImageMetadata read(MediaSource source) {
        return read(source, MediaLimits.DEFAULT);
    }

    /**
     * Reads the metadata of an image.
     *
     * <p>A metadata item that is itself corrupt (an XMP packet that is not well-formed XML, an
     * ICC profile cut short) is left out; only a corrupt file structure fails the read.
     *
     * @param source the image, read from offset zero; not closed
     * @param limits the resource bounds
     * @return the metadata
     * @throws NullPointerException         if an argument is {@code null}
     * @throws MediaException.Unsupported   if the format is not recognized
     * @throws MediaException.Malformed     if the file structure is corrupt
     * @throws MediaException.LimitExceeded if a metadata item exceeds the limits
     * @throws MediaException.Io            if reading fails
     */
    public static ImageMetadata read(MediaSource source, MediaLimits limits) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(limits, "limits");
        return ImageFiles.guard(() -> ImageFiles.open(source, limits).metadata());
    }

    /**
     * Reads the metadata of an in-memory image under {@link MediaLimits#DEFAULT}.
     *
     * @param image the complete image file
     * @return the metadata
     * @throws NullPointerException if {@code image} is {@code null}
     * @throws MediaException.Unsupported if the format is not recognized
     * @throws MediaException.Malformed if the file structure is corrupt
     */
    public static ImageMetadata read(byte[] image) {
        return read(MediaSource.of(image));
    }

    /**
     * Reads the metadata of an image file under {@link MediaLimits#DEFAULT}.
     *
     * @param file the path of the image file
     * @return the metadata
     * @throws NullPointerException if {@code file} is {@code null}
     * @throws MediaException.Unsupported if the format is not recognized
     * @throws MediaException.Malformed if the file structure is corrupt
     * @throws MediaException.Io if the file cannot be read
     */
    public static ImageMetadata read(Path file) {
        try (var source = MediaSource.of(file)) {
            return read(source);
        }
    }

    /**
     * Returns the format of the file.
     *
     * @return the format
     */
    public ImageFormat format() {
        return format;
    }

    /**
     * {@return a copy of the EXIF block, the TIFF structure starting with its byte-order mark, or
     * empty when the file has none}
     *
     * <p>The block is returned as stored, even when it is not a readable TIFF structure;
     * {@link #exif()} parses it.
     */
    public Optional<byte[]> exifBytes() {
        return exif == null ? Optional.empty() : Optional.of(exif.clone());
    }

    /**
     * {@return the parsed EXIF directories, or empty when the file has no EXIF block or it is not
     * a readable TIFF structure}
     *
     * <p>{@link #exifBytes()} returns the block as stored.
     */
    public Optional<ExifData> exif() {
        if (exif == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ExifReader.parse(exif));
        } catch (MediaException exception) {
            return Optional.empty();
        }
    }

    /**
     * Returns the display orientation the EXIF block records.
     *
     * @return the orientation, {@link Orientation#NORMAL} when absent
     */
    public Orientation orientation() {
        return ExifReader.orientation(exif);
    }

    /**
     * Returns the XMP packet.
     *
     * @return the packet, or empty when the file has none
     */
    public Optional<XmpPacket> xmp() {
        return Optional.ofNullable(xmp);
    }

    /**
     * Returns the IPTC-IIM data.
     *
     * @return the data, or empty when the file has none
     */
    public Optional<IptcData> iptc() {
        return Optional.ofNullable(iptc);
    }

    /**
     * Returns the embedded ICC color profile.
     *
     * @return a copy of the profile, or empty when the file has none
     */
    public Optional<byte[]> iccProfile() {
        return iccProfile == null ? Optional.empty() : Optional.of(iccProfile.clone());
    }

    /**
     * Returns the embedded ICC color profile, parsed.
     *
     * @return the profile, or empty when the file has none or it is not a valid profile
     */
    public Optional<ColorProfile> colorProfile() {
        if (iccProfile == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(ColorProfile.parse(iccProfile));
        } catch (MediaException exception) {
            return Optional.empty();
        }
    }

    /**
     * Returns the comments: JPEG {@code COM} segments, GIF comment extensions and PNG
     * {@code Comment} text.
     *
     * @return the comments in file order; unmodifiable
     */
    public List<String> comments() {
        return comments;
    }

    /**
     * Returns the PNG text entries other than the comments and the XMP packet.
     *
     * @return the text keyed by keyword, in file order; unmodifiable, empty for other formats
     */
    public Map<String, String> text() {
        return text;
    }

    /**
     * Returns whether the metadata records where the picture was taken: GPS coordinates in EXIF
     * or XMP, or place names in XMP or IPTC.
     *
     * @return {@code true} when a location is recorded
     */
    public boolean hasLocation() {
        if (exif().map(ExifData::hasLocation).orElse(false)) {
            return true;
        }
        if (xmp != null) {
            for (var property : xmp.properties()) {
                if (Locations.isLocation(property)) {
                    return true;
                }
            }
        }
        return iptc != null && iptc.hasLocation();
    }

    /**
     * Returns whether the file holds no metadata at all.
     *
     * @return {@code true} without EXIF, XMP, IPTC, ICC profile, comments and text
     */
    public boolean isEmpty() {
        return exif == null && xmp == null && iptc == null && iccProfile == null && comments.isEmpty() && text.isEmpty();
    }

    /**
     * Returns the EXIF block without copying it.
     *
     * @return the block, or {@code null}
     */
    byte[] rawExif() {
        return exif;
    }

    @Override
    public String toString() {
        return "ImageMetadata[format=" + format + ", exif=" + (exif != null ? exif.length + " bytes" : "none") + ", xmp="
                + (xmp != null ? xmp.properties().size() + " properties" : "none") + ", iptc="
                + (iptc != null ? iptc.dataSets().size() + " data sets" : "none") + ", iccProfile="
                + (iccProfile != null ? iccProfile.length + " bytes" : "none") + ", comments=" + comments.size()
                + ", text=" + text.keySet() + "]";
    }
}
