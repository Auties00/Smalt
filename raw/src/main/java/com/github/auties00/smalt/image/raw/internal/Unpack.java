package com.github.auties00.smalt.image.raw.internal;

/**
 * Unpacks bit-packed unsigned samples into 16-bit values: the uncompressed layouts of TIFF and of
 * the vendor raw formats.
 *
 * <p>Every method reads at most up to its {@code end} index and leaves the samples it cannot read
 * untouched, so a truncated file decodes to its end without failing.
 */
final class Unpack {
    /**
     * Prevents instantiation.
     */
    private Unpack() {
        throw new AssertionError();
    }

    /**
     * Unpacks samples stored most significant bit first, back to back (TIFF fill order 1).
     *
     * @param data the bytes
     * @param start the index of the first byte
     * @param end the index after the last readable byte
     * @param bits the bits of each sample, from 1 to 16
     * @param out the samples
     * @param target the index of the first sample written
     * @param count the number of samples
     */
    static void msbFirst(byte[] data, int start, int end, int bits, short[] out, int target, int count) {
        var buffer = 0L;
        var buffered = 0;
        var position = start;
        var mask = (1 << bits) - 1;
        for (var index = 0; index < count; index++) {
            while (buffered < bits) {
                if (position >= end) {
                    return;
                }
                buffer = (buffer << 8) | (data[position++] & 0xFF);
                buffered += 8;
            }
            buffered -= bits;
            out[target + index] = (short) ((buffer >>> buffered) & mask);
        }
    }

    /**
     * Unpacks 12-bit samples stored most significant bit first, two in every three bytes; the
     * result equals {@link #msbFirst} with 12 bits for an even count.
     *
     * @param data the bytes
     * @param start the index of the first byte
     * @param end the index after the last readable byte
     * @param out the samples
     * @param target the index of the first sample written
     * @param count the number of samples, even
     */
    static void msbFirst12(byte[] data, int start, int end, short[] out, int target, int count) {
        var pairs = Math.min(count / 2, (end - start) / 3);
        var position = start;
        var index = target;
        for (var pair = 0; pair < pairs; pair++) {
            var a = data[position] & 0xFF;
            var b = data[position + 1] & 0xFF;
            var c = data[position + 2] & 0xFF;
            out[index] = (short) ((a << 4) | (b >>> 4));
            out[index + 1] = (short) (((b & 0x0F) << 8) | c);
            position += 3;
            index += 2;
        }
    }

    /**
     * Unpacks samples stored least significant bit first in little-endian order, back to back.
     *
     * @param data the bytes
     * @param start the index of the first byte
     * @param end the index after the last readable byte
     * @param bits the bits of each sample, from 1 to 16
     * @param out the samples
     * @param target the index of the first sample written
     * @param count the number of samples
     */
    static void lsbFirst(byte[] data, int start, int end, int bits, short[] out, int target, int count) {
        var buffer = 0L;
        var buffered = 0;
        var position = start;
        var mask = (1 << bits) - 1;
        for (var index = 0; index < count; index++) {
            while (buffered < bits) {
                if (position >= end) {
                    return;
                }
                buffer |= (long) (data[position++] & 0xFF) << buffered;
                buffered += 8;
            }
            out[target + index] = (short) (buffer & mask);
            buffer >>>= bits;
            buffered -= bits;
        }
    }

    /**
     * Unpacks samples stored most significant bit first in a bit stream made of little-endian
     * 32-bit words (the packed layout of Olympus files).
     *
     * @param data the bytes
     * @param start the index of the first byte, at a word boundary
     * @param end the index after the last readable byte
     * @param bits the bits of each sample, from 1 to 16
     * @param out the samples
     * @param target the index of the first sample written
     * @param count the number of samples
     */
    static void msbFirstWords(byte[] data, int start, int end, int bits, short[] out, int target, int count) {
        var buffer = 0L;
        var buffered = 0;
        var position = start;
        var mask = (1 << bits) - 1;
        for (var index = 0; index < count; index++) {
            if (buffered < bits) {
                if (position + 4 > end) {
                    return;
                }
                var word = (data[position] & 0xFFL) | (data[position + 1] & 0xFFL) << 8 | (data[position + 2] & 0xFFL) << 16
                        | (data[position + 3] & 0xFFL) << 24;
                position += 4;
                buffer = (buffer << 32) | word;
                buffered += 32;
            }
            buffered -= bits;
            out[target + index] = (short) ((buffer >>> buffered) & mask);
        }
    }

    /**
     * Unpacks 16-bit words holding one sample each, keeping their low {@code bits} bits.
     *
     * @param data the bytes
     * @param start the index of the first byte
     * @param end the index after the last readable byte
     * @param little whether the words are little endian
     * @param bits the significant bits of each word
     * @param out the samples
     * @param target the index of the first sample written
     * @param count the number of samples
     */
    static void words(byte[] data, int start, int end, boolean little, int bits, short[] out, int target, int count) {
        var available = Math.min(count, (end - start) / 2);
        var mask = (1 << bits) - 1;
        for (var index = 0; index < available; index++) {
            var a = data[start + 2 * index] & 0xFF;
            var b = data[start + 2 * index + 1] & 0xFF;
            out[target + index] = (short) ((little ? a | (b << 8) : (a << 8) | b) & mask);
        }
    }
}
