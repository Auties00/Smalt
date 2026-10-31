package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Reads bits most significant first from a byte array, without JPEG byte stuffing, and decodes
 * canonical Huffman codes given in the {@code DHT} form.
 *
 * <p>Past the end of the data the reader supplies zero bits, so a truncated stream decodes to its
 * end without failing.
 */
final class MsbBits {
    /**
     * The number of leading bits the Huffman lookup tables resolve.
     */
    static final int LOOKUP_BITS = 11;

    /**
     * The data.
     */
    private final byte[] data;

    /**
     * The index after the readable data.
     */
    private final int end;

    /**
     * The index of the next byte to load.
     */
    private int position;

    /**
     * The buffered bits, in the low {@link #count} bits.
     */
    private long buffer;

    /**
     * The number of buffered bits.
     */
    private int count;

    /**
     * Constructs a reader.
     *
     * @param data the bytes to read
     * @param start the index of the first byte
     * @param end the index after the last byte
     */
    MsbBits(byte[] data, int start, int end) {
        this.data = data;
        this.position = start;
        this.end = end;
    }

    /**
     * Loads bytes until at least 57 bits are buffered.
     */
    private void fill() {
        while (count <= 56) {
            var next = position < end ? data[position] & 0xFF : 0;
            position++;
            buffer = (buffer << 8) | next;
            count += 8;
        }
    }

    /**
     * Reads bits.
     *
     * @param bits the number of bits, from 0 to 32
     * @return the value
     */
    int read(int bits) {
        if (bits == 0) {
            return 0;
        }
        if (count < bits) {
            fill();
        }
        count -= bits;
        return (int) ((buffer >>> count) & ((1L << bits) - 1));
    }

    /**
     * Consumes zero bits, up to a limit, and returns how many it consumed; the bit that stops the
     * run is not consumed.
     *
     * @param limit the most zero bits consumed, at most 32
     * @return the number of zero bits consumed
     */
    int zeros(int limit) {
        if (count < limit + 1) {
            fill();
        }
        var window = buffer << (64 - count);
        var run = Math.min(Long.numberOfLeadingZeros(window), limit);
        count -= run;
        return run;
    }

    /**
     * Returns the next bits without consuming them.
     *
     * @param bits the number of bits, from 1 to 32
     * @return the value
     */
    int peek(int bits) {
        if (count < bits) {
            fill();
        }
        return (int) ((buffer >>> (count - bits)) & ((1L << bits) - 1));
    }

    /**
     * Consumes bits that were peeked.
     *
     * @param bits the number of bits
     */
    void skip(int bits) {
        count -= bits;
    }

    /**
     * Returns whether the reader has read past the end of the data.
     *
     * @return whether zero bits are being supplied
     */
    boolean exhausted() {
        return position - count / 8 > end;
    }

    /**
     * Decodes one Huffman code.
     *
     * @param table the canonical Huffman table of the code
     * @return the value of the code, negative values included, or {@code 0} for a code the table
     * does not assign
     */
    int decode(Table table) {
        var entry = table.lookup[peek(LOOKUP_BITS)];
        if (entry != 0) {
            skip(entry & 0xFF);
            return entry >> 8;
        }
        var code = 0;
        for (var length = 1; length <= 16; length++) {
            code = (code << 1) | read(1);
            if (table.maxCode[length] >= code) {
                var index = code + table.offset[length];
                return index >= 0 && index < table.values.length ? table.values[index] : 0;
            }
        }
        return 0;
    }

    /**
     * Holds a canonical Huffman table.
     */
    static final class Table {
        /**
         * For each {@link #LOOKUP_BITS}-bit prefix, the code length in the low byte and the value,
         * which may be negative, above it, or {@code 0} when the code is longer.
         */
        final int[] lookup = new int[1 << LOOKUP_BITS];

        /**
         * The largest code of each length, or {@code -1}.
         */
        final int[] maxCode = new int[17];

        /**
         * The value index of the first code of each length minus that code.
         */
        final int[] offset = new int[17];

        /**
         * The values in code order.
         */
        final int[] values;

        /**
         * Builds a table from sixteen code counts followed by the values; codes beyond the values
         * given are left unassigned.
         *
         * @param spec the counts and values
         * @throws MediaException.Malformed if the counts give more codes of a length than the length has
         */
        Table(int[] spec) {
            values = Arrays.copyOfRange(spec, 16, spec.length);
            var code = 0;
            var index = 0;
            for (var length = 1; length <= 16; length++) {
                var count = spec[length - 1];
                offset[length] = index - code;
                var assigned = 0;
                if (code + count > 1 << length) {
                    throw new MediaException.Malformed("Huffman table of more codes of " + length
                            + " bits than there are");
                }
                for (var k = 0; k < count; k++) {
                    if (index < values.length) {
                        if (length <= LOOKUP_BITS) {
                            var shift = LOOKUP_BITS - length;
                            var first = code << shift;
                            for (var fill = 0; fill < (1 << shift); fill++) {
                                lookup[first + fill] = (values[index] << 8) | length;
                            }
                        }
                        assigned++;
                    }
                    code++;
                    index++;
                }
                maxCode[length] = assigned == 0 ? -1 : code - 1 - (count - assigned);
                code <<= 1;
            }
        }
    }
}
