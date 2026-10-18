package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Reads the dimensions of an embedded JPEG stream from its frame header, without decoding it.
 */
final class JpegSize {
    /**
     * The most bytes scanned for the frame header.
     */
    private static final int MAX_SCAN = 1 << 18;

    /**
     * Prevents instantiation.
     */
    private JpegSize() {
        throw new AssertionError();
    }

    /**
     * Reads the size of a baseline, extended or progressive DCT JPEG stream.
     *
     * @param reader any reader over the file
     * @param offset the absolute offset of the stream
     * @param length the length of the stream
     * @return the width and height, or {@code null} when the bytes are not a DCT JPEG stream or its
     * frame header was not found
     */
    static int[] read(TiffReader reader, long offset, long length) {
        return read(reader, offset, length, false);
    }

    /**
     * Reads the size of a baseline, extended or progressive DCT JPEG stream, optionally reading its
     * first byte as {@code 0xFF}.
     *
     * @param reader any reader over the file
     * @param offset the absolute offset of the stream
     * @param length the length of the stream
     * @param damagedStart whether the stream's first byte is to be read as {@code 0xFF}
     * @return the width and height, or {@code null} when the bytes are not a DCT JPEG stream or its
     * frame header was not found
     */
    static int[] read(TiffReader reader, long offset, long length, boolean damagedStart) {
        var data = new byte[(int) Math.min(length, MAX_SCAN)];
        var available = reader.readAvailable(offset, data, 0, data.length);
        if (damagedStart && available > 0) {
            data[0] = (byte) 0xFF;
        }
        if (available < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
            return null;
        }
        var position = 2;
        while (position + 4 <= available) {
            if ((data[position] & 0xFF) != 0xFF) {
                return null;
            }
            var marker = data[position + 1] & 0xFF;
            if (marker == 0xFF) {
                position++;
                continue;
            }
            var segment = ByteArrays.u16be(data, position + 2);
            if (marker == 0xC0 || marker == 0xC1 || marker == 0xC2) {
                if (position + 9 > available) {
                    return null;
                }
                var height = ByteArrays.u16be(data, position + 5);
                var width = ByteArrays.u16be(data, position + 7);
                return width > 0 && height > 0 ? new int[]{width, height} : null;
            }
            if ((marker >= 0xC3 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC)
                    || marker == 0xDA || marker == 0xD9 || segment < 2) {
                return null;
            }
            position += 2 + segment;
        }
        return null;
    }
}
