/* Ports libvorbis 1.3.7 (lib/sharedbook.c, codebook.c, res0.c).
   Copyright Xiph.Org Foundation; BSD 3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.audio.vorbis.internal;

import com.github.auties00.smalt.internal.bits.LsbBitWriter;

/**
 * Encodes entries and vectors with one Vorbis codebook.
 *
 * <p>The code words come from the lengths in canonical order (lowest values first), bit-reversed for
 * the least-significant-first packer. Vector quantization assumes the centered integer lattice books
 * of the libvorbis encoder: the nearest lattice point is computed directly, and a full search runs
 * only when that point is an unused entry ({@code _make_words}, {@code vorbis_book_encode},
 * {@code local_book_besterror}).
 *
 * <p>Instances are not thread-safe.
 */
public final class EncoderCodebook {
    /**
     * The static description.
     */
    public final StaticCodebook book;

    /**
     * The code word of each entry, least significant bit first.
     */
    private final int[] codes;

    /**
     * The number of lattice values per dimension.
     */
    private final int quantValues;

    /**
     * The smallest lattice value.
     */
    private final int minValue;

    /**
     * The lattice step.
     */
    private final int delta;

    /**
     * Scratch: the lattice point chosen for a vector.
     */
    private final int[] point = new int[8];

    /**
     * Scratch: the lattice point of the full search.
     */
    private final int[] walk = new int[8];

    /**
     * Prepares a codebook for encoding.
     *
     * @param book the static description
     * @throws IllegalArgumentException if the lengths do not form a valid prefix code
     */
    public EncoderCodebook(StaticCodebook book) {
        this.book = book;
        this.codes = makeWords(book.lengths());
        this.quantValues = book.quantValues();
        this.minValue = (int) Math.rint(StaticCodebook.unpackFloat(book.quantMin()));
        this.delta = (int) Math.rint(StaticCodebook.unpackFloat(book.quantDelta()));
    }

    /**
     * Returns the number of entries.
     *
     * @return the entries
     */
    public int entries() {
        return book.entries();
    }

    /**
     * Returns the vector dimension.
     *
     * @return the dimension
     */
    public int dim() {
        return book.dim();
    }

    /**
     * Writes the code word of an entry.
     *
     * @param entry the entry number, a used entry of this codebook
     * @param out the destination
     * @return the number of bits written
     */
    public int encode(int entry, LsbBitWriter out) {
        if (entry < 0 || entry >= book.entries()) {
            return 0;
        }
        var length = book.lengths()[entry];
        out.write(codes[entry], length);
        return length;
    }

    /**
     * Finds the lattice entry nearest a vector, subtracts its value from the vector and returns
     * it.
     *
     * @param a the values
     * @param offset the index of the vector's first value
     * @return the entry, or {@code -1} when none is usable
     */
    public int bestError(int[] a, int offset) {
        var dim = book.dim();
        var qv = quantValues;
        var ze = qv >> 1;
        var index = 0;
        var p = point;
        if (delta != 1) {
            for (int i = 0, o = dim; i < dim; i++) {
                --o;
                var v = (a[offset + o] - minValue + (delta >> 1)) / delta;
                var m = v < ze ? ((ze - v) << 1) - 1 : ((v - ze) << 1);
                index = index * qv + (m < 0 ? 0 : (m >= qv ? qv - 1 : m));
                p[o] = v * delta + minValue;
            }
        } else {
            for (int i = 0, o = dim; i < dim; i++) {
                --o;
                var v = a[offset + o] - minValue;
                var m = v < ze ? ((ze - v) << 1) - 1 : ((v - ze) << 1);
                index = index * qv + (m < 0 ? 0 : (m >= qv ? qv - 1 : m));
                p[o] = v * delta + minValue;
            }
        }
        if (book.lengths()[index] <= 0) {
            var best = -1;
            var e = walk;
            java.util.Arrays.fill(e, 0);
            var maxValue = minValue + delta * (qv - 1);
            for (var i = 0; i < book.entries(); i++) {
                if (book.lengths()[i] > 0) {
                    var error = 0;
                    for (var j = 0; j < dim; j++) {
                        var value = e[j] - a[offset + j];
                        error += value * value;
                    }
                    if (best == -1 || error < best) {
                        System.arraycopy(e, 0, p, 0, 8);
                        best = error;
                        index = i;
                    }
                }
                var j = 0;
                while (e[j] >= maxValue) {
                    e[j++] = 0;
                }
                if (e[j] >= 0) {
                    e[j] += delta;
                }
                e[j] = -e[j];
            }
        }
        if (index > -1) {
            for (var i = 0; i < dim; i++) {
                a[offset + i] -= p[i];
            }
        }
        return index;
    }

    /**
     * Assigns canonical code words to lengths and bit-reverses them.
     *
     * @param lengths the code word lengths, {@code 0} for unused entries
     * @return the code word of each entry
     * @throws IllegalArgumentException if the lengths over- or under-populate the code tree
     */
    static int[] makeWords(byte[] lengths) {
        var n = lengths.length;
        var marker = new long[33];
        var r = new long[n];
        var count = 0;
        for (var i = 0; i < n; i++) {
            int length = lengths[i];
            if (length > 0) {
                var entry = marker[length];
                if (length < 32 && (entry >>> length) != 0) {
                    throw new IllegalArgumentException("overpopulated Vorbis code tree");
                }
                r[count++] = entry;
                for (var j = length; j > 0; j--) {
                    if ((marker[j] & 1) != 0) {
                        if (j == 1) {
                            marker[1]++;
                        } else {
                            marker[j] = (marker[j - 1] << 1) & 0xFFFFFFFFL;
                        }
                        break;
                    }
                    marker[j]++;
                }
                for (var j = length + 1; j < 33; j++) {
                    if ((marker[j] >>> 1) == entry) {
                        entry = marker[j];
                        marker[j] = (marker[j - 1] << 1) & 0xFFFFFFFFL;
                    } else {
                        break;
                    }
                }
            } else {
                count++;
            }
        }
        if (!(count == 1 && marker[2] == 2)) {
            for (var i = 1; i < 33; i++) {
                if ((marker[i] & (0xFFFFFFFFL >>> (32 - i))) != 0) {
                    throw new IllegalArgumentException("underpopulated Vorbis code tree");
                }
            }
        }
        var words = new int[n];
        for (var i = 0; i < n; i++) {
            long temp = 0;
            for (var j = 0; j < lengths[i]; j++) {
                temp <<= 1;
                temp |= (r[i] >>> j) & 1;
            }
            words[i] = (int) temp;
        }
        return words;
    }
}
