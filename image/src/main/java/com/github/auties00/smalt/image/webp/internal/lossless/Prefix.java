package com.github.auties00.smalt.image.webp.internal.lossless;

/**
 * Codes LZ77 lengths and distances as prefix symbols with extra bits, and maps distances to the
 * short plane codes of the 2D distance map.
 *
 * <p>A value {@code v} of at least 3 is coded with prefix {@code 2 * h + s} and {@code h - 1}
 * extra bits, where {@code h} is the index of the highest set bit of {@code v - 1} and {@code s}
 * the bit below it; values 1 and 2 use prefixes 0 and 1 with no extra bits (RFC 9649, section
 * 3.2.1).
 */
final class Prefix {
    /**
     * The plane code (minus one) of every offset within 8 pixels left or right and 7 rows up:
     * index {@code yoffset * 16 + 8 - xoffset}, 255 where unused (from libwebp's
     * {@code plane_to_code_lut}).
     */
    private static final int[] PLANE_TO_CODE_LUT = {
            96, 73, 55, 39, 23, 13, 5, 1, 255, 255, 255, 255, 255, 255, 255, 255,
            101, 78, 58, 42, 26, 16, 8, 2, 0, 3, 9, 17, 27, 43, 59, 79,
            102, 86, 62, 46, 32, 20, 10, 6, 4, 7, 11, 21, 33, 47, 63, 87,
            105, 90, 70, 52, 37, 28, 18, 14, 12, 15, 19, 29, 38, 53, 71, 91,
            110, 99, 82, 66, 48, 35, 30, 24, 22, 25, 31, 36, 49, 67, 83, 100,
            115, 108, 94, 76, 64, 50, 44, 40, 34, 41, 45, 51, 65, 77, 95, 109,
            118, 113, 103, 92, 80, 68, 60, 56, 54, 57, 61, 69, 81, 93, 104, 114,
            119, 116, 111, 106, 97, 88, 84, 74, 72, 75, 85, 89, 98, 107, 112, 117
    };

    /**
     * Prevents instantiation.
     */
    private Prefix() {
        throw new AssertionError();
    }

    /**
     * Returns the prefix symbol of a length or distance.
     *
     * @param value the value; values up to 1 map to prefix 0
     * @return the prefix symbol
     */
    static int code(int value) {
        if (value <= 2) {
            return value == 2 ? 1 : 0;
        }
        var d = value - 1;
        var highest = 31 - Integer.numberOfLeadingZeros(d);
        var second = (d >>> (highest - 1)) & 1;
        return 2 * highest + second;
    }

    /**
     * Returns the number of extra bits of a length or distance.
     *
     * @param value the value, at least 1
     * @return the extra bit count
     */
    static int extraBits(int value) {
        if (value <= 2) {
            return 0;
        }
        return 30 - Integer.numberOfLeadingZeros(value - 1);
    }

    /**
     * Returns the extra bits value of a length or distance.
     *
     * @param value the value, at least 1
     * @return the extra bits
     */
    static int extraValue(int value) {
        if (value <= 2) {
            return 0;
        }
        var d = value - 1;
        return d & ((1 << extraBits(value)) - 1);
    }

    /**
     * Maps a linear distance to its distance code: a short plane code when the offset is close
     * to the current pixel, the distance plus 120 otherwise.
     *
     * @param xsize the image width
     * @param dist  the linear distance, at least 1
     * @return the distance code, at least 1
     */
    static int distanceToPlaneCode(int xsize, int dist) {
        var yoffset = dist / xsize;
        var xoffset = dist - yoffset * xsize;
        if (xoffset <= 8 && yoffset < 8) {
            return PLANE_TO_CODE_LUT[yoffset * 16 + 8 - xoffset] + 1;
        } else if (xoffset > xsize - 8 && yoffset < 7) {
            return PLANE_TO_CODE_LUT[(yoffset + 1) * 16 + 8 + (xsize - xoffset)] + 1;
        }
        return dist + 120;
    }
}
