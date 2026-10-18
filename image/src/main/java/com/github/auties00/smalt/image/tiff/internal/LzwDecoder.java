package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

/**
 * Decodes TIFF LZW data (compression 5): the TIFF 6.0 variant with most significant bit first
 * codes and early code-width change, and the pre-6.0 variant with least significant bit first
 * codes that libtiff still reads.
 *
 * <p>Codes are 9 to 12 bits wide; 256 clears the table and 257 ends the data. A string is copied
 * from the place where it was first written to the output rather than walked through a linked
 * table, so each code costs one bounded copy. Decoding stops at the end-of-information code, at
 * the end of the input, or once the output is full; a code past the next free entry is
 * malformed. Unlike libtiff, data that does not start with a clear code and data that keeps
 * coding after the table is full are decoded (the table simply stops growing).
 *
 * <p>The bytes of the output past the returned length, up to 8 bytes beyond it when the array is
 * that long, may be overwritten with unspecified values. An instance reuses its tables across
 * calls and is not thread-safe.
 *
 * @implNote This implementation follows libtiff's tif_lzw.c for the code-width schedule (the
 * 6.0 variant widens one code early, the old variant does not), the detection of the old variant
 * (a first byte of zero followed by a byte with its low bit set) and its larger table. The 6.0
 * variant refills its bit buffer 32 bits at a time and copies strings 8 bytes at a time when the
 * source lies at least 8 bytes behind, letting the last block overshoot the string; the overshoot
 * lands only on output not yet written, which later strings or the caller overwrite.
 */
public final class LzwDecoder {
    /**
     * The clear code.
     */
    private static final int CLEAR = 256;

    /**
     * The end-of-information code.
     */
    private static final int EOI = 257;

    /**
     * The first code assigned to a string.
     */
    private static final int FIRST = 258;

    /**
     * The table size of the 6.0 variant.
     */
    private static final int TABLE = 4096;

    /**
     * The table size of the old variant, whose encoders could overrun 4096 entries.
     */
    private static final int COMPAT_TABLE = 4096 + 1024;

    /**
     * The output offset where the string of each code was first written.
     */
    private final int[] starts = new int[COMPAT_TABLE];

    /**
     * The length of the string of each code.
     */
    private final int[] lengths = new int[COMPAT_TABLE];

    /**
     * Constructs a decoder.
     */
    public LzwDecoder() {
    }

    /**
     * Decodes LZW data.
     *
     * @param input       the compressed bytes
     * @param inputOffset the index of the first compressed byte
     * @param inputLength the number of compressed bytes
     * @param output      the destination
     * @param outOffset   the index of the first output byte
     * @param outLength   the number of bytes wanted
     * @return the number of bytes written, at most {@code outLength}
     * @throws MediaException.Malformed if a code refers to an undefined string
     */
    public int decode(byte[] input, int inputOffset, int inputLength, byte[] output, int outOffset, int outLength) {
        if (inputLength >= 2 && input[inputOffset] == 0 && (input[inputOffset + 1] & 1) != 0) {
            return decodeCompat(input, inputOffset, inputLength, output, outOffset, outLength);
        }
        var starts = this.starts;
        var lengths = this.lengths;
        var position = inputOffset;
        var end = inputOffset + inputLength;
        var out = outOffset;
        var outEnd = outOffset + outLength;
        // the 8-byte block copies may run to the array end, past the requested output
        var copyEnd = output.length - 8;
        var buffer = 0L;
        var bits = 0;
        var width = 9;
        var mask = 511;
        var widenAt = 511;
        var next = FIRST;
        var oldStart = -1;
        var oldLength = 0;
        while (out < outEnd) {
            if (bits < width) {
                if (position + 4 <= end) {
                    buffer = (buffer << 32) | (ByteArrays.i32be(input, position) & 0xFFFFFFFFL);
                    position += 4;
                    bits += 32;
                } else {
                    while (bits < width) {
                        if (position >= end) {
                            return out - outOffset;
                        }
                        buffer = (buffer << 8) | (input[position++] & 0xFF);
                        bits += 8;
                    }
                }
            }
            bits -= width;
            var code = (int) (buffer >>> bits) & mask;
            int source;
            int length;
            if (code >= FIRST && code < next) {
                source = starts[code];
                length = lengths[code];
            } else if (code < CLEAR) {
                output[out] = (byte) code;
                if (oldStart >= 0 && next < TABLE) {
                    starts[next] = oldStart;
                    lengths[next] = oldLength + 1;
                    if (++next >= widenAt && width < 12) {
                        width++;
                        mask = (mask << 1) | 1;
                        widenAt = mask;
                    }
                }
                oldStart = out++;
                oldLength = 1;
                continue;
            } else if (code == CLEAR) {
                width = 9;
                mask = 511;
                widenAt = 511;
                next = FIRST;
                oldStart = -1;
                continue;
            } else if (code == EOI) {
                break;
            } else if (code == next && oldStart >= 0 && next < TABLE) {
                source = oldStart;
                length = oldLength + 1;
            } else {
                throw new MediaException.Malformed("LZW code " + code + " is undefined (next free code " + next + ")");
            }
            if (oldStart < 0) {
                throw new MediaException.Malformed("LZW data starts with string code " + code);
            }
            var count = Math.min(length, outEnd - out);
            if (out - source >= 8 && out + count <= copyEnd) {
                var from = source;
                var to = out;
                var stop = out + count;
                do {
                    ByteArrays.put64le(output, to, ByteArrays.i64le(output, from));
                    from += 8;
                    to += 8;
                } while (to < stop);
            } else {
                for (var i = 0; i < count; i++) {
                    output[out + i] = output[source + i];
                }
            }
            if (next < TABLE) {
                starts[next] = oldStart;
                lengths[next] = oldLength + 1;
                if (++next >= widenAt && width < 12) {
                    width++;
                    mask = (mask << 1) | 1;
                    widenAt = mask;
                }
            }
            oldStart = out;
            oldLength = length;
            out += count;
        }
        return out - outOffset;
    }

    /**
     * Decodes the pre-6.0 variant: least significant bit first codes without the early width
     * change.
     *
     * @param input       the compressed bytes
     * @param inputOffset the index of the first compressed byte
     * @param inputLength the number of compressed bytes
     * @param output      the destination
     * @param outOffset   the index of the first output byte
     * @param outLength   the number of bytes wanted
     * @return the number of bytes written, at most {@code outLength}
     * @throws MediaException.Malformed if a code refers to an undefined string
     */
    private int decodeCompat(byte[] input, int inputOffset, int inputLength, byte[] output, int outOffset, int outLength) {
        var starts = this.starts;
        var lengths = this.lengths;
        var position = inputOffset;
        var end = inputOffset + inputLength;
        var out = outOffset;
        var outEnd = outOffset + outLength;
        var buffer = 0L;
        var bits = 0;
        var width = 9;
        var next = FIRST;
        var oldStart = -1;
        var oldLength = 0;
        while (out < outEnd) {
            while (bits < width) {
                if (position >= end) {
                    return out - outOffset;
                }
                buffer |= (long) (input[position++] & 0xFF) << bits;
                bits += 8;
            }
            var code = (int) buffer & ((1 << width) - 1);
            buffer >>>= width;
            bits -= width;
            if (code == CLEAR) {
                width = 9;
                next = FIRST;
                oldStart = -1;
                continue;
            }
            if (code == EOI) {
                break;
            }
            if (oldStart < 0) {
                if (code > 255) {
                    throw new MediaException.Malformed("LZW data starts with string code " + code);
                }
                output[out] = (byte) code;
                oldStart = out++;
                oldLength = 1;
                continue;
            }
            int length;
            if (code < 256) {
                output[out] = (byte) code;
                length = 1;
            } else if (code < next) {
                length = lengths[code];
                var source = starts[code];
                var copy = Math.min(length, outEnd - out);
                for (var i = 0; i < copy; i++) {
                    output[out + i] = output[source + i];
                }
            } else if (code == next && next < COMPAT_TABLE) {
                length = oldLength + 1;
                var copy = Math.min(length, outEnd - out);
                for (var i = 0; i < copy; i++) {
                    output[out + i] = output[oldStart + i];
                }
            } else {
                throw new MediaException.Malformed("LZW code " + code + " is undefined (next free code " + next + ")");
            }
            if (next < COMPAT_TABLE) {
                starts[next] = oldStart;
                lengths[next] = oldLength + 1;
                next++;
                if (next >= (1 << width) && width < 12) {
                    width++;
                }
            }
            oldStart = out;
            oldLength = length;
            out += Math.min(length, outEnd - out);
        }
        return out - outOffset;
    }
}
