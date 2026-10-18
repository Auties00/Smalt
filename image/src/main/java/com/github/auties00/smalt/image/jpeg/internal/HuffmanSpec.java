package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Describes a Huffman table the way a DHT segment codes it: the number of codes of each length
 * and the symbols in code order.
 *
 * <p>The code of every symbol follows from this description (T.81 Annex C): codes are assigned in
 * increasing numeric order, first to the shortest length. The constructor only checks the counts;
 * whether they describe a valid prefix code is checked by {@link #codes()}.
 *
 * @param bits   the code counts; {@code bits[l]} is the number of codes of length {@code l} for
 *               {@code l} from 1 to 16, and {@code bits[0]} is unused
 * @param values the symbols, in code order; as many as the counts add up to
 */
public record HuffmanSpec(int[] bits, int[] values) {
    /**
     * The luminance DC table of T.81 Table K.3.
     */
    public static final HuffmanSpec STD_DC_LUMINANCE = new HuffmanSpec(JpegTables.STD_DC_LUMINANCE_BITS, JpegTables.STD_DC_LUMINANCE_VALUES);

    /**
     * The chrominance DC table of T.81 Table K.4.
     */
    public static final HuffmanSpec STD_DC_CHROMINANCE = new HuffmanSpec(JpegTables.STD_DC_CHROMINANCE_BITS, JpegTables.STD_DC_CHROMINANCE_VALUES);

    /**
     * The luminance AC table of T.81 Table K.5.
     */
    public static final HuffmanSpec STD_AC_LUMINANCE = new HuffmanSpec(JpegTables.STD_AC_LUMINANCE_BITS, JpegTables.STD_AC_LUMINANCE_VALUES);

    /**
     * The chrominance AC table of T.81 Table K.6.
     */
    public static final HuffmanSpec STD_AC_CHROMINANCE = new HuffmanSpec(JpegTables.STD_AC_CHROMINANCE_BITS, JpegTables.STD_AC_CHROMINANCE_VALUES);

    /**
     * Validates the counts and copies the arrays.
     *
     * @throws NullPointerException     if an array is {@code null}
     * @throws MediaException.Malformed if {@code bits} does not have 17 entries, a count is
     *                                  negative, the counts add up to more than 256, or
     *                                  {@code values} does not hold exactly that many symbols
     *                                  from 0 to 255
     */
    public HuffmanSpec {
        bits = bits.clone();
        values = values.clone();
        if (bits.length != 17) {
            throw new MediaException.Malformed("a Huffman table has 16 code-length counts, got " + (bits.length - 1));
        }
        var total = 0;
        for (var length = 1; length <= 16; length++) {
            if (bits[length] < 0) {
                throw new MediaException.Malformed("negative Huffman code count");
            }
            total += bits[length];
        }
        if (total > 256) {
            throw new MediaException.Malformed("Huffman table declares " + total + " symbols, more than 256");
        }
        if (values.length != total) {
            throw new MediaException.Malformed("Huffman table declares " + total + " symbols but has " + values.length);
        }
        for (var value : values) {
            if (value < 0 || value > 255) {
                throw new MediaException.Malformed("Huffman symbol " + value + " is not a byte");
            }
        }
    }

    /**
     * Returns the number of symbols.
     *
     * @return the symbol count, from 0 to 256
     */
    public int size() {
        return values.length;
    }

    /**
     * Returns the code length of every symbol, in code order (T.81 Figure C.1).
     *
     * @return the lengths, one per entry of {@link #values()}
     */
    public int[] lengths() {
        var lengths = new int[values.length];
        var p = 0;
        for (var length = 1; length <= 16; length++) {
            for (var i = 0; i < bits[length]; i++) {
                lengths[p++] = length;
            }
        }
        return lengths;
    }

    /**
     * Returns the code of every symbol, in code order (T.81 Figure C.2).
     *
     * @return the codes, right-aligned in their length, one per entry of {@link #values()}
     * @throws MediaException.Malformed if the counts oversubscribe a code length, so that some
     *                                  code would not fit or would be all ones
     */
    public int[] codes() {
        var lengths = lengths();
        var codes = new int[values.length];
        var code = 0;
        var p = 0;
        for (var length = 1; length <= 16; length++) {
            for (var i = 0; i < bits[length]; i++) {
                codes[p++] = code++;
            }
            if (code >= (1 << length) && p > 0 && lengths[p - 1] == length) {
                throw new MediaException.Malformed("Huffman code lengths oversubscribe length " + length);
            }
            code <<= 1;
        }
        return codes;
    }

    /**
     * Returns a copy of the code counts.
     *
     * @return the counts, 17 entries
     */
    @Override
    public int[] bits() {
        return bits.clone();
    }

    /**
     * Returns a copy of the symbols.
     *
     * @return the symbols, in code order
     */
    @Override
    public int[] values() {
        return values.clone();
    }

    /**
     * Returns the number of codes of one length without copying.
     *
     * @param length the code length, from 1 to 16
     * @return the count
     */
    public int count(int length) {
        return bits[length];
    }

    /**
     * Returns one symbol without copying.
     *
     * @param index the index in code order
     * @return the symbol
     */
    public int value(int index) {
        return values[index];
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HuffmanSpec that && Arrays.equals(bits, that.bits) && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bits) * 31 + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "HuffmanSpec[bits=" + Arrays.toString(bits) + ", values=" + values.length + "]";
    }
}
