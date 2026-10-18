package com.github.auties00.smalt.image.gif;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Decompresses the variable-length-code LZW data of one GIF image (GIF89a appendix F) into
 * color indices.
 *
 * <p>Codes are read least-significant bit first, starting at {@code minimumCodeSize + 1} bits and
 * growing by one bit each time the next free code reaches the current code space, up to 12 bits.
 * A clear code resets the table and the code size; the end code stops decoding. When the table is
 * full (4096 entries) no entry is added and decoding continues with 12-bit codes until a clear
 * code arrives (the deferred clear that encoders are allowed to use).
 *
 * <p>Decoding never fails: it stops, keeping every index produced so far, at the end code, when
 * the output is full, when the data runs out, and at the first code that refers past the current
 * table (a corrupt stream), which is how browsers render damaged GIFs. A minimum code size outside
 * 1..8 produces no indices.
 *
 * @implNote This implementation exploits that the string of every table entry already appears in
 *           the output: an entry is the previous code's string, which was written just before the
 *           current code's string, extended by the current string's first byte, which follows it.
 *           Each entry is therefore stored as the output position and length of its string, and
 *           decoding a code copies its string forward from an earlier part of the output instead
 *           of walking a prefix chain one byte at a time; a string of up to eight bytes that lies
 *           at least eight bytes back is moved as one 64-bit word, whose extra bytes land where
 *           later strings are written anyway. The only overlapping copy is the {@code KwKwK}
 *           case, whose last byte repeats the string's first byte. A string that would overflow
 *           the output is clipped to its first bytes. Codes are read through a 64-bit buffer
 *           refilled eight bytes at a time.
 */
final class GifLzwDecoder {
    /**
     * The number of codes of a 12-bit table.
     */
    private static final int TABLE_SIZE = 4096;

    /**
     * The longest code, in bits.
     */
    private static final int MAX_CODE_SIZE = 12;

    /**
     * The longest string copied byte by byte; longer strings are copied in bulk.
     */
    private static final int SHORT_COPY = 16;

    /**
     * Reads and writes eight bytes at any offset of a byte array, little-endian.
     */
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * The output position of the string of each entry above the end code.
     */
    private final int[] starts;

    /**
     * The length of the string of each entry above the end code.
     */
    private final int[] lengths;

    /**
     * Constructs a decoder with its tables.
     */
    GifLzwDecoder() {
        this.starts = new int[TABLE_SIZE];
        this.lengths = new int[TABLE_SIZE];
    }

    /**
     * Decodes one image's LZW data.
     *
     * @param data            the concatenated sub-block payloads
     * @param dataLength      the number of valid bytes in {@code data}
     * @param minimumCodeSize the LZW minimum code size from the image data
     * @param output          the destination of the color indices
     * @param count           the number of indices the image needs; at most {@code output.length}
     * @return the number of indices produced, from 0 to {@code count}
     */
    int decode(byte[] data, int dataLength, int minimumCodeSize, byte[] output, int count) {
        if (minimumCodeSize < 1 || minimumCodeSize > 8 || count <= 0) {
            return 0;
        }
        var starts = this.starts;
        var lengths = this.lengths;
        var clear = 1 << minimumCodeSize;
        var end = clear + 1;
        var codeSize = minimumCodeSize + 1;
        var mask = (1 << codeSize) - 1;
        var next = clear + 2;
        var previous = -1;
        var previousStart = 0;
        var previousLength = 0;
        var bits = 0L;
        var available = 0;
        var position = 0;
        var written = 0;
        while (written < count) {
            if (available < codeSize) {
                if (position + 8 <= dataLength) {
                    bits |= (long) LONG_LE.get(data, position) << available;
                    position += (63 - available) >>> 3;
                    available |= 56;
                } else {
                    while (available <= 56 && position < dataLength) {
                        bits |= (data[position++] & 0xFFL) << available;
                        available += 8;
                    }
                    if (available < codeSize) {
                        break;
                    }
                }
            }
            var code = (int) bits & mask;
            bits >>>= codeSize;
            available -= codeSize;
            if (code == clear) {
                codeSize = minimumCodeSize + 1;
                mask = (1 << codeSize) - 1;
                next = clear + 2;
                previous = -1;
                continue;
            }
            if (code == end) {
                break;
            }
            if (previous < 0) {
                if (code > end) {
                    break;
                }
                previousStart = written;
                previousLength = 1;
                output[written++] = (byte) code;
                previous = code;
                // with a minimum code size of 1 the first free code (4) already overflows 2 bits
                if (next > mask) {
                    codeSize++;
                    mask = (1 << codeSize) - 1;
                }
                continue;
            }
            int start;
            int length;
            if (code < next) {
                if (next < TABLE_SIZE) {
                    starts[next] = previousStart;
                    lengths[next] = previousLength + 1;
                    next++;
                }
                if (code < clear) {
                    start = -1;
                    length = 1;
                } else {
                    start = starts[code];
                    length = lengths[code];
                }
            } else if (code == next && next < TABLE_SIZE) {
                starts[next] = previousStart;
                lengths[next] = previousLength + 1;
                next++;
                start = previousStart;
                length = previousLength + 1;
            } else {
                break;
            }
            var copied = Math.min(length, count - written);
            if (start < 0) {
                output[written] = (byte) code;
            } else if (copied <= 8 && start + 8 <= written && written + 8 <= output.length) {
                LONG_LE.set(output, written, (long) LONG_LE.get(output, start));
            } else if (copied <= SHORT_COPY) {
                for (var i = 0; i < copied; i++) {
                    output[written + i] = output[start + i];
                }
            } else if (start + copied > written) {
                System.arraycopy(output, start, output, written, copied - 1);
                output[written + copied - 1] = output[start];
            } else {
                System.arraycopy(output, start, output, written, copied);
            }
            previousStart = written;
            previousLength = length;
            written += copied;
            previous = code;
            if (next > mask && codeSize < MAX_CODE_SIZE) {
                codeSize++;
                mask = (1 << codeSize) - 1;
            }
        }
        return written;
    }
}
