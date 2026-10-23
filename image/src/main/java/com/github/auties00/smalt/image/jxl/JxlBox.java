package com.github.auties00.smalt.image.jxl;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds a box of a JPEG XL container other than the codestream (ISO/IEC 18181-2): metadata such as an
 * {@code Exif}, {@code xml } (XMP), {@code jumb} (JUMBF) or {@code jhgm} (gain map) box, as
 * {@link JxlDecoder#readBoxes(com.github.auties00.smalt.io.MediaSource) readBoxes} lists it.
 *
 * <p>A box stored Brotli-compressed in a {@code brob} box is presented as the box it compresses, with its
 * content decompressed and {@link #compressed()} set.
 *
 * <p>Two boxes are equal when they have the same type, the same compression flag and contents of equal bytes.
 * Instances are not thread-safe: the content array is shared, not copied.
 *
 * @param type the four-character box type, for example {@code "Exif"} or {@code "xml "}; for a box stored in a
 * {@code brob} box, the type of the box it compresses
 * @param content the payload of the box, decompressed when it was stored in a {@code brob} box
 * @param compressed whether the box was stored Brotli-compressed in a {@code brob} box
 */
public record JxlBox(String type, byte[] content, boolean compressed) {
    /**
     * Validates the box.
     *
     * @throws IllegalArgumentException if the type is not four characters long
     */
    public JxlBox {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(content, "content");
        if (type.length() != 4) {
            throw new IllegalArgumentException("a box type has four characters: " + type);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof JxlBox that && type.equals(that.type) && compressed == that.compressed
                && Arrays.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return (type.hashCode() * 31 + Arrays.hashCode(content)) * 31 + Boolean.hashCode(compressed);
    }

    @Override
    public String toString() {
        return "JxlBox[type=" + type + ", content=" + content.length + " bytes, compressed=" + compressed + "]";
    }
}
