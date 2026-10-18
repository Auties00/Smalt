package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Corrects the image offsets of a Multi-Picture Format index (CIPA DC-007), the {@code APP2 MPF}
 * segment of the JPEG files that carry secondary images (depth maps, gain maps, stereo pairs)
 * after the end of the primary image.
 *
 * <p>The index is a TIFF structure whose first directory holds the {@code MPEntry} tag
 * ({@code 0xB002}): sixteen bytes per image, the third field being the offset of the image
 * relative to the TIFF header, zero for the primary image.
 */
final class MultiPicture {
    /**
     * The tag of the image entries.
     */
    private static final int MP_ENTRY = 0xB002;

    /**
     * Prevents instantiation.
     */
    private MultiPicture() {
        throw new AssertionError();
    }

    /**
     * Shifts the non-zero image offsets of an index in place.
     *
     * @param segment the segment bytes
     * @param header the index of the TIFF header within the segment
     * @param delta the change in distance between the header and the secondary images
     * @return {@code true} when the entries were found and shifted
     */
    static boolean shift(byte[] segment, int header, long delta) {
        if (header + 8 > segment.length) {
            return false;
        }
        boolean little;
        if (segment[header] == 'I' && segment[header + 1] == 'I') {
            little = true;
        } else if (segment[header] == 'M' && segment[header + 1] == 'M') {
            little = false;
        } else {
            return false;
        }
        var ifd = header + ByteArrays.u32(segment, header + 4, little);
        if (ifd < header || ifd + 2 > segment.length) {
            return false;
        }
        var count = ByteArrays.u16(segment, (int) ifd, little);
        for (var i = 0; i < count; i++) {
            var entry = (int) ifd + 2 + 12 * i;
            if (entry + 12 > segment.length) {
                return false;
            }
            if (ByteArrays.u16(segment, entry, little) != MP_ENTRY) {
                continue;
            }
            var length = ByteArrays.u32(segment, entry + 4, little);
            var at = header + ByteArrays.u32(segment, entry + 8, little);
            if (length % 16 != 0 || at < header || at + length > segment.length) {
                return false;
            }
            for (var image = 0; image < length / 16; image++) {
                var field = (int) at + image * 16 + 8;
                var offset = ByteArrays.u32(segment, field, little);
                if (offset != 0) {
                    ByteArrays.put32(segment, field, offset + delta, little);
                }
            }
            return true;
        }
        return false;
    }
}
