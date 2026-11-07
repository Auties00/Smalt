package com.github.auties00.smalt.video.ffv1.internal;

/**
 * The constant tables of RFC 9043: the state transition tables of the range coder, the run lengths of the Golomb
 * Rice run mode and the CRC of the configuration record and the slices.
 */
public final class Ffv1Tables {
    /**
     * The number of states of one context of the range coder ({@code CONTEXT_SIZE}).
     */
    public static final int CONTEXT_SIZE = 32;

    /**
     * The number of quantized sample differences of a context ({@code MAX_CONTEXT_INPUTS}).
     */
    public static final int CONTEXT_INPUTS = 5;

    /**
     * The largest context count of a quantization table set.
     */
    public static final int MAX_CONTEXTS = 32768;

    /**
     * The largest number of quantization table sets.
     */
    public static final int MAX_QUANT_SETS = 8;

    /**
     * The default state transition table for a decoded 1 (RFC 9043, figure 24).
     */
    static final int[] DEFAULT_ONE_STATE = {
            0, 0, 0, 0, 0, 0, 0, 0, 20, 21, 22, 23, 24, 25, 26, 27,
            28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 37, 38, 39, 40, 41, 42,
            43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 56, 57,
            58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73,
            74, 75, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88,
            89, 90, 91, 92, 93, 94, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103,
            104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 114, 115, 116, 117, 118,
            119, 120, 121, 122, 123, 124, 125, 126, 127, 128, 129, 130, 131, 132, 133, 133,
            134, 135, 136, 137, 138, 139, 140, 141, 142, 143, 144, 145, 146, 147, 148, 149,
            150, 151, 152, 152, 153, 154, 155, 156, 157, 158, 159, 160, 161, 162, 163, 164,
            165, 166, 167, 168, 169, 170, 171, 171, 172, 173, 174, 175, 176, 177, 178, 179,
            180, 181, 182, 183, 184, 185, 186, 187, 188, 189, 190, 190, 191, 192, 194, 194,
            195, 196, 197, 198, 199, 200, 201, 202, 202, 204, 205, 206, 207, 208, 209, 209,
            210, 211, 212, 213, 215, 215, 216, 217, 218, 219, 220, 220, 222, 223, 224, 225,
            226, 227, 227, 229, 229, 230, 231, 232, 234, 234, 235, 236, 237, 238, 239, 240,
            241, 242, 243, 244, 245, 246, 247, 248, 248, 0, 0, 0, 0, 0, 0, 0
    };

    /**
     * The default state transition table for a decoded 0.
     */
    static final int[] DEFAULT_ZERO_STATE = zeroStates(DEFAULT_ONE_STATE);

    /**
     * The alternative state transition table for a decoded 1 (RFC 9043, figure 25), which the encoder stores as
     * the custom table of coder type 2.
     */
    static final int[] ALTERNATIVE_ONE_STATE = {
            0, 10, 10, 10, 10, 16, 16, 16, 28, 16, 16, 29, 42, 49, 20, 49,
            59, 25, 26, 26, 27, 31, 33, 33, 33, 34, 34, 37, 67, 38, 39, 39,
            40, 40, 41, 79, 43, 44, 45, 45, 48, 48, 64, 50, 51, 52, 88, 52,
            53, 74, 55, 57, 58, 58, 74, 60, 101, 61, 62, 84, 66, 66, 68, 69,
            87, 82, 71, 97, 73, 73, 82, 75, 111, 77, 94, 78, 87, 81, 83, 97,
            85, 83, 94, 86, 99, 89, 90, 99, 111, 92, 93, 134, 95, 98, 105, 98,
            105, 110, 102, 108, 102, 118, 103, 106, 106, 113, 109, 112, 114, 112, 116, 125,
            115, 116, 117, 117, 126, 119, 125, 121, 121, 123, 145, 124, 126, 131, 127, 129,
            165, 130, 132, 138, 133, 135, 145, 136, 137, 139, 146, 141, 143, 142, 144, 148,
            147, 155, 151, 149, 151, 150, 152, 157, 153, 154, 156, 168, 158, 162, 161, 160,
            172, 163, 169, 164, 166, 184, 167, 170, 177, 174, 171, 173, 182, 176, 180, 178,
            175, 189, 179, 181, 186, 183, 192, 185, 200, 187, 191, 188, 190, 197, 193, 196,
            197, 194, 195, 196, 198, 202, 199, 201, 210, 203, 207, 204, 205, 206, 208, 214,
            209, 211, 221, 212, 213, 215, 224, 216, 217, 218, 219, 220, 222, 228, 223, 225,
            226, 224, 227, 229, 240, 230, 231, 232, 233, 234, 235, 236, 238, 239, 237, 242,
            241, 243, 242, 244, 245, 246, 247, 248, 249, 250, 251, 252, 252, 253, 254, 255
    };

    /**
     * The binary logarithm of the run lengths of the Golomb Rice run mode, by run index ({@code log2_run}).
     */
    static final int[] LOG2_RUN = {
            0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
            4, 4, 5, 5, 6, 6, 7, 7, 8, 9, 10, 11, 12, 13, 14, 15,
            16, 17, 18, 19, 20, 21, 22, 23, 24
    };

    /**
     * The CRC-32 remainders of every byte, polynomial {@code 0x04C11DB7}, most significant bit first.
     */
    private static final int[] CRC = new int[256];

    static {
        for (var i = 0; i < 256; i++) {
            var c = i << 24;
            for (var bit = 0; bit < 8; bit++) {
                c = c < 0 ? c << 1 ^ 0x04C11DB7 : c << 1;
            }
            CRC[i] = c;
        }
    }

    private Ffv1Tables() {
    }

    /**
     * Returns the state transition table for a decoded 0 that pairs with a table for a decoded 1.
     *
     * @param one the table for a decoded 1, 256 entries
     * @return the table for a decoded 0 ({@code zero_state_i = 256 - one_state_(256 - i)}, entry 0 being 0)
     */
    static int[] zeroStates(int[] one) {
        var zero = new int[256];
        for (var i = 1; i < 256; i++) {
            zero[i] = 256 - one[256 - i] & 0xFF;
        }
        return zero;
    }

    /**
     * Computes the CRC of a range of bytes: generator {@code 0x104C11DB7}, initial value 0, no inversion.
     *
     * @param data the bytes
     * @param offset the first byte
     * @param length the number of bytes
     * @return the remainder; 0 for a range that ends with its own parity
     */
    public static int crc(byte[] data, int offset, int length) {
        var c = 0;
        for (int i = offset, end = offset + length; i < end; i++) {
            c = c << 8 ^ CRC[(c >>> 24 ^ data[i]) & 0xFF];
        }
        return c;
    }
}
