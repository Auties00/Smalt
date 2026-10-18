package com.github.auties00.smalt.image.tiff.internal;

/**
 * Decodes ThunderScan 4-bit data (TIFF compression 32809).
 *
 * <p>Each row is coded on its own, starting from a previous pixel of zero. A code byte's top two
 * bits select a run of the previous pixel (count in the low 6 bits), three 2-bit deltas, two
 * 3-bit deltas, or one raw 4-bit pixel; the delta value 2 (2-bit) or 4 (3-bit) skips its pixel.
 *
 * @implNote This implementation is a port of libtiff's tif_thunder.c {@code ThunderDecode}.
 */
public final class ThunderScan {
    /**
     * The 2-bit delta values.
     */
    private static final int[] TWO_BIT = {0, 1, 0, -1};

    /**
     * The 3-bit delta values.
     */
    private static final int[] THREE_BIT = {0, 1, 2, 3, 0, -3, -2, -1};

    /**
     * Prevents instantiation.
     */
    private ThunderScan() {
        throw new AssertionError();
    }

    /**
     * Decodes rows of ThunderScan data into 4-bit samples, two per byte, high nibble first.
     *
     * @param input    the compressed bytes
     * @param length   the number of compressed bytes
     * @param output   the destination rows, zero-filled
     * @param rowBytes the length of one row
     * @param width    the number of pixels per row
     * @param rows     the number of rows
     * @return the number of bytes of complete rows produced
     */
    public static int decode(byte[] input, int length, byte[] output, int rowBytes, int width, int rows) {
        var in = 0;
        for (var row = 0; row < rows; row++) {
            var base = row * rowBytes;
            var last = 0;
            var count = 0;
            while (in < length && count < width) {
                var n = input[in++] & 0xFF;
                switch (n & 0xC0) {
                    case 0x00 -> {
                        var run = n & 0x3F;
                        for (var i = 0; i < run && count < width; i++) {
                            count = put(output, base, count, last);
                        }
                    }
                    case 0x40 -> {
                        for (var shift = 4; shift >= 0; shift -= 2) {
                            var delta = (n >> shift) & 3;
                            if (delta != 2 && count < width) {
                                last = (last + TWO_BIT[delta]) & 0xF;
                                count = put(output, base, count, last);
                            }
                        }
                    }
                    case 0x80 -> {
                        for (var shift = 3; shift >= 0; shift -= 3) {
                            var delta = (n >> shift) & 7;
                            if (delta != 4 && count < width) {
                                last = (last + THREE_BIT[delta]) & 0xF;
                                count = put(output, base, count, last);
                            }
                        }
                    }
                    default -> {
                        last = n & 0xF;
                        count = put(output, base, count, last);
                    }
                }
            }
            if (count < width) {
                return base;
            }
        }
        return rows * rowBytes;
    }

    /**
     * Writes one 4-bit pixel.
     *
     * @param output the destination
     * @param base   the index of the row
     * @param count  the number of pixels already in the row
     * @param value  the pixel
     * @return the new pixel count
     */
    private static int put(byte[] output, int base, int count, int value) {
        var at = base + (count >> 1);
        if ((count & 1) == 0) {
            output[at] = (byte) (value << 4);
        } else {
            output[at] |= (byte) value;
        }
        return count + 1;
    }
}
