/* Ports OpenEXR 3.3.5 (src/lib/OpenEXRCore/internal_huf.c). Copyright Contributors to the OpenEXR Project;
   BSD-3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.image.exr.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Decodes the canonical Huffman coding of 16-bit symbols that PIZ and DWA compression use: a packed table of code
 * lengths with runs of zero lengths, then the codes, with the largest symbol standing for a run of the previous one.
 */
public final class ExrHuffman {
    private static final int ENCODING_BITS = 16;

    private static final int DECODING_BITS = 14;

    private static final int ENCODING_SIZE = (1 << ENCODING_BITS) + 1;

    private static final int DECODING_SIZE = 1 << DECODING_BITS;

    private static final int DECODING_MASK = DECODING_SIZE - 1;

    private static final int SHORT_ZEROCODE_RUN = 59;

    private static final int LONG_ZEROCODE_RUN = 63;

    private static final int SHORTEST_LONG_RUN = 2 + LONG_ZEROCODE_RUN - SHORT_ZEROCODE_RUN;

    private ExrHuffman() {
        throw new AssertionError();
    }

    /**
     * Decodes a Huffman block.
     *
     * @param data the bytes
     * @param offset the offset of the block header
     * @param length the bytes of the block, header included
     * @param out the symbols, filled from index zero
     * @param count the number of symbols to decode
     * @throws MediaException.Malformed if the block is invalid or does not hold {@code count} symbols
     */
    public static void decode(byte[] data, int offset, int length, short[] out, int count) {
        if (length < 20) {
            if (count != 0) {
                throw new MediaException.Malformed("the OpenEXR Huffman block is shorter than its header");
            }
            return;
        }
        var min = ExrHeader.i32(data, offset);
        var max = ExrHeader.i32(data, offset + 4);
        var bits = ExrHeader.i32(data, offset + 12) & 0xFFFFFFFFL;
        if (min < 0 || max < 0 || min >= ENCODING_SIZE || max >= ENCODING_SIZE || min > max) {
            throw new MediaException.Malformed("invalid OpenEXR Huffman symbol range " + min + " to " + max);
        }
        var end = offset + length;
        var position = offset + 20;
        if (position + (bits + 7) / 8 > end) {
            throw new MediaException.Malformed("the OpenEXR Huffman block is shorter than its codes");
        }
        var codes = new long[ENCODING_SIZE];
        position = unpackTable(data, position, end, min, max, codes);
        if (bits > 8L * (end - position)) {
            throw new MediaException.Malformed("the OpenEXR Huffman codes run past their block");
        }
        var table = new Table();
        table.build(codes, min, max);
        decodeCodes(codes, table, data, position, bits, max, out, count);
    }

    /**
     * Holds the decoding table: one entry per 14-bit prefix, holding a short code directly or the symbols of the
     * long codes starting with it.
     */
    private static final class Table {
        final int[] lengths = new int[DECODING_SIZE];

        final int[] symbols = new int[DECODING_SIZE];

        final int[][] longs = new int[DECODING_SIZE][];

        final int[] longCounts = new int[DECODING_SIZE];

        void build(long[] codes, int min, int max) {
            for (var symbol = min; symbol <= max; symbol++) {
                var code = codes[symbol] >>> 6;
                var length = (int) (codes[symbol] & 63);
                if (length < 64 && (code >>> length) != 0) {
                    throw new MediaException.Malformed("invalid OpenEXR Huffman code of symbol " + symbol);
                }
                if (length > DECODING_BITS) {
                    var index = (int) (code >>> (length - DECODING_BITS));
                    if (lengths[index] != 0) {
                        throw new MediaException.Malformed("an OpenEXR Huffman long code shares a short prefix");
                    }
                    var list = longs[index];
                    if (list == null) {
                        list = new int[2];
                        longs[index] = list;
                    } else if (longCounts[index] == list.length) {
                        list = Arrays.copyOf(list, list.length * 2);
                        longs[index] = list;
                    }
                    list[longCounts[index]++] = symbol;
                } else if (length > 0) {
                    var start = (int) (code << (DECODING_BITS - length));
                    var span = 1 << (DECODING_BITS - length);
                    for (var index = start; index < start + span; index++) {
                        if (lengths[index] != 0 || longs[index] != null) {
                            throw new MediaException.Malformed("overlapping OpenEXR Huffman codes");
                        }
                        lengths[index] = length;
                        symbols[index] = symbol;
                    }
                }
            }
        }
    }

    /**
     * Unpacks the code lengths and assigns the canonical codes.
     *
     * @return the offset past the packed table
     */
    private static int unpackTable(byte[] data, int position, int end, int min, int max, long[] codes) {
        long buffer = 0;
        var available = 0;
        for (var symbol = min; symbol <= max; symbol++) {
            if (available < 6) {
                if (position >= end) {
                    throw new MediaException.Malformed("the OpenEXR Huffman table ends early");
                }
                buffer = buffer << 8 | (data[position++] & 0xFF);
                available += 8;
            }
            available -= 6;
            var length = (int) (buffer >>> available) & 63;
            codes[symbol] = length;
            if (length == LONG_ZEROCODE_RUN) {
                if (available < 8) {
                    if (position >= end) {
                        throw new MediaException.Malformed("the OpenEXR Huffman table ends early");
                    }
                    buffer = buffer << 8 | (data[position++] & 0xFF);
                    available += 8;
                }
                available -= 8;
                var run = ((int) (buffer >>> available) & 0xFF) + SHORTEST_LONG_RUN;
                if (symbol + run > max + 1) {
                    throw new MediaException.Malformed("an OpenEXR Huffman zero run passes the last symbol");
                }
                Arrays.fill(codes, symbol, symbol + run, 0);
                symbol += run - 1;
            } else if (length >= SHORT_ZEROCODE_RUN) {
                var run = length - SHORT_ZEROCODE_RUN + 2;
                if (symbol + run > max + 1) {
                    throw new MediaException.Malformed("an OpenEXR Huffman zero run passes the last symbol");
                }
                Arrays.fill(codes, symbol, symbol + run, 0);
                symbol += run - 1;
            }
        }
        canonical(codes);
        return position;
    }

    /**
     * Turns code lengths into canonical codes, stored as {@code code << 6 | length}: shorter codes are numerically
     * higher, and codes of one length increase with their symbols.
     */
    private static void canonical(long[] codes) {
        var counts = new long[59];
        for (var code : codes) {
            counts[(int) code]++;
        }
        long next = 0;
        for (var length = 58; length > 0; length--) {
            var following = (next + counts[length]) >> 1;
            counts[length] = next;
            next = following;
        }
        for (var symbol = 0; symbol < codes.length; symbol++) {
            var length = (int) codes[symbol];
            if (length > 0) {
                codes[symbol] = length | (counts[length]++ << 6);
            }
        }
    }

    /**
     * Holds the state of the code reader.
     */
    private static final class Reader {
        final byte[] data;

        final int end;

        final short[] out;

        final int count;

        final int runCode;

        int position;

        long buffer;

        int available;

        int written;

        Reader(byte[] data, int position, int end, short[] out, int count, int runCode) {
            this.data = data;
            this.position = position;
            this.end = end;
            this.out = out;
            this.count = count;
            this.runCode = runCode;
        }

        void fill() {
            buffer = buffer << 8 | (data[position++] & 0xFF);
            available += 8;
        }

        /**
         * Outputs a decoded symbol: the run code repeats the previous symbol the next 8 bits' times.
         */
        void code(int symbol) {
            if (symbol == runCode) {
                if (available < 8) {
                    if (position >= end) {
                        throw new MediaException.Malformed("an OpenEXR Huffman run ends early");
                    }
                    fill();
                }
                available -= 8;
                written = repeat((int) (buffer >>> available) & 0xFF, out, written, count);
            } else {
                written = single(symbol, out, written, count);
            }
        }
    }

    /**
     * Decodes the codes.
     */
    private static void decodeCodes(long[] codes, Table table, byte[] data, int position, long bits, int runCode,
                                    short[] out, int count) {
        var reader = new Reader(data, position, position + (int) ((bits + 7) / 8), out, count, runCode);
        while (reader.position < reader.end) {
            reader.fill();
            while (reader.available >= DECODING_BITS) {
                var index = (int) (reader.buffer >>> (reader.available - DECODING_BITS)) & DECODING_MASK;
                var length = table.lengths[index];
                if (length != 0) {
                    reader.available -= length;
                    reader.code(table.symbols[index]);
                    continue;
                }
                var list = table.longs[index];
                if (list == null) {
                    throw new MediaException.Malformed("invalid OpenEXR Huffman code");
                }
                var found = false;
                for (var candidate = 0; candidate < table.longCounts[index]; candidate++) {
                    var symbol = list[candidate];
                    var codeLength = (int) (codes[symbol] & 63);
                    while (reader.available < codeLength && reader.position < reader.end) {
                        reader.fill();
                    }
                    if (reader.available >= codeLength && (codes[symbol] >>> 6)
                            == ((reader.buffer >>> (reader.available - codeLength)) & ((1L << codeLength) - 1))) {
                        reader.available -= codeLength;
                        reader.code(symbol);
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new MediaException.Malformed("invalid OpenEXR Huffman long code");
                }
            }
        }
        var skip = (int) ((8 - bits) & 7);
        reader.buffer >>>= skip;
        reader.available -= skip;
        while (reader.available > 0) {
            var index = (int) (reader.buffer << (DECODING_BITS - reader.available)) & DECODING_MASK;
            var length = table.lengths[index];
            if (length == 0 || length > reader.available) {
                throw new MediaException.Malformed("invalid OpenEXR Huffman code at the end");
            }
            reader.available -= length;
            reader.code(table.symbols[index]);
        }
        if (reader.written != count) {
            throw new MediaException.Malformed("the OpenEXR Huffman block holds " + reader.written + " of " + count
                    + " symbols");
        }
    }

    /**
     * Writes one symbol.
     */
    private static int single(int symbol, short[] out, int written, int count) {
        if (written >= count) {
            throw new MediaException.Malformed("the OpenEXR Huffman block holds more symbols than expected");
        }
        out[written] = (short) symbol;
        return written + 1;
    }

    /**
     * Repeats the last symbol written.
     */
    private static int repeat(int times, short[] out, int written, int count) {
        if (written + times > count) {
            throw new MediaException.Malformed("an OpenEXR Huffman run passes the expected symbols");
        }
        if (written == 0) {
            throw new MediaException.Malformed("an OpenEXR Huffman run has no symbol to repeat");
        }
        var value = out[written - 1];
        Arrays.fill(out, written, written + times, value);
        return written + times;
    }
}
