package com.github.auties00.smalt.image.raw.internal;

/**
 * Decodes the rows of Phase One's sensor data: IIQ L (compressions 3 and 8) and the tone-coded IIQ S of the older
 * backs (compression 5).
 *
 * <p>A row is a stream of little-endian 32-bit words whose bits are read from the most significant one. It codes
 * groups of eight photosites, the even and the odd columns separately: each group starts with the width of the even
 * columns' numbers and that of the odd columns', a prefix code, then come its eight numbers in column order.
 * A number of width 16 is the photosite's value; one of a smaller width {@code w}, less
 * {@code 2^(w - 1) - 1}, is added to the value two columns before. The width codes are:
 * {@snippet :
 * 1        the width of the group before     0010     6
 * 011      7                                 00011    10
 * 010      8                                 00010    11
 * 0011     9                                 000011   12
 * 000010   5                                 000001   13
 * 000000   16
 * }
 * The photosites after a row's last whole group, when its width is not a multiple of eight, are 16-bit values with no
 * width codes.
 *
 * @implNote This implementation's coding was derived from CC0 sample files against the photosites of Adobe DNG
 * Converter, run as a black box: every row of the samples ends where the file's table of rows says it does.
 */
final class IiqDecoder {
    /**
     * Prevents instantiation.
     */
    private IiqDecoder() {
        throw new AssertionError();
    }

    /**
     * Decodes a row. Bits past the end of the data read as zeros.
     *
     * @param data the sensor data
     * @param offset the row's offset
     * @param width the photosites of the row
     * @param multiplier what the numbers are multiplied by
     * @param curve the values of the numbers, or {@code null}
     * @param out the photosites
     * @param base the index of the row's first photosite
     */
    static void row(byte[] data, int offset, int width, int multiplier, int[] curve, int[] out, int base) {
        var position = offset;
        var buffer = 0L;
        var available = 0;
        int widthEven = 5, widthOdd = 5, even = 0, odd = 0;
        for (var x = 0; x < width; ) {
            if (width - x < 8) {
                for (; x < width; x++) {
                    if (available < 16) {
                        buffer = buffer << 32 | word(data, position);
                        position += 4;
                        available += 32;
                    }
                    available -= 16;
                    var value = (int) (buffer >>> available) & 0xFFFF;
                    out[base + x] = curve == null ? value * multiplier : curve[Math.min(value, curve.length - 1)];
                }
                break;
            }
            for (var lane = 0; lane < 2; lane++) {
                if (available < 16) {
                    buffer = buffer << 32 | word(data, position);
                    position += 4;
                    available += 32;
                }
                var head = (int) (buffer >>> (available - 6)) & 0x3F;
                int used, coded;
                if (head >= 32) {
                    used = 1;
                    coded = lane == 0 ? widthEven : widthOdd;
                } else if (head >= 16) {
                    used = 3;
                    coded = head >= 24 ? 7 : 8;
                } else if (head >= 8) {
                    used = 4;
                    coded = head >= 12 ? 9 : 6;
                } else if (head >= 4) {
                    used = 5;
                    coded = head >= 6 ? 10 : 11;
                } else {
                    used = 6;
                    coded = head == 3 ? 12 : head == 2 ? 5 : head == 1 ? 13 : 16;
                }
                available -= used;
                if (lane == 0) {
                    widthEven = coded;
                } else {
                    widthOdd = coded;
                }
            }
            for (var index = 0; index < 8 && x < width; index++, x++) {
                var bits = (index & 1) == 0 ? widthEven : widthOdd;
                if (available < bits) {
                    buffer = buffer << 32 | word(data, position);
                    position += 4;
                    available += 32;
                }
                available -= bits;
                var number = (int) (buffer >>> available) & ((1 << bits) - 1);
                int value;
                if ((index & 1) == 0) {
                    even = bits == 16 ? number : even + number - ((1 << (bits - 1)) - 1);
                    value = even;
                } else {
                    odd = bits == 16 ? number : odd + number - ((1 << (bits - 1)) - 1);
                    value = odd;
                }
                out[base + x] = curve == null ? value * multiplier
                        : curve[value < 0 ? 0 : value >= curve.length ? curve.length - 1 : value];
            }
        }
    }

    /**
     * {@return the little-endian word at an offset, zero past the data's end}
     *
     * @param data the bytes to read from
     * @param position the offset
     */
    private static long word(byte[] data, int position) {
        if (position < 0 || position > data.length - 4) {
            return 0;
        }
        return (data[position] & 0xFFL) | (data[position + 1] & 0xFFL) << 8 | (data[position + 2] & 0xFFL) << 16
                | (data[position + 3] & 0xFFL) << 24;
    }
}
