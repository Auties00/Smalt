package com.github.auties00.smalt.image.jpeg.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Holds one application or comment marker segment copied from a source file.
 *
 * @param marker the marker code, {@code 0xE0} to {@code 0xEF} or {@code 0xFE}
 * @param data the bytes after the length field; shared, not copied
 */
record MarkerSegment(int marker, byte[] data) {
    /**
     * {@return whether the data starts with an ASCII signature}
     *
     * @param signature the ASCII text to look for
     */
    boolean startsWith(String signature) {
        var bytes = signature.getBytes(StandardCharsets.ISO_8859_1);
        return data.length >= bytes.length && Arrays.equals(data, 0, bytes.length, bytes, 0, bytes.length);
    }

    /**
     * {@return whether the segment is an {@code APP1} segment holding EXIF data, as
     * {@link JpegExif#prefixLength} recognizes it}
     */
    boolean isExif() {
        return marker == 0xE1 && JpegExif.prefixLength(data, 0, data.length) != 0;
    }
}
