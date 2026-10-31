package com.github.auties00.smalt.audio.gsm;

/**
 * The two byte layouts of GSM 06.10 frames: the 33-byte frames of RFC 3551 (the signature {@code 0xD}, then the
 * 260 parameter bits from the most significant bit of each byte) and Microsoft's 65-byte blocks of two frames (the
 * parameter bits of both from the least significant bit of each byte, the second frame starting at bit 260).
 *
 * <p>Arguments are not checked; values outside the documented ranges give unspecified results.
 */
final class GsmFrames {
    /**
     * The bytes of a frame of RFC 3551.
     */
    static final int FRAME_BYTES = 33;

    /**
     * The bytes of a block of two frames in Microsoft's layout.
     */
    static final int BLOCK_BYTES = 65;

    /**
     * The signature in the top four bits of a frame of RFC 3551.
     */
    static final int SIGNATURE = 0xD;

    private GsmFrames() {
    }

    /**
     * Reads the parameters of a frame of RFC 3551.
     *
     * @param data the bytes
     * @param offset the index of the frame's first byte
     * @param params receives the 76 parameters
     * @return whether the frame carries the signature
     */
    static boolean unpack(byte[] data, int offset, int[] params) {
        if ((data[offset] & 0xF0) >>> 4 != SIGNATURE) {
            return false;
        }
        var bit = (long) offset * 8 + 4;
        for (var i = 0; i < Gsm0610.PARAMETERS; i++) {
            var width = Gsm0610.WIDTHS[i];
            var value = 0;
            for (var b = 0; b < width; b++, bit++) {
                value = value << 1 | (data[(int) (bit >>> 3)] >>> (7 - (int) (bit & 7))) & 1;
            }
            params[i] = value;
        }
        return true;
    }

    /**
     * Writes the parameters of a frame of RFC 3551.
     *
     * @param params the 76 parameters
     * @param data receives the frame
     * @param offset the index of the frame's first byte
     */
    static void pack(int[] params, byte[] data, int offset) {
        data[offset] = (byte) (SIGNATURE << 4);
        for (var i = 1; i < FRAME_BYTES; i++) {
            data[offset + i] = 0;
        }
        var bit = (long) offset * 8 + 4;
        for (var i = 0; i < Gsm0610.PARAMETERS; i++) {
            var width = Gsm0610.WIDTHS[i];
            for (var b = width - 1; b >= 0; b--, bit++) {
                data[(int) (bit >>> 3)] |= (byte) (((params[i] >>> b) & 1) << (7 - (int) (bit & 7)));
            }
        }
    }

    /**
     * Reads the parameters of one frame of a block in Microsoft's layout.
     *
     * @param data the bytes
     * @param offset the index of the block's first byte
     * @param second whether to read the block's second frame
     * @param params receives the 76 parameters
     */
    static void unpackMs(byte[] data, int offset, boolean second, int[] params) {
        var bit = (long) offset * 8 + (second ? 260 : 0);
        for (var i = 0; i < Gsm0610.PARAMETERS; i++) {
            var width = Gsm0610.WIDTHS[i];
            var value = 0;
            for (var b = 0; b < width; b++, bit++) {
                value |= ((data[(int) (bit >>> 3)] >>> (int) (bit & 7)) & 1) << b;
            }
            params[i] = value;
        }
    }

    /**
     * Writes the parameters of one frame of a block in Microsoft's layout; the first frame clears the block.
     *
     * @param params the 76 parameters
     * @param data receives the block
     * @param offset the index of the block's first byte
     * @param second whether to write the block's second frame
     */
    static void packMs(int[] params, byte[] data, int offset, boolean second) {
        if (!second) {
            for (var i = 0; i < BLOCK_BYTES; i++) {
                data[offset + i] = 0;
            }
        }
        var bit = (long) offset * 8 + (second ? 260 : 0);
        for (var i = 0; i < Gsm0610.PARAMETERS; i++) {
            var width = Gsm0610.WIDTHS[i];
            for (var b = 0; b < width; b++, bit++) {
                data[(int) (bit >>> 3)] |= (byte) (((params[i] >>> b) & 1) << (int) (bit & 7));
            }
        }
    }
}
