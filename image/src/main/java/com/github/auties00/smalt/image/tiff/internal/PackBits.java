package com.github.auties00.smalt.image.tiff.internal;

import java.io.ByteArrayOutputStream;

/**
 * Decodes and encodes PackBits run-length data (TIFF compression 32773, TIFF 6.0 section 9).
 *
 * <p>Each run starts with a signed header byte {@code n}: {@code 0..127} copies the next
 * {@code n + 1} bytes, {@code -127..-1} repeats the next byte {@code 1 - n} times, and
 * {@code -128} is skipped.
 */
public final class PackBits {
    /**
     * Prevents instantiation.
     */
    private PackBits() {
        throw new AssertionError();
    }

    /**
     * Decodes PackBits data.
     *
     * <p>Decoding stops at the end of the input or once the output is full; a run that crosses
     * the end of the output is truncated, as libtiff does.
     *
     * @param input       the compressed bytes
     * @param inputOffset the index of the first compressed byte
     * @param inputLength the number of compressed bytes
     * @param output      the destination
     * @param outOffset   the index of the first output byte
     * @param outLength   the number of bytes wanted
     * @return the number of bytes written
     */
    public static int decode(byte[] input, int inputOffset, int inputLength, byte[] output, int outOffset,
                             int outLength) {
        var in = inputOffset;
        var inEnd = inputOffset + inputLength;
        var out = outOffset;
        var outEnd = outOffset + outLength;
        while (in < inEnd && out < outEnd) {
            int header = input[in++];
            if (header >= 0) {
                var count = Math.min(Math.min(header + 1, inEnd - in), outEnd - out);
                System.arraycopy(input, in, output, out, count);
                in += header + 1;
                out += count;
            } else if (header != -128) {
                if (in >= inEnd) {
                    break;
                }
                var value = input[in++];
                var count = Math.min(1 - header, outEnd - out);
                for (var i = 0; i < count; i++) {
                    output[out + i] = value;
                }
                out += count;
            }
        }
        return out - outOffset;
    }

    /**
     * Encodes rows of bytes, each row coded on its own as TIFF requires.
     *
     * <p>A run of three or more equal bytes is coded as a repeat, everything else as literals.
     *
     * @param input    the bytes
     * @param offset   the index of the first byte
     * @param rowBytes the length of one row
     * @param rows     the number of rows
     * @return the encoded data
     */
    public static byte[] encode(byte[] input, int offset, int rowBytes, int rows) {
        var out = new ByteArrayOutputStream(rowBytes * rows / 2 + 16);
        for (var row = 0; row < rows; row++) {
            encodeRow(input, offset + row * rowBytes, rowBytes, out);
        }
        return out.toByteArray();
    }

    /**
     * Encodes one row.
     *
     * @param input  the bytes
     * @param start  the index of the first byte of the row
     * @param length the length of the row
     * @param out    the destination
     */
    private static void encodeRow(byte[] input, int start, int length, ByteArrayOutputStream out) {
        var end = start + length;
        var position = start;
        var literal = start;
        while (position < end) {
            var value = input[position];
            var run = 1;
            while (position + run < end && run < 128 && input[position + run] == value) {
                run++;
            }
            if (run >= 3) {
                writeLiterals(input, literal, position, out);
                out.write(1 - run);
                out.write(value);
                position += run;
                literal = position;
            } else {
                position += run;
                if (position - literal >= 128) {
                    writeLiterals(input, literal, literal + 128, out);
                    literal += 128;
                }
            }
        }
        writeLiterals(input, literal, end, out);
    }

    /**
     * Writes a stretch of literal bytes as runs of at most 128.
     *
     * @param input the bytes
     * @param from  the index of the first literal
     * @param to    the index after the last literal
     * @param out   the destination
     */
    private static void writeLiterals(byte[] input, int from, int to, ByteArrayOutputStream out) {
        while (from < to) {
            var count = Math.min(128, to - from);
            out.write(count - 1);
            out.write(input, from, count);
            from += count;
        }
    }
}
