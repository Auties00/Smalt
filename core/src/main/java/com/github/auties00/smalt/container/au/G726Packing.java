package com.github.auties00.smalt.container.au;

/**
 * Converts G.726 codes between the packing of AU files, from the least significant bit of each byte, and the
 * packing of {@link com.github.auties00.smalt.track.Codec#ADPCM_G726} packets, from the most significant bit.
 *
 * <p>Codes are converted in units of the bytes that hold a whole number of them: one byte of 2 or 4-bit codes, three
 * or five bytes of eight 3 or 5-bit codes. A partial unit at the end keeps the codes that fit in it, whole, in their
 * order, and zero bits after them.
 */
final class G726Packing {
    /**
     * Prevents instantiation.
     */
    private G726Packing() {
        throw new AssertionError();
    }

    /**
     * Repacks codes stored from the least significant bit of each byte from the most significant bit, in place.
     *
     * @param data the codes
     * @param length the number of bytes of {@code data} to convert
     * @param bits the code size, from 2 to 5
     */
    static void toMsbFirst(byte[] data, int length, int bits) {
        convert(data, length, bits, true);
    }

    /**
     * Repacks codes stored from the most significant bit of each byte from the least significant bit, in place.
     *
     * @param data the codes
     * @param length the number of bytes of {@code data} to convert
     * @param bits the code size, from 2 to 5
     */
    static void toLsbFirst(byte[] data, int length, int bits) {
        convert(data, length, bits, false);
    }

    /**
     * Repacks codes in place.
     *
     * @param data the codes
     * @param length the number of bytes to convert
     * @param bits the code size, from 2 to 5
     * @param toMsb whether the codes go from the least significant bit first to the most significant bit first
     */
    private static void convert(byte[] data, int length, int bits, boolean toMsb) {
        var unit = bits == 3 || bits == 5 ? bits : 1;
        var mask = (1 << bits) - 1;
        for (var start = 0; start < length; start += unit) {
            var bytes = Math.min(unit, length - start);
            var codes = bytes * 8 / bits;
            var value = 0L;
            for (var index = 0; index < bytes; index++) {
                value |= (long) (data[start + index] & 0xFF) << (toMsb ? 8 * index : 8 * (unit - 1 - index));
            }
            var result = 0L;
            for (var code = 0; code < codes; code++) {
                long field;
                if (toMsb) {
                    field = value >>> (bits * code) & mask;
                    result |= field << (8 * unit - bits * (code + 1));
                } else {
                    field = value >>> (8 * unit - bits * (code + 1)) & mask;
                    result |= field << (bits * code);
                }
            }
            for (var index = 0; index < bytes; index++) {
                data[start + index] = (byte) (toMsb ? result >>> (8 * (unit - 1 - index)) : result >>> (8 * index));
            }
        }
    }
}
