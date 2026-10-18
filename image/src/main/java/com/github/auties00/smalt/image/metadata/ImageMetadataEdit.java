package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.exif.ExifData;
import com.github.auties00.smalt.metadata.XmpPacket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds the settings of a metadata change that {@link ImageMetadataEditor} applies without
 * re-encoding the pixels. Instances are immutable and thread-safe; create them with
 * {@link #builder()} or a preset such as {@link #keepAll()}.
 *
 * <p>Each kind of metadata is kept, removed or replaced: EXIF, XMP, IPTC, the ICC color profile,
 * the comments and the PNG text.
 *
 * @apiNote {@link #stripAll()} keeps the ICC profile: it defines what the pixel values mean, so
 * removing it changes the colors of wide-gamut photos. To remove it too, use
 * {@code stripAll().toBuilder().removeIccProfile().build()}.
 */
public final class ImageMetadataEdit {
    /**
     * The edit of {@link #keepAll()}.
     */
    private static final ImageMetadataEdit KEEP_ALL = new Builder().build();

    /**
     * The edit of {@link #stripAll()}.
     */
    private static final ImageMetadataEdit STRIP_ALL = new Builder().removeExif().removeXmp().removeIptc()
            .removeComments().removeText().removeOtherMetadata(true).build();

    /**
     * The edit of {@link #stripLocation()}.
     */
    private static final ImageMetadataEdit STRIP_LOCATION = new Builder().removeLocation(true).build();

    private final Change<ExifData> exif;

    private final Change<XmpPacket> xmp;

    private final Change<IptcData> iptc;

    private final Change<ColorProfile> colorProfile;

    /**
     * The ICC bytes of the replacing color profile, or {@code null}.
     */
    private final byte[] iccBytes;

    private final Change<List<String>> comments;

    private final Change<Map<String, String>> text;

    private final Orientation orientation;

    private final boolean removeLocation;

    private final boolean removeThumbnail;

    private final boolean removeOther;

    private final MediaLimits limits;

    private ImageMetadataEdit(Builder builder) {
        this.exif = builder.exif;
        this.xmp = builder.xmp;
        this.iptc = builder.iptc;
        this.colorProfile = builder.colorProfile;
        this.iccBytes = builder.colorProfile instanceof Change.Replace<ColorProfile>(var profile)
                ? profile.toByteArray() : null;
        this.comments = builder.comments;
        this.text = builder.text;
        this.orientation = builder.orientation;
        this.removeLocation = builder.removeLocation;
        this.removeThumbnail = builder.removeThumbnail;
        this.removeOther = builder.removeOther;
        this.limits = builder.limits;
    }

    /**
     * Returns the edit that changes nothing.
     *
     * @return the edit
     */
    public static ImageMetadataEdit keepAll() {
        return KEEP_ALL;
    }

    /**
     * Returns the edit that removes every metadata item except the ICC color profile: EXIF,
     * XMP, IPTC, comments, PNG text and the other metadata.
     *
     * @return the edit
     */
    public static ImageMetadataEdit stripAll() {
        return STRIP_ALL;
    }

    /**
     * Returns the edit that removes where the picture was taken and keeps everything else.
     *
     * @return the edit
     */
    public static ImageMetadataEdit stripLocation() {
        return STRIP_LOCATION;
    }

    /**
     * Returns a builder initialized with {@link #keepAll()}.
     *
     * @return the builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Returns a builder initialized with this edit.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return what happens to the EXIF block, with the replacing EXIF directories and thumbnail when it is
     * replaced; {@link Change.Keep} by default}
     */
    public Change<ExifData> exif() {
        return exif;
    }

    /**
     * {@return what happens to the XMP packet, with the replacing packet when it is replaced;
     * {@link Change.Keep} by default}
     */
    public Change<XmpPacket> xmp() {
        return xmp;
    }

    /**
     * {@return what happens to the IPTC data, with the replacing data when it is replaced;
     * {@link Change.Keep} by default}
     */
    public Change<IptcData> iptc() {
        return iptc;
    }

    /**
     * {@return what happens to the ICC color profile, with the replacing profile when it is replaced;
     * {@link Change.Keep} by default}
     *
     * <p>The ICC bytes of a replacing profile are written unchanged. A high-dynamic-range profile, which
     * has no ICC form, is rejected.
     */
    public Change<ColorProfile> colorProfile() {
        return colorProfile;
    }

    /**
     * {@return what happens to the comments, with the replacing comments, in order and unmodifiable, when
     * they are replaced; {@link Change.Keep} by default}
     */
    public Change<List<String>> comments() {
        return comments;
    }

    /**
     * {@return what happens to the PNG text, with the replacing text keyed by keyword, in insertion order
     * and unmodifiable, when it is replaced; {@link Change.Keep} by default}
     */
    public Change<Map<String, String>> text() {
        return text;
    }

    /**
     * {@return the orientation to record, or empty to keep the recorded one; empty by default}
     *
     * <p>The orientation is recorded in EXIF, created when absent, and in the XMP
     * {@code tiff:Orientation} property when present.
     */
    public Optional<Orientation> orientation() {
        return Optional.ofNullable(orientation);
    }

    /**
     * {@return whether the location is removed: the EXIF GPS directory, the XMP GPS coordinates and
     * place names, and the IPTC place fields; {@code false} by default}
     */
    public boolean removesLocation() {
        return removeLocation;
    }

    /**
     * {@return whether the EXIF thumbnail is removed; {@code false} by default}
     */
    public boolean removesThumbnail() {
        return removeThumbnail;
    }

    /**
     * {@return whether the metadata of no other kind is removed: JPEG application segments other
     * than JFIF, Adobe and MPF, and PNG {@code tIME}; {@code false} by default}
     */
    public boolean removesOtherMetadata() {
        return removeOther;
    }

    /**
     * {@return the resource bounds enforced while reading the image; {@link MediaLimits#DEFAULT} by
     * default}
     */
    public MediaLimits limits() {
        return limits;
    }

    /**
     * Returns the ICC bytes of the replacing color profile without copying them.
     *
     * @return the bytes, or {@code null}
     */
    byte[] iccBytes() {
        return iccBytes;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ImageMetadataEdit that && exif.equals(that.exif) && xmp.equals(that.xmp)
                && iptc.equals(that.iptc) && colorProfile.equals(that.colorProfile) && comments.equals(that.comments)
                && text.equals(that.text) && orientation == that.orientation && removeLocation == that.removeLocation
                && removeThumbnail == that.removeThumbnail && removeOther == that.removeOther && limits.equals(that.limits);
    }

    @Override
    public int hashCode() {
        return Objects.hash(exif, xmp, iptc, colorProfile, comments, text, orientation, removeLocation, removeThumbnail,
                removeOther, limits);
    }

    @Override
    public String toString() {
        return "ImageMetadataEdit[exif=" + kind(exif) + ", xmp=" + kind(xmp) + ", iptc=" + kind(iptc) + ", icc="
                + kind(colorProfile) + ", comments=" + kind(comments) + ", text=" + kind(text) + ", orientation="
                + orientation
                + ", removeLocation=" + removeLocation + ", removeThumbnail=" + removeThumbnail + ", removeOther="
                + removeOther + "]";
    }

    /**
     * Names a change for {@link #toString()}.
     *
     * @param change what happens to one kind of metadata
     * @return {@code KEEP}, {@code REMOVE} or {@code REPLACE}
     */
    private static String kind(Change<?> change) {
        return switch (change) {
            case Change.Keep<?> _ -> "KEEP";
            case Change.Remove<?> _ -> "REMOVE";
            case Change.Replace<?> _ -> "REPLACE";
        };
    }

    /**
     * What an edit does to one kind of metadata: keeps it as the file has it, removes it, or replaces it.
     *
     * @param <T> the type of the replacing value
     */
    public sealed interface Change<T> permits Change.Keep, Change.Remove, Change.Replace {
        /**
         * Keeps the metadata as the file has it.
         *
         * @param <T> the type of the replacing value
         */
        record Keep<T>() implements Change<T> {
        }

        /**
         * Removes the metadata.
         *
         * @param <T> the type of the replacing value
         */
        record Remove<T>() implements Change<T> {
        }

        /**
         * Replaces the metadata with a value.
         *
         * @param value the replacing value
         * @param <T> the type of the replacing value
         */
        record Replace<T>(T value) implements Change<T> {
            /**
             * Validates the value.
             */
            public Replace {
                Objects.requireNonNull(value, "value");
            }
        }
    }

    /**
     * A builder of {@link ImageMetadataEdit}.
     *
     * <p>Setters reject only {@code null}; {@link #build()} checks the color profile. For each kind
     * of metadata, the last of its replace, remove and keep calls wins. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private Change<ExifData> exif = new Change.Keep<>();

        private Change<XmpPacket> xmp = new Change.Keep<>();

        private Change<IptcData> iptc = new Change.Keep<>();

        private Change<ColorProfile> colorProfile = new Change.Keep<>();

        private Change<List<String>> comments = new Change.Keep<>();

        private Change<Map<String, String>> text = new Change.Keep<>();

        private Orientation orientation;

        private boolean removeLocation;

        private boolean removeThumbnail;

        private boolean removeOther;

        private MediaLimits limits = MediaLimits.DEFAULT;

        private Builder() {
        }

        private Builder(ImageMetadataEdit edit) {
            this.exif = edit.exif;
            this.xmp = edit.xmp;
            this.iptc = edit.iptc;
            this.colorProfile = edit.colorProfile;
            this.comments = edit.comments;
            this.text = edit.text;
            this.orientation = edit.orientation;
            this.removeLocation = edit.removeLocation;
            this.removeThumbnail = edit.removeThumbnail;
            this.removeOther = edit.removeOther;
            this.limits = edit.limits;
        }

        /**
         * Replaces the EXIF block; see {@link ImageMetadataEdit#exif()}.
         *
         * @param exif the value
         * @return this builder
         */
        public Builder exif(ExifData exif) {
            this.exif = new Change.Replace<>(exif);
            return this;
        }

        /**
         * Removes the EXIF block; see {@link ImageMetadataEdit#exif()}.
         *
         * @return this builder
         */
        public Builder removeExif() {
            this.exif = new Change.Remove<>();
            return this;
        }

        /**
         * Keeps the EXIF block as the image has it; see {@link ImageMetadataEdit#exif()}.
         *
         * @return this builder
         */
        public Builder keepExif() {
            this.exif = new Change.Keep<>();
            return this;
        }

        /**
         * Replaces the XMP packet; see {@link ImageMetadataEdit#xmp()}.
         *
         * @param xmp the value
         * @return this builder
         */
        public Builder xmp(XmpPacket xmp) {
            this.xmp = new Change.Replace<>(xmp);
            return this;
        }

        /**
         * Removes the XMP packet; see {@link ImageMetadataEdit#xmp()}.
         *
         * @return this builder
         */
        public Builder removeXmp() {
            this.xmp = new Change.Remove<>();
            return this;
        }

        /**
         * Keeps the XMP packet as the image has it; see {@link ImageMetadataEdit#xmp()}.
         *
         * @return this builder
         */
        public Builder keepXmp() {
            this.xmp = new Change.Keep<>();
            return this;
        }

        /**
         * Replaces the IPTC data; see {@link ImageMetadataEdit#iptc()}.
         *
         * @param iptc the value
         * @return this builder
         */
        public Builder iptc(IptcData iptc) {
            this.iptc = new Change.Replace<>(iptc);
            return this;
        }

        /**
         * Removes the IPTC data; see {@link ImageMetadataEdit#iptc()}.
         *
         * @return this builder
         */
        public Builder removeIptc() {
            this.iptc = new Change.Remove<>();
            return this;
        }

        /**
         * Keeps the IPTC data as the image has it; see {@link ImageMetadataEdit#iptc()}.
         *
         * @return this builder
         */
        public Builder keepIptc() {
            this.iptc = new Change.Keep<>();
            return this;
        }

        /**
         * Replaces the ICC color profile; see {@link ImageMetadataEdit#colorProfile()}.
         *
         * @param profile the value
         * @return this builder
         */
        public Builder colorProfile(ColorProfile profile) {
            this.colorProfile = new Change.Replace<>(profile);
            return this;
        }

        /**
         * Removes the ICC color profile; see {@link ImageMetadataEdit#colorProfile()}.
         *
         * @return this builder
         */
        public Builder removeIccProfile() {
            this.colorProfile = new Change.Remove<>();
            return this;
        }

        /**
         * Keeps the ICC color profile as the image has it; see {@link ImageMetadataEdit#colorProfile()}.
         *
         * @return this builder
         */
        public Builder keepIccProfile() {
            this.colorProfile = new Change.Keep<>();
            return this;
        }

        /**
         * Replaces the comments; see {@link ImageMetadataEdit#comments()}.
         *
         * @param comments the value, copied
         * @return this builder
         * @throws NullPointerException if a comment is {@code null}
         */
        public Builder comments(List<String> comments) {
            this.comments = new Change.Replace<>(List.copyOf(comments));
            return this;
        }

        /**
         * Removes the comments; see {@link ImageMetadataEdit#comments()}.
         *
         * @return this builder
         */
        public Builder removeComments() {
            this.comments = new Change.Remove<>();
            return this;
        }

        /**
         * Keeps the comments as the image has them; see {@link ImageMetadataEdit#comments()}.
         *
         * @return this builder
         */
        public Builder keepComments() {
            this.comments = new Change.Keep<>();
            return this;
        }

        /**
         * Replaces the PNG text; see {@link ImageMetadataEdit#text()}.
         *
         * @param text the value, copied
         * @return this builder
         * @throws NullPointerException if a keyword or a text is {@code null}
         */
        public Builder text(Map<String, String> text) {
            var copy = new LinkedHashMap<String, String>();
            for (var entry : text.entrySet()) {
                copy.put(Objects.requireNonNull(entry.getKey(), "keyword"), Objects.requireNonNull(entry.getValue(), "text"));
            }
            this.text = new Change.Replace<>(Collections.unmodifiableMap(copy));
            return this;
        }

        /**
         * Removes the PNG text; see {@link ImageMetadataEdit#text()}.
         *
         * @return this builder
         */
        public Builder removeText() {
            this.text = new Change.Remove<>();
            return this;
        }

        /**
         * Keeps the PNG text as the image has it; see {@link ImageMetadataEdit#text()}.
         *
         * @return this builder
         */
        public Builder keepText() {
            this.text = new Change.Keep<>();
            return this;
        }

        /**
         * Sets the orientation to record; see {@link ImageMetadataEdit#orientation()}.
         *
         * @param orientation the value
         * @return this builder
         */
        public Builder orientation(Orientation orientation) {
            this.orientation = Objects.requireNonNull(orientation, "orientation");
            return this;
        }

        /**
         * Keeps the recorded orientation; see {@link ImageMetadataEdit#orientation()}.
         *
         * @return this builder
         */
        public Builder keepOrientation() {
            this.orientation = null;
            return this;
        }

        /**
         * Sets whether the location is removed; see {@link ImageMetadataEdit#removesLocation()}.
         *
         * @param removeLocation the value
         * @return this builder
         */
        public Builder removeLocation(boolean removeLocation) {
            this.removeLocation = removeLocation;
            return this;
        }

        /**
         * Sets whether the EXIF thumbnail is removed; see {@link ImageMetadataEdit#removesThumbnail()}.
         *
         * @param removeThumbnail the value
         * @return this builder
         */
        public Builder removeThumbnail(boolean removeThumbnail) {
            this.removeThumbnail = removeThumbnail;
            return this;
        }

        /**
         * Sets whether the metadata of no other kind is removed; see
         * {@link ImageMetadataEdit#removesOtherMetadata()}.
         *
         * @param removeOtherMetadata the value
         * @return this builder
         */
        public Builder removeOtherMetadata(boolean removeOtherMetadata) {
            this.removeOther = removeOtherMetadata;
            return this;
        }

        /**
         * Sets the resource bounds enforced while reading the image; see
         * {@link ImageMetadataEdit#limits()}.
         *
         * @param limits the value
         * @return this builder
         */
        public Builder limits(MediaLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        /**
         * Returns the edit holding the settings of this builder.
         *
         * @return the edit
         * @throws MediaException.Unsupported if the replacing color profile has no ICC form (a
         * high-dynamic-range profile)
         */
        public ImageMetadataEdit build() {
            if (colorProfile instanceof Change.Replace<ColorProfile>(var profile) && profile.isHighDynamicRange()) {
                throw new MediaException.Unsupported("icc", "a high-dynamic-range profile has no ICC form");
            }
            return new ImageMetadataEdit(this);
        }
    }
}
