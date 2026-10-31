package com.github.auties00.smalt.audio.dts.internal;

/**
 * Recognizes the four packings of the DTS core sync word (16-bit or 14-bit words, big or little endian) and
 * converts the 14-bit and little-endian packings to the plain big-endian bit stream the decoders read.
 *
 * <p>14-bit packings carry 14 bits of the stream in every 16-bit word, the top two bits copying the sign of the
 * third, as DTS audio CDs store it in 16-bit PCM.
 */
public final class DtsFraming {
    /**
     * The big-endian 16-bit packing: the plain bit stream.
     */
    public static final int BIG_ENDIAN_16 = 0;

    /**
     * The little-endian 16-bit packing: the bytes of every 16-bit word swapped.
     */
    public static final int LITTLE_ENDIAN_16 = 1;

    /**
     * The big-endian 14-bit packing.
     */
    public static final int BIG_ENDIAN_14 = 2;

    /**
     * The little-endian 14-bit packing.
     */
    public static final int LITTLE_ENDIAN_14 = 3;

    /**
     * The extension substream sync word.
     */
    public static final int EXSS_SYNC = 0x64582025;

    /**
     * Prevents instantiation.
     */
    private DtsFraming() {
    }

    /**
     * {@return the big-endian 32-bit word at a position}
     *
     * @param data the bytes
     * @param offset the position; four bytes must be available
     */
    public static int readInt(byte[] data, int offset) {
        return CoreDecoder.readInt(data, offset);
    }

    /**
     * Returns the packing of a core sync word at a position, or {@code -1}.
     *
     * @param data the bytes
     * @param offset the position
     * @param length the number of bytes available from the position
     * @return the packing, or {@code -1} if no core sync word starts there
     */
    public static int packing(byte[] data, int offset, int length) {
        if (length < 6) {
            return -1;
        }
        var word = CoreDecoder.readInt(data, offset);
        var next = (data[offset + 4] & 0xFF) << 8 | data[offset + 5] & 0xFF;
        if (word == CoreHeader.SYNC) {
            return BIG_ENDIAN_16;
        }
        if (word == 0xFE7F0180) {
            return LITTLE_ENDIAN_16;
        }
        if (word == 0x1FFFE800 && (next & 0xFFF0) == 0x07F0) {
            return BIG_ENDIAN_14;
        }
        if (word == 0xFF1F00E8 && (next & 0xF0FF) == 0xF007) {
            return LITTLE_ENDIAN_14;
        }
        return -1;
    }

    /**
     * Converts a run of bytes of a packing to the plain bit stream.
     *
     * @param data the bytes
     * @param offset the index of the first byte, at a 16-bit word
     * @param length the number of bytes, even
     * @param packing the packing of the bytes, one of the constants of this class
     * @param out receives the plain bytes; at least {@code length} bytes
     * @return the number of plain bytes written
     */
    public static int unpack(byte[] data, int offset, int length, int packing, byte[] out) {
        if (packing == BIG_ENDIAN_16) {
            System.arraycopy(data, offset, out, 0, length);
            return length;
        }
        var words = length >> 1;
        if (packing == LITTLE_ENDIAN_16) {
            for (var i = 0; i < words; i++) {
                out[2 * i] = data[offset + 2 * i + 1];
                out[2 * i + 1] = data[offset + 2 * i];
            }
            return words * 2;
        }
        var little = packing == LITTLE_ENDIAN_14;
        long accumulator = 0;
        var bits = 0;
        var written = 0;
        for (var i = 0; i < words; i++) {
            var hi = data[offset + 2 * i + (little ? 1 : 0)] & 0xFF;
            var lo = data[offset + 2 * i + (little ? 0 : 1)] & 0xFF;
            accumulator = accumulator << 14 | ((hi << 8 | lo) & 0x3FFF);
            bits += 14;
            while (bits >= 8) {
                bits -= 8;
                out[written++] = (byte) (accumulator >>> bits);
            }
        }
        return written;
    }

    /**
     * {@return the number of packed bytes that hold a run of plain bytes}
     *
     * @param plain the number of plain bytes
     * @param packing the packing of the stream, one of the constants of this class
     */
    public static int packedLength(int plain, int packing) {
        if (packing < BIG_ENDIAN_14) {
            return plain;
        }
        return (plain * 8 + 13) / 14 * 2;
    }
}
