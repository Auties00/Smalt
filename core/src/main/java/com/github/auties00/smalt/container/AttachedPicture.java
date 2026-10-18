package com.github.auties00.smalt.container;

import com.github.auties00.smalt.image.ImageFormat;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Holds a picture embedded in a media file, such as the cover art of an album.
 *
 * <p>Pictures come from MP4 {@code covr} items, ID3v2 {@code APIC} frames, FLAC {@code PICTURE}
 * blocks, Vorbis comment {@code METADATA_BLOCK_PICTURE} fields and Matroska attachments whose
 * media type is an image. The bytes are the stored image file, not decoded pixels; decode them
 * with {@code Images.decode}.
 *
 * <p>Instances are immutable and thread-safe; {@link #data()} returns a copy.
 */
public final class AttachedPicture {
    private final Type type;
    private final String mediaType;
    private final String description;

    /**
     * The image file bytes, owned by this instance.
     */
    private final byte[] data;

    private AttachedPicture(Type type, String mediaType, String description, byte[] data) {
        this.type = type;
        this.mediaType = mediaType;
        this.description = description;
        this.data = data;
    }

    /**
     * Returns a picture of the given role and content.
     *
     * @param type the role of the picture
     * @param mediaType the declared media type, for example {@code image/jpeg}, or empty when the container declares
     * none
     * @param description the description, or empty when the container stores none
     * @param data the image file bytes, copied
     * @return the picture
     */
    public static AttachedPicture of(Type type, String mediaType, String description, byte[] data) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(description, "description");
        return new AttachedPicture(type, mediaType, description, data.clone());
    }

    /**
     * {@return the role of the picture}
     */
    public Type type() {
        return type;
    }

    /**
     * {@return the media type the container declares, empty when it declares none}
     */
    public String mediaType() {
        return mediaType;
    }

    /**
     * {@return the description the container stores, possibly empty}
     */
    public String description() {
        return description;
    }

    /**
     * {@return a copy of the image file bytes}
     */
    public byte[] data() {
        return data.clone();
    }

    /**
     * {@return the size of the image file, in bytes}
     */
    public int size() {
        return data.length;
    }

    /**
     * Recognizes the image format from the leading bytes, ignoring the declared media type.
     *
     * @return the format, or empty when the bytes match no format {@link ImageFormat#detect} knows
     */
    public Optional<ImageFormat> format() {
        return ImageFormat.detect(data, data.length);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof AttachedPicture that && type == that.type && mediaType.equals(that.mediaType)
                && description.equals(that.description) && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, mediaType, description, Arrays.hashCode(data));
    }

    @Override
    public String toString() {
        return "AttachedPicture[type=" + type + ", mediaType=" + mediaType + ", description=" + description
                + ", size=" + data.length + "]";
    }

    /**
     * Enumerates the roles a picture plays, as ID3v2 {@code APIC} and FLAC {@code PICTURE} number
     * them.
     *
     * <p>This enum may gain constants in later releases; a {@code switch} over it needs a
     * {@code default} branch.
     */
    public enum Type {
        /**
         * Another picture (code 0).
         */
        OTHER,

        /**
         * A 32 by 32 pixel PNG file icon (code 1).
         */
        FILE_ICON,

        /**
         * Another file icon (code 2).
         */
        OTHER_FILE_ICON,

        /**
         * The front cover (code 3); also every MP4 {@code covr} item and a Matroska attachment
         * named like a cover.
         */
        FRONT_COVER,

        /**
         * The back cover (code 4).
         */
        BACK_COVER,

        /**
         * A leaflet page (code 5).
         */
        LEAFLET_PAGE,

        /**
         * The media itself, such as the label side of a CD (code 6).
         */
        MEDIA,

        /**
         * The lead artist or performer (code 7).
         */
        LEAD_ARTIST,

        /**
         * An artist or performer (code 8).
         */
        ARTIST,

        /**
         * The conductor (code 9).
         */
        CONDUCTOR,

        /**
         * The band or orchestra (code 10).
         */
        BAND,

        /**
         * The composer (code 11).
         */
        COMPOSER,

        /**
         * The lyricist or text writer (code 12).
         */
        LYRICIST,

        /**
         * The recording location (code 13).
         */
        RECORDING_LOCATION,

        /**
         * A picture taken during recording (code 14).
         */
        DURING_RECORDING,

        /**
         * A picture taken during performance (code 15).
         */
        DURING_PERFORMANCE,

        /**
         * A screen capture of a film or video (code 16).
         */
        SCREEN_CAPTURE,

        /**
         * A bright colored fish (code 17).
         */
        BRIGHT_COLORED_FISH,

        /**
         * An illustration (code 18).
         */
        ILLUSTRATION,

        /**
         * The band or artist logotype (code 19).
         */
        BAND_LOGOTYPE,

        /**
         * The publisher or studio logotype (code 20).
         */
        PUBLISHER_LOGOTYPE;

        /**
         * Returns the role an ID3v2 or FLAC picture type code names.
         *
         * @param code the code, from 0 to 20
         * @return the role, or {@link #OTHER} for an unknown code
         */
        public static Type of(int code) {
            var values = values();
            return code >= 0 && code < values.length ? values[code] : OTHER;
        }

        /**
         * {@return the ID3v2 and FLAC code of this role, from 0 to 20}
         */
        public int code() {
            return ordinal();
        }
    }
}
