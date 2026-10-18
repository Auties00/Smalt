package com.github.auties00.smalt.image.jpeg.internal;

/**
 * Recognizes the identifier that starts the payload of an {@code APP1} segment holding EXIF data,
 * the one rule for every JPEG reader and writer of this module.
 *
 * <p>The identifier is {@code "Exif\0"} followed by a pad byte, {@code 0x00} as the EXIF
 * specification writes it or {@code 0xFF} as some cameras write it; the TIFF structure follows it.
 * This class is thread-safe.
 */
public final class JpegExif {
    /**
     * The length of the identifier.
     */
    public static final int PREFIX = 6;

    /**
     * Prevents instantiation.
     */
    private JpegExif() {
        throw new AssertionError();
    }

    /**
     * {@return the length of the EXIF identifier at the start of a segment payload: {@value #PREFIX}
     * when present, otherwise 0}
     *
     * @param data the array holding the payload
     * @param offset the index of the payload's first byte
     * @param length the payload length
     */
    public static int prefixLength(byte[] data, int offset, int length) {
        if (length < PREFIX || data[offset] != 'E' || data[offset + 1] != 'x' || data[offset + 2] != 'i'
                || data[offset + 3] != 'f' || data[offset + 4] != 0) {
            return 0;
        }
        var pad = data[offset + 5];
        return pad == 0 || pad == (byte) 0xFF ? PREFIX : 0;
    }
}
