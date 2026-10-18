package com.github.auties00.smalt.image.webp.internal.lossless;

/**
 * Builds the two-level lookup tables that decode canonical prefix codes.
 *
 * <p>A table entry is an {@code int} packing the number of bits in its upper 16 bits and a value
 * in its lower 16 bits. A root table has {@code 1 << rootBits} entries indexed by the next
 * {@code rootBits} input bits (least-significant bit first). An entry of a code no longer than
 * {@code rootBits} holds the code length and the symbol. An entry of a longer prefix holds
 * {@code rootBits} plus the second-level table's index width and, as its value, the distance from
 * the entry to the start of the second-level table; that table is indexed by the following bits
 * and holds the remaining code length and the symbol.
 *
 * @implNote This implementation follows libwebp's {@code src/utils/huffman_utils.c} (BSD-3),
 * which derives from zlib's {@code inflate_table}: codes are enumerated in canonical order with
 * bit-reversed keys, and the tree is checked for over-subscription and completeness. A single
 * used symbol yields a table of zero-length codes.
 */
public final class HuffmanTables {
    /**
     * The longest code length the format allows.
     */
    public static final int MAX_CODE_LENGTH = 15;

    /**
     * Prevents instantiation.
     */
    private HuffmanTables() {
        throw new AssertionError();
    }

    /**
     * Packs a table entry.
     *
     * @param bits  the number of bits, or the second-level width plus the root bits
     * @param value the symbol or the second-level table distance
     * @return the packed entry
     */
    static int entry(int bits, int value) {
        return (bits << 16) | value;
    }

    /**
     * Returns the key following {@code key} for codes of length {@code len}, in bit-reversed
     * canonical order.
     *
     * @param key the current bit-reversed code
     * @param len the code length
     * @return the next bit-reversed code
     */
    private static int nextKey(int key, int len) {
        var step = 1 << (len - 1);
        while ((key & step) != 0) {
            step >>= 1;
        }
        return step != 0 ? (key & (step - 1)) + step : key;
    }

    /**
     * Stores {@code code} at every {@code step}-th entry of {@code table[base, base + end)},
     * walking down from the end.
     *
     * @param table the table
     * @param base  the index of the first entry of the range
     * @param step  the replication step
     * @param end   the length of the range, a multiple of {@code step}
     * @param code  the packed entry
     */
    private static void replicate(int[] table, int base, int step, int end, int code) {
        do {
            end -= step;
            table[base + end] = code;
        } while (end > 0);
    }

    /**
     * Returns the index width of the second-level table that starts with codes of length
     * {@code len}.
     *
     * @param count    the remaining number of codes of each length
     * @param len      the length of the first code of the table
     * @param rootBits the root table width
     * @return the second-level index width
     */
    private static int nextTableBitSize(int[] count, int len, int rootBits) {
        var left = 1 << (len - rootBits);
        while (len < MAX_CODE_LENGTH) {
            left -= count[len];
            if (left <= 0) {
                break;
            }
            ++len;
            left <<= 1;
        }
        return len - rootBits;
    }

    /**
     * Validates code lengths and, when a destination is given, fills the lookup tables.
     *
     * <p>With a {@code null} destination only the validation runs and the total table size is
     * returned, so callers can size storage before the filling pass. The filling pass needs
     * exactly the returned number of entries starting at {@code offset}.
     *
     * @param table       the destination, or {@code null} to only validate
     * @param offset      the index of the root table in {@code table}
     * @param rootBits    the root table index width, positive
     * @param codeLengths the code length of every symbol; lengths above 15 are invalid
     * @param size        the number of symbols to read from {@code codeLengths}
     * @param sorted      scratch space of at least {@code size} entries, required when filling
     * @return the number of table entries, or {@code 0} when the lengths do not form a valid
     *         prefix code (all zero, over-subscribed or incomplete)
     */
    public static int build(int[] table, int offset, int rootBits, int[] codeLengths, int size, int[] sorted) {
        var count = new int[MAX_CODE_LENGTH + 1];
        var offs = new int[MAX_CODE_LENGTH + 1];
        var totalSize = 1 << rootBits;
        for (var symbol = 0; symbol < size; ++symbol) {
            var len = codeLengths[symbol];
            if (len > MAX_CODE_LENGTH || len < 0) {
                return 0;
            }
            ++count[len];
        }
        if (count[0] == size) {
            return 0;
        }
        offs[1] = 0;
        for (var len = 1; len < MAX_CODE_LENGTH; ++len) {
            if (count[len] > (1 << len)) {
                return 0;
            }
            offs[len + 1] = offs[len] + count[len];
        }
        var fill = table != null;
        for (var symbol = 0; symbol < size; ++symbol) {
            var len = codeLengths[symbol];
            if (len > 0) {
                if (fill) {
                    if (offs[len] >= size) {
                        return 0;
                    }
                    sorted[offs[len]++] = symbol;
                } else {
                    offs[len]++;
                }
            }
        }
        if (offs[MAX_CODE_LENGTH] == 1) {
            if (fill) {
                replicate(table, offset, 1, totalSize, entry(0, sorted[0]));
            }
            return totalSize;
        }
        var low = -1;
        var mask = totalSize - 1;
        var key = 0;
        var numNodes = 1;
        var numOpen = 1;
        var tableBits = rootBits;
        var tableSize = 1 << tableBits;
        var tablePos = offset;
        var symbol = 0;
        int len;
        int step;
        for (len = 1, step = 2; len <= rootBits; ++len, step <<= 1) {
            numOpen <<= 1;
            numNodes += numOpen;
            numOpen -= count[len];
            if (numOpen < 0) {
                return 0;
            }
            if (!fill) {
                continue;
            }
            for (; count[len] > 0; --count[len]) {
                replicate(table, tablePos + key, step, tableSize, entry(len, sorted[symbol++]));
                key = nextKey(key, len);
            }
        }
        for (len = rootBits + 1, step = 2; len <= MAX_CODE_LENGTH; ++len, step <<= 1) {
            numOpen <<= 1;
            numNodes += numOpen;
            numOpen -= count[len];
            if (numOpen < 0) {
                return 0;
            }
            for (; count[len] > 0; --count[len]) {
                if ((key & mask) != low) {
                    if (fill) {
                        tablePos += tableSize;
                    }
                    tableBits = nextTableBitSize(count, len, rootBits);
                    tableSize = 1 << tableBits;
                    totalSize += tableSize;
                    low = key & mask;
                    if (fill) {
                        table[offset + low] = entry(tableBits + rootBits, tablePos - offset - low);
                    }
                }
                if (fill) {
                    replicate(table, tablePos + (key >> rootBits), step, tableSize, entry(len - rootBits, sorted[symbol++]));
                }
                key = nextKey(key, len);
            }
        }
        if (numNodes != 2 * offs[MAX_CODE_LENGTH] - 1) {
            return 0;
        }
        return totalSize;
    }
}
