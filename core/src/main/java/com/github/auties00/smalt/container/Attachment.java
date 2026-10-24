package com.github.auties00.smalt.container;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds a file embedded in a media file that is not a picture, such as a font a subtitle track
 * uses.
 *
 * <p>Attachments come from Matroska {@code AttachedFile} elements whose media type is not an
 * image; attached images are {@link AttachedPicture}s. The bytes are the stored file.
 *
 * <p>Instances are immutable and thread-safe; {@link #data()} returns a copy.
 */
public final class Attachment {
    private final String fileName;
    private final String mediaType;
    private final String description;

    /**
     * The file bytes, owned by this instance.
     */
    private final byte[] data;

    private Attachment(String fileName, String mediaType, String description, byte[] data) {
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.description = description;
        this.data = data;
    }

    /**
     * Returns an attachment of the given name and content.
     *
     * @param fileName the file name, not blank, for example {@code DejaVuSans.ttf}
     * @param mediaType the media type, not blank, for example {@code font/ttf}
     * @param description the description, or empty for none
     * @param data the file bytes, copied
     * @return the attachment
     * @throws IllegalArgumentException if the file name or the media type is blank
     */
    public static Attachment of(String fileName, String mediaType, String description, byte[] data) {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(mediaType, "mediaType");
        Objects.requireNonNull(description, "description");
        if (fileName.isBlank() || mediaType.isBlank()) {
            throw new IllegalArgumentException("an attachment needs a file name and a media type");
        }
        return new Attachment(fileName, mediaType, description, data.clone());
    }

    /**
     * {@return the file name}
     */
    public String fileName() {
        return fileName;
    }

    /**
     * {@return the media type}
     */
    public String mediaType() {
        return mediaType;
    }

    /**
     * {@return the description, possibly empty}
     */
    public String description() {
        return description;
    }

    /**
     * {@return a copy of the file bytes}
     */
    public byte[] data() {
        return data.clone();
    }

    /**
     * {@return the size of the file, in bytes}
     */
    public int size() {
        return data.length;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Attachment that && fileName.equals(that.fileName) && mediaType.equals(that.mediaType)
                && description.equals(that.description) && Arrays.equals(data, that.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileName, mediaType, description, Arrays.hashCode(data));
    }

    @Override
    public String toString() {
        return "Attachment[fileName=" + fileName + ", mediaType=" + mediaType + ", description=" + description
                + ", size=" + data.length + "]";
    }
}
