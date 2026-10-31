package com.github.auties00.smalt.probe;

/**
 * Describes the DXGI formats of a DDS file's DX10 header as the probe reports them.
 */
final class DdsFormats {
    private DdsFormats() {
        throw new AssertionError();
    }

    /**
     * {@return whether a DXGI format carries alpha}
     *
     * @param format the {@code DXGI_FORMAT} code
     */
    static boolean alpha(int format) {
        return switch (format) {
            case 1, 2, 3, 4, 9, 10, 11, 12, 13, 14, 23, 24, 25, 27, 28, 29, 30, 31, 32, 65, 70, 71, 72, 73, 74, 75,
                 76, 77, 78, 86, 87, 89, 90, 91, 97, 98, 99, 115 -> true;
            default -> false;
        };
    }

    /**
     * {@return the bits per sample a DXGI format decodes to: 32 for 32-bit floats and integers, 16 for half floats,
     * 16-bit and 10-bit integers and BC6H, 8 otherwise}
     *
     * @param format the {@code DXGI_FORMAT} code
     */
    static int bits(int format) {
        return switch (format) {
            case 1, 2, 3, 4, 5, 6, 7, 8, 15, 16, 17, 18, 39, 40, 41, 42, 43 -> 32;
            case 9, 10, 11, 12, 13, 14, 23, 24, 25, 26, 33, 34, 35, 36, 37, 38, 53, 54, 55, 56, 57, 58, 59, 67, 89,
                 94, 95, 96 -> 16;
            default -> 8;
        };
    }
}
