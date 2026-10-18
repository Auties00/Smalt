/* Ports X3F tools (x3f_io.c: x3f_load_true, true_decode_one_color, get_true_diff, x3f_load_huffman,
   huffman_decode_row) at commit e8b48ca. Copyright 2015 Roland and Erik Karlsson; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Decodes the two raw image codings of Sigma X3F files this decoder reads into the three color
 * layers of a Foveon sensor, interleaved bottom, middle, top.
 *
 * <p>The TRUE coding (raw image type 3 or 1, format 30) stores each layer as its own Huffman coded
 * stream. Its header holds three 16-bit seeds, a 16-bit reserved value, the code table as pairs of
 * a code length and a code left-aligned in a byte (the table ending with a zero length; the
 * position of a code in the table is the number of difference bits that follow it) and the byte
 * length of each layer's stream; each stream starts at a 16-byte boundary after the previous one.
 * A difference of {@code n} bits whose first bit is 0 is negative, the bits minus {@code 2^n - 1}.
 * Each layer value is the difference plus the previous value of the same column parity on the row;
 * the first two values of a row add to the first two values of the row two rows above, the first
 * rows to the seed.
 *
 * <p>The older Huffman coding (raw image type 3, format 6, with a row size of 0) holds a table of
 * 1024 signed 16-bit difference values, 1024 codes (a 5-bit length above a 27-bit code), the rows
 * and finally the offset of each row from the first coded byte. A row codes the bottom, middle and
 * top values of each pixel in turn; each value is a signed 16-bit running sum of its layer's
 * differences from 0 at the row start, so it can be negative.
 *
 * <p>Codes are read most significant bit first. A stream that uses a code its table does not
 * assign, or runs past its end, is malformed.
 *
 * @implNote This implementation ports the decoding of the X3F tools library, whose license was
 * checked; its output is bit-identical to that library's for the CC0 sample files of the raw module's
 * tests. The three TRUE layers decode in parallel, and the Huffman rows in parallel bands; both
 * resolve a code and its difference bits with one table lookup when they fit in
 * {@link #LOOKUP_BITS} bits.
 */
final class X3fDecoder {
    /**
     * The bits a table lookup resolves.
     */
    static final int LOOKUP_BITS = 12;

    /**
     * The flag of a lookup entry that holds the code and its difference bits.
     */
    private static final int FUSED = 0x20;

    /**
     * The longest TRUE difference, in bits.
     */
    private static final int MAX_DIFFERENCE_BITS = 16;

    /**
     * The entries of the Huffman coding's tables.
     */
    private static final int HUFFMAN_ENTRIES = 1024;

    /**
     * The value added to the Huffman coding's signed layer values to store them unsigned.
     */
    static final int HUFFMAN_BIAS = 1024;

    /**
     * Prevents instantiation.
     */
    private X3fDecoder() {
        throw new AssertionError();
    }

    /**
     * Reads bits most significant first, supplying zero bits past the end of the data.
     */
    static final class Bits {
        /**
         * The data.
         */
        private final byte[] data;

        /**
         * The index after the data.
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
         * @param data the bytes
         * @param start the index of the first byte
         * @param end the index after the last byte
         */
        Bits(byte[] data, int start, int end) {
            this.data = data;
            this.position = start;
            this.end = end;
        }

        /**
         * Decodes one TRUE difference.
         *
         * @param lookup the lookup of {@link #trueLookup(byte[], int, int)}
         * @return the difference
         * @throws MediaException.Malformed if the code is not in the table
         */
        int difference(int[] lookup) {
            if (count < 32) {
                while (count <= 56) {
                    buffer = (buffer << 8) | (position < end ? data[position] & 0xFF : 0);
                    position++;
                    count += 8;
                }
            }
            var entry = lookup[(int) (buffer >>> (count - LOOKUP_BITS)) & ((1 << LOOKUP_BITS) - 1)];
            if ((entry & FUSED) != 0) {
                count -= entry & 0x1F;
                return entry >> 8;
            }
            if (entry == 0) {
                throw new MediaException.Malformed("X3F code not in its table");
            }
            count -= entry & 0x1F;
            var bits = entry >>> 8;
            var value = (int) (buffer >>> (count - bits)) & ((1 << bits) - 1);
            count -= bits;
            return signed(value, bits);
        }

        /**
         * Returns the bits consumed since the first byte.
         *
         * @param start the index of the first byte
         * @return the number of bits read
         */
        long consumed(int start) {
            return (long) (position - start) * 8 - count;
        }
    }

    /**
     * Returns the signed difference of a TRUE code's difference bits.
     *
     * @param value the bits
     * @param bits the number of bits
     * @return the difference
     */
    private static int signed(int value, int bits) {
        if (bits == 0) {
            return 0;
        }
        return (value >>> (bits - 1)) == 0 ? value - ((1 << bits) - 1) : value;
    }

    /**
     * Builds the lookup of a TRUE code table: indexed by the next {@link #LOOKUP_BITS} bits, an
     * entry holds the bits a code and its difference bits take, the {@link #FUSED} flag and the
     * difference above bit 8, or, when they do not fit, the code length and the number of
     * difference bits above bit 8; 0 marks bits no code starts.
     *
     * @param data the bytes holding the table
     * @param start the index of the table
     * @param end the index after which the table may not extend
     * @return the lookup
     * @throws MediaException.Malformed if the table is unterminated or assigns a code twice
     */
    static int[] trueLookup(byte[] data, int start, int end) {
        var lookup = new int[1 << LOOKUP_BITS];
        var position = start;
        for (var index = 0; ; index++) {
            if (position + 2 > end) {
                throw new MediaException.Malformed("unterminated X3F code table");
            }
            var length = data[position] & 0xFF;
            var code = (data[position + 1] & 0xFF) >>> (8 - Math.min(8, Math.max(length, 1)));
            position += 2;
            if (length == 0) {
                return lookup;
            }
            if (length > 8 || index > MAX_DIFFERENCE_BITS) {
                throw new MediaException.Malformed("X3F code table entry of " + length + " bits at " + index);
            }
            var shift = LOOKUP_BITS - length;
            var first = code << shift;
            for (var suffix = 0; suffix < 1 << shift; suffix++) {
                var slot = first | suffix;
                if (lookup[slot] != 0) {
                    throw new MediaException.Malformed("X3F code table assigns a code twice");
                }
                if (length + index <= LOOKUP_BITS) {
                    var value = index == 0 ? 0 : (suffix >>> (shift - index)) & ((1 << index) - 1);
                    lookup[slot] = (signed(value, index) << 8) | FUSED | (length + index);
                } else {
                    lookup[slot] = (index << 8) | length;
                }
            }
        }
    }

    /**
     * Describes a TRUE raw image section.
     *
     * @param seeds the seed of each layer
     * @param table the index of the code table in the section body
     * @param starts the index of each layer's stream in the section body
     * @param lengths the byte length of each layer's stream
     */
    record TrueLayout(int[] seeds, int table, int[] starts, int[] lengths) {
    }

    /**
     * Reads the header of a TRUE raw image section.
     *
     * @param body the section from its 28-byte image header on, or its first bytes
     * @param size the full length of the section
     * @return the layout
     * @throws MediaException.Malformed if the header is truncated or the streams overrun the section
     */
    static TrueLayout trueLayout(byte[] body, long size) {
        var position = 28;
        if (body.length < position + 10) {
            throw new MediaException.Malformed("truncated X3F TRUE header");
        }
        var seeds = new int[]{ByteArrays.u16(body, position, true), ByteArrays.u16(body, position + 2, true),
                ByteArrays.u16(body, position + 4, true)};
        var table = position + 8;
        position = table;
        while (true) {
            if (position + 2 > body.length) {
                throw new MediaException.Malformed("unterminated X3F code table");
            }
            var length = body[position] & 0xFF;
            position += 2;
            if (length == 0) {
                break;
            }
        }
        if (position + 12 > body.length) {
            throw new MediaException.Malformed("truncated X3F TRUE header");
        }
        var starts = new int[3];
        var lengths = new int[3];
        var start = (long) position + 12;
        for (var layer = 0; layer < 3; layer++) {
            var length = ByteArrays.u32(body, position + 4 * layer, true);
            if (start + length > size) {
                throw new MediaException.Malformed("X3F layer stream of " + length + " bytes overruns its section");
            }
            starts[layer] = (int) start;
            lengths[layer] = (int) length;
            start += (length + 15) / 16 * 16;
        }
        return new TrueLayout(seeds, table, starts, lengths);
    }

    /**
     * Decodes the three layers of a TRUE section, in parallel.
     *
     * @param body the whole section
     * @param layout its layout
     * @param width the width of each layer
     * @param height the height of each layer
     * @param out the interleaved output, {@code 3 * width * height} values
     * @throws MediaException.Malformed if a stream is corrupt
     */
    static void decodeTrue(byte[] body, TrueLayout layout, int width, int height, short[] out) {
        var lookup = trueLookup(body, layout.table, body.length);
        ParallelBands.run(3, layer -> decodeLayer(body, layout.starts[layer], layout.lengths[layer], lookup,
                layout.seeds[layer], width, height, out, layer));
    }

    /**
     * Decodes one TRUE layer.
     *
     * @param data the bytes holding the stream
     * @param start the index of the stream
     * @param length the byte length of the stream
     * @param lookup the code lookup
     * @param seed the layer's seed
     * @param width the layer width
     * @param height the layer height
     * @param out the interleaved output
     * @param layer the layer, the offset of its values in each pixel
     * @throws MediaException.Malformed if the stream is corrupt or runs past its end
     */
    private static void decodeLayer(byte[] data, int start, int length, int[] lookup, int seed, int width,
                                    int height, short[] out, int layer) {
        var end = start + length;
        var position = start;
        var buffer = 0L;
        var count = 0;
        var even0 = seed;
        var even1 = seed;
        var odd0 = seed;
        var odd1 = seed;
        var mask = (1 << LOOKUP_BITS) - 1;
        for (var row = 0; row < height; row++) {
            var target = row * width * 3 + layer;
            var accumulator0 = (row & 1) == 0 ? even0 : odd0;
            var accumulator1 = (row & 1) == 0 ? even1 : odd1;
            for (var column = 0; column < width; column++) {
                if (count < 32) {
                    if (position + 8 <= end) {
                        while (count <= 56) {
                            buffer = (buffer << 8) | (data[position++] & 0xFF);
                            count += 8;
                        }
                    } else {
                        while (count <= 56) {
                            buffer = (buffer << 8) | (position < end ? data[position] & 0xFF : 0);
                            position++;
                            count += 8;
                        }
                    }
                }
                var entry = lookup[(int) (buffer >>> (count - LOOKUP_BITS)) & mask];
                int difference;
                if ((entry & FUSED) != 0) {
                    count -= entry & 0x1F;
                    difference = entry >> 8;
                } else {
                    if (entry == 0) {
                        throw new MediaException.Malformed("X3F code not in its table");
                    }
                    count -= entry & 0x1F;
                    var bits = entry >>> 8;
                    difference = signed((int) (buffer >>> (count - bits)) & ((1 << bits) - 1), bits);
                    count -= bits;
                }
                int value;
                if ((column & 1) == 0) {
                    value = accumulator0 += difference;
                } else {
                    value = accumulator1 += difference;
                }
                if (column == 1) {
                    if ((row & 1) == 0) {
                        even0 = accumulator0;
                        even1 = accumulator1;
                    } else {
                        odd0 = accumulator0;
                        odd1 = accumulator1;
                    }
                }
                out[target] = (short) Math.clamp(value, 0, 0xFFFF);
                target += 3;
            }
            if (width == 1) {
                if ((row & 1) == 0) {
                    even0 = accumulator0;
                } else {
                    odd0 = accumulator0;
                }
            }
        }
        if ((long) (position - start) * 8 - count > (long) length * 8) {
            throw new MediaException.Malformed("X3F layer stream runs past its end");
        }
    }

    /**
     * Decodes the rows of a Huffman coded section, in parallel bands.
     *
     * @param body the whole section
     * @param width the image width
     * @param height the image height
     * @param out the interleaved output, {@code 3 * width * height} values, each the signed layer
     * value plus {@link #HUFFMAN_BIAS}, clamped to 16 bits
     * @throws MediaException.Malformed if the tables, the row offsets or a row are corrupt
     */
    static void decodeHuffman(byte[] body, int width, int height, short[] out) {
        var values = new int[HUFFMAN_ENTRIES];
        var position = 28;
        for (var index = 0; index < HUFFMAN_ENTRIES; index++) {
            values[index] = (short) ByteArrays.u16(body, position, true);
            position += 2;
        }
        var lengths = new int[HUFFMAN_ENTRIES];
        var codes = new int[HUFFMAN_ENTRIES];
        for (var index = 0; index < HUFFMAN_ENTRIES; index++) {
            var entry = ByteArrays.u32(body, position, true);
            lengths[index] = (int) (entry >>> 27);
            codes[index] = (int) (entry & 0x07FFFFFF);
            position += 4;
        }
        var data = position;
        var offsets = body.length - 4 * height;
        if (offsets < data) {
            throw new MediaException.Malformed("X3F Huffman section too short for " + height + " rows");
        }
        var tree = HuffmanTree.of(values, lengths, codes);
        var bands = ParallelBands.bands(height, (long) width * height * 3);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, height);
            var last = ParallelBands.start(band + 1, bands, height);
            for (var row = first; row < last; row++) {
                var offset = ByteArrays.u32(body, offsets + 4 * row, true);
                if (offset > offsets - data) {
                    throw new MediaException.Malformed("X3F row offset " + offset + " out of range");
                }
                decodeRow(body, data + (int) offset, offsets, tree, out, row * width * 3, width);
            }
        });
    }

    /**
     * Decodes one row of the Huffman coding.
     *
     * @param data the bytes
     * @param start the index of the row's first coded byte
     * @param end the index after the coded data
     * @param tree the code tree
     * @param out the output
     * @param target the index of the row's first value
     * @param width the image width
     * @throws MediaException.Malformed if a code is not in the table
     */
    private static void decodeRow(byte[] data, int start, int end, HuffmanTree tree, short[] out, int target,
                                  int width) {
        var position = start;
        var buffer = 0L;
        var count = 0;
        var lookup = tree.lookup;
        var mask = (1 << LOOKUP_BITS) - 1;
        short bottom = 0;
        short middle = 0;
        short top = 0;
        for (var index = 0; index < width * 3; index++) {
            if (count < 32) {
                while (count <= 56) {
                    buffer = (buffer << 8) | (position < end ? data[position] & 0xFF : 0);
                    position++;
                    count += 8;
                }
            }
            var entry = lookup[(int) (buffer >>> (count - LOOKUP_BITS)) & mask];
            int difference;
            if ((entry & FUSED) != 0) {
                count -= entry & 0x1F;
                difference = entry >> 8;
            } else {
                count -= LOOKUP_BITS;
                var node = entry >>> 8;
                if (entry == 0) {
                    throw new MediaException.Malformed("X3F code not in its table");
                }
                while (true) {
                    if (count == 0) {
                        buffer = (buffer << 8) | (position < end ? data[position] & 0xFF : 0);
                        position++;
                        count = 8;
                    }
                    count--;
                    node = tree.children[2 * node + (int) ((buffer >>> count) & 1)];
                    if (node == 0) {
                        throw new MediaException.Malformed("X3F code not in its table");
                    }
                    if (node < 0) {
                        difference = tree.leaves[-node - 1];
                        break;
                    }
                }
            }
            var layer = index % 3;
            int value;
            if (layer == 0) {
                value = bottom += (short) difference;
            } else if (layer == 1) {
                value = middle += (short) difference;
            } else {
                value = top += (short) difference;
            }
            out[target + index] = (short) Math.clamp(value + HUFFMAN_BIAS, 0, 0xFFFF);
        }
    }

    /**
     * Holds the codes of the Huffman coding: a lookup for codes of up to {@link #LOOKUP_BITS} bits
     * and a binary tree continuing the longer ones.
     */
    private static final class HuffmanTree {
        /**
         * The lookup: {@link #FUSED}, the code length and the value above bit 8 for a short code;
         * the tree node reached after {@link #LOOKUP_BITS} bits above bit 8 for a long one; 0 for
         * bits no code starts.
         */
        final int[] lookup = new int[1 << LOOKUP_BITS];

        /**
         * The two children of each node: a node index, the negated leaf index minus one, or 0 for
         * none.
         */
        int[] children = new int[64];

        /**
         * The value of each leaf.
         */
        final int[] leaves;

        /**
         * The nodes in use.
         */
        private int nodes = 1;

        /**
         * Constructs an empty tree.
         *
         * @param leaves the number of leaves
         */
        private HuffmanTree(int leaves) {
            this.leaves = new int[leaves];
        }

        /**
         * Builds the codes.
         *
         * @param values the value of each table index
         * @param lengths the code length of each index, 0 for none
         * @param codes the code of each index, right-aligned
         * @return the codes
         * @throws MediaException.Malformed if two codes collide
         */
        static HuffmanTree of(int[] values, int[] lengths, int[] codes) {
            var tree = new HuffmanTree(values.length);
            for (var index = 0; index < values.length; index++) {
                var length = lengths[index];
                if (length == 0) {
                    continue;
                }
                if (length > 27) {
                    throw new MediaException.Malformed("X3F Huffman code of " + length + " bits");
                }
                var code = codes[index] & ((1 << length) - 1);
                if (length <= LOOKUP_BITS) {
                    var shift = LOOKUP_BITS - length;
                    for (var suffix = 0; suffix < 1 << shift; suffix++) {
                        var slot = (code << shift) | suffix;
                        if (tree.lookup[slot] != 0) {
                            throw new MediaException.Malformed("X3F Huffman table assigns a code twice");
                        }
                        tree.lookup[slot] = (values[index] << 8) | FUSED | length;
                    }
                } else {
                    var slot = code >>> (length - LOOKUP_BITS);
                    var entry = tree.lookup[slot];
                    if ((entry & FUSED) != 0) {
                        throw new MediaException.Malformed("X3F Huffman table assigns a code twice");
                    }
                    var node = entry == 0 ? tree.node() : entry >>> 8;
                    tree.lookup[slot] = node << 8;
                    for (var bit = length - LOOKUP_BITS - 1; bit >= 0; bit--) {
                        var branch = 2 * node + ((code >>> bit) & 1);
                        if (bit == 0) {
                            if (tree.children[branch] != 0) {
                                throw new MediaException.Malformed("X3F Huffman table assigns a code twice");
                            }
                            tree.leaves[index] = values[index];
                            tree.children[branch] = -index - 1;
                        } else {
                            if (tree.children[branch] < 0) {
                                throw new MediaException.Malformed("X3F Huffman table assigns a code twice");
                            }
                            if (tree.children[branch] == 0) {
                                var child = tree.node();
                                tree.children[branch] = child;
                            }
                            node = tree.children[branch];
                        }
                    }
                }
            }
            return tree;
        }

        /**
         * Allocates a node.
         *
         * @return its index, never 0
         */
        private int node() {
            if (2 * nodes + 2 > children.length) {
                children = java.util.Arrays.copyOf(children, children.length * 2);
            }
            return nodes++;
        }
    }
}
